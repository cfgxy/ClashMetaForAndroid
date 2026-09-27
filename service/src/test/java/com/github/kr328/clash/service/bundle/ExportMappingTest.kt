package com.github.kr328.clash.service.bundle

import com.github.kr328.clash.service.model.CustomRule
import com.github.kr328.clash.service.model.CustomRuleProvider
import com.github.kr328.clash.service.model.RulePosition
import com.github.kr328.clash.service.model.RuleProviderBehavior
import com.github.kr328.clash.service.model.RuleProviderFormat
import com.github.kr328.clash.service.model.RuleProviderType
import com.github.kr328.clash.service.model.RuleProviderUpdateInterval
import com.github.kr328.clash.service.model.RuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本机自有层 → 包内表示。只覆盖用户自己定义的规则与规则集声明。 */
class ExportMappingTest {
    private val rules = listOf(
        CustomRule(RuleType.DOMAIN_SUFFIX, "example.com", "PROXY", RulePosition.PREPEND),
        CustomRule(RuleType.GEOIP, "CN", "DIRECT", RulePosition.APPEND),
        CustomRule(RuleType.RULE_SET, "ad-block", "REJECT", RulePosition.PREPEND),
    )

    private val providers = listOf(
        CustomRuleProvider(
            name = "ad-block",
            type = RuleProviderType.HTTP,
            behavior = RuleProviderBehavior.DOMAIN,
            format = RuleProviderFormat.YAML,
            url = "https://rules.example.com/ad.yaml?token=secret",
            updateInterval = RuleProviderUpdateInterval.DAILY,
        )
    )

    @Test
    fun `规则按位置分入 prepend 与 append`() {
        val sequence = toRuleSequence(rules)

        assertEquals(
            listOf("DOMAIN-SUFFIX,example.com,PROXY", "RULE-SET,ad-block,REJECT"),
            sequence.prepend,
        )
        assertEquals(listOf("GEOIP,CN,DIRECT"), sequence.append)
    }

    @Test
    fun `delete 恒为空 本端没有抑制订阅规则的能力`() {
        assertTrue(toRuleSequence(rules).delete.isEmpty())
    }

    @Test
    fun `规则集只带声明字段 不带本地 path`() {
        val exported = toBundleProviders(providers).single()

        assertEquals("ad-block", exported.name)
        assertEquals("http", exported.type)
        assertEquals("domain", exported.behavior)
        assertEquals("yaml", exported.format)
        assertEquals(86400L, exported.intervalSeconds)

        val yaml = BundleCodec.buildProvidersYaml(listOf(exported)).decodeToString()
        assertFalse(yaml.contains("path"))
    }

    @Test
    fun `不自动更新的规则集不写 interval 字段`() {
        val never = providers.map { it.copy(updateInterval = RuleProviderUpdateInterval.NEVER) }
        val yaml = BundleCodec.buildProvidersYaml(toBundleProviders(never)).decodeToString()

        assertFalse(yaml.contains("interval"))
    }

    @Test
    fun `导出提示只给出主机名 不回显订阅令牌`() {
        val hosts = exportedUrlHosts(toBundleProviders(providers))

        assertEquals(listOf("rules.example.com"), hosts)
    }
}
