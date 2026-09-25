package com.github.kr328.clash.service.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomRuleProviderValidationTest {
    private fun httpProvider(name: String = "ads", url: String = "https://example.com/ads.yaml") =
        CustomRuleProvider(
            name = name,
            type = RuleProviderType.HTTP,
            behavior = RuleProviderBehavior.DOMAIN,
            format = RuleProviderFormat.YAML,
            url = url,
            updateInterval = RuleProviderUpdateInterval.HOURLY,
        )

    @Test
    fun `valid name and url accepted`() {
        httpProvider().validate()
    }

    @Test
    fun `name with path traversal chars rejected`() {
        val ex = assertThrows(RuleProviderSyntaxException::class.java) {
            httpProvider(name = "../../etc/passwd").validate()
        }
        assertEquals(RuleProviderValidationField.NAME, ex.field)
    }

    @Test
    fun `name with slash rejected`() {
        val ex = assertThrows(RuleProviderSyntaxException::class.java) {
            httpProvider(name = "a/b").validate()
        }
        assertEquals(RuleProviderValidationField.NAME, ex.field)
    }

    @Test
    fun `blank name rejected`() {
        val ex = assertThrows(RuleProviderSyntaxException::class.java) {
            httpProvider(name = "").validate()
        }
        assertEquals(RuleProviderValidationField.NAME, ex.field)
    }

    @Test
    fun `name over 64 chars rejected`() {
        val ex = assertThrows(RuleProviderSyntaxException::class.java) {
            httpProvider(name = "a".repeat(65)).validate()
        }
        assertEquals(RuleProviderValidationField.NAME, ex.field)
    }

    @Test
    fun `name with underscore and hyphen accepted`() {
        httpProvider(name = "my_rule-set_1").validate()
    }

    @Test
    fun `blank url rejected for non-file type`() {
        val ex = assertThrows(RuleProviderSyntaxException::class.java) {
            httpProvider(url = "").validate()
        }
        assertEquals(RuleProviderValidationField.URL, ex.field)
    }

    @Test
    fun `non-http scheme rejected`() {
        val ex = assertThrows(RuleProviderSyntaxException::class.java) {
            httpProvider(url = "ftp://example.com/ads.yaml").validate()
        }
        assertEquals(RuleProviderValidationField.URL, ex.field)
    }

    @Test
    fun `malformed url rejected`() {
        val ex = assertThrows(RuleProviderSyntaxException::class.java) {
            httpProvider(url = "not a url").validate()
        }
        assertEquals(RuleProviderValidationField.URL, ex.field)
    }

    @Test
    fun `file type does not require url`() {
        val provider = CustomRuleProvider(
            name = "local",
            type = RuleProviderType.FILE,
            behavior = RuleProviderBehavior.CLASSICAL,
            format = RuleProviderFormat.TEXT,
            url = "",
            updateInterval = RuleProviderUpdateInterval.NEVER,
        )
        provider.validate()
    }

    @Test
    fun `derivedPath is determined by name and format`() {
        val provider = httpProvider(name = "ads").copy(format = RuleProviderFormat.MRS)
        assertEquals("rule_providers/ads.mrs", provider.derivedPath())
    }

    // ---- 订阅 URL 可能携带 token，日志/异常文案不得输出完整 URL ----

    @Test
    fun `maskUrl strips query string and token`() {
        val masked = maskUrl("https://example.com/ads.yaml?token=super-secret")
        assertEquals("https://example.com/***", masked)
        assertFalse(masked.contains("super-secret"))
    }

    @Test
    fun `malformed url syntax exception message does not leak query string`() {
        val ex = assertThrows(RuleProviderSyntaxException::class.java) {
            httpProvider(url = "ftp://example.com/ads.yaml?token=super-secret").validate()
        }
        assertFalse(ex.message?.contains("super-secret") ?: false)
    }

    @Test
    fun `type literal round trip`() {
        RuleProviderType.entries.forEach { type ->
            assertEquals(type, RuleProviderType.fromLiteral(type.literal))
        }
    }

    @Test
    fun `behavior literal round trip`() {
        RuleProviderBehavior.entries.forEach { behavior ->
            assertEquals(behavior, RuleProviderBehavior.fromLiteral(behavior.literal))
        }
    }

    @Test
    fun `format literal round trip`() {
        RuleProviderFormat.entries.forEach { format ->
            assertEquals(format, RuleProviderFormat.fromLiteral(format.literal))
        }
    }

    @Test
    fun `update interval seconds mapping matches ruling`() {
        assertEquals(null, RuleProviderUpdateInterval.NEVER.seconds)
        assertEquals(3600L, RuleProviderUpdateInterval.HOURLY.seconds)
        assertEquals(21600L, RuleProviderUpdateInterval.EVERY_6_HOURS.seconds)
        assertEquals(43200L, RuleProviderUpdateInterval.EVERY_12_HOURS.seconds)
        assertEquals(86400L, RuleProviderUpdateInterval.DAILY.seconds)
    }

    @Test
    fun `update interval fromSeconds round trip`() {
        RuleProviderUpdateInterval.entries.forEach { interval ->
            assertEquals(interval, RuleProviderUpdateInterval.fromSeconds(interval.seconds))
        }
    }
}
