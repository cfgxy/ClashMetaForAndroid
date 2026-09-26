package com.github.kr328.clash.service.override

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuleSeqTest {
    @Test
    fun `prepend inserts before existing rules`() {
        val root = mutableMapOf<String, Any?>("rules" to listOf("DOMAIN,example.com,DIRECT"))
        RuleSeq.apply(root, prepend = listOf("DOMAIN,example.org,REJECT"), append = emptyList())

        @Suppress("UNCHECKED_CAST")
        val rules = root["rules"] as List<String>
        assertEquals(listOf("DOMAIN,example.org,REJECT", "DOMAIN,example.com,DIRECT"), rules)
    }

    @Test
    fun `append inserts after existing rules`() {
        val root = mutableMapOf<String, Any?>("rules" to listOf("DOMAIN,example.com,DIRECT"))
        RuleSeq.apply(root, prepend = emptyList(), append = listOf("MATCH,DIRECT"))

        @Suppress("UNCHECKED_CAST")
        val rules = root["rules"] as List<String>
        assertEquals(listOf("DOMAIN,example.com,DIRECT", "MATCH,DIRECT"), rules)
    }

    @Test
    fun `prepend and append combined preserve order`() {
        val root = mutableMapOf<String, Any?>("rules" to listOf("DOMAIN,example.com,DIRECT"))
        RuleSeq.apply(
            root,
            prepend = listOf("DOMAIN,example.org,REJECT"),
            append = listOf("MATCH,DIRECT"),
        )

        @Suppress("UNCHECKED_CAST")
        val rules = root["rules"] as List<String>
        assertEquals(
            listOf("DOMAIN,example.org,REJECT", "DOMAIN,example.com,DIRECT", "MATCH,DIRECT"),
            rules,
        )
    }

    @Test
    fun `no-op when both prepend and append empty`() {
        val original = listOf("DOMAIN,example.com,DIRECT")
        val root = mutableMapOf<String, Any?>("rules" to original)
        val result = RuleSeq.apply(root, prepend = emptyList(), append = emptyList())

        assertEquals(original, result["rules"])
    }

    @Test
    fun `missing rules key treated as empty list`() {
        val root = mutableMapOf<String, Any?>()
        RuleSeq.apply(root, prepend = listOf("DOMAIN,example.com,DIRECT"), append = emptyList())

        @Suppress("UNCHECKED_CAST")
        val rules = root["rules"] as List<String>
        assertEquals(listOf("DOMAIN,example.com,DIRECT"), rules)
    }

    @Test
    fun `non-list rules field throws`() {
        val root = mutableMapOf<String, Any?>("rules" to "not-a-list")
        assertThrows(IllegalStateException::class.java) {
            RuleSeq.apply(root, prepend = listOf("DOMAIN,example.com,DIRECT"), append = emptyList())
        }
    }
}
