package com.github.kr328.clash.service.bundle

import com.github.kr328.clash.service.model.CustomRule
import com.github.kr328.clash.service.model.CustomRuleProvider
import com.github.kr328.clash.service.model.RulePosition
import com.github.kr328.clash.service.model.RuleProviderBehavior
import com.github.kr328.clash.service.model.RuleProviderFormat
import com.github.kr328.clash.service.model.RuleProviderType
import com.github.kr328.clash.service.model.RuleProviderUpdateInterval
import com.github.kr328.clash.service.model.RuleType
import com.github.kr328.clash.service.model.maskUrl
import com.github.kr328.clash.service.model.validate

/** 同名规则集的处置方式，由用户在导入界面逐个选择。 */
sealed class ProviderDecision {
    /** 用包内声明覆盖本地同名规则集，规则引用不变。 */
    object Overwrite : ProviderDecision()

    /** 保留本地声明，不导入包内这一条；包内引用它的规则仍导入，指向保留的本地声明。 */
    object Skip : ProviderDecision()

    /** 以新名字导入，包内规则里的 `RULE-SET,<旧名>` 同步改写为新名。 */
    data class Rename(val newName: String) : ProviderDecision()
}

/** 计划中要写入的一条规则集。 */
data class PlannedProvider(
    val target: CustomRuleProvider,
    /** 包内原始名字；与 [target] 的 name 不同即发生了改名。 */
    val sourceName: String,
    /** true 表示覆盖本地同名条目，false 表示新增。 */
    val overwrite: Boolean,
)

/** 计划中要写入的一条规则。 */
data class PlannedRule(
    val rule: CustomRule,
    /** 包内原始规则行，仅用于向用户展示计划，不参与写入。 */
    val sourceLine: String,
)

/** 因用户选择跳过（或声明不合法、批次内最终撞名）而未写入的规则集，附包内引用它的规则行（用于告知影响面）。 */
data class SkippedProvider(
    val name: String,
    val referencingLines: List<String>,
)

/** 本端无法表达或无法落地的条目，连同原因一起报告——不静默丢弃、不静默改写。 */
data class UnsupportedRule(
    val line: String,
    val reason: String,
)

/**
 * 导入计划：一次性算清「要写什么、跳过什么、还缺什么」。
 *
 * 计划本身不触碰数据库。只有 [unmappedPolicies] 为空（全部线路名已映射）时才允许执行写入，
 * 由 [requireComplete] 把关。
 */
data class ImportPlan(
    val providers: List<PlannedProvider>,
    val rules: List<PlannedRule>,
    val skippedProviders: List<SkippedProvider>,
    /** 与本地既有规则逐字节相同，因此无需重复写入的行。 */
    val skippedDuplicateRules: List<String>,
    val unsupportedRules: List<UnsupportedRule>,
    /**
     * 包内声明里本端无法精确表达、已按最近似取值（档位/默认值）导入的条目。
     * 与 [unsupportedRules] 的区别：这些条目**已写入**，只是取值被本端收敛；后者完全不写入。
     * 同样走如实上报通道——与 [ignoredDeleteCount] 一样不让用户莫名少掉或改掉东西。
     */
    val degradedEntries: List<UnsupportedRule>,
    /** 包内 `delete` 数组的条数：本端没有抑制订阅规则的能力，只如实告知忽略了多少条。 */
    val ignoredDeleteCount: Int,
    /** 包内出现但用户尚未给出本地目标的线路名。非空即不可执行。 */
    val unmappedPolicies: List<String>,
) {
    val isExecutable: Boolean
        get() = unmappedPolicies.isEmpty()

    fun requireComplete() {
        if (!isExecutable) {
            throw IllegalStateException("线路名映射未完成：${unmappedPolicies.size} 项待选择")
        }
    }
}

/** 规则行的结构化形式。`no-resolve` 等尾部修饰本端不支持，解析时据此判为不支持。 */
private data class ParsedRuleLine(
    val typeLiteral: String,
    val content: String,
    val policy: String,
)

private fun parseRuleLine(line: String): ParsedRuleLine? {
    val fields = line.split(',').map { it.trim() }

    if (fields.size != 3) return null
    if (fields.any { it.isEmpty() }) return null

    return ParsedRuleLine(fields[0], fields[1], fields[2])
}

/**
 * 判断一条规则行是否引用了名为 [name] 的规则集。逻辑规则（AND/OR/NOT）把子规则嵌在括号里，
 * 本端不支持导入它们，但仍需在跳过规则集时把这些行报告给用户，所以这里按 token 边界匹配整行，
 * 不依赖行本身能否被 [parseRuleLine] 解析。
 *
 * 对外公开：同名规则集的处置对话框要在计划生成之前就告诉用户「跳过它会连带丢掉哪些规则」。
 */
fun referencesProvider(line: String, name: String): Boolean =
    Regex("(^|[,(\\s])RULE-SET\\s*,\\s*${Regex.escape(name)}($|[,)\\s])", RegexOption.IGNORE_CASE)
        .containsMatchIn(line)

