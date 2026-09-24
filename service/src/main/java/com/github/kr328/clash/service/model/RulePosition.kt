package com.github.kr328.clash.service.model

import kotlinx.serialization.Serializable

/**
 * 自定义规则相对订阅原生 rules 的插入位置。
 * PREPEND = 设计稿「优先匹配」：插在订阅规则之前，优先命中；
 * APPEND = 设计稿「兜底匹配」：插在订阅规则之后，订阅规则均未命中才轮到。
 */
@Serializable
enum class RulePosition {
    PREPEND, APPEND
}
