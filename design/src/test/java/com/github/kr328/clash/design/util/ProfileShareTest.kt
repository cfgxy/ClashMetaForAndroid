package com.github.kr328.clash.design.util

import com.github.kr328.clash.service.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ProfileShareTest {
    private fun profileOf(
        type: Profile.Type,
        source: String,
    ): Profile = Profile(
        uuid = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        name = "sample",
        type = type,
        source = source,
        active = false,
        interval = 0,
        upload = 0,
        download = 0,
        total = 0,
        expire = 0,
        updatedAt = 0,
        imported = true,
        pending = false,
    )

    @Test
    fun `订阅类型返回订阅地址原值`() {
        val url = "https://example.invalid/subscribe?token=placeholder"

        assertEquals(url, ProfileShare.shareContentOf(profileOf(Profile.Type.Url, url)))
    }

    @Test
    fun `订阅地址两端空白被裁剪后返回`() {
        val url = "https://example.invalid/subscribe"

        assertEquals(url, ProfileShare.shareContentOf(profileOf(Profile.Type.Url, "  $url\n")))
    }

    @Test
    fun `本地文件类型不可分享`() {
        assertNull(ProfileShare.shareContentOf(profileOf(Profile.Type.File, "config.yaml")))
    }

    @Test
    fun `外部导入类型不可分享`() {
        assertNull(
            ProfileShare.shareContentOf(
                profileOf(Profile.Type.External, "content://example.invalid/config")
            )
        )
    }

    @Test
    fun `空白订阅地址不可分享`() {
        assertNull(ProfileShare.shareContentOf(profileOf(Profile.Type.Url, "   ")))
    }

    @Test
    fun `非 http 协议的订阅地址不可分享`() {
        assertNull(
            ProfileShare.shareContentOf(profileOf(Profile.Type.Url, "file:///sdcard/config.yaml"))
        )
    }

    @Test
    fun `http 与 https 协议均可分享`() {
        val http = "http://example.invalid/subscribe"
        val https = "HTTPS://example.invalid/subscribe"

        assertEquals(http, ProfileShare.shareContentOf(profileOf(Profile.Type.Url, http)))
        assertEquals(https, ProfileShare.shareContentOf(profileOf(Profile.Type.Url, https)))
    }

    @Test
    fun `可分享判定与取值逻辑一致`() {
        assertTrue(ProfileShare.isShareable(profileOf(Profile.Type.Url, "https://example.invalid/s")))
        assertFalse(ProfileShare.isShareable(profileOf(Profile.Type.File, "config.yaml")))
    }
}
