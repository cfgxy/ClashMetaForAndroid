package com.github.kr328.clash.service.bundle

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 输入不是可解析的 zip（含空包、entry 结构损坏）。 */
class NotAZipException(message: String) : Exception(message)

/** 包体超出 [BundleFormat] 的解包资源上限。 */
class BundleTooLargeException(message: String) : Exception(message)

/**
 * 规则包的 zip 读写，只用 JDK 内置 `java.util.zip`，不引入新依赖。
 *
 * 写侧用 deflate（三个小文本文件，压缩后体积显著更小，且桌面端读侧同时支持 store 与 deflate）。
 * 读侧走 [ZipInputStream] 顺序读，不需要中央目录随机访问，因此 SAF 的输入流可直接消费，
 * 无需先落地临时文件。
 */
object BundleZip {
    fun write(output: OutputStream, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(output).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
    }

    /**
     * 顺序读出全部 entry 并按归一化后的名字索引。
     *
     * 每个 entry 与总量都受 [BundleFormat] 上限约束，且上限在**边读边计数**时生效——不依赖
     * `ZipEntry.getSize()`（本地文件头里的声明值可以被伪造，且流式写出的包里常为 -1）。
     *
     * @throws UnsafeEntryPathException entry 名为绝对路径或含 `..` 段，整包拒绝。
     */
    fun read(input: InputStream): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        var totalBytes = 0L

        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break

                    if (entries.size >= BundleFormat.MAX_ENTRIES) {
                        throw BundleTooLargeException("zip entry 数量超过上限 ${BundleFormat.MAX_ENTRIES}")
                    }

                    // 目录项不携带内容，但名字同样要过穿越校验后才被忽略。
                    val name = normalizeEntryPath(entry.name)

                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }

                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(8 * 1024)

                    while (true) {
                        val read = zip.read(chunk)
                        if (read < 0) break

                        if (buffer.size() + read > BundleFormat.MAX_ENTRY_BYTES) {
                            throw BundleTooLargeException("zip 单个文件超过上限 ${BundleFormat.MAX_ENTRY_BYTES} 字节")
                        }

                        totalBytes += read

                        if (totalBytes > BundleFormat.MAX_TOTAL_BYTES) {
                            throw BundleTooLargeException("zip 解压总量超过上限 ${BundleFormat.MAX_TOTAL_BYTES} 字节")
                        }

                        buffer.write(chunk, 0, read)
                    }

                    zip.closeEntry()

                    entries[name] = buffer.toByteArray()
                }
            }
        } catch (e: ZipException) {
            throw NotAZipException(e.message ?: "zip 结构损坏")
        } catch (e: IOException) {
            throw NotAZipException(e.message ?: "zip 读取失败")
        }

        if (entries.isEmpty()) {
            throw NotAZipException("zip 内没有任何文件")
        }

        return entries
    }

    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)

        return digest.joinToString("") { "%02x".format(it) }
    }
}