/** 把行内 `RULE-SET,<from>` 改写为 `RULE-SET,<to>`，其余部分原样保留。 */
internal fun rewriteProviderReference(line: String, from: String, to: String): String =
    Regex("(^|[,(\\s])(RULE-SET\\s*,\\s*)${Regex.escape(from)}($|[,)\\s])", RegexOption.IGNORE_CASE)
        .replace(line) { "${it.groupValues[1]}${it.groupValues[2]}$to${it.groupValues[3]}" }

/**
 * 更新间隔的档位收敛：PC 端是任意秒数，本端只有 1/6/12/24 小时四档。
 * 精确命中档位原样返回；缺省（null）按「永不更新」；其余按最近档收敛并给出降级说明——
 * 不静默吞成 NEVER（那会让用户以为再也不更新的规则集从此静默）。
 */
private fun resolveUpdateInterval(seconds: Long?): Pair<RuleProviderUpdateInterval, String?> {
    if (seconds == null) return RuleProviderUpdateInterval.NEVER to null

    RuleProviderUpdateInterval.entries
        .firstOrNull { it.seconds == seconds }
        ?.let { return it to null }

    val nearest = RuleProviderUpdateInterval.entries
        .filter { it.seconds != null }
        .minWithOrNull(compareBy({ kotlin.math.abs(it.seconds!! - seconds) }, { -it.seconds!! }))
        ?: return RuleProviderUpdateInterval.NEVER to null

    val hours = nearest.seconds!! / 3600
    return nearest to "更新间隔 $seconds 秒不在本端档位（1/6/12/24 小时），已按 $hours 小时导入"
}

/**
 * 计算导入计划。
 *
 * @param existingProviderNames 本地该 profile 已有的规则集名字。
 * @param existingRuleLines 本地该 profile 已有的规则行（`TYPE,VALUE,POLICY`），用于逐字节去重。
 * @param decisions 同名规则集的处置选择，键为包内原始名字；未给出的同名项按 [ProviderDecision.Skip]
 *   处理（宁可少写，不覆盖用户本地声明）。
 * @param policyMapping 包内线路名 → 本地线路名。值为空或缺失即视为未映射。
 */
