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
    DST_PORT("DST-PORT");

    companion object {
        fun fromLiteral(literal: String): RuleType? = entries.find { it.literal == literal }
    }
}
