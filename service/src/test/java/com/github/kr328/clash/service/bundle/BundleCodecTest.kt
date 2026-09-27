package com.github.kr328.clash.service.bundle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Date

/**
 * 打包/解包的往返一致性、版本分支与全部拒绝原因。
 *
 * 构造非法包时一律绕过 [BundleCodec.write] 直接拼 zip：写侧永远产出合法包，只有手工拼装
 * 才能覆盖「对端或攻击者给了一个坏包」这条真实路径。
 */
class BundleCodecTest {
    private val sequence = RuleSequence(
        prepend = listOf("DOMAIN-SUFFIX,example.com,PROXY", "RULE-SET,ad-block,REJECT"),
        append = listOf("GEOIP,CN,DIRECT"),
        delete = listOf("DOMAIN,tracker.example.org,DIRECT"),
    )

    private val providers = listOf(
        BundleProvider(
            name = "ad-block",
            type = "http",
            behavior = "domain",
            format = "yaml",
            url = "https://rules.example.com/ad.yaml?token=secret",
            intervalSeconds = 86400L,
        )
    )

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { BundleZip.write(it, entries.toList()) }.toByteArray()

    private fun manifestJson(
        formatVersion: String,
        contents: List<Triple<String, String, Int>> = emptyList(),
    ): ByteArray {
        val contentsJson = contents.joinToString(",") { (path, sha, count) ->
            """{"path":"$path","sha256":"$sha","entryCount":$count}"""
        }

        return (
            """{"formatVersion":"$formatVersion",""" +
                """"generator":{"app":"clash-verge-rev","version":"2.0.0"},""" +
                """"createdAt":"2026-09-26T00:00:00.000Z",""" +
                """"proxyPolicies":[],"contents":[$contentsJson]}"""
            ).toByteArray()
    }

    private fun rejectionOf(bytes: ByteArray): BundleRejection =
        assertThrows(BundleImportException::class.java) { BundleCodec.read(bytes) }.rejection

    // region 往返

    @Test
    fun `往返一致 含 delete 数组`() {
        val bytes = BundleCodec.writeToBytes(sequence, providers, "3.1.0", Date(0L))
        val result = BundleCodec.read(bytes)

        assertEquals(sequence, result.bundle.sequence)
        assertEquals(providers, result.bundle.providers)
        assertFalse(result.producedByNewerMinor)
    }

    @Test
    fun `往返后 manifest 字段与格式契约一致`() {
        val bytes = BundleCodec.writeToBytes(sequence, providers, "3.1.0", Date(0L))
        val manifest = BundleCodec.read(bytes).bundle.manifest

        assertEquals(BundleFormat.VERSION, manifest.formatVersion)
        assertEquals(BundleFormat.GENERATOR_APP, manifest.generatorApp)
        assertEquals("3.1.0", manifest.generatorVersion)
        assertEquals("1970-01-01T00:00:00.000Z", manifest.createdAt)
        // delete 里的线路名不进 proxyPolicies：那些行不会写入本地。
        assertEquals(listOf("DIRECT", "PROXY", "REJECT"), manifest.proxyPolicies)
        assertEquals(
            listOf(BundleFormat.RULES_ENTRY, BundleFormat.PROVIDERS_ENTRY),
            manifest.contents.map { it.path },
        )
        assertEquals(listOf(4, 1), manifest.contents.map { it.entryCount })
    }

    @Test
    fun `manifest 声明的摘要与实际内容匹配`() {
        val bytes = BundleCodec.writeToBytes(sequence, providers, "3.1.0", Date(0L))
        val entries = BundleZip.read(bytes.inputStream())
        val manifest = BundleCodec.read(bytes).bundle.manifest

        for (declared in manifest.contents) {
            assertEquals(declared.path, declared.sha256, BundleZip.sha256Hex(entries.getValue(declared.path)))
        }
    }

    @Test
    fun `空规则序列往返为空`() {
        val bytes = BundleCodec.writeToBytes(RuleSequence(), emptyList(), "3.1.0", Date(0L))
        val result = BundleCodec.read(bytes)

        assertTrue(result.bundle.sequence.isEmpty)
        assertTrue(result.bundle.providers.isEmpty())
    }

    // endregion

    // region 版本分支

    @Test
    fun `相同 minor 不标记新版本`() {
        val sequenceYaml = BundleCodec.buildSequenceYaml(RuleSequence(append = listOf("GEOIP,CN,DIRECT")))
        val bytes = zipOf(
            BundleFormat.MANIFEST_ENTRY to manifestJson("1.0"),
            BundleFormat.RULES_ENTRY to sequenceYaml,
        )

        assertFalse(BundleCodec.read(bytes).producedByNewerMinor)
    }

