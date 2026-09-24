package com.github.kr328.clash.service.model

/**
 * 按 profile 绑定的一条自定义分流规则（图形界面表单的领域对象），
 * 与 Room 实体 RuleOverride 分离，便于在纯 Kotlin 层做格式化与校验，不依赖 Android/Room。
 */
data class CustomRule(
    val ruleType: RuleType,
    val content: String,
    val policy: String,
    val position: RulePosition,
)

class RuleSyntaxException(message: String) : IllegalArgumentException(message)

/**
 * 规则内容/策略的语法级校验，覆盖「合法 mihomo 规则字面量」的最基本形态。
 * 不做语义校验（如 GEOIP 国家码是否真实存在、策略是否指向真实 proxy-group）——
 * 语义校验依赖内核，见 ADR-001 关于 Clash.load 不适用于本处的记录。
 */
private val CIDR4_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}/\\d{1,2}$")
private val CIDR6_REGEX = Regex("^[0-9A-Fa-f:]+/\\d{1,3}$")
private val PORT_REGEX = Regex("^\\d{1,5}$")

fun CustomRule.validate() {
    if (content.isBlank()) throw RuleSyntaxException("规则内容不能为空")
    if (content.contains(',')) throw RuleSyntaxException("规则内容不能包含英文逗号：$content")
    if (policy.isBlank()) throw RuleSyntaxException("目标策略不能为空")
    if (policy.contains(',')) throw RuleSyntaxException("目标策略不能包含英文逗号：$policy")

    when (ruleType) {
        RuleType.IP_CIDR -> {
            if (!CIDR4_REGEX.matches(content)) {
                throw RuleSyntaxException("IP-CIDR 格式非法，应形如 192.168.0.0/16：$content")
            }
        }

        RuleType.IP_CIDR6 -> {
            if (!CIDR6_REGEX.matches(content)) {
                throw RuleSyntaxException("IP-CIDR6 格式非法：$content")
            }
        }

        RuleType.DST_PORT -> {
            if (!PORT_REGEX.matches(content) || content.toInt() !in 1..65535) {
                throw RuleSyntaxException("DST-PORT 必须是 1-65535 的端口号：$content")
            }
        }

        RuleType.DOMAIN_REGEX -> {
            try {
                Regex(content)
            } catch (e: Exception) {
                throw RuleSyntaxException("DOMAIN-REGEX 不是合法正则表达式：$content")
            }
        }

        RuleType.GEOIP -> {
            if (!Regex("^[A-Za-z]{2,}$").matches(content)) {
                throw RuleSyntaxException("GEOIP 应为国家/地区代码（字母）：$content")
            }
        }

        else -> {
            // DOMAIN / DOMAIN-SUFFIX / DOMAIN-KEYWORD / IP-ASN / PROCESS-NAME / PROCESS-PATH：
            // 非空且不含逗号即满足语法要求，已在上方统一校验。
        }
    }
}

/** 格式化为 mihomo 规则字面量：`TYPE,VALUE,POLICY`。 */
fun CustomRule.toRuleLine(): String {
    validate()
    return "${ruleType.literal},$content,$policy"
}
