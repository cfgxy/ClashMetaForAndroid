package com.github.kr328.clash.service.bundle

import com.github.kr328.clash.service.model.RulePosition
import com.github.kr328.clash.service.model.RuleProviderBehavior
import com.github.kr328.clash.service.model.RuleProviderFormat
import com.github.kr328.clash.service.model.RuleProviderType
import com.github.kr328.clash.service.model.RuleProviderUpdateInterval
import com.github.kr328.clash.service.model.RuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导入计划：同名规则集三种处置、线路名映射、去重、不支持项与 delete 计数。 */
class ImportPlanTest {
    private fun bundleOf(
        prepend: List<String> = emptyList(),
        append: List<String> = emptyList(),
        delete: List<String> = emptyList(),
        providers: List<BundleProvider> = emptyList(),
    ) = RuleBundle(
        manifest = BundleManifest(
            formatVersion = BundleFormat.VERSION,
            generatorApp = "clash-verge-rev",
            generatorVersion = "2.0.0",
            createdAt = "2026-09-26T00:00:00.000Z",
            proxyPolicies = emptyList(),
            contents = emptyList(),
        ),
        sequence = RuleSequence(prepend, append, delete),
        providers = providers,
    )

    private fun provider(name: String, url: String = "https://rules.example.com/$name.yaml") =
        BundleProvider(name, "http", "domain", "yaml", url, 86400L)

    private val identity = mapOf("PROXY" to "PROXY", "DIRECT" to "DIRECT", "REJECT" to "REJECT")

    @Test
    fun `无冲突时规则集与规则全部入计划`() {
        val plan = buildImportPlan(
            bundle = bundleOf(
                prepend = listOf("RULE-SET,ad-block,REJECT"),
                append = listOf("DOMAIN-SUFFIX,example.com,PROXY"),
                providers = listOf(provider("ad-block")),
            ),
            existingProviderNames = emptySet(),
            existingRuleLines = emptySet(),
            decisions = emptyMap(),
            policyMapping = identity,
        )

        assertTrue(plan.isExecutable)
        assertEquals(listOf("ad-block"), plan.providers.map { it.target.name })
        assertFalse(plan.providers.single().overwrite)
        assertEquals(
            listOf(RulePosition.PREPEND, RulePosition.APPEND),
            plan.rules.map { it.rule.position },
        )
    }

    @Test
    fun `同名规则集默认跳过时引用它的规则仍被导入`() {
        val plan = buildImportPlan(
            bundle = bundleOf(
                append = listOf("RULE-SET,ad-block,REJECT", "DOMAIN,example.com,PROXY"),
                providers = listOf(provider("ad-block")),
            ),
            existingProviderNames = setOf("ad-block"),
            existingRuleLines = emptySet(),
            decisions = emptyMap(),
            policyMapping = identity,
        )

        assertTrue(plan.providers.isEmpty())
        assertEquals(listOf("ad-block"), plan.skippedProviders.map { it.name })
        // 本机同名声明保留，引用它的规则照常导入并指向它——跳过不再连坐规则。
        assertEquals(
            listOf("RULE-SET,ad-block,REJECT", "DOMAIN,example.com,PROXY"),
            plan.rules.map { it.sourceLine },
        )
        assertEquals("ad-block", plan.rules.first().rule.content)
        assertTrue(plan.unsupportedRules.isEmpty())
    }

    @Test
    fun `同名规则集选择覆盖时入计划并标记覆盖`() {
        val plan = buildImportPlan(
            bundle = bundleOf(
                append = listOf("RULE-SET,ad-block,REJECT"),
                providers = listOf(provider("ad-block")),
            ),
            existingProviderNames = setOf("ad-block"),
            existingRuleLines = emptySet(),
            decisions = mapOf("ad-block" to ProviderDecision.Overwrite),
            policyMapping = identity,
        )

        assertTrue(plan.providers.single().overwrite)
        assertEquals("ad-block", plan.rules.single().rule.content)
    }

