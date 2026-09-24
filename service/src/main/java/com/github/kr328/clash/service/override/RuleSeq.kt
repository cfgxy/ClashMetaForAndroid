package com.github.kr328.clash.service.override

/**
 * 对已解析为 Map 的 config.yaml 做 rules 字段的 prepend/append，纯数据操作，
 * 不涉及 YAML 序列化、文件与 Android，便于独立单测。
 * 对应 ADR-001 方案 A 的 Seq 能力（参照 clash-verge-rev src-tauri/src/enhance/seq.rs 的 prepend/append 语义）。
 */
object RuleSeq {
    private const val RULES_KEY = "rules"

    /**
     * @param root 已解析的 config.yaml 顶层 Map（key 为 String，value 类型由 YAML 库决定）。
     * @param prepend 插在订阅原有 rules 之前的规则行，按列表顺序保留（先出现的先匹配）。
     * @param append 插在订阅原有 rules 之后的规则行。
     * @return 更新后的 root（原地修改并返回，便于链式调用）。
     */
    fun apply(
        root: MutableMap<String, Any?>,
        prepend: List<String>,
        append: List<String>,
    ): MutableMap<String, Any?> {
        if (prepend.isEmpty() && append.isEmpty()) return root

        val existing: List<Any?> = when (val current = root[RULES_KEY]) {
            is List<*> -> current
            null -> emptyList()
            else -> throw IllegalStateException("config.yaml 的 rules 字段不是列表：${current::class}")
        }

        val merged = ArrayList<Any?>(prepend.size + existing.size + append.size)
        merged.addAll(prepend)
        merged.addAll(existing)
        merged.addAll(append)

        root[RULES_KEY] = merged
        return root
    }
}
