package com.github.kr328.clash.service.bundle

/**
 * 自定义规则包格式，与桌面端（clash-verge-rev）共享同一契约，规范本体见
 * `docs/adr/0001-custom-rule-bundle-format.md`。
 *
 * 包内只承载「用户自己定义的那一层」：规则序列（prepend/append/delete）与用户自己声明的
 * rule-providers（只有声明，没有内容）。订阅源自带的 rules / rule-providers 不读、不打包、
 * 不修改——订阅自己维护自己的规则，内核自己重新抓取规则集内容。
 */
object BundleFormat {
    /** `major.minor`：major 不同拒绝整包；minor 更高放行并提示忽略了未识别字段。 */
    const val VERSION = "1.0"
    const val MAJOR = 1
    const val MINOR = 0

    const val MANIFEST_ENTRY = "manifest.json"
    const val RULES_ENTRY = "rules/sequence.yaml"
    const val PROVIDERS_ENTRY = "providers/providers.yaml"

    /** 本端生成的包在 manifest.generator.app 里的标识，桌面端为 `clash-verge-rev`。 */
    const val GENERATOR_APP = "clash-meta-for-android"

    /**
     * 解包资源上限。包内固定只有三个小文本文件，这里的上限用于在解压前截断
     * 被刻意构造的超大/超多 entry（zip bomb），不是对正常包的功能限制。
     */
    const val MAX_ENTRIES = 64
    const val MAX_ENTRY_BYTES = 4L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 16L * 1024 * 1024

    /** 内核内置策略，永远可作为线路名映射的目标，不依赖当前配置的 proxy-groups。 */
    val BUILTIN_POLICIES = listOf("DIRECT", "REJECT", "REJECT-DROP", "PASS")

    private val VERSION_REGEX = Regex("^(\\d+)\\.(\\d+)$")

    fun parseVersion(raw: String?): FormatVersion? {
        val matched = VERSION_REGEX.matchEntire(raw?.trim() ?: return null) ?: return null

        return FormatVersion(matched.groupValues[1].toInt(), matched.groupValues[2].toInt())
    }
}

data class FormatVersion(val major: Int, val minor: Int)

/**
 * 规则序列的三个数组，顺序原样保留。
 *
 * `delete` 的语义是「抑制订阅源自带的某几条规则行」。Android 侧当前没有抑制能力（规则只做
 * prepend/append，见 [com.github.kr328.clash.service.override.RuleSeq]），所以本端导出时该数组
 * 恒为空；导入桌面端带 `delete` 的包时，本类型仍完整保留该数组，由导入流程显式告知用户忽略了
 * 多少条——字段进出都不丢，避免往返时静默改变包内容。
 */
data class RuleSequence(
    val prepend: List<String> = emptyList(),
    val append: List<String> = emptyList(),
    val delete: List<String> = emptyList(),
) {
    val entryCount: Int
        get() = prepend.size + append.size + delete.size

    val isEmpty: Boolean
        get() = entryCount == 0
}

/**
 * 包内的一条规则集声明。字段与桌面端 `providers/providers.yaml` 的条目一致；
 * `path` 不进包——它是各端按自己的目录布局派生的本地缓存位置，跨端没有意义。
 */
data class BundleProvider(
    val name: String,
    val type: String,
    val behavior: String,
    val format: String,
    val url: String,
    val intervalSeconds: Long?,
)

data class BundleContentEntry(
    val path: String,
    val sha256: String,
    val entryCount: Int,
)

data class BundleManifest(
    val formatVersion: String,
    val generatorApp: String,
    val generatorVersion: String,
    val createdAt: String,
    /** 包内 prepend/append 规则引用到的全部线路名（去重排序），供导入端直接生成映射界面。 */
    val proxyPolicies: List<String>,
    val contents: List<BundleContentEntry>,
)

data class RuleBundle(
    val manifest: BundleManifest,
    val sequence: RuleSequence,
    val providers: List<BundleProvider>,
)

data class ReadBundleResult(
    val bundle: RuleBundle,
    /** 包的 minor 高于本端支持：已放行，但包内可能存在本端未识别并忽略的字段。 */
    val producedByNewerMinor: Boolean,
)

/**
 * 取一条规则行的线路策略。规则行形如 `TYPE,VALUE,POLICY`，部分类型允许在策略之后再跟
 * `no-resolve`，此时策略是倒数第二段。
 */
fun extractProxyPolicy(ruleLine: String): String? {
    val fields = ruleLine.split(',').map { it.trim() }

    if (fields.size < 2) return null

    val last = fields.last()
    val policy = if (last.equals("no-resolve", ignoreCase = true)) {
        fields.getOrNull(fields.size - 2)
    } else {
        last
    }

    return policy?.takeIf { it.isNotEmpty() }
}

/**
 * 导入后会真正写入的规则所引用的线路名。`delete` 里的行是拿去和订阅自有规则比对的，
 * 不会写入本地，因此不需要本地存在对应策略，也就不进映射清单。
 */
fun collectProxyPolicies(sequence: RuleSequence): List<String> =
    (sequence.prepend + sequence.append)
        .mapNotNull { extractProxyPolicy(it) }
        .distinct()
        .sorted()