    @Test
    fun `更高 minor 放行并标记`() {
        val sequenceYaml = BundleCodec.buildSequenceYaml(RuleSequence(append = listOf("GEOIP,CN,DIRECT")))
        val bytes = zipOf(
            BundleFormat.MANIFEST_ENTRY to manifestJson("1.7"),
            BundleFormat.RULES_ENTRY to sequenceYaml,
        )
        val result = BundleCodec.read(bytes)

        assertTrue(result.producedByNewerMinor)
        assertEquals(listOf("GEOIP,CN,DIRECT"), result.bundle.sequence.append)
    }

    @Test
    fun `更高 major 拒绝整包`() {
        val bytes = zipOf(
            BundleFormat.MANIFEST_ENTRY to manifestJson("2.0"),
            BundleFormat.RULES_ENTRY to BundleCodec.buildSequenceYaml(RuleSequence()),
        )

        assertEquals(BundleRejection.FormatVersionUnsupported("2.0"), rejectionOf(bytes))
    }

    @Test
    fun `更低 major 同样拒绝整包`() {
        val bytes = zipOf(
            BundleFormat.MANIFEST_ENTRY to manifestJson("0.9"),
            BundleFormat.RULES_ENTRY to BundleCodec.buildSequenceYaml(RuleSequence()),
        )

        assertEquals(BundleRejection.FormatVersionUnsupported("0.9"), rejectionOf(bytes))
    }

    // endregion

    // region 拒绝原因

    @Test
    fun `拒绝原因 非 zip`() {
        val rejection = rejectionOf("这不是一个 zip 文件".toByteArray())

        assertTrue(rejection is BundleRejection.NotAZip)
    }

    @Test
    fun `拒绝原因 空 zip`() {
        // 合法的空 zip（只有 end-of-central-directory）也没有任何可用条目。
        val empty = ByteArrayOutputStream().also { BundleZip.write(it, emptyList()) }.toByteArray()

        assertTrue(rejectionOf(empty) is BundleRejection.NotAZip)
    }

    @Test
    fun `拒绝原因 缺少 manifest`() {
        val bytes = zipOf(BundleFormat.RULES_ENTRY to BundleCodec.buildSequenceYaml(RuleSequence()))

        assertEquals(BundleRejection.ManifestMissing, rejectionOf(bytes))
    }

    @Test
    fun `拒绝原因 manifest 不是合法 JSON`() {
        val bytes = zipOf(BundleFormat.MANIFEST_ENTRY to "{ not json".toByteArray())

        assertTrue(rejectionOf(bytes) is BundleRejection.ManifestInvalid)
    }

    @Test
    fun `拒绝原因 manifest 缺 formatVersion`() {
        val bytes = zipOf(BundleFormat.MANIFEST_ENTRY to """{"generator":{}}""".toByteArray())

        assertTrue(rejectionOf(bytes) is BundleRejection.ManifestInvalid)
    }

    @Test
    fun `拒绝原因 entry 含父目录段`() {
        val bytes = zipOf(
            "../${BundleFormat.MANIFEST_ENTRY}" to manifestJson("1.0"),
            BundleFormat.RULES_ENTRY to BundleCodec.buildSequenceYaml(RuleSequence()),
        )

        assertEquals(BundleRejection.UnsafeEntryPath, rejectionOf(bytes))
    }

    @Test
    fun `拒绝原因 entry 为绝对路径`() {
        val bytes = zipOf(
            "/${BundleFormat.MANIFEST_ENTRY}" to manifestJson("1.0"),
            BundleFormat.RULES_ENTRY to BundleCodec.buildSequenceYaml(RuleSequence()),
        )

        assertEquals(BundleRejection.UnsafeEntryPath, rejectionOf(bytes))
    }

    @Test
    fun `拒绝原因 缺少规则序列文件`() {
        val bytes = zipOf(BundleFormat.MANIFEST_ENTRY to manifestJson("1.0"))

        assertEquals(BundleRejection.ContentMissing(BundleFormat.RULES_ENTRY), rejectionOf(bytes))
    }

    @Test
    fun `拒绝原因 内容与声明摘要不符`() {
        val sequenceYaml = BundleCodec.buildSequenceYaml(RuleSequence(append = listOf("GEOIP,CN,DIRECT")))
        val bytes = zipOf(
            BundleFormat.MANIFEST_ENTRY to manifestJson(
                "1.0",
                listOf(Triple(BundleFormat.RULES_ENTRY, "0".repeat(64), 1)),
            ),
            BundleFormat.RULES_ENTRY to sequenceYaml,
        )

        assertEquals(BundleRejection.ContentCorrupt(BundleFormat.RULES_ENTRY), rejectionOf(bytes))
    }

