package com.github.kr328.clash.service.override

import com.github.kr328.clash.service.model.CustomRule
import com.github.kr328.clash.service.model.CustomRuleProvider
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File

/**
 * 把一个 profile 的自定义规则与规则集定义在**同一次写入、同一次内核校验**内应用到 config.yaml。
 *
 * 为什么必须合并（QA P1-A 根因）：此前 [RuleOverrideApplier.applyToFile] 与
 * [RuleProviderOverrideApplier.applyToFile] 各自写文件、各自触发一次内核校验，且规则先于
 * 规则集注入。`RULE-SET,<name>,<policy>` 这类规则在第一次校验时 `rule-providers:` 段尚不存在，
 * 内核必然报 `rule set [<name>] not found`，于是规则永远过不了校验、被记为失效——用户按标准流程
 * 「先建规则集、再建引用它的规则」100% 命中。顺序对调也不彻底：规则集段先写入时，若用户同时删掉
 * 某规则集而引用规则仍在（或反之），仍会出现「其中一段单独送校验必然失败」的窗口。
 *
 * 因此本类确立的顺序契约是：**providers 声明与引用它的 RULE-SET 规则在同一次内核校验中同时可见**。
 * 实现方式是先在内存 Map 上依次完成两段变换（复用两个 applier 的 `mutateRoot`，保持各自的
 * marker 幂等语义不变），序列化落盘一次，再做唯一一次 [validate]。
 *
 * 回退与幂等语义与两个单独 applier 完全一致：语法/解析阶段失败不触碰原文件；[validate] 失败时把
 * 文件恢复为调用前内容并抛出，两个 marker 都不更新——即「整体不落地，旧配置原样生效」。
 * 两段都为空且此前从未注入过时直接返回 false，不触碰 config.yaml（未使用本功能的 profile 不受影响）。
 */
object ProfileOverridesApplier {
    /**
     * @return 本次是否实际修改了 [configFile]（两段内容与上一轮 marker 记录均为空时短路，返回 false）。
     */
    suspend fun applyToFile(
        configFile: File,
        rules: List<CustomRule>,
        providers: List<CustomRuleProvider>,
        validate: suspend (File) -> Unit = {},
    ): Boolean {
        val (prependLines, appendLines) = RuleOverrideApplier.formatLines(rules)

        val ruleMarker = RuleOverrideApplier.markerFile(configFile)
        val (previousPrepend, previousAppend) = RuleOverrideApplier.readMarker(ruleMarker)

        val providerMarker = RuleProviderOverrideApplier.markerFile(configFile)
        val previousNames = RuleProviderOverrideApplier.readMarker(providerMarker)

        val ruleUntouched = prependLines.isEmpty() && appendLines.isEmpty() &&
            previousPrepend.isEmpty() && previousAppend.isEmpty()
        val providerUntouched = providers.isEmpty() && previousNames.isEmpty()

        if (ruleUntouched && providerUntouched) {
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
            throw RuleOverrideException("config.yaml 不是合法 YAML，无法应用自定义规则与规则集定义", e)
        }

        @Suppress("UNCHECKED_CAST")
        val root: MutableMap<String, Any?> = when (parsed) {
            is MutableMap<*, *> -> parsed as MutableMap<String, Any?>
            is Map<*, *> -> LinkedHashMap(parsed as Map<String, Any?>)
            null -> LinkedHashMap()
            else -> throw RuleOverrideException("config.yaml 顶层不是 Mapping，无法应用自定义规则与规则集定义")
        }

        // 先 providers 后 rules 只影响 YAML 里两段的相对书写位置，不影响语义：
        // 二者在同一次 dump 中落盘，对内核而言是同时可见的。
        RuleProviderOverrideApplier.mutateRoot(root, providers, previousNames)
        RuleOverrideApplier.mutateRoot(root, prependLines, appendLines, previousPrepend, previousAppend)

        val dump = Dump(DumpSettings.builder().build())
        val dumped = try {
            dump.dumpToString(root)
        } catch (e: Exception) {
            throw RuleOverrideException("合并自定义规则与规则集定义后的配置无法序列化为 YAML", e)
        }

        configFile.writeText(dumped)

        try {
            validate(configFile.parentFile ?: configFile)
        } catch (e: Exception) {
            configFile.writeText(originalText)
            throw RuleOverrideException("自定义规则或规则集定义未通过校验，更新已回退：${e.message}", e)
        }

        RuleOverrideApplier.writeMarker(ruleMarker, prependLines, appendLines)
        RuleProviderOverrideApplier.writeMarker(providerMarker, providers.map { it.name })

        return true
    }
}
