package com.github.kr328.clash.service.override

import com.github.kr328.clash.service.model.CustomRule
import com.github.kr328.clash.service.model.CustomRuleProvider
import com.github.kr328.clash.service.model.RulePosition
import com.github.kr328.clash.service.model.RuleProviderBehavior
import com.github.kr328.clash.service.model.RuleProviderFormat
import com.github.kr328.clash.service.model.RuleProviderType
import com.github.kr328.clash.service.model.RuleProviderUpdateInterval
import com.github.kr328.clash.service.model.RuleType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File

/**
 * 回归 QA P1-A：规则集声明与引用它的 RULE-SET 规则必须在**同一次内核校验**中同时可见。
 *
 * 这些用例用一个模拟内核校验器代替真实 `Clash.fetchAndValid`——真实内核在校验期看到的就是
 * 落盘后的 config.yaml，因此「校验回调被调用时文件里有什么」正是内核视角的等价观测点。
 * 校验器实现 mihomo 的关键语义：`RULE-SET,<name>,...` 引用的 name 必须在 `rule-providers`
 * 段中存在，否则报 `rule set [<name>] not found`（与 QA 三轮复现的报错文本同源）。
 */
class ProfileOverridesApplierTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val baseYaml = """
        port: 7890
        proxy-groups:
          - name: Proxy
            type: select
            proxies:
              - DIRECT
        rules:
          - MATCH,DIRECT
    """.trimIndent()

    private fun adsProvider(name: String = "ads") = CustomRuleProvider(
        name = name,
        type = RuleProviderType.HTTP,
        behavior = RuleProviderBehavior.DOMAIN,
        format = RuleProviderFormat.YAML,
        url = "https://example.com/$name.yaml",
        updateInterval = RuleProviderUpdateInterval.HOURLY,
    )

    private fun ruleSetRule(name: String = "ads", policy: String = "Proxy") = CustomRule(
        ruleType = RuleType.RULE_SET,
        content = name,
        policy = policy,
        position = RulePosition.PREPEND,
    )

    /** 记录每次校验被调用时 config.yaml 的内容，并按 mihomo 语义拒绝悬空 RULE-SET 引用。 */
    private class KernelValidatorStub {
        val observed = ArrayList<String>()

        var invocations = 0
            private set

        fun validate(dir: File) {
            invocations++

            val text = dir.resolve("config.yaml").readText()
            observed.add(text)

            val load = Load(LoadSettings.builder().build())

            @Suppress("UNCHECKED_CAST")
            val root = load.loadFromString(text) as? Map<String, Any?> ?: return

            @Suppress("UNCHECKED_CAST")
            val declared = (root["rule-providers"] as? Map<String, Any?>)?.keys.orEmpty()

            @Suppress("UNCHECKED_CAST")
            val rules = (root["rules"] as? List<Any?>).orEmpty().map { it.toString() }

            rules.forEach { line ->
                val parts = line.split(",")
                if (parts.firstOrNull()?.trim()?.uppercase() == "RULE-SET") {
                    val name = parts.getOrNull(1)?.trim().orEmpty()
                    if (name !in declared) {
                        throw IllegalStateException("rule set [$name] not found")
                    }
                }
            }
        }
    }

    // ---- 顺序契约（P1-A 的直接回归）----

    @Test
    fun `providers and referencing RULE-SET rule are visible in the same validation`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        val kernel = KernelValidatorStub()

        val changed = ProfileOverridesApplier.applyToFile(
            file,
            rules = listOf(ruleSetRule()),
            providers = listOf(adsProvider()),
        ) { kernel.validate(it) }

        assertTrue(changed)

        // 整次应用只做一次内核校验；该次校验同时看到 providers 声明与引用它的规则。
        assertEquals(1, kernel.invocations)

        val seen = kernel.observed.single()
        assertTrue("校验时应已包含 rule-providers 声明：$seen", seen.contains("ads:"))
        assertTrue("校验时应已包含 RULE-SET 规则：$seen", seen.contains("RULE-SET,ads,Proxy"))
    }

    @Test
    fun `RULE-SET rule referencing a declared provider passes kernel validation`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        val kernel = KernelValidatorStub()

        // 旧实现（规则先校验、规则集后注入）在这里必然抛 `rule set [ads] not found`。
        ProfileOverridesApplier.applyToFile(
            file,
            rules = listOf(ruleSetRule()),
            providers = listOf(adsProvider()),
        ) { kernel.validate(it) }

        val text = file.readText()
        assertTrue(text.contains("RULE-SET,ads,Proxy"))
        assertTrue(text.contains("path: ./rule_providers/ads.yaml"))
    }

    @Test
    fun `dangling RULE-SET reference still fails and rolls back both sections`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        val kernel = KernelValidatorStub()

        assertThrows(RuleOverrideException::class.java) {
            runBlocking {
                ProfileOverridesApplier.applyToFile(
                    file,
                    rules = listOf(ruleSetRule(name = "missing")),
                    providers = listOf(adsProvider()),
                ) { kernel.validate(it) }
            }
        }

        assertEquals(baseYaml, file.readText())
        assertFalse(RuleOverrideApplier.markerFile(file).exists())
        assertFalse(RuleProviderOverrideApplier.markerFile(file).exists())
    }

    // ---- 负向回归（分派卡验收项 4）----

    @Test
    fun `plain rule without any provider still applies`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        val kernel = KernelValidatorStub()

        val changed = ProfileOverridesApplier.applyToFile(
            file,
            rules = listOf(
                CustomRule(RuleType.DOMAIN_SUFFIX, "example.com", "DIRECT", RulePosition.PREPEND)
            ),
            providers = emptyList(),
        ) { kernel.validate(it) }

        assertTrue(changed)
        assertEquals(1, kernel.invocations)

        val text = file.readText()
        assertTrue(text.contains("DOMAIN-SUFFIX,example.com,DIRECT"))
        assertFalse(text.contains("rule-providers"))
    }

    @Test
    fun `provider validation failure rolls back the whole application`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        assertThrows(RuleOverrideException::class.java) {
            runBlocking {
                ProfileOverridesApplier.applyToFile(
                    file,
                    rules = listOf(
                        CustomRule(RuleType.DOMAIN, "example.com", "DIRECT", RulePosition.PREPEND)
                    ),
                    providers = listOf(adsProvider()),
                ) { throw IllegalStateException("kernel rejected rule-providers") }
            }
        }

        assertEquals(baseYaml, file.readText())
        assertFalse(RuleOverrideApplier.markerFile(file).exists())
        assertFalse(RuleProviderOverrideApplier.markerFile(file).exists())
    }

    @Test
    fun `profile never using the feature leaves config untouched and skips validation`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        val kernel = KernelValidatorStub()

        val changed = ProfileOverridesApplier.applyToFile(
            file,
            rules = emptyList(),
            providers = emptyList(),
        ) { kernel.validate(it) }

        assertFalse(changed)
        assertEquals(0, kernel.invocations)
        assertEquals(baseYaml, file.readText())
    }

    // ---- 幂等：两段合并后仍收敛（Type.File profile 每轮复制上轮结果）----

    @Test
    fun `applying the same rules and providers twice is byte-identical`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        val kernel = KernelValidatorStub()
        val rules = listOf(ruleSetRule())
        val providers = listOf(adsProvider())

        ProfileOverridesApplier.applyToFile(file, rules, providers) { kernel.validate(it) }
        val afterFirst = file.readText()

        ProfileOverridesApplier.applyToFile(file, rules, providers) { kernel.validate(it) }
        val afterSecond = file.readText()

        assertEquals(afterFirst, afterSecond)
        assertEquals(1, Regex("RULE-SET,ads,Proxy").findAll(afterSecond).count())
        assertEquals(1, Regex("^\\s*ads:", RegexOption.MULTILINE).findAll(afterSecond).count())
    }

    @Test
    fun `removing a provider and its reference restores the original config`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        val kernel = KernelValidatorStub()

        ProfileOverridesApplier.applyToFile(
            file,
            listOf(ruleSetRule()),
            listOf(adsProvider()),
        ) { kernel.validate(it) }

        ProfileOverridesApplier.applyToFile(
            file,
            emptyList(),
            emptyList(),
        ) { kernel.validate(it) }

        val text = file.readText()
        assertFalse(text.contains("rule-providers"))
        assertFalse(text.contains("RULE-SET"))
        assertTrue(text.contains("MATCH,DIRECT"))
    }
}
