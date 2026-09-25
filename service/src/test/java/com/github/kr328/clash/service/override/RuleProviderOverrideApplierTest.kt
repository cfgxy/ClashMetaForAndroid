package com.github.kr328.clash.service.override

import com.github.kr328.clash.service.model.CustomRuleProvider
import com.github.kr328.clash.service.model.RuleProviderBehavior
import com.github.kr328.clash.service.model.RuleProviderFormat
import com.github.kr328.clash.service.model.RuleProviderType
import com.github.kr328.clash.service.model.RuleProviderUpdateInterval
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuleProviderOverrideApplierTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val baseYaml = """
        port: 7890
        rules:
          - MATCH,DIRECT
    """.trimIndent()

    private fun httpProvider(
        name: String = "ads",
        interval: RuleProviderUpdateInterval = RuleProviderUpdateInterval.HOURLY,
    ) = CustomRuleProvider(
        name = name,
        type = RuleProviderType.HTTP,
        behavior = RuleProviderBehavior.DOMAIN,
        format = RuleProviderFormat.YAML,
        url = "https://example.com/ads.yaml?token=secret",
        updateInterval = interval,
    )

    private fun fileProvider(name: String = "local") = CustomRuleProvider(
        name = name,
        type = RuleProviderType.FILE,
        behavior = RuleProviderBehavior.CLASSICAL,
        format = RuleProviderFormat.TEXT,
        url = "",
        updateInterval = RuleProviderUpdateInterval.NEVER,
    )

    @Test
    fun `provider lands under rule-providers with expected entry shape`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        RuleProviderOverrideApplier.applyToFile(file, listOf(httpProvider()))

        val text = file.readText()
        assertTrue(text.contains("rule-providers"))
        assertTrue(text.contains("type: http"))
        assertTrue(text.contains("behavior: domain"))
        assertTrue(text.contains("format: yaml"))
        assertTrue(text.contains("path: ./rule_providers/ads.yaml"))
        assertTrue(text.contains("interval: 3600"))
        assertTrue(text.contains("url:"))
        assertTrue(text.contains("https://example.com/ads.yaml?token=secret"))
    }

    @Test
    fun `FILE type provider never writes url field`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        RuleProviderOverrideApplier.applyToFile(file, listOf(fileProvider()))

        val text = file.readText()
        assertTrue(text.contains("type: file"))
        assertFalse(text.contains("url:"))
        assertFalse(text.contains("interval:"))
    }

    @Test
    fun `empty provider list leaves file byte-identical`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        RuleProviderOverrideApplier.applyToFile(file, emptyList())

        assertEquals(baseYaml, file.readText())
    }

    @Test
    fun `missing config file throws and does not create it`() = runBlocking {
        val file = tempFolder.root.resolve("missing.yaml")

        assertThrows(RuleOverrideException::class.java) {
            runBlocking {
                RuleProviderOverrideApplier.applyToFile(file, listOf(httpProvider()))
            }
        }

        assertEquals(false, file.exists())
    }

    @Test
    fun `malformed yaml throws and leaves file byte-identical`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        val malformed = "rule-providers: [unclosed"
        file.writeText(malformed)

        assertThrows(RuleOverrideException::class.java) {
            runBlocking {
                RuleProviderOverrideApplier.applyToFile(file, listOf(httpProvider()))
            }
        }

        assertEquals(malformed, file.readText())
    }

    // ---- 幂等语义：与 RuleOverrideApplier 一致，仅换成 map key 覆盖而非行内容剔除 ----

    @Test
    fun `applying the same provider twice is idempotent`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        val providers = listOf(httpProvider(), fileProvider())

        RuleProviderOverrideApplier.applyToFile(file, providers)
        val afterFirst = file.readText()

        // 模拟 Type.File profile 每次 apply 前 processingDir 都是上一轮已合并结果的复制品。
        RuleProviderOverrideApplier.applyToFile(file, providers)
        val afterSecond = file.readText()

        assertEquals(afterFirst, afterSecond)

        val occurrences = Regex("^\\s*ads:", RegexOption.MULTILINE).findAll(afterSecond).count()
        assertEquals(1, occurrences)
    }

    @Test
    fun `applying three times stays byte-identical to applying once`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        val providers = listOf(httpProvider())

        RuleProviderOverrideApplier.applyToFile(file, providers)
        val once = file.readText()

        repeat(2) { RuleProviderOverrideApplier.applyToFile(file, providers) }
        val thrice = file.readText()

        assertEquals(once, thrice)
    }

    @Test
    fun `deleting all providers after a previous apply strips rule-providers section back out`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        RuleProviderOverrideApplier.applyToFile(file, listOf(httpProvider()))
        assertTrue(file.readText().contains("rule-providers"))

        RuleProviderOverrideApplier.applyToFile(file, emptyList())

        assertFalse(file.readText().contains("rule-providers"))
    }

    @Test
    fun `renaming a provider removes the old key instead of leaving it orphaned`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        RuleProviderOverrideApplier.applyToFile(file, listOf(httpProvider(name = "old-name")))
        assertTrue(file.readText().contains("old-name:"))

        RuleProviderOverrideApplier.applyToFile(file, listOf(httpProvider(name = "new-name")))
        val text = file.readText()
        assertFalse(text.contains("old-name:"))
        assertTrue(text.contains("new-name:"))
    }

    // ---- 内核级校验失败时，文件回退到应用前内容，且不视为已应用 ----

    @Test
    fun `kernel validation failure rolls back file to pre-merge content`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        assertThrows(RuleOverrideException::class.java) {
            runBlocking {
                RuleProviderOverrideApplier.applyToFile(file, listOf(httpProvider())) {
                    // 模拟 Clash.fetchAndValid 对规则集定义的内核级拒绝。
                    throw IllegalStateException("invalid rule-provider")
                }
            }
        }

        assertEquals(baseYaml, file.readText())
    }

    @Test
    fun `kernel validation receives the merged file before committing`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        var seenDuringValidation: String? = null

        RuleProviderOverrideApplier.applyToFile(file, listOf(httpProvider())) {
            seenDuringValidation = file.readText()
        }

        assertTrue(seenDuringValidation?.contains("rule-providers") == true)
        assertEquals(file.readText(), seenDuringValidation)
    }

    @Test
    fun `kernel validation failure does not register the provider as applied`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        val provider = httpProvider()

        assertThrows(RuleOverrideException::class.java) {
            runBlocking {
                RuleProviderOverrideApplier.applyToFile(file, listOf(provider)) {
                    throw IllegalStateException("invalid rule-provider")
                }
            }
        }

        // 拒绝后再次以空列表应用：上一轮从未真正生效（marker 未写入），必须是纯粹的空操作。
        RuleProviderOverrideApplier.applyToFile(file, emptyList())
        assertEquals(baseYaml, file.readText())
    }
}
