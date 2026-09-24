package com.github.kr328.clash.service.override

import com.github.kr328.clash.service.model.CustomRule
import com.github.kr328.clash.service.model.RulePosition
import com.github.kr328.clash.service.model.toRuleLine
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File

class RuleOverrideException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 把按 profile 绑定的自定义规则应用到 config.yaml，对应 ADR-001 的处理时机：
 * 插在 fetchAndValid 完成之后、processingDir -> importedDir 拷贝之前。
 *
 * 回退语义：本函数在全部规则格式化、YAML 解析、合并与外部校验均成功之前不提交变更；
 * 语法/解析阶段失败时从不触碰原文件，[validate] 阶段失败时把文件恢复为调用前内容，
 * 两种失败都会抛出异常，调用方（ProfileProcessor）据此天然获得
 * 「失败不拷贝、旧配置原样生效」的回退路径，不需要额外状态机或备份文件。
 *
 * 幂等语义（Review 阻断项 A1）：Type.File 类型 profile 的 processingDir 内容
 * 来自上一轮已合并结果（ProfileManager.cloneImportedFiles 原样复制 importedDir），
 * 直接再次 prepend/append 会造成规则重复叠加。本函数用同目录下的 marker 文件
 * （[markerFile]）记录上一轮实际注入的 prepend/append 行，每次应用前先从现有
 * rules 中剔除这批行、再注入本轮计算结果，使连续多次应用收敛到同一结果。
 *
 * 校验语义（Review 阻断项 A2）：格式/语法校验止于纯 Kotlin 层，无法发现「策略名指向
 * 不存在的 proxy-group」这类语义错误。合并写入 config.yaml 后，调用方可传入 [validate]
 * 触发内核级重校验（如 `Clash.fetchAndValid(processingDir, source, force = false)`，
 * 该入口不调用 hub.ApplyConfig，不触碰运行中内核）；validate 抛出的任何异常都会使本函数
 * 把文件恢复到调用前内容并包装抛出，不写 marker，不视为已应用。
 */
object RuleOverrideApplier {
    private const val RULES_KEY = "rules"

    private fun markerFile(configFile: File): File =
        File(configFile.parentFile, ".${configFile.name}.rule_override.marker")

    /**
     * @return 本次是否实际修改了 [configFile]（规则集与上一轮 marker 记录均为空时短路，返回 false）。
     */
    suspend fun applyToFile(
        configFile: File,
        rules: List<CustomRule>,
        validate: suspend (File) -> Unit = {},
    ): Boolean {
        val prependLines = ArrayList<String>()
        val appendLines = ArrayList<String>()
        for (rule in rules) {
            val line = try {
                rule.toRuleLine()
            } catch (e: Exception) {
                throw RuleOverrideException("自定义规则语法非法：${e.message}", e)
            }
            when (rule.position) {
                RulePosition.PREPEND -> prependLines.add(line)
                RulePosition.APPEND -> appendLines.add(line)
            }
        }

        val marker = markerFile(configFile)
        val (previousPrepend, previousAppend) = readMarker(marker)

        if (prependLines.isEmpty() && appendLines.isEmpty() &&
            previousPrepend.isEmpty() && previousAppend.isEmpty()
        ) {
            return false
        }

        if (!configFile.isFile) {
            throw RuleOverrideException("config.yaml 不存在：${configFile.absolutePath}")
        }

        val originalText = configFile.readText()

        val load = Load(LoadSettings.builder().build())
        val parsed = try {
            load.loadFromString(originalText)
        } catch (e: Exception) {
            throw RuleOverrideException("config.yaml 不是合法 YAML，无法应用自定义规则", e)
        }

        @Suppress("UNCHECKED_CAST")
        val root: MutableMap<String, Any?> = when (parsed) {
            is MutableMap<*, *> -> parsed as MutableMap<String, Any?>
            is Map<*, *> -> LinkedHashMap(parsed as Map<String, Any?>)
            null -> LinkedHashMap()
            else -> throw RuleOverrideException("config.yaml 顶层不是 Mapping，无法应用自定义规则")
        }

        val existing: List<Any?> = when (val current = root[RULES_KEY]) {
            is List<*> -> current
            null -> emptyList()
            else -> throw RuleOverrideException("config.yaml 的 rules 字段不是列表：${current::class}")
        }

        root[RULES_KEY] = stripInjected(existing, previousPrepend, previousAppend)

        RuleSeq.apply(root, prependLines, appendLines)

        val dump = Dump(DumpSettings.builder().build())
        val dumped = try {
            dump.dumpToString(root)
        } catch (e: Exception) {
            throw RuleOverrideException("合并自定义规则后的配置无法序列化为 YAML", e)
        }

        configFile.writeText(dumped)

        try {
            validate(configFile.parentFile ?: configFile)
        } catch (e: Exception) {
            configFile.writeText(originalText)
            throw RuleOverrideException("自定义规则未通过校验，更新已回退：${e.message}", e)
        }

        writeMarker(marker, prependLines, appendLines)

        return true
    }

    /**
     * 从现有 rules 中剔除上一轮由本功能注入的 prefix/suffix。只有严格逐项相等的前缀/后缀
     * 才剔除；不匹配（如远程订阅被重新下载、内容与 marker 记录不一致）时原样保留，
     * 不强行删除——这一分支不会产生重复注入，因为不匹配即意味着这批行本就不在 existing 中。
     */
    private fun stripInjected(
        existing: List<Any?>,
        previousPrepend: List<String>,
        previousAppend: List<String>,
    ): List<Any?> {
        var result = existing

        if (previousPrepend.isNotEmpty() &&
            result.size >= previousPrepend.size &&
            result.subList(0, previousPrepend.size) == previousPrepend
        ) {
            result = result.subList(previousPrepend.size, result.size)
        }

        if (previousAppend.isNotEmpty() &&
            result.size >= previousAppend.size &&
            result.subList(result.size - previousAppend.size, result.size) == previousAppend
        ) {
            result = result.subList(0, result.size - previousAppend.size)
        }

        return result
    }

    private fun readMarker(marker: File): Pair<List<String>, List<String>> {
        if (!marker.isFile) return emptyList<String>() to emptyList()

        return try {
            val load = Load(LoadSettings.builder().build())
            @Suppress("UNCHECKED_CAST")
            val parsed = load.loadFromString(marker.readText()) as? Map<String, Any?>
                ?: return emptyList<String>() to emptyList()

            @Suppress("UNCHECKED_CAST")
            val prepend = (parsed["prepend"] as? List<Any?>)?.map { it.toString() } ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val append = (parsed["append"] as? List<Any?>)?.map { it.toString() } ?: emptyList()
            prepend to append
        } catch (e: Exception) {
            // marker 文件损坏或被外部改动：按「无历史注入记录」处理，不阻断本次应用。
            emptyList<String>() to emptyList()
        }
    }

    private fun writeMarker(marker: File, prependLines: List<String>, appendLines: List<String>) {
        if (prependLines.isEmpty() && appendLines.isEmpty()) {
            marker.delete()
            return
        }

        val dump = Dump(DumpSettings.builder().build())
        marker.writeText(dump.dumpToString(mapOf("prepend" to prependLines, "append" to appendLines)))
    }
}