    @Test
    fun `同名规则集改名时规则引用同步改写`() {
        val plan = buildImportPlan(
            bundle = bundleOf(
                append = listOf("RULE-SET,ad-block,REJECT"),
                providers = listOf(provider("ad-block")),
            ),
            existingProviderNames = setOf("ad-block"),
            existingRuleLines = emptySet(),
            decisions = mapOf("ad-block" to ProviderDecision.Rename("ad-block-imported")),
            policyMapping = identity,
        )

        assertEquals("ad-block-imported", plan.providers.single().target.name)
        assertEquals("ad-block", plan.providers.single().sourceName)
        assertEquals("ad-block-imported", plan.rules.single().rule.content)
    }

    @Test
    fun `未映射的线路名使规则不写入且计划不可执行`() {
        val plan = buildImportPlan(
            bundle = bundleOf(append = listOf("DOMAIN,example.com,香港节点", "GEOIP,CN,DIRECT")),
            existingProviderNames = emptySet(),
            existingRuleLines = emptySet(),
            decisions = emptyMap(),
            policyMapping = mapOf("DIRECT" to "DIRECT"),
        )

        assertFalse(plan.isExecutable)
        assertEquals(listOf("香港节点"), plan.unmappedPolicies)
        assertEquals(listOf("GEOIP,CN,DIRECT"), plan.rules.map { it.sourceLine })
        assertThrows(IllegalStateException::class.java) { plan.requireComplete() }
    }

    @Test
    fun `映射到本地线路名后规则按新策略写入`() {
        val plan = buildImportPlan(
            bundle = bundleOf(append = listOf("DOMAIN,example.com,香港节点")),
            existingProviderNames = emptySet(),
            existingRuleLines = emptySet(),
            decisions = emptyMap(),
            policyMapping = mapOf("香港节点" to "HK-Group"),
        )

        assertTrue(plan.isExecutable)
        assertEquals("HK-Group", plan.rules.single().rule.policy)
        plan.requireComplete()
    }

    @Test
    fun `与本地逐字相同的规则被去重`() {
        val plan = buildImportPlan(
            bundle = bundleOf(append = listOf("DOMAIN,example.com,PROXY", "GEOIP,CN,DIRECT")),
            existingProviderNames = emptySet(),
            existingRuleLines = setOf("DOMAIN,example.com,PROXY"),
            decisions = emptyMap(),
            policyMapping = identity,
        )

        assertEquals(listOf("DOMAIN,example.com,PROXY"), plan.skippedDuplicateRules)
        assertEquals(listOf("GEOIP,CN,DIRECT"), plan.rules.map { it.sourceLine })
    }

    @Test
    fun `不支持的规则类型与畸形规则行被单列`() {
        val plan = buildImportPlan(
            bundle = bundleOf(
                append = listOf(
                    "AND,((DOMAIN,example.com),(DST-PORT,443)),PROXY",
                    "SRC-IP-CIDR,192.168.1.0/24,DIRECT",
                    "MATCH,PROXY",
                ),
            ),
            existingProviderNames = emptySet(),
            existingRuleLines = emptySet(),
            decisions = emptyMap(),
            policyMapping = identity,
        )

        assertTrue(plan.rules.isEmpty())
        assertEquals(3, plan.unsupportedRules.size)
    }

    @Test
    fun `内容未通过本端校验的规则被单列`() {
        val plan = buildImportPlan(
            bundle = bundleOf(append = listOf("IP-CIDR,not-a-cidr,DIRECT")),
            existingProviderNames = emptySet(),
            existingRuleLines = emptySet(),
            decisions = emptyMap(),
            policyMapping = identity,
        )

        assertTrue(plan.rules.isEmpty())
        assertEquals(listOf("IP-CIDR,not-a-cidr,DIRECT"), plan.unsupportedRules.map { it.line })
    }

