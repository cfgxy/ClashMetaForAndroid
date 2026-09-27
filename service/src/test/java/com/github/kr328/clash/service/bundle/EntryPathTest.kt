package com.github.kr328.clash.service.bundle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 归一化只负责「去空段与 `.` 段」，任何穿越形态一律抛异常。
 * 断言有效性由「移除 [normalizeEntryPath] 里的两处 throw 后本类必须失败」保证。
 */
class EntryPathTest {
    @Test
    fun `普通相对路径原样归一化`() {
        assertEquals("rules/sequence.yaml", normalizeEntryPath("rules/sequence.yaml"))
    }

    @Test
    fun `空段与点段被折叠`() {
        assertEquals("rules/sequence.yaml", normalizeEntryPath("./rules//./sequence.yaml"))
    }

    @Test
    fun `反斜杠分隔符按 posix 解释`() {
        assertEquals("providers/providers.yaml", normalizeEntryPath("providers\\providers.yaml"))
    }

    @Test
    fun `前导父目录段被拒绝`() {
        assertThrows(UnsafeEntryPathException::class.java) {
            normalizeEntryPath("../manifest.json")
        }
    }

    @Test
    fun `中间父目录段被拒绝`() {
        assertThrows(UnsafeEntryPathException::class.java) {
            normalizeEntryPath("rules/../../etc/passwd")
        }
    }

    @Test
    fun `反斜杠形式的父目录段被拒绝`() {
        assertThrows(UnsafeEntryPathException::class.java) {
            normalizeEntryPath("..\\..\\manifest.json")
        }
    }

    @Test
    fun `posix 绝对路径被拒绝`() {
        assertThrows(UnsafeEntryPathException::class.java) {
            normalizeEntryPath("/etc/passwd")
        }
    }

    @Test
    fun `windows 盘符绝对路径被拒绝`() {
        assertThrows(UnsafeEntryPathException::class.java) {
            normalizeEntryPath("C:/Windows/system32/drivers/etc/hosts")
        }
    }

    @Test
    fun `空名字被拒绝`() {
        assertThrows(UnsafeEntryPathException::class.java) {
            normalizeEntryPath("./")
        }
    }

    @Test
    fun `异常不携带肇事条目名`() {
        val message = assertThrows(UnsafeEntryPathException::class.java) {
            normalizeEntryPath("../secret-token-abcdef/manifest.json")
        }.message.orEmpty()

        assertEquals(false, message.contains("secret-token-abcdef"))
    }
}
