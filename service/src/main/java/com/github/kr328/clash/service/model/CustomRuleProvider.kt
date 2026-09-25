package com.github.kr328.clash.service.model

/**
 * 按 profile 绑定的一条规则集定义（图形界面表单的领域对象），字段集合取自
 * mihomo rules/provider/parse.go，与 Room 实体 data.RuleProvider 分离，
 * 便于在纯 Kotlin 层做格式化与校验，不依赖 Android/Room（同 [CustomRule] 的分层方式）。
 */
data class CustomRuleProvider(
    val name: String,
    val type: RuleProviderType,
    val behavior: RuleProviderBehavior,
    val format: RuleProviderFormat,
    val url: String,
    val updateInterval: RuleProviderUpdateInterval,
)

enum class RuleProviderValidationField { NAME, TYPE, URL }

class RuleProviderSyntaxException(val field: RuleProviderValidationField, message: String) :
    IllegalArgumentException(message)

/**
 * 规则集名称白名单——path 由名称自动生成（`rule_providers/<name>.<format>`），
 * 名称本身即构成防路径穿越的第一道结构性约束：只允许字母数字下划线短横线，
 * 从根上排除 `..`、`/`、`\` 等路径穿越字符，不依赖事后转义或黑名单过滤。
 */
private val NAME_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

fun CustomRuleProvider.validate() {
    if (!NAME_REGEX.matches(name)) {
        throw RuleProviderSyntaxException(
            RuleProviderValidationField.NAME,
            "规则集名称只能包含字母、数字、下划线、短横线，长度 1-64：$name"
        )
    }

    // INLINE 的内容载体是 config.yaml 里的 payload 列表，既不使用 url 也不使用 path
    // （见 mihomo rules/provider/parse.go）。本期表单只覆盖 url/path 两种载体，没有
    // payload 的编辑入口，放行 INLINE 只会写出缺 payload 的非法 rule-providers 条目，
    // 因此在领域校验层直接拒绝——UI 候选集与服务层拒绝同源，不依赖 UI 自觉过滤。
    if (type == RuleProviderType.INLINE) {
        throw RuleProviderSyntaxException(
            RuleProviderValidationField.TYPE,
            "inline 类型规则集需要 payload 字段，本版本不支持"
        )
    }

    if (type != RuleProviderType.FILE) {
        if (url.isBlank()) {
            throw RuleProviderSyntaxException(RuleProviderValidationField.URL, "规则集 URL 不能为空")
        }
        val scheme = try {
            java.net.URI(url).scheme?.lowercase()
        } catch (e: Exception) {
            throw RuleProviderSyntaxException(RuleProviderValidationField.URL, "规则集 URL 格式非法：${maskUrl(url)}")
        }
        if (scheme != "http" && scheme != "https") {
            throw RuleProviderSyntaxException(RuleProviderValidationField.URL, "规则集 URL 必须是 http/https：${maskUrl(url)}")
        }
    }
}

/** path 由名称与格式确定性生成，用户不可自由输入，结构性消除路径穿越风险。 */
fun CustomRuleProvider.derivedPath(): String = "rule_providers/$name.${format.literal}"

/**
 * 脱敏展示：规则集 URL 可能携带订阅令牌（query string），日志与异常文案一律只保留
 * scheme+host，不输出完整 URL（分派卡验收项要求）。
 */
fun maskUrl(url: String): String {
    return try {
        val uri = java.net.URI(url)
        val host = uri.host ?: return "(invalid-url)"
        "${uri.scheme}://$host/***"
    } catch (e: Exception) {
        "(invalid-url)"
    }
}