    @Test
    fun `声明不合法的规则集被跳过且提示只含主机名`() {
        val plan = buildImportPlan(
            bundle = bundleOf(
                append = listOf("RULE-SET,bad-set,REJECT"),
                providers = listOf(provider("bad-set", "ftp://rules.example.com/bad.yaml?token=secret")),
            ),
            existingProviderNames = emptySet(),
            existingRuleLines = emptySet(),
            decisions = emptyMap(),
            policyMapping = identity,
        )

        assertTrue(plan.providers.isEmpty())
        assertEquals(listOf("bad-set"), plan.skippedProviders.map { it.name })
        assertTrue(plan.rules.isEmpty())
        // 两条如实上报：声明本身不合法一条；引用它的规则因无处落地一条。包外声明缺失不再静默。
        assertEquals(2, plan.unsupportedRules.size)

        val reported = plan.unsupportedRules.first().line
        assertTrue(reported.contains("rules.example.com"))
        assertFalse(reported.contains("token"))
    }

    @Test
    fun `引用包外且本机没有的规则集时该规则单列其余照常`() {
        // PC 端合法场景：规则引用订阅自带的规则集，声明不随包走、本机也没有。
        // 计划阶段必须把它挑出来单列，而不是让它在事务里引爆整批回滚。
        val plan = buildImportPlan(
            bundle = bundleOf(
                prepend = listOf("RULE-SET,missing-set,REJECT", "DOMAIN,github.com,PROXY"),
                providers = listOf(provider("ad-block")),
            ),
            existingProviderNames = emptySet(),
            existingRuleLines = emptySet(),
            decisions = emptyMap(),
            policyMapping = identity,
        )

        assertEquals(listOf("DOMAIN,github.com,PROXY"), plan.rules.map { it.sourceLine })
        assertEquals(listOf("RULE-SET,missing-set,REJECT"), plan.unsupportedRules.map { it.line })
        assertEquals(listOf("ad-block"), plan.providers.map { it.target.name })
        assertTrue(plan.isExecutable)
    }

    @Test
    fun `引用改名后的规则集按新名单列或落位`() {
        val plan = buildImportPlan(
            bundle = bundleOf(
                append = listOf("RULE-SET,ad-block,REJECT"),
                providers = listOf(provider("ad-block", "ftp://rules.example.com/x.yaml")),
            ),
            existingProviderNames = setOf("ad-block"),
            existingRuleLines = emptySet(),
            decisions = mapOf("ad-block" to ProviderDecision.Rename("renamed")),
            policyMapping = identity,
        )

        // 改名后的声明不合法被拒：改名撤销，规则回落到本机既有同名声明上照常导入。
        assertTrue(plan.providers.isEmpty())
        assertEquals("ad-block", plan.rules.single().rule.content)
        assertEquals(1, plan.unsupportedRules.size)
    }

    @Test
    fun `改名撞上包内将新增的名字时计划级兜底让后到者跳过`() {
        val plan = buildImportPlan(
            bundle = bundleOf(
                append = listOf("RULE-SET,b,PROXY"),
                providers = listOf(provider("a"), provider("b")),
            ),
            existingProviderNames = setOf("b"),
            existingRuleLines = emptySet(),
            decisions = mapOf("b" to ProviderDecision.Rename("a")),
            policyMapping = identity,
        )

        // a 先入计划（新增）；b 改名为 a 在计划级兜底下让位并单列，a 的声明不被覆盖。
        // 界面的改名占用名单（含包内新增名）正常不会放行到这里，这里是纯函数层的最后防线。
        assertEquals(listOf("a"), plan.providers.map { it.target.name })
        assertFalse(plan.providers.single().overwrite)
        assertEquals(listOf("b"), plan.skippedProviders.map { it.name })
        assertTrue(plan.unsupportedRules.isNotEmpty())
        // b 的引用回落到本机既有同名声明，照常导入。
        assertEquals("b", plan.rules.single().rule.content)
    }

    @Test
    fun `interval 非四档与未知枚举字面量按近似值导入并单列告知`() {
        val plan = buildImportPlan(
            bundle = bundleOf(
                providers = listOf(
                    BundleProvider(
                        name = "odd",
                        type = "gopher",
                        behavior = "domain",
                        format = "yaml",
                        url = "https://rules.example.com/odd.yaml",
                        intervalSeconds = 7200L,
                    ),
                ),
            ),
            existingProviderNames = emptySet(),
            existingRuleLines = emptySet(),
            decisions = emptyMap(),
            policyMapping = identity,
        )

        val target = plan.providers.single().target

        // 类型字面量不认识 → 按默认值；7200 秒不在四档 → 收敛到最近的 1 小时档，绝不静默变永不更新。
        assertEquals(RuleProviderType.HTTP, target.type)
        assertEquals(RuleProviderUpdateInterval.HOURLY, target.updateInterval)
        assertEquals(2, plan.degradedEntries.size)
        assertTrue(plan.degradedEntries.any { it.reason.contains("7200") })
    }

