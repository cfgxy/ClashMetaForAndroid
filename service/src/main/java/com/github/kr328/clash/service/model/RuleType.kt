package com.github.kr328.clash.service.model

import kotlinx.serialization.Serializable

/**
 * 本期图形界面暴露的规则类型枚举（设计板 F3，三组共 11 种）。
 * 与 mihomo 规则语法一一对应，值即写入 config.yaml 的字面量。
 */
@Serializable
enum class RuleType(val literal: String) {
    DOMAIN("DOMAIN"),
    DOMAIN_SUFFIX("DOMAIN-SUFFIX"),
    DOMAIN_KEYWORD("DOMAIN-KEYWORD"),
    DOMAIN_REGEX("DOMAIN-REGEX"),
    IP_CIDR("IP-CIDR"),
    IP_CIDR6("IP-CIDR6"),
    GEOIP("GEOIP"),
    IP_ASN("IP-ASN"),
    PROCESS_NAME("PROCESS-NAME"),
    PROCESS_PATH("PROCESS-PATH"),
    DST_PORT("DST-PORT"),
    // 裁定一（RUYI-176 规则集管理）纳入：content 取值为规则集名称，引用一个 rule-providers
    // 声明的规则集；GUI 层用下拉选择而非自由文本，从结构上消除引用不存在规则集的可能。
    RULE_SET("RULE-SET");

    companion object {
        fun fromLiteral(literal: String): RuleType? = entries.find { it.literal == literal }
    }
}
