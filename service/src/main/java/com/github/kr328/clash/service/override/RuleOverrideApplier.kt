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
 * 回退语义：本函数在全部规则格式化、YAML 解析与合并均成功之前不写文件；
 * 任一步失败均抛出异常且不触碰原文件，调用方（ProfileProcessor）据此天然获得
 * 「失败不拷贝、旧配置原样生效」的回退路径，不需要额外状态机或备份文件。
 *
 * 校验范围说明（与 ADR-001 原设计的偏差，已在交付回报中向 Leader 说明）：
 * 本函数只做 YAML 结构级校验（能否解析、rules 是否为列表、每条规则字面量语法是否合法），
 * 不调用 Clash.load 做内核级语义校验——Clash.load 实际会把目标目录的配置整体应用到
 * 正在运行的内核（core/src/main/golang/native/config/load.go 的 hub.ApplyConfig），
 * 而这里的目标目录是 processingDir 暂存区，既非当前激活 profile，也不是它最终落地的
 * importedDir 路径，调用 Clash.load 会让运行中的内核错误指向一个即将被清空的临时目录。
 */
object RuleOverrideApplier {
    fun applyToFile(configFile: File, rules: List<CustomRule>) {
        if (rules.isEmpty()) return

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

        RuleSeq.apply(root, prependLines, appendLines)

        val dump = Dump(DumpSettings.builder().build())
        val dumped = try {
            dump.dumpToString(root)
        } catch (e: Exception) {
            throw RuleOverrideException("合并自定义规则后的配置无法序列化为 YAML", e)
        }

        configFile.writeText(dumped)
    }
}