    @Test
    fun `缺省更新间隔对应永不更新且不进降级清单`() {
        val plan = buildImportPlan(
            bundle = bundleOf(
                providers = listOf(
                    BundleProvider("no-interval", "http", "domain", "yaml", "https://rules.example.com/n.yaml", null),
                ),
            ),
            existingProviderNames = emptySet(),
            existingRuleLines = emptySet(),
            decisions = emptyMap(),
            policyMapping = identity,
        )

        assertEquals(RuleProviderUpdateInterval.NEVER, plan.providers.single().target.updateInterval)
        assertTrue(plan.degradedEntries.isEmpty())
    }

    @Test
    fun `delete 数组按条计数供界面告知用户`() {
        val plan = buildImportPlan(
            bundle = bundleOf(delete = listOf("DOMAIN,a.example.com,DIRECT", "DOMAIN,b.example.com,DIRECT")),
            existingProviderNames = emptySet(),
            existingRuleLines = emptySet(),
            decisions = emptyMap(),
            policyMapping = identity,
        )

        assertEquals(2, plan.ignoredDeleteCount)
        assertTrue(plan.rules.isEmpty())
        assertTrue(plan.isExecutable)
    }

    @Test
    fun `规则集字段原样映射到本端领域对象`() {
        val plan = buildImportPlan(
            bundle = bundleOf(providers = listOf(provider("ad-block"))),
            existingProviderNames = emptySet(),
            existingRuleLines = emptySet(),
            decisions = emptyMap(),
            policyMapping = emptyMap(),
        )
        val target = plan.providers.single().target

        assertEquals(RuleProviderType.HTTP, target.type)
        assertEquals(RuleProviderBehavior.DOMAIN, target.behavior)
        assertEquals(RuleProviderFormat.YAML, target.format)
        assertEquals(RuleProviderUpdateInterval.DAILY, target.updateInterval)
    }

    @Test
    fun `逻辑规则行也能被检出引用了被跳过的规则集`() {
        assertTrue(referencesProvider("AND,((RULE-SET,ad-block),(DST-PORT,443)),PROXY", "ad-block"))
        assertFalse(referencesProvider("AND,((RULE-SET,ad-block-extra),(DST-PORT,443)),PROXY", "ad-block"))
    }

    @Test
    fun `改写规则集引用不误伤同前缀的名字`() {
        assertEquals(
            "RULE-SET,renamed,REJECT",
            rewriteProviderReference("RULE-SET,ad-block,REJECT", "ad-block", "renamed"),
        )
        assertEquals(
            "RULE-SET,ad-block-extra,REJECT",
            rewriteProviderReference("RULE-SET,ad-block-extra,REJECT", "ad-block", "renamed"),
        )
    }

    @Test
    fun `默认线路映射只预选同名项`() {
        val mapping = defaultPolicyMapping(
            bundlePolicies = listOf("DIRECT", "PROXY", "香港节点"),
            availablePolicies = listOf("PROXY", "US-Group"),
        )

        assertEquals(mapOf("DIRECT" to "DIRECT", "PROXY" to "PROXY"), mapping)
    }

    @Test
    fun `规则类型字面量大小写不敏感`() {
        val plan = buildImportPlan(
            bundle = bundleOf(append = listOf("domain-suffix,example.com,DIRECT")),
            existingProviderNames = emptySet(),
            existingRuleLines = emptySet(),
            decisions = emptyMap(),
            policyMapping = identity,
        )

        assertEquals(RuleType.DOMAIN_SUFFIX, plan.rules.single().rule.ruleType)
    }
}