    @Test
    fun `声明摘要匹配时放行`() {
        val sequenceYaml = BundleCodec.buildSequenceYaml(RuleSequence(append = listOf("GEOIP,CN,DIRECT")))
        val bytes = zipOf(
            BundleFormat.MANIFEST_ENTRY to manifestJson(
                "1.0",
                listOf(Triple(BundleFormat.RULES_ENTRY, BundleZip.sha256Hex(sequenceYaml), 1)),
            ),
            BundleFormat.RULES_ENTRY to sequenceYaml,
        )

        assertEquals(listOf("GEOIP,CN,DIRECT"), BundleCodec.read(bytes).bundle.sequence.append)
    }

    @Test
    fun `拒绝原因 规则序列不是合法文档`() {
        val bytes = zipOf(
            BundleFormat.MANIFEST_ENTRY to manifestJson("1.0"),
            BundleFormat.RULES_ENTRY to "- 顶层是序列不是映射\n".toByteArray(),
        )

        assertTrue(rejectionOf(bytes) is BundleRejection.ContentInvalid)
    }

    @Test
    fun `拒绝原因 包体超出解包上限`() {
        val huge = ByteArray(BundleFormat.MAX_ENTRY_BYTES.toInt() + 1)
        val bytes = zipOf(
            BundleFormat.MANIFEST_ENTRY to manifestJson("1.0"),
            BundleFormat.RULES_ENTRY to huge,
        )

        assertTrue(rejectionOf(bytes) is BundleRejection.TooLarge)
    }

    @Test
    fun `拒绝原因 entry 数量超出上限`() {
        val entries = (0..BundleFormat.MAX_ENTRIES).map { "padding/$it.txt" to ByteArray(1) }
        val bytes = ByteArrayOutputStream().also { BundleZip.write(it, entries) }.toByteArray()

        assertTrue(rejectionOf(bytes) is BundleRejection.TooLarge)
    }

    // endregion

    @Test
    fun `规则集文件缺失视为没有规则集 不算损包`() {
        val sequenceYaml = BundleCodec.buildSequenceYaml(RuleSequence(append = listOf("GEOIP,CN,DIRECT")))
        val bytes = zipOf(
            BundleFormat.MANIFEST_ENTRY to manifestJson("1.0"),
            BundleFormat.RULES_ENTRY to sequenceYaml,
        )

        assertTrue(BundleCodec.read(bytes).bundle.providers.isEmpty())
    }

    @Test
    fun `规则集条目缺 interval 时解析为不自动更新`() {
        val providersYaml = """
            rule-providers:
              ad-block:
                type: http
                behavior: domain
                format: yaml
                url: https://rules.example.com/ad.yaml
        """.trimIndent().toByteArray()

        val bytes = zipOf(
            BundleFormat.MANIFEST_ENTRY to manifestJson("1.0"),
            BundleFormat.RULES_ENTRY to BundleCodec.buildSequenceYaml(RuleSequence()),
            BundleFormat.PROVIDERS_ENTRY to providersYaml,
        )
        val parsed = BundleCodec.read(bytes).bundle.providers.single()

        assertEquals("ad-block", parsed.name)
        assertEquals(null, parsed.intervalSeconds)
    }

    @Test
    fun `manifest 字段被写成对象时按拒绝分类处理`() {
        // jsonPrimitive 遇到非原始类型会抛 IllegalArgumentException，
        // 必须收编进 ManifestInvalid，不能以裸异常落到界面通用兜底。
        val bytes = zipOf(
            BundleFormat.MANIFEST_ENTRY to
                """{"formatVersion":{"major":1,"minor":0},"generator":{},"createdAt":"","proxyPolicies":[],"contents":[]}"""
                    .toByteArray(),
        )

        assertEquals(
            BundleRejection.ManifestInvalid::class,
            rejectionOf(bytes)::class,
        )
    }

    @Test
    fun `空 sequence 文档与空 providers 文档同口径按空节放行`() {
        val bytes = zipOf(
            BundleFormat.MANIFEST_ENTRY to manifestJson("1.0"),
            BundleFormat.RULES_ENTRY to "".toByteArray(),
            BundleFormat.PROVIDERS_ENTRY to BundleCodec.buildProvidersYaml(providers),
        )
        val bundle = BundleCodec.read(bytes).bundle

        assertEquals(RuleSequence(emptyList(), emptyList(), emptyList()), bundle.sequence)
        assertEquals(providers, bundle.providers)
    }
}
