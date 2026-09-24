package com.github.kr328.clash.service.override

import com.github.kr328.clash.service.model.CustomRule
import com.github.kr328.clash.service.model.RulePosition
import com.github.kr328.clash.service.model.RuleType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuleOverrideApplierTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val baseYaml = """
        port: 7890
        rules:
          - DOMAIN,example.com,DIRECT
          - MATCH,DIRECT
    """.trimIndent()

    @Test
    fun `append rule lands after existing rules`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        RuleOverrideApplier.applyToFile(
            file,
            listOf(CustomRule(RuleType.DOMAIN, "example.org", "REJECT", RulePosition.APPEND)),
        )

        val text = file.readText()
        val domainIndex = text.indexOf("DOMAIN,example.com,DIRECT")
        val matchIndex = text.indexOf("MATCH,DIRECT")
        val appendedIndex = text.indexOf("DOMAIN,example.org,REJECT")
        assert(domainIndex in 0 until matchIndex)
        assert(matchIndex in 0 until appendedIndex)
    }

    @Test
    fun `prepend rule lands before existing rules`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        RuleOverrideApplier.applyToFile(
            file,
            listOf(CustomRule(RuleType.DOMAIN, "example.org", "REJECT", RulePosition.PREPEND)),
        )

        val text = file.readText()
        val prependedIndex = text.indexOf("DOMAIN,example.org,REJECT")
        val domainIndex = text.indexOf("DOMAIN,example.com,DIRECT")
        assert(prependedIndex in 0 until domainIndex)
    }

    @Test
    fun `empty rule list leaves file byte-identical`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        RuleOverrideApplier.applyToFile(file, emptyList())

        assertEquals(baseYaml, file.readText())
    }

    @Test
    fun `invalid rule throws and leaves file byte-identical`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        assertThrows(RuleOverrideException::class.java) {
            runBlocking {
                RuleOverrideApplier.applyToFile(
                    file,
                    listOf(CustomRule(RuleType.IP_CIDR, "not-a-cidr", "DIRECT", RulePosition.APPEND)),
                )
            }
        }

        assertEquals(baseYaml, file.readText())
    }

    @Test
    fun `missing config file throws and does not create it`() = runBlocking {
        val file = tempFolder.root.resolve("missing.yaml")

        assertThrows(RuleOverrideException::class.java) {
            runBlocking {
                RuleOverrideApplier.applyToFile(
                    file,
                    listOf(CustomRule(RuleType.DOMAIN, "example.com", "DIRECT", RulePosition.APPEND)),
                )
            }
        }

        assertEquals(false, file.exists())
    }

    @Test
    fun `malformed yaml throws and leaves file byte-identical`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        val malformed = "rules: [unclosed"
        file.writeText(malformed)

        assertThrows(RuleOverrideException::class.java) {
            runBlocking {
                RuleOverrideApplier.applyToFile(
                    file,
                    listOf(CustomRule(RuleType.DOMAIN, "example.com", "DIRECT", RulePosition.APPEND)),
                )
            }
        }

        assertEquals(malformed, file.readText())
    }

    // ---- A1：同一文件连续 apply 两次，结果与 apply 一次字节一致（不重复叠加） ----

    @Test
    fun `applying the same rules twice is idempotent`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        val rules = listOf(
            CustomRule(RuleType.DOMAIN, "example.org", "REJECT", RulePosition.PREPEND),
            CustomRule(RuleType.DOMAIN_SUFFIX, "ads.example.com", "REJECT", RulePosition.APPEND),
        )

        RuleOverrideApplier.applyToFile(file, rules)
        val afterFirst = file.readText()

        // 模拟 Type.File profile 每次 apply 前 processingDir 都是上一轮已合并结果的复制品：
        // 用同一批规则、同一份已含注入内容的文件再应用一次。
        RuleOverrideApplier.applyToFile(file, rules)
        val afterSecond = file.readText()

        assertEquals(afterFirst, afterSecond)

        // 且没有重复叠加：每条注入规则只出现一次。
        val occurrences = Regex("DOMAIN,example.org,REJECT").findAll(afterSecond).count()
        assertEquals(1, occurrences)
    }

    @Test
    fun `applying three times stays byte-identical to applying once`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        val rules = listOf(CustomRule(RuleType.DOMAIN, "example.org", "REJECT", RulePosition.APPEND))

        RuleOverrideApplier.applyToFile(file, rules)
        val once = file.readText()

        repeat(2) { RuleOverrideApplier.applyToFile(file, rules) }
        val thrice = file.readText()

        assertEquals(once, thrice)
    }

    @Test
    fun `deleting all rules after a previous apply strips injected lines back out`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        val rules = listOf(CustomRule(RuleType.DOMAIN, "example.org", "REJECT", RulePosition.PREPEND))
        RuleOverrideApplier.applyToFile(file, rules)
        assertTrue(file.readText().contains("DOMAIN,example.org,REJECT"))

        RuleOverrideApplier.applyToFile(file, emptyList())

        assertFalse(file.readText().contains("DOMAIN,example.org,REJECT"))
    }

    // ---- A2：合并后内核级校验失败时，文件回退到应用前内容，且不视为已应用 ----

    @Test
    fun `kernel validation failure rolls back file to pre-merge content`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        assertThrows(RuleOverrideException::class.java) {
            runBlocking {
                RuleOverrideApplier.applyToFile(
                    file,
                    listOf(CustomRule(RuleType.DOMAIN, "example.com", "NotAGroup", RulePosition.APPEND)),
                ) {
                    // 模拟 Clash.fetchAndValid 对语义非法策略名的内核级拒绝。
                    throw IllegalStateException("proxy group 'NotAGroup' not found")
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

        RuleOverrideApplier.applyToFile(
            file,
            listOf(CustomRule(RuleType.DOMAIN, "example.org", "DIRECT", RulePosition.APPEND)),
        ) {
            seenDuringValidation = file.readText()
        }

        assertTrue(seenDuringValidation?.contains("DOMAIN,example.org,DIRECT") == true)
        assertEquals(file.readText(), seenDuringValidation)
    }

    @Test
    fun `kernel validation failure does not register the rules as applied`() = runBlocking {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        val badRules = listOf(CustomRule(RuleType.DOMAIN, "example.com", "NotAGroup", RulePosition.APPEND))

        assertThrows(RuleOverrideException::class.java) {
            runBlocking {
                RuleOverrideApplier.applyToFile(file, badRules) {
                    throw IllegalStateException("proxy group 'NotAGroup' not found")
                }
            }
        }

        // 拒绝后再次以空规则集应用：既然上一轮从未真正生效（marker 未写入），
        // 这次必须是纯粹的空操作，文件保持不变——否则会误删本不存在的“注入内容”。
        RuleOverrideApplier.applyToFile(file, emptyList())
        assertEquals(baseYaml, file.readText())
    }
}