fun buildImportPlan(
    bundle: RuleBundle,
    existingProviderNames: Set<String>,
    existingRuleLines: Set<String>,
    decisions: Map<String, ProviderDecision>,
    policyMapping: Map<String, String>,
): ImportPlan {
    val plannedProviders = ArrayList<PlannedProvider>()
    val skippedProviders = ArrayList<SkippedProvider>()
    val unsupported = ArrayList<UnsupportedRule>()
    val degraded = ArrayList<UnsupportedRule>()
    val renames = HashMap<String, String>()
    val usedFinalNames = HashSet<String>()

    val sourceLines = bundle.sequence.prepend + bundle.sequence.append

    fun skipped(provider: BundleProvider) = SkippedProvider(
        name = provider.name,
        referencingLines = sourceLines.filter { referencesProvider(it, provider.name) },
    )

    for (provider in bundle.providers) {
        val decision = when {
            provider.name !in existingProviderNames -> ProviderDecision.Overwrite
            else -> decisions[provider.name] ?: ProviderDecision.Skip
        }

        val targetName = when (decision) {
            is ProviderDecision.Rename -> decision.newName
            else -> provider.name
        }

        if (decision is ProviderDecision.Skip) {
            // 保留本机同名声明。本机声明必然存在（Skip 只对同名项出现），
            // 引用它的规则照常导入并指向这份声明——跳过只影响「哪份声明生效」，不再连坐规则。
            skippedProviders.add(skipped(provider))
            continue
        }

        if (decision is ProviderDecision.Rename) {
            renames[provider.name] = targetName
        }

        // 同一批次内两个声明落到同一个最终名字时，后到者让位：否则会静默覆盖先写入的声明，
        // 落库条数与预检显示对不上。正常调用方的改名占用名单（含包内新增名）不会走到这里。
        if (targetName in usedFinalNames) {
            renames.remove(provider.name)
            skippedProviders.add(skipped(provider))
            unsupported.add(
                UnsupportedRule(
                    line = "RULE-SET ${provider.name}",
                    reason = "与本次导入的另一规则集最终同名，已跳过",
                )
            )
            continue
        }

        val (interval, intervalDegradation) = resolveUpdateInterval(provider.intervalSeconds)
        val type = RuleProviderType.fromLiteral(provider.type)
        val behavior = RuleProviderBehavior.fromLiteral(provider.behavior)
        val format = RuleProviderFormat.fromLiteral(provider.format)

        // 包内有、本端表达不了的取值：按最近似值导入并如实上报，不静默降级。
        // 说明里不回显无法识别的字面量与 URL——那是包内可控字符串。
        intervalDegradation?.let { degraded.add(UnsupportedRule("RULE-SET ${provider.name}", it)) }
        if (type == null) {
            degraded.add(UnsupportedRule("RULE-SET ${provider.name}", "来源类型字面量本端不认识，已按 http 导入"))
        }
        if (behavior == null) {
            degraded.add(UnsupportedRule("RULE-SET ${provider.name}", "匹配语义字面量本端不认识，已按 classical 导入"))
        }
        if (format == null) {
            degraded.add(UnsupportedRule("RULE-SET ${provider.name}", "文件格式字面量本端不认识，已按 yaml 导入"))
        }

        val candidate = CustomRuleProvider(
            name = targetName,
            type = type ?: RuleProviderType.HTTP,
            behavior = behavior ?: RuleProviderBehavior.CLASSICAL,
            format = format ?: RuleProviderFormat.YAML,
            url = provider.url,
            updateInterval = interval,
        )

        try {
            candidate.validate()
        } catch (e: Exception) {
            // 规则集声明本身不合法（名字越界、URL 非 http/https 等）：不写入，并按引用它的规则一并跳过。
            // 提示里 URL 一律脱敏——它可能带订阅令牌。
            // 若已登记改名则撤销：让引用它的规则回落到原始名字上解析（同名本机声明仍在时照常导入）。
            renames.remove(provider.name)
            skippedProviders.add(skipped(provider))
            unsupported.add(
                UnsupportedRule(
                    line = "RULE-SET ${provider.name} (${maskUrl(provider.url)})",
                    reason = "规则集声明不合法，已跳过",
                )
            )
            continue
        }

        usedFinalNames.add(targetName)
        plannedProviders.add(
            PlannedProvider(
                target = candidate,
                sourceName = provider.name,
                // 覆盖与否看最终名字：改名后的名字撞上本地既有声明时，写入同样是一次覆盖。
                overwrite = targetName in existingProviderNames,
            )
        )
    }

    // 规则引用能落到的名字全集 = 本批将写入的声明（最终名）∪ 本机既有声明。
    // 两边都没有的引用写进去就是悬挂引用，内核会拒绝整份配置——计划阶段单列并如实告知，
    // 不让它进入写入集合（写入侧的事务内校验因此只作兜底，正常不会再触发）。
    val resolvableProviderNames = plannedProviders.map { it.target.name }.toSet() + existingProviderNames

    val plannedRules = ArrayList<PlannedRule>()
    val duplicates = ArrayList<String>()
    val unmapped = LinkedHashSet<String>()

    fun planSection(lines: List<String>, position: RulePosition) {
        for (line in lines) {
            val parsed = parseRuleLine(line)

            if (parsed == null) {
                unsupported.add(UnsupportedRule(line, "规则行不是 类型,内容,策略 的三段形式"))
                continue
            }

            val ruleType = RuleType.fromLiteral(parsed.typeLiteral.uppercase())

            if (ruleType == null) {
                unsupported.add(UnsupportedRule(line, "本版本不支持的规则类型：${parsed.typeLiteral}"))
                continue
            }

            val content = if (ruleType == RuleType.RULE_SET) {
                renames[parsed.content] ?: parsed.content
            } else {
                parsed.content
            }

            if (ruleType == RuleType.RULE_SET && content !in resolvableProviderNames) {
                unsupported.add(
                    UnsupportedRule(
                        line,
                        "引用的规则集「${parsed.content}」不在包内声明中，本机也没有同名规则集",
                    )
                )
                continue
            }

            val mapped = policyMapping[parsed.policy]?.takeIf { it.isNotBlank() }

            if (mapped == null) {
                unmapped.add(parsed.policy)
                continue
            }

            val rule = CustomRule(
                ruleType = ruleType,
                content = content,
                policy = mapped,
                position = position,
            )

            try {
                rule.validate()
            } catch (e: Exception) {
                unsupported.add(UnsupportedRule(line, "规则内容未通过本端校验"))
                continue
            }

            val resultLine = "${ruleType.literal},$content,$mapped"

            if (resultLine in existingRuleLines) {
                duplicates.add(line)
                continue
            }

            plannedRules.add(PlannedRule(rule, line))
        }
    }

    planSection(bundle.sequence.prepend, RulePosition.PREPEND)
    planSection(bundle.sequence.append, RulePosition.APPEND)

    return ImportPlan(
        providers = plannedProviders,
        rules = plannedRules,
        skippedProviders = skippedProviders,
        skippedDuplicateRules = duplicates,
        unsupportedRules = unsupported,
        degradedEntries = degraded,
        ignoredDeleteCount = bundle.sequence.delete.size,
        unmappedPolicies = unmapped.toList(),
    )
}

/**
 * 待用户决策的线路名清单：包内出现的每个线路名配一组候选（内置策略 + 当前 profile 实际可用组名），
 * 同名时预选同名项。
 */
fun defaultPolicyMapping(
    bundlePolicies: List<String>,
    availablePolicies: List<String>,
): Map<String, String> {
    val available = BundleFormat.BUILTIN_POLICIES + availablePolicies

    return bundlePolicies.mapNotNull { policy ->
        available.firstOrNull { it == policy }?.let { policy to it }
    }.toMap()
}
