package com.github.kr328.clash.service.bundle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * 两端格式契约的逐字段对照：本类断言的是 ADR 0001（clash-verge-rev `docs/adr/0001-custom-rule-bundle-format.md`）
 * 字段表里写死的**键名与结构**，而不是本端实现「碰巧产出什么」。
 *
 * 与 [BundleCodecTest] 的分工：那边验行为（往返、拒绝、版本分支），这里验字面契约——
 * 任何一端改了键名，PC 端就读不了本端的包，所以键名变更必须先动 ADR。
 */
class BundleContractTest {
    private val sequence = RuleSequence(
        prepend = listOf("DOMAIN-SUFFIX,example.com,Proxy"),
        append = listOf("RULE-SET,ads,REJECT"),
        delete = listOf("GEOIP,CN,DIRECT"),
    )

    private val providers = listOf(
        BundleProvider(
            name = "ads",
            type = "http",
            behavior = "domain",
            format = "yaml",
            url = "https://example.com/ads.yaml",
            intervalSeconds = 86400,
        )
    )

    private fun entries(): Map<String, ByteArray> = BundleZip.read(
        BundleCodec.writeToBytes(sequence, providers, "1.2.3", Date(0)).inputStream()
    )

    private fun manifest(): JsonObject =
        Json.parseToJsonElement(String(entries().getValue(BundleFormat.MANIFEST_ENTRY))).jsonObject

    @Test
    fun `包内三个 entry 的路径与 ADR 布局一致`() {
        assertEquals(
            listOf("manifest.json", "rules/sequence.yaml", "providers/providers.yaml").sorted(),
            entries().keys.sorted(),
        )
    }

    @Test
    fun `manifest 顶层字段与 ADR 字段表一致`() {
        assertEquals(
            listOf("formatVersion", "generator", "createdAt", "proxyPolicies", "contents"),
            manifest().keys.toList(),
        )
    }

    @Test
    fun `generator 是 app 与 version 两个子字段`() {
        val generator = manifest().getValue("generator").jsonObject

        assertEquals(listOf("app", "version"), generator.keys.toList())
        assertEquals("clash-meta-for-android", generator.getValue("app").toString().trim('"'))
    }

    @Test
    fun `contents 每项是 path sha256 entryCount`() {
        for (entry in manifest().getValue("contents").jsonArray) {
            assertEquals(listOf("path", "sha256", "entryCount"), entry.jsonObject.keys.toList())
        }
    }

    @Test
    fun `sequence yaml 顶层是 prepend append delete 三个数组`() {
        val yaml = String(entries().getValue(BundleFormat.RULES_ENTRY))

        // 逐行首列匹配，避免把规则内容里的同名字符串误判为顶层键。
        assertEquals(
            listOf("append", "delete", "prepend"),
            yaml.lines().mapNotNull { Regex("^([a-z]+):").find(it)?.groupValues?.get(1) }.sorted(),
        )
    }

    @Test
    fun `providers yaml 顶层是 rule-providers 映射`() {
        val yaml = String(entries().getValue(BundleFormat.PROVIDERS_ENTRY))

        assertTrue(yaml.startsWith("rule-providers:"))
    }

    @Test
    fun `规则集条目只带声明字段且不含 path`() {
        val yaml = String(entries().getValue(BundleFormat.PROVIDERS_ENTRY))

        for (field in listOf("type", "behavior", "format", "url", "interval")) {
            assertTrue(field, yaml.contains("$field:"))
        }

        // path 由各端按自己的目录布局派生，进包即是把一端的目录结构强加给另一端。
        assertFalse(yaml.contains("path:"))
    }

    @Test
    fun `formatVersion 是 ADR 当前规定的 1 点 0`() {
        assertEquals("\"1.0\"", manifest().getValue("formatVersion").toString())
    }
}
