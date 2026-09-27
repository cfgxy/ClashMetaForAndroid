package com.github.kr328.clash.service.model

import kotlinx.serialization.Serializable

/**
 * 按 profile 绑定的一条自定义分流规则（图形界面表单的领域对象），
 * 与 Room 实体 RuleOverride 分离，便于在纯 Kotlin 层做格式化与校验，不依赖 Android/Room。
 */
@Serializable
data class CustomRule(
    val ruleType: RuleType,
    val content: String,
    val policy: String,
    val position: RulePosition,
)

/**
 * 校验失败字段枚举——供 UI 层路由错误到对应输入框并取本地化字符串资源，
 * 替代此前按错误文案中的中文关键字子串判断字段归属的方式（该判断一旦文案进 string
 * 资源并被本地化就会失效，且硬编码中文无法适配非中文 locale）。
 */
enum class RuleValidationField { CONTENT, POLICY }

class RuleSyntaxException(val field: RuleValidationField, message: String) : IllegalArgumentException(message)

/**
 * 规则内容/策略的语法级校验，覆盖「合法 mihomo 规则字面量」的最基本形态。
 * 不做语义校验（如 GEOIP 国家码是否真实存在、策略是否指向真实 proxy-group）——
 * 语义校验依赖内核，见 ADR-001 关于 Clash.load 不适用于本处的记录。
 * 异常 message 保留中文技术细节供日志/调试使用；面向用户的文案由 UI 层按 [RuleSyntaxException.field]
 * 取本地化字符串资源展示，不直接展示 message。
 */
private val CIDR4_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}/\\d{1,2}$")
private val CIDR6_REGEX = Regex("^[0-9A-Fa-f:]+/\\d{1,3}$")
private val PORT_REGEX = Regex("^\\d{1,5}$")

fun CustomRule.validate() {
    if (content.isBlank()) throw RuleSyntaxException(RuleValidationField.CONTENT, "规则内容不能为空")
    if (content.contains(',')) throw RuleSyntaxException(RuleValidationField.CONTENT, "规则内容不能包含英文逗号：$content")
    if (policy.isBlank()) throw RuleSyntaxException(RuleValidationField.POLICY, "目标策略不能为空")
    if (policy.contains(',')) throw RuleSyntaxException(RuleValidationField.POLICY, "目标策略不能包含英文逗号：$policy")

    when (ruleType) {
        RuleType.IP_CIDR -> {
            if (!CIDR4_REGEX.matches(content)) {
                throw RuleSyntaxException(RuleValidationField.CONTENT, "IP-CIDR 格式非法，应形如 192.168.0.0/16：$content")
            }
        }

        RuleType.IP_CIDR6 -> {
            if (!CIDR6_REGEX.matches(content)) {
                throw RuleSyntaxException(RuleValidationField.CONTENT, "IP-CIDR6 格式非法：$content")
            }
        }

        RuleType.DST_PORT -> {
            if (!PORT_REGEX.matches(content) || content.toInt() !in 1..65535) {
                throw RuleSyntaxException(RuleValidationField.CONTENT, "DST-PORT 必须是 1-65535 的端口号：$content")
            }
        }

        RuleType.DOMAIN_REGEX -> {
            try {
                Regex(content)
            } catch (e: Exception) {
                throw RuleSyntaxException(RuleValidationField.CONTENT, "DOMAIN-REGEX 不是合法正则表达式：$content")
            }
        }

        RuleType.GEOIP -> {
            if (!Regex("^[A-Za-z]{2,}$").matches(content)) {
                throw RuleSyntaxException(RuleValidationField.CONTENT, "GEOIP 应为国家/地区代码（字母）：$content")
            }
        }

        RuleType.RULE_SET -> {
            // 语法层只校验字符集（与规则集命名白名单一致）；content 是否真实指向已声明的
            // 规则集属语义校验，由 ProfileManager.addRuleOverride/updateRuleOverride 在有
            // DB 访问能力的服务层完成——本函数不依赖 Android/Room，拿不到 profile 的规则集清单。
            if (!Regex("^[A-Za-z0-9_-]{1,64}$").matches(content)) {
                throw RuleSyntaxException(RuleValidationField.CONTENT, "RULE-SET 应为规则集名称：$content")
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
