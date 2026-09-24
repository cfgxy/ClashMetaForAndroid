package com.github.kr328.clash.service.override

import com.github.kr328.clash.service.model.CustomRule
import com.github.kr328.clash.service.model.RulePosition
import com.github.kr328.clash.service.model.RuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun `append rule lands after existing rules`() {
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
    fun `prepend rule lands before existing rules`() {
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
    fun `empty rule list leaves file byte-identical`() {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        RuleOverrideApplier.applyToFile(file, emptyList())

        assertEquals(baseYaml, file.readText())
    }

    @Test
    fun `invalid rule throws and leaves file byte-identical`() {
        val file = tempFolder.newFile("config.yaml")
        file.writeText(baseYaml)

        assertThrows(RuleOverrideException::class.java) {
            RuleOverrideApplier.applyToFile(
                file,
                listOf(CustomRule(RuleType.IP_CIDR, "not-a-cidr", "DIRECT", RulePosition.APPEND)),
            )
        }

        assertEquals(baseYaml, file.readText())
    }

    @Test
    fun `missing config file throws and does not create it`() {
        val file = tempFolder.root.resolve("missing.yaml")

        assertThrows(RuleOverrideException::class.java) {
            RuleOverrideApplier.applyToFile(
                file,
                listOf(CustomRule(RuleType.DOMAIN, "example.com", "DIRECT", RulePosition.APPEND)),
            )
        }

        assertEquals(false, file.exists())
    }

    @Test
    fun `malformed yaml throws and leaves file byte-identical`() {
        val file = tempFolder.newFile("config.yaml")
        val malformed = "rules: [unclosed"
        file.writeText(malformed)

        assertThrows(RuleOverrideException::class.java) {
            RuleOverrideApplier.applyToFile(
                file,
                listOf(CustomRule(RuleType.DOMAIN, "example.com", "DIRECT", RulePosition.APPEND)),
            )
        }

        assertEquals(malformed, file.readText())
    }
}
