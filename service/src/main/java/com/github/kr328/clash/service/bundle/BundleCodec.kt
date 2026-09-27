package com.github.kr328.clash.service.bundle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 导入被拒绝的原因。每一项都对应一条用户可读提示，UI 按类型取本地化文案，
 * 不解析异常 message。
 *
 * [UnsafeEntryPath] 刻意不携带肇事条目名：那是攻击者可控的字符串，回显到界面或日志等于
 * 把它带进了另一个上下文。
 */
sealed class BundleRejection {
    data class NotAZip(val detail: String) : BundleRejection()
    object ManifestMissing : BundleRejection()
    data class ManifestInvalid(val detail: String) : BundleRejection()
    data class FormatVersionUnsupported(val version: String) : BundleRejection()
    object UnsafeEntryPath : BundleRejection()
    data class ContentMissing(val path: String) : BundleRejection()
    data class ContentCorrupt(val path: String) : BundleRejection()
    data class ContentInvalid(val path: String, val detail: String) : BundleRejection()

    /** 包体超出解包资源上限。桌面端无此上限，属本端对 zip bomb 的额外防护，不改变格式契约。 */
    data class TooLarge(val detail: String) : BundleRejection()
}

class BundleImportException(val rejection: BundleRejection) :
    Exception("rule bundle rejected: ${rejection::class.simpleName}")

/**
 * 规则包的打包与解包。解包**只解析不写入**：任何拒绝都发生在这里，拿到
 * [ReadBundleResult] 的调用方才可以放心去写 profile，导入因此天然是原子的。
 */
object BundleCodec {
    // prettyPrintIndent 仍是实验 API：缩进只影响 manifest 的可读性，不进入格式契约，
    // 契约层面的一致性由字段与取值保证。
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    // ISO-8601 / RFC 3339，UTC。桌面端用 Date.toISOString()，此处对齐到同一形状。
    private fun formatCreatedAt(at: Date): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
        format.timeZone = TimeZone.getTimeZone("UTC")

