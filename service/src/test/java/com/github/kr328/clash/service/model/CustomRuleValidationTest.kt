package com.github.kr328.clash.service.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CustomRuleValidationTest {
    @Test
    fun `valid domain rule formats to rule line`() {
        val rule = CustomRule(RuleType.DOMAIN, "example.com", "DIRECT", RulePosition.APPEND)
        assertEquals("DOMAIN,example.com,DIRECT", rule.toRuleLine())
    }

    @Test
    fun `valid ip-cidr rule formats to rule line`() {
        val rule = CustomRule(RuleType.IP_CIDR, "192.168.0.0/16", "DIRECT", RulePosition.PREPEND)
        assertEquals("IP-CIDR,192.168.0.0/16,DIRECT", rule.toRuleLine())
    }

    @Test
    fun `invalid ip-cidr rejected`() {
        val rule = CustomRule(RuleType.IP_CIDR, "not-a-cidr", "DIRECT", RulePosition.APPEND)
        assertThrows(RuleSyntaxException::class.java) { rule.validate() }
    }

    @Test
    fun `valid ip-cidr6 rule formats to rule line`() {
        val rule = CustomRule(RuleType.IP_CIDR6, "2001:db8::/32", "DIRECT", RulePosition.APPEND)
        assertEquals("IP-CIDR6,2001:db8::/32,DIRECT", rule.toRuleLine())
    }

    @Test
    fun `invalid ip-cidr6 rejected`() {
        val rule = CustomRule(RuleType.IP_CIDR6, "not-a-cidr6", "DIRECT", RulePosition.APPEND)
        assertThrows(RuleSyntaxException::class.java) { rule.validate() }
    }

    @Test
    fun `dst-port in valid range accepted`() {
        val rule = CustomRule(RuleType.DST_PORT, "443", "DIRECT", RulePosition.APPEND)
        assertEquals("DST-PORT,443,DIRECT", rule.toRuleLine())
    }

    @Test
    fun `dst-port out of range rejected`() {
        val rule = CustomRule(RuleType.DST_PORT, "70000", "DIRECT", RulePosition.APPEND)
        assertThrows(RuleSyntaxException::class.java) { rule.validate() }
    }

    @Test
    fun `dst-port non-numeric rejected`() {
        val rule = CustomRule(RuleType.DST_PORT, "abc", "DIRECT", RulePosition.APPEND)
        assertThrows(RuleSyntaxException::class.java) { rule.validate() }
    }

    @Test
    fun `domain-regex valid pattern accepted`() {
        val rule = CustomRule(RuleType.DOMAIN_REGEX, "^ads\\.example\\.com$", "REJECT", RulePosition.PREPEND)
        assertEquals("DOMAIN-REGEX,^ads\\.example\\.com$,REJECT", rule.toRuleLine())
    }

    @Test
    fun `domain-regex invalid pattern rejected`() {
        val rule = CustomRule(RuleType.DOMAIN_REGEX, "(unclosed", "REJECT", RulePosition.PREPEND)
        assertThrows(RuleSyntaxException::class.java) { rule.validate() }
    }

    @Test
    fun `geoip valid country code accepted`() {
        val rule = CustomRule(RuleType.GEOIP, "CN", "DIRECT", RulePosition.APPEND)
        assertEquals("GEOIP,CN,DIRECT", rule.toRuleLine())
    }

    @Test
    fun `geoip non-alpha code rejected`() {
        val rule = CustomRule(RuleType.GEOIP, "123", "DIRECT", RulePosition.APPEND)
        assertThrows(RuleSyntaxException::class.java) { rule.validate() }
    }

    @Test
    fun `blank content rejected regardless of type`() {
        val rule = CustomRule(RuleType.DOMAIN_SUFFIX, "   ", "DIRECT", RulePosition.APPEND)
        assertThrows(RuleSyntaxException::class.java) { rule.validate() }
    }

    @Test
    fun `content containing comma rejected`() {
        val rule = CustomRule(RuleType.DOMAIN, "example.com,evil", "DIRECT", RulePosition.APPEND)
        assertThrows(RuleSyntaxException::class.java) { rule.validate() }
    }

    @Test
    fun `blank policy rejected`() {
        val rule = CustomRule(RuleType.DOMAIN, "example.com", "  ", RulePosition.APPEND)
        assertThrows(RuleSyntaxException::class.java) { rule.validate() }
    }

    @Test
    fun `rule type literal round trip`() {
        RuleType.values().forEach { type ->
            assertEquals(type, RuleType.fromLiteral(type.literal))
        }
    }
}
