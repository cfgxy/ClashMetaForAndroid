package com.github.kr328.clash.service.override

import com.github.kr328.clash.service.model.CustomRuleProvider
import com.github.kr328.clash.service.model.derivedPath
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File

/**
 * 把按 profile 绑定的规则集定义应用到 config.yaml 的 rule-providers 段，注入时机与
 * [RuleOverrideApplier] 相同（fetchAndValid 之后、processingDir -> importedDir 拷贝之前），
 * 在同一次 applyRuleOverrides 调用中先后执行，共享同一份内核级重校验。
 *
 * 幂等语义与 [RuleOverrideApplier] 相同的 marker 文件机制，但这里 marker 记录的是「上一轮
 * 本功能写入的规则集名称集合」而非行内容——因为这里操作的是 map 而非 list，天然幂等的覆盖
 * 方式是「先删掉上一轮写入的 key，再写入本轮 key」，不需要按内容逐项比对。
 */
/**
 * 规则集删除时仍被 RULE-SET 规则引用（裁定一）。UI 层捕获后只提供「取消」与
 * 「清空引用并删除」两个分支，不提供绕过引用检查的直接删除选项。
 */
class RuleProviderReferencedException(val referenceCount: Int, message: String) : Exception(message)

object RuleProviderOverrideApplier {
    private const val RULE_PROVIDERS_KEY = "rule-providers"

    private fun markerFile(configFile: File): File =
        File(configFile.parentFile, ".${configFile.name}.rule_provider.marker")

    /**
     * @return 本次是否实际修改了 [configFile]（规则集定义与上一轮 marker 记录均为空时短路，返回 false）。
     */
    suspend fun applyToFile(
        configFile: File,
        providers: List<CustomRuleProvider>,
        validate: suspend (File) -> Unit = {},
    ): Boolean {
        val marker = markerFile(configFile)
        val previousNames = readMarker(marker)

        if (providers.isEmpty() && previousNames.isEmpty()) {
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
            throw RuleOverrideException("config.yaml 不是合法 YAML，无法应用规则集定义", e)
        }

        @Suppress("UNCHECKED_CAST")
        val root: MutableMap<String, Any?> = when (parsed) {
            is MutableMap<*, *> -> parsed as MutableMap<String, Any?>
            is Map<*, *> -> LinkedHashMap(parsed as Map<String, Any?>)
            null -> LinkedHashMap()
            else -> throw RuleOverrideException("config.yaml 顶层不是 Mapping，无法应用规则集定义")
        }

        @Suppress("UNCHECKED_CAST")
        val existing: MutableMap<String, Any?> = when (val current = root[RULE_PROVIDERS_KEY]) {
            is MutableMap<*, *> -> LinkedHashMap(current as Map<String, Any?>)
            is Map<*, *> -> LinkedHashMap(current as Map<String, Any?>)
            null -> LinkedHashMap()
            else -> throw RuleOverrideException("config.yaml 的 rule-providers 字段不是 Mapping：${current::class}")
        }

        previousNames.forEach { existing.remove(it) }

        for (provider in providers) {
            existing[provider.name] = provider.toEntry()
        }

        if (existing.isEmpty()) {
            root.remove(RULE_PROVIDERS_KEY)
        } else {
            root[RULE_PROVIDERS_KEY] = existing
        }

        val dump = Dump(DumpSettings.builder().build())
        val dumped = try {
            dump.dumpToString(root)
        } catch (e: Exception) {
            throw RuleOverrideException("合并规则集定义后的配置无法序列化为 YAML", e)
        }

        configFile.writeText(dumped)

        try {
            validate(configFile.parentFile ?: configFile)
        } catch (e: Exception) {
            configFile.writeText(originalText)
            throw RuleOverrideException("规则集定义未通过校验，更新已回退：${e.message}", e)
        }

        writeMarker(marker, providers.map { it.name })

        return true
    }

    private fun CustomRuleProvider.toEntry(): Map<String, Any?> {
        val entry = LinkedHashMap<String, Any?>()
        entry["type"] = type.literal
        entry["behavior"] = behavior.literal
        entry["format"] = format.literal
        if (type != com.github.kr328.clash.service.model.RuleProviderType.FILE) {
            entry["url"] = url
        }
        entry["path"] = "./${derivedPath()}"
        updateInterval.seconds?.let { entry["interval"] = it }
        return entry
    }

    private fun readMarker(marker: File): List<String> {
        if (!marker.isFile) return emptyList()

        return try {
            val load = Load(LoadSettings.builder().build())
            @Suppress("UNCHECKED_CAST")
            val parsed = load.loadFromString(marker.readText()) as? Map<String, Any?>
                ?: return emptyList()

            @Suppress("UNCHECKED_CAST")
            (parsed["names"] as? List<Any?>)?.map { it.toString() } ?: emptyList()
        } catch (e: Exception) {
            // marker 文件损坏或被外部改动：按「无历史注入记录」处理，不阻断本次应用。
            emptyList()
        }
    }

    private fun writeMarker(marker: File, names: List<String>) {
        if (names.isEmpty()) {
            marker.delete()
            return
        }

        val dump = Dump(DumpSettings.builder().build())
        marker.writeText(dump.dumpToString(mapOf("names" to names)))
    }
}