        return format.format(at)
    }

    private fun dumpYaml(root: Map<String, Any?>): String =
        Dump(DumpSettings.builder().build()).dumpToString(root)

    fun buildSequenceYaml(sequence: RuleSequence): ByteArray = dumpYaml(
        linkedMapOf(
            "prepend" to sequence.prepend,
            "append" to sequence.append,
            "delete" to sequence.delete,
        )
    ).toByteArray()

    fun buildProvidersYaml(providers: List<BundleProvider>): ByteArray {
        val map = LinkedHashMap<String, Any?>()

        for (provider in providers) {
            val entry = LinkedHashMap<String, Any?>()
            entry["type"] = provider.type
            entry["behavior"] = provider.behavior
            entry["format"] = provider.format
            if (provider.url.isNotEmpty()) entry["url"] = provider.url
            provider.intervalSeconds?.let { entry["interval"] = it }

            map[provider.name] = entry
        }

        return dumpYaml(linkedMapOf("rule-providers" to map)).toByteArray()
    }

    fun buildManifestJson(manifest: BundleManifest): ByteArray {
        val obj = buildJsonObject {
            put("formatVersion", manifest.formatVersion)
            put("generator", buildJsonObject {
                put("app", manifest.generatorApp)
                put("version", manifest.generatorVersion)
            })
            put("createdAt", manifest.createdAt)
            put("proxyPolicies", buildJsonArray {
                manifest.proxyPolicies.forEach { add(JsonPrimitive(it)) }
            })
            put("contents", buildJsonArray {
                manifest.contents.forEach { entry ->
                    add(buildJsonObject {
                        put("path", entry.path)
                        put("sha256", entry.sha256)
                        put("entryCount", entry.entryCount)
                    })
                }
            })
        }

        return "${json.encodeToString(JsonObject.serializer(), obj)}\n".toByteArray()
    }

    /** 打包写出到 [output]（SAF 的 outputStream 可直接传入）。 */
    fun write(
        output: OutputStream,
        sequence: RuleSequence,
        providers: List<BundleProvider>,
        appVersion: String,
        createdAt: Date = Date(),
    ) {
        val sequenceYaml = buildSequenceYaml(sequence)
        val providersYaml = buildProvidersYaml(providers)

        val manifest = BundleManifest(
            formatVersion = BundleFormat.VERSION,
            generatorApp = BundleFormat.GENERATOR_APP,
            generatorVersion = appVersion,
            createdAt = formatCreatedAt(createdAt),
            proxyPolicies = collectProxyPolicies(sequence),
            contents = listOf(
                BundleContentEntry(
                    path = BundleFormat.RULES_ENTRY,
                    sha256 = BundleZip.sha256Hex(sequenceYaml),
                    entryCount = sequence.entryCount,
                ),
                BundleContentEntry(
                    path = BundleFormat.PROVIDERS_ENTRY,
                    sha256 = BundleZip.sha256Hex(providersYaml),
                    entryCount = providers.size,
                ),
            ),
        )

        BundleZip.write(
            output,
            listOf(
                BundleFormat.MANIFEST_ENTRY to buildManifestJson(manifest),
                BundleFormat.RULES_ENTRY to sequenceYaml,
                BundleFormat.PROVIDERS_ENTRY to providersYaml,
            ),
        )
    }

    /** 便于测试与自证：直接产出包字节。 */
    fun writeToBytes(
        sequence: RuleSequence,
        providers: List<BundleProvider>,
        appVersion: String,
        createdAt: Date = Date(),
    ): ByteArray = ByteArrayOutputStream()
        .also { write(it, sequence, providers, appVersion, createdAt) }
        .toByteArray()

    fun read(input: InputStream): ReadBundleResult = read(input.readBytes())

    fun read(bytes: ByteArray): ReadBundleResult {
        val entries = try {
            BundleZip.read(bytes.inputStream())
        } catch (e: UnsafeEntryPathException) {
            throw BundleImportException(BundleRejection.UnsafeEntryPath)
        } catch (e: BundleTooLargeException) {
            throw BundleImportException(BundleRejection.TooLarge(e.message ?: ""))
        } catch (e: NotAZipException) {
            throw BundleImportException(BundleRejection.NotAZip(e.message ?: ""))
        }

        val manifestBytes = entries[BundleFormat.MANIFEST_ENTRY]
            ?: throw BundleImportException(BundleRejection.ManifestMissing)

        val manifest = parseManifest(manifestBytes)
        val version = BundleFormat.parseVersion(manifest.formatVersion)

        val sequence = parseSequence(takeVerified(entries, manifest, BundleFormat.RULES_ENTRY))

        // providers.yaml 缺失视为「一个规则集都没定义」，不算损包：桌面端同口径。
        val providers = if (entries.containsKey(BundleFormat.PROVIDERS_ENTRY)) {
            parseProviders(takeVerified(entries, manifest, BundleFormat.PROVIDERS_ENTRY))
        } else {
            emptyList()
        }

        return ReadBundleResult(
            bundle = RuleBundle(manifest, sequence, providers),
            producedByNewerMinor = (version?.minor ?: 0) > BundleFormat.MINOR,
        )
    }

    private fun parseManifest(bytes: ByteArray): BundleManifest {
        val root = try {
            json.parseToJsonElement(bytes.decodeToString()).jsonObject
        } catch (e: Exception) {
            throw BundleImportException(
                BundleRejection.ManifestInvalid(e.message ?: "manifest 不是合法 JSON")
            )
        }

        // 字段取值统一收进拒绝分类：字段被写成对象/数组时 jsonPrimitive 会抛
        // IllegalArgumentException，不能让它绕开 ManifestInvalid 落到通用兜底。
        try {
            return parseManifestFields(root)
        } catch (e: BundleImportException) {
            throw e
        } catch (e: Exception) {
            throw BundleImportException(BundleRejection.ManifestInvalid("manifest 字段类型不符合约定"))
        }
    }

    private fun parseManifestFields(root: JsonObject): BundleManifest {
        val rawVersion = root["formatVersion"]?.jsonPrimitive?.contentOrNull
        val version = BundleFormat.parseVersion(rawVersion)
            ?: throw BundleImportException(
                BundleRejection.ManifestInvalid("formatVersion 缺失或不是 major.minor")
            )

        if (version.major != BundleFormat.MAJOR) {
            throw BundleImportException(
                BundleRejection.FormatVersionUnsupported(rawVersion.orEmpty())
            )
        }

        val generator = root["generator"] as? JsonObject

        return BundleManifest(
            formatVersion = "${version.major}.${version.minor}",
            generatorApp = generator?.get("app")?.jsonPrimitive?.contentOrNull.orEmpty(),
            generatorVersion = generator?.get("version")?.jsonPrimitive?.contentOrNull.orEmpty(),
            createdAt = root["createdAt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            proxyPolicies = (root["proxyPolicies"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty(),
            contents = (root["contents"] as? JsonArray)
                ?.mapNotNull { element ->
                    val entry = element as? JsonObject ?: return@mapNotNull null
                    val path = entry["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val sha256 = entry["sha256"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

                    BundleContentEntry(
                        path = path,
                        sha256 = sha256,
                        entryCount = entry["entryCount"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
                    )
                }
                .orEmpty(),
        )
    }

    /**
     * manifest 没声明摘要时仍按文件名取用（字段本身是可选的完整性声明）；只有**声明了且不匹配**
     * 才判为损包——这样本端不会因为对端 manifest 少写一项就整包拒绝。
     */
    private fun takeVerified(
        entries: Map<String, ByteArray>,
        manifest: BundleManifest,
        path: String,
    ): ByteArray {
        val data = entries[path]
            ?: throw BundleImportException(BundleRejection.ContentMissing(path))

        val declared = manifest.contents.firstOrNull { it.path == path }

        if (declared != null && BundleZip.sha256Hex(data) != declared.sha256) {
            throw BundleImportException(BundleRejection.ContentCorrupt(path))
        }

        return data
    }

    private fun loadYaml(bytes: ByteArray, path: String): Any? = try {
        Load(LoadSettings.builder().build()).loadFromString(bytes.decodeToString())
    } catch (e: Exception) {
        throw BundleImportException(
            BundleRejection.ContentInvalid(path, e.message ?: "不是合法 YAML")
        )
    }

    private fun asStringList(value: Any?): List<String> =
        (value as? List<*>)?.filterIsInstance<String>().orEmpty()

    private fun parseSequence(bytes: ByteArray): RuleSequence {
        val path = BundleFormat.RULES_ENTRY
        val parsed = loadYaml(bytes, path)

        // 空文档与 providers.yaml 同口径：视为「这一节为空」，不算损包。
        // 顶层是其他类型（字符串/列表）仍然拒绝——那不是空，是坏。
        if (parsed == null) {
            return RuleSequence(emptyList(), emptyList(), emptyList())
        }

        @Suppress("UNCHECKED_CAST")
        val root = parsed as? Map<String, Any?>
            ?: throw BundleImportException(
                BundleRejection.ContentInvalid(path, "规则序列顶层不是 Mapping")
            )

        return RuleSequence(
            prepend = asStringList(root["prepend"]),
            append = asStringList(root["append"]),
            delete = asStringList(root["delete"]),
        )
    }

    private fun parseProviders(bytes: ByteArray): List<BundleProvider> {
        val path = BundleFormat.PROVIDERS_ENTRY
        val parsed = loadYaml(bytes, path) ?: return emptyList()

        @Suppress("UNCHECKED_CAST")
        val root = parsed as? Map<String, Any?>
            ?: throw BundleImportException(
                BundleRejection.ContentInvalid(path, "规则集文档顶层不是 Mapping")
            )

        @Suppress("UNCHECKED_CAST")
        val map = root["rule-providers"] as? Map<String, Any?> ?: return emptyList()

        return map.mapNotNull { (name, value) ->
            @Suppress("UNCHECKED_CAST")
            val entry = value as? Map<String, Any?> ?: return@mapNotNull null

            BundleProvider(
                name = name,
                type = (entry["type"] as? String).orEmpty(),
                behavior = (entry["behavior"] as? String).orEmpty(),
                format = (entry["format"] as? String).orEmpty(),
                url = (entry["url"] as? String).orEmpty(),
                intervalSeconds = when (val interval = entry["interval"]) {
                    is Number -> interval.toLong().takeIf { it > 0 }
                    is String -> interval.toLongOrNull()?.takeIf { it > 0 }
                    else -> null
                },
            )
        }
    }
}
