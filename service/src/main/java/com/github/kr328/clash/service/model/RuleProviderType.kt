package com.github.kr328.clash.service.model

import kotlinx.serialization.Serializable

/**
 * 规则集来源类型，字面量取自 mihomo rules/provider/parse.go，写入 config.yaml 的 type 字段。
 */
@Serializable
enum class RuleProviderType(val literal: String) {
    FILE("file"),
    HTTP("http"),
    INLINE("inline");

    companion object {
        fun fromLiteral(literal: String): RuleProviderType? = entries.find { it.literal == literal }

        /**
         * 图形界面可选的来源类型。INLINE 的内容载体是 payload 列表而非 url/path，
         * 本版本表单不提供 payload 编辑，故不进候选集；同一判据在
         * [com.github.kr328.clash.service.model.validate] 里以异常方式兜底，
         * 避免 UI 与服务层两套口径。
         */
        val selectable: List<RuleProviderType> = entries.filter { it != INLINE }
    }
}

/** 规则集内容的匹配语义，字面量同样取自 mihomo rules/provider/parse.go 的 behavior 字段。 */
@Serializable
enum class RuleProviderBehavior(val literal: String) {
    DOMAIN("domain"),
    IPCIDR("ipcidr"),
    CLASSICAL("classical");

    companion object {
        fun fromLiteral(literal: String): RuleProviderBehavior? = entries.find { it.literal == literal }
    }
}

/** 规则集文件格式，字面量取自 mihomo rules/provider/parse.go 的 format 字段。 */
@Serializable
enum class RuleProviderFormat(val literal: String) {
    YAML("yaml"),
    TEXT("text"),
    MRS("mrs");

    companion object {
        fun fromLiteral(literal: String): RuleProviderFormat? = entries.find { it.literal == literal }
    }
}

/**
 * 更新间隔预设——分派卡裁定三：mihomo interval 字段单位为秒，四档预设按此固定映射。
 * NEVER 对应字段缺省（不写 interval 键），其余按 3600/21600/43200/86400 落地。
 */
@Serializable
enum class RuleProviderUpdateInterval(val seconds: Long?) {
    NEVER(null),
    HOURLY(3600L),
    EVERY_6_HOURS(21600L),
    EVERY_12_HOURS(43200L),
    DAILY(86400L);

    companion object {
        fun fromSeconds(seconds: Long?): RuleProviderUpdateInterval =
            entries.find { it.seconds == seconds } ?: NEVER
    }
}
