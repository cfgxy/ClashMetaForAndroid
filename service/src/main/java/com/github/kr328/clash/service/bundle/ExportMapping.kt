package com.github.kr328.clash.service.bundle

import com.github.kr328.clash.service.model.CustomRule
import com.github.kr328.clash.service.model.CustomRuleProvider
import com.github.kr328.clash.service.model.RulePosition
import com.github.kr328.clash.service.model.toRuleLine

/**
 * 本机「我们自己定义的那一层」→ 包内规则序列。
 *
 * 入参只是本 profile 的自定义规则（Room 表 `rule_override`），顺序沿用查询顺序
 * （position, sortOrder）。订阅源自带的 rules 不在这张表里，因此天然不会被打包。
 *
 * `delete` 恒为空：本端没有「抑制订阅自有规则」的能力，凭空造出该数组等于伪造语义。
 */
fun toRuleSequence(rules: List<CustomRule>): RuleSequence = RuleSequence(
    prepend = rules.filter { it.position == RulePosition.PREPEND }.map { it.toRuleLine() },
    append = rules.filter { it.position == RulePosition.APPEND }.map { it.toRuleLine() },
    delete = emptyList(),
)

/**
 * 本机规则集声明 → 包内规则集条目。只带声明不带内容：规则集内容由各端内核按 url 自行抓取，
 * `path` 也不进包（各端按自己的目录布局派生）。
 */
fun toBundleProviders(providers: List<CustomRuleProvider>): List<BundleProvider> = providers.map {
    BundleProvider(
        name = it.name,
        type = it.type.literal,
        behavior = it.behavior.literal,
        format = it.format.literal,
        url = it.url,
        intervalSeconds = it.updateInterval.seconds,
    )
}

/**
 * 包内规则集 URL 的主机名清单（去重），用于在导出确认界面提示「包里含订阅地址」。
 * 只出 host，不出完整 URL——query string 可能带订阅令牌。
 */
fun exportedUrlHosts(providers: List<BundleProvider>): List<String> = providers
    .mapNotNull { provider ->
        provider.url.takeIf { it.isNotBlank() }?.let {
            try {
                java.net.URI(it).host
            } catch (e: Exception) {
                null
            }
        }
    }
    .distinct()
    .sorted()
