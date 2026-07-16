package com.nexora.source.config

import com.nexora.source.api.CompatibilityDiagnostic
import com.nexora.source.api.CompatibilityIssueCode
import com.nexora.source.api.CompatibilitySeverity
import com.nexora.source.api.ConfigImportKind
import com.nexora.source.api.LegacyConfigFields
import com.nexora.source.api.LegacyConfigId
import com.nexora.source.api.LegacyConfigImportResult
import com.nexora.source.api.LegacyConfigSnapshot
import com.nexora.source.api.LegacyParseDescriptor
import com.nexora.source.api.LegacySiteDescriptor
import com.nexora.source.api.LegacySourceKey
import com.nexora.source.api.RawJson
import com.nexora.source.api.SourceAvailability
import com.nexora.source.api.SourceOperationalState
import com.nexora.source.api.SourceSearchCapability
import com.nexora.source.api.SourceUserActivation
import java.net.URI
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalSerializationApi::class)
public class DefaultLegacyConfigImporter(
    private val remoteArrayLoader: RemoteArrayLoader = RemoteArrayLoader.disabled(),
) {
    public companion object {
        public const val MAX_INPUT_BYTES: Int = 2 * 1024 * 1024
        public const val MAX_JSON_DEPTH: Int = 64
        public const val MAX_SITES_PER_CONFIG: Int = 500
        private const val MAX_REMOTE_REFERENCES: Int = 32
        private const val MAX_REMOTE_DEPTH: Int = 8
        private const val MAX_SITE_KEY_CHARS: Int = 256
        private const val DEFAULT_TIMEOUT_MILLIS: Long = 15_000L

        private val BASE64_MARKER = Regex("[A-Za-z0-9]{8}\\*\\*")
        private val HEX = Regex("[0-9a-fA-F]+")
        private val KNOWN_TOP_LEVEL_FIELDS = setOf(
            "spider",
            "sites",
            "parses",
            "rules",
            "headers",
            "hosts",
            "flags",
            "danmaku",
            "doh",
            "proxy",
            "ads",
            "wallpaper",
            "logo",
            "notice",
            "lives",
        )
        private val OBJECT_ARRAY_FIELDS = listOf(
            "sites",
            "parses",
            "rules",
            "headers",
            "doh",
            "proxy",
            "lives",
        )
    }

    private val json = Json {
        isLenient = true
        allowTrailingComma = true
    }

    public suspend fun importConfig(
        text: String,
        displayName: String,
        kind: ConfigImportKind,
        origin: String? = null,
    ): LegacyConfigImportResult {
        val encoded = try {
            encodeUtf8Strict(text)
        } catch (_: Exception) {
            return fatal(
                CompatibilityIssueCode.INVALID_UTF8,
                "配置文本包含无效的 Unicode 字符。",
            )
        }
        return importConfig(encoded, displayName, kind, origin)
    }

    public suspend fun importConfig(
        bytes: ByteArray,
        displayName: String,
        kind: ConfigImportKind,
        origin: String? = null,
    ): LegacyConfigImportResult {
        val diagnostics = mutableListOf<CompatibilityDiagnostic>()
        val decoded = try {
            decodePayload(bytes)
        } catch (failure: ImportFailure) {
            return fatal(failure.code, failure.message ?: "无法解码配置内容。")
        }
        val root = try {
            parseJson(decoded)
        } catch (failure: ImportFailure) {
            return fatal(failure.code, failure.message ?: "配置内容不是有效的 JSON。")
        }
        if (root !is JsonObject) {
            return fatal(CompatibilityIssueCode.INVALID_JSON, "配置根节点必须是 JSON 对象。")
        }

        val originalCanonical = canonical(root)
        val configId = LegacyConfigId(
            sha256(
                configIdentityBytes(
                    originalCanonical = originalCanonical,
                    kind = kind,
                    origin = origin,
                ),
            ),
        )
        val expandedMap = root.toMutableMap()
        val expandedFields = linkedMapOf<String, List<ExpandedObject>>()
        val referenceBudget = ReferenceBudget()

        for (field in OBJECT_ARRAY_FIELDS) {
            val element = root[field] ?: continue
            val issueCode = when (field) {
                "sites" -> CompatibilityIssueCode.SITE_INVALID
                "parses" -> CompatibilityIssueCode.PARSE_INVALID
                else -> CompatibilityIssueCode.FIELD_TYPE_MISMATCH
            }
            val objects = expandObjectArray(
                element = element,
                path = "$.${field}",
                origin = origin,
                invalidItemCode = issueCode,
                diagnostics = diagnostics,
                activeReferences = linkedSetOf(),
                budget = referenceBudget,
                depth = 0,
            )
            expandedFields[field] = objects
            expandedMap[field] = JsonArray(objects.map(ExpandedObject::value))
        }

        val spider = optionalScalar(root, "spider", diagnostics)?.let { value ->
            normalizeRelativeUrl(value, origin, "$.spider", diagnostics)
        }
        val sites = parseSites(
            expandedFields["sites"].orEmpty(),
            configId,
            spider.orEmpty(),
            diagnostics,
        )
        val parses = parseParses(expandedFields["parses"].orEmpty(), diagnostics)

        val notice = root["notice"]?.takeUnless { element -> element is JsonNull }?.let { RawJson(canonical(it)) }
        val lives = expandedFields["lives"].orEmpty().map { item -> RawJson(canonical(item.value)) }
        if (notice != null) diagnostics += policyDiagnostic("$.notice", "已保留旧版公告字段，但 Nexora 不会显示该公告。")
        if (lives.isNotEmpty()) diagnostics += policyDiagnostic("$.lives", "已保留旧版直播源字段，但 Nexora 不会启用这些直播源。")
        if (root.containsKey("drm")) diagnostics += policyDiagnostic("$.drm", "已保留旧版 DRM 字段，但 Nexora 不会执行其中的内容。")

        val fields = LegacyConfigFields(
            spider = spider,
            sites = sites,
            parses = parses,
            rules = rawObjects(expandedFields["rules"]),
            headers = rawObjects(expandedFields["headers"]),
            hosts = stringList(root, "hosts", diagnostics),
            flags = stringList(root, "flags", diagnostics),
            danmaku = optionalScalar(root, "danmaku", diagnostics)?.let { value ->
                normalizeRelativeUrl(value, origin, "$.danmaku", diagnostics)
            },
            doh = rawObjects(expandedFields["doh"]),
            proxy = rawObjects(expandedFields["proxy"]),
            ads = stringList(root, "ads", diagnostics),
            wallpaper = optionalScalar(root, "wallpaper", diagnostics)?.let { value ->
                normalizeRelativeUrl(value, origin, "$.wallpaper", diagnostics)
            },
            logo = optionalScalar(root, "logo", diagnostics)?.let { value ->
                normalizeRelativeUrl(value, origin, "$.logo", diagnostics)
            },
            notice = notice,
            lives = lives,
        )

        val unknown = root.entries
            .filter { (key, _) -> key !in KNOWN_TOP_LEVEL_FIELDS }
            .sortedBy(Map.Entry<String, JsonElement>::key)
            .associate { (key, value) -> key to RawJson(canonical(value)) }
        val snapshot = LegacyConfigSnapshot(
            id = configId,
            displayName = displayName,
            importKind = kind,
            origin = origin,
            originDisplay = redactOriginForDisplay(origin),
            original = RawJson(originalCanonical),
            expanded = RawJson(canonical(JsonObject(expandedMap))),
            fields = fields,
            unknownTopLevelFields = unknown,
            diagnostics = diagnostics.toList(),
        )
        return LegacyConfigImportResult(listOf(snapshot), diagnostics.toList())
    }

    private fun decodePayload(input: ByteArray): String {
        checkSize(input.size)
        var text = try {
            decodeUtf8Strict(input)
        } catch (_: Exception) {
            throw ImportFailure(CompatibilityIssueCode.INVALID_UTF8, "配置内容不是有效的 UTF-8 文本。")
        }.removePrefix("\uFEFF").trim()
        if (looksLikeJson(text)) return checkedDecodedText(text)

        val marker = BASE64_MARKER.find(text)
        if (marker != null) {
            val payload = text.substring(marker.range.last + 1)
            text = decodeBase64Text(payload)
        } else if (text.replace(Regex("\\s+"), "").startsWith("2423")) {
            text = decode2423(text)
        } else {
            text = decodeBase64Text(text)
        }

        if (text.replace(Regex("\\s+"), "").startsWith("2423")) text = decode2423(text)
        return checkedDecodedText(text.removePrefix("\uFEFF").trim())
    }

    private fun decodeBase64Text(value: String): String {
        val compact = value.filterNot(Char::isWhitespace)
        if (compact.isEmpty()) throw ImportFailure(CompatibilityIssueCode.INVALID_BASE64, "Base64 配置内容为空。")
        val bytes = try {
            Base64.getDecoder().decode(compact)
        } catch (_: IllegalArgumentException) {
            try {
                Base64.getUrlDecoder().decode(compact)
            } catch (_: IllegalArgumentException) {
                throw ImportFailure(CompatibilityIssueCode.INVALID_BASE64, "配置内容不是有效的 Base64。")
            }
        }
        checkSize(bytes.size)
        return try {
            decodeUtf8Strict(bytes)
        } catch (_: Exception) {
            throw ImportFailure(CompatibilityIssueCode.INVALID_UTF8, "Base64 解码后的配置不是有效的 UTF-8 文本。")
        }
    }

    private fun decode2423(value: String): String {
        val compact = value.filterNot(Char::isWhitespace)
        if (compact.length % 2 != 0 || !HEX.matches(compact) || !compact.startsWith("2423")) {
            throw ImportFailure(CompatibilityIssueCode.INVALID_2423_AES, "2423 AES 数据帧格式错误。")
        }
        val frame = try {
            hexToBytes(compact)
        } catch (_: Exception) {
            throw ImportFailure(CompatibilityIssueCode.INVALID_2423_AES, "2423 AES 数据帧包含无效的十六进制内容。")
        }
        if (frame.size < 2 + 2 + 13 + 16 || frame[0] != '$'.code.toByte() || frame[1] != '#'.code.toByte()) {
            throw ImportFailure(CompatibilityIssueCode.INVALID_2423_AES, "2423 AES 数据帧不完整。")
        }
        val closing = findClosingMarker(frame)
        if (closing < 2 || closing + 2 > frame.size - 13) {
            throw ImportFailure(CompatibilityIssueCode.INVALID_2423_AES, "2423 AES 数据帧缺少密钥结束标记。")
        }
        val keySeed = ascii(frame.copyOfRange(2, closing)).lowercase(Locale.ROOT)
        val ivSeed = ascii(frame.copyOfRange(frame.size - 13, frame.size)).lowercase(Locale.ROOT)
        if (keySeed.isEmpty() || keySeed.length > 16 || ivSeed.length != 13) {
            throw ImportFailure(CompatibilityIssueCode.INVALID_2423_AES, "2423 AES 密钥或 IV 种子长度无效。")
        }
        val cipherBytes = frame.copyOfRange(closing + 2, frame.size - 13)
        if (cipherBytes.isEmpty() || cipherBytes.size % 16 != 0) {
            throw ImportFailure(CompatibilityIssueCode.INVALID_2423_AES, "2423 AES 密文长度无效。")
        }
        val key = keySeed.padEnd(16, '0').toByteArray(StandardCharsets.US_ASCII)
        val iv = ivSeed.padEnd(16, '0').toByteArray(StandardCharsets.US_ASCII)
        val plaintext = try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(cipherBytes)
        } catch (_: Exception) {
            throw ImportFailure(CompatibilityIssueCode.INVALID_2423_AES, "无法解密 2423 AES 配置内容。")
        }
        checkSize(plaintext.size)
        return try {
            decodeUtf8Strict(plaintext)
        } catch (_: Exception) {
            throw ImportFailure(CompatibilityIssueCode.INVALID_UTF8, "2423 AES 解密后的配置不是有效的 UTF-8 文本。")
        }
    }

    private fun checkedDecodedText(value: String): String {
        checkSize(encodeUtf8Strict(value).size)
        return value
    }

    private fun checkSize(size: Int) {
        if (size > MAX_INPUT_BYTES) {
            throw ImportFailure(CompatibilityIssueCode.INPUT_TOO_LARGE, "配置内容超过 2 MiB 上限。")
        }
    }

    private fun parseJson(value: String): JsonElement {
        enforceDepth(value)
        return try {
            json.parseToJsonElement(value)
        } catch (_: Exception) {
            throw ImportFailure(CompatibilityIssueCode.INVALID_JSON, "配置内容不是有效的 JSON。")
        }
    }

    private fun enforceDepth(value: String) {
        var depth = 0
        var inString = false
        var escaped = false
        for (character in value) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                continue
            }
            when (character) {
                '"' -> inString = true
                '{', '[' -> {
                    depth += 1
                    if (depth > MAX_JSON_DEPTH) {
                        throw ImportFailure(
                            CompatibilityIssueCode.INVALID_JSON,
                            "配置的 JSON 嵌套深度超过 $MAX_JSON_DEPTH 层上限。",
                        )
                    }
                }
                '}', ']' -> depth -= 1
            }
        }
    }

    private suspend fun expandObjectArray(
        element: JsonElement,
        path: String,
        origin: String?,
        invalidItemCode: CompatibilityIssueCode,
        diagnostics: MutableList<CompatibilityDiagnostic>,
        activeReferences: MutableSet<String>,
        budget: ReferenceBudget,
        depth: Int,
    ): List<ExpandedObject> {
        val result = mutableListOf<ExpandedObject>()

        suspend fun visit(node: JsonElement, nodePath: String, nodeOrigin: String?, remoteDepth: Int) {
            when (node) {
                is JsonObject -> result += ExpandedObject(node, nodeOrigin, nodePath)
                is JsonArray -> node.forEachIndexed { index, child ->
                    visit(child, "$nodePath[$index]", nodeOrigin, remoteDepth)
                }
                is JsonPrimitive -> {
                    if (!node.isString) {
                        diagnostics += recoverable(invalidItemCode, nodePath, "此处应为 JSON 对象或远程引用 URL。")
                        return
                    }
                    val resolved = resolveRemoteReference(node.content, nodeOrigin)
                    if (resolved == null) {
                        diagnostics += recoverable(
                            CompatibilityIssueCode.REMOTE_REFERENCE_FAILED,
                            nodePath,
                            "远程引用 URL 无效。",
                        )
                        return
                    }
                    if (remoteDepth >= MAX_REMOTE_DEPTH || budget.count >= MAX_REMOTE_REFERENCES) {
                        diagnostics += recoverable(
                            CompatibilityIssueCode.REMOTE_REFERENCE_FAILED,
                            nodePath,
                            "远程引用数量或嵌套深度已达到安全上限。",
                        )
                        return
                    }
                    if (!activeReferences.add(resolved)) {
                        diagnostics += recoverable(
                            CompatibilityIssueCode.REMOTE_REFERENCE_FAILED,
                            nodePath,
                            "检测到远程引用循环。",
                        )
                        return
                    }
                    budget.count += 1
                    try {
                        val bytes = remoteArrayLoader.load(resolved)
                        checkSize(bytes.size)
                        val remoteText = decodeUtf8Strict(bytes).removePrefix("\uFEFF").trim()
                        val remoteElement = parseJson(remoteText)
                        visit(remoteElement, "$nodePath@remote", resolved, remoteDepth + 1)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        diagnostics += recoverable(
                            CompatibilityIssueCode.REMOTE_REFERENCE_FAILED,
                            nodePath,
                            "无法加载或解析远程引用，其他有效条目仍会继续导入。",
                        )
                    } finally {
                        activeReferences.remove(resolved)
                    }
                }
                JsonNull -> diagnostics += recoverable(invalidItemCode, nodePath, "null 不是有效的对象条目。")
            }
        }

        visit(element, path, origin, depth)
        return result
    }

    private fun parseSites(
        objects: List<ExpandedObject>,
        configId: LegacyConfigId,
        globalSpider: String,
        diagnostics: MutableList<CompatibilityDiagnostic>,
    ): List<LegacySiteDescriptor> {
        val result = mutableListOf<LegacySiteDescriptor>()
        val keys = mutableSetOf<String>()
        if (objects.size > MAX_SITES_PER_CONFIG) {
            diagnostics += recoverable(
                CompatibilityIssueCode.RESOURCE_LIMIT,
                "$.sites",
                "站点数量超过 $MAX_SITES_PER_CONFIG 条安全上限，超出部分已保留但不会启用。",
            )
        }
        objects.take(MAX_SITES_PER_CONFIG).forEach { item ->
            val key = scalar(item.value["key"])?.trim().orEmpty()
            if (key.isEmpty()) {
                diagnostics += recoverable(CompatibilityIssueCode.SITE_INVALID, item.path, "站点缺少 key，已跳过该条目。")
                return@forEach
            }
            if (key.length > MAX_SITE_KEY_CHARS || key.any(Char::isISOControl)) {
                diagnostics += recoverable(
                    CompatibilityIssueCode.SITE_INVALID,
                    "${item.path}.key",
                    "站点 key 过长或包含控制字符，已跳过该条目。",
                )
                return@forEach
            }
            if (!keys.add(key)) {
                diagnostics += recoverable(CompatibilityIssueCode.SITE_INVALID, item.path, "站点 key 重复，已跳过该条目。")
                return@forEach
            }
            val type = integer(item.value["type"])
            if (item.value.containsKey("type") && type == null) {
                diagnostics += recoverable(CompatibilityIssueCode.SITE_INVALID, "${item.path}.type", "站点 type 无效，已跳过该条目。")
                return@forEach
            }
            val legacyType = type ?: 0
            val timeout = integer(item.value["timeout"])
            if (item.value.containsKey("timeout") && timeout == null) {
                diagnostics += recoverable(CompatibilityIssueCode.SITE_INVALID, "${item.path}.timeout", "站点 timeout 无效，已跳过该条目。")
                return@forEach
            }
            val searchable = integer(item.value["searchable"]) ?: 1
            val quickSearch = (integer(item.value["quickSearch"]) ?: 1) == 1
            val availability = when (legacyType) {
                0, 1, 4 -> SourceAvailability.AVAILABLE
                3 -> SourceAvailability.TEMPORARILY_UNAVAILABLE
                else -> SourceAvailability.LOAD_FAILED
            }
            if (legacyType == 3) {
                diagnostics += policyDiagnostic(item.path, "已保留插件站点描述，但 Nexora 禁止执行旧版插件。")
            } else if (legacyType !in setOf(0, 1, 4)) {
                diagnostics += recoverable(
                    CompatibilityIssueCode.UNSUPPORTED_SOURCE_TYPE,
                    "${item.path}.type",
                    "暂不支持站点类型 $legacyType，已将其标记为加载失败。",
                )
            }
            val api = normalizeRelativeUrl(scalar(item.value["api"]).orEmpty(), item.origin, "${item.path}.api", diagnostics)
            val ext = extensionText(item.value["ext"])
            val jarValue = scalar(item.value["jar"]).orEmpty().ifEmpty { globalSpider }
            val jar = normalizeRelativeUrl(jarValue, item.origin, "${item.path}.jar", diagnostics)
            val playUrl = normalizeRelativeUrl(
                scalar(item.value["playUrl"]).orEmpty(),
                item.origin,
                "${item.path}.playUrl",
                diagnostics,
            )
            result += LegacySiteDescriptor(
                sourceKey = LegacySourceKey("${configId.value}:$key"),
                legacyKey = key,
                name = scalar(item.value["name"])?.trim().orEmpty().ifEmpty { key },
                type = legacyType,
                api = api,
                ext = normalizeRelativeUrl(ext, item.origin, "${item.path}.ext", diagnostics),
                jar = jar,
                playUrl = playUrl,
                timeoutMillis = (timeout ?: 15).coerceIn(1, 60).toLong() * 1_000L,
                quickSearch = quickSearch,
                categories = primitiveList(item.value["categories"], "${item.path}.categories", diagnostics),
                requestHeaders = stringMap(item.value["header"], "${item.path}.header", diagnostics),
                state = SourceOperationalState(
                    searchCapability = if (searchable == 0 || legacyType !in setOf(0, 1, 3, 4)) {
                        SourceSearchCapability.UNSUPPORTED
                    } else {
                        SourceSearchCapability.SUPPORTED
                    },
                    userActivation = SourceUserActivation.ENABLED,
                    availability = availability,
                ),
                raw = RawJson(canonical(item.value)),
            )
        }
        return result
    }

    private fun parseParses(
        objects: List<ExpandedObject>,
        diagnostics: MutableList<CompatibilityDiagnostic>,
    ): List<LegacyParseDescriptor> {
        val result = mutableListOf<LegacyParseDescriptor>()
        val names = mutableSetOf<String>()
        objects.forEach { item ->
            val name = scalar(item.value["name"])?.trim().orEmpty()
            if (name.isEmpty()) {
                diagnostics += recoverable(CompatibilityIssueCode.PARSE_INVALID, item.path, "解析器缺少 name，已跳过该条目。")
                return@forEach
            }
            if (!names.add(name)) {
                diagnostics += recoverable(CompatibilityIssueCode.PARSE_INVALID, item.path, "解析器 name 重复，已跳过该条目。")
                return@forEach
            }
            val type = integer(item.value["type"])
            if (item.value.containsKey("type") && type == null) {
                diagnostics += recoverable(CompatibilityIssueCode.PARSE_INVALID, "${item.path}.type", "解析器 type 无效，已跳过该条目。")
                return@forEach
            }
            result += LegacyParseDescriptor(
                name = name,
                type = type ?: 0,
                url = normalizeRelativeUrl(
                    scalar(item.value["url"]).orEmpty(),
                    item.origin,
                    "${item.path}.url",
                    diagnostics,
                ),
                ext = item.value["ext"]?.takeUnless { value -> value is JsonNull }?.let { RawJson(canonical(it)) },
                raw = RawJson(canonical(item.value)),
            )
        }
        return result
    }

    private fun optionalScalar(
        root: JsonObject,
        key: String,
        diagnostics: MutableList<CompatibilityDiagnostic>,
    ): String? {
        val element = root[key] ?: return null
        if (element is JsonNull) return null
        val value = scalar(element)
        if (value == null) {
            diagnostics += recoverable(
                CompatibilityIssueCode.FIELD_TYPE_MISMATCH,
                "$.${key}",
                "字段 '$key' 必须是字符串、数字或布尔值。",
            )
        }
        return value
    }

    private fun stringList(
        root: JsonObject,
        key: String,
        diagnostics: MutableList<CompatibilityDiagnostic>,
    ): List<String> = primitiveList(root[key], "$.${key}", diagnostics)

    private fun primitiveList(
        element: JsonElement?,
        path: String,
        diagnostics: MutableList<CompatibilityDiagnostic>,
    ): List<String> {
        if (element == null || element is JsonNull) return emptyList()
        val values = if (element is JsonArray) element else JsonArray(listOf(element))
        return buildList {
            values.forEachIndexed { index, value ->
                val scalar = scalar(value)
                if (scalar == null) {
                    diagnostics += recoverable(
                        CompatibilityIssueCode.FIELD_TYPE_MISMATCH,
                        "$path[$index]",
                        "此处应为字符串、数字或布尔值。",
                    )
                } else {
                    add(scalar)
                }
            }
        }
    }

    private fun stringMap(
        element: JsonElement?,
        path: String,
        diagnostics: MutableList<CompatibilityDiagnostic>,
    ): Map<String, String> {
        if (element == null || element is JsonNull) return emptyMap()
        if (element !is JsonObject) {
            diagnostics += recoverable(CompatibilityIssueCode.FIELD_TYPE_MISMATCH, path, "此处应为 JSON 对象。")
            return emptyMap()
        }
        return buildMap {
            element.entries.sortedBy(Map.Entry<String, JsonElement>::key).forEach { (key, value) ->
                val scalar = scalar(value)
                if (scalar == null) {
                    diagnostics += recoverable(
                        CompatibilityIssueCode.FIELD_TYPE_MISMATCH,
                        "$path.$key",
                        "请求头的值必须是字符串、数字或布尔值。",
                    )
                } else {
                    put(key, scalar)
                }
            }
        }
    }

    private fun normalizeRelativeUrl(
        value: String,
        origin: String?,
        path: String,
        diagnostics: MutableList<CompatibilityDiagnostic>,
    ): String {
        if (value.isEmpty() || (!value.startsWith("./") && !value.startsWith("../"))) return value
        if (origin.isNullOrBlank()) return value
        return try {
            URI(origin).resolve(value).normalize().toASCIIString()
        } catch (_: Exception) {
            diagnostics += recoverable(CompatibilityIssueCode.INVALID_URL, path, "无法根据配置来源解析相对 URL。")
            value
        }
    }

    private fun resolveRemoteReference(reference: String, origin: String?): String? = try {
        val candidate = URI(reference.trim())
        val resolved = if (candidate.isAbsolute) candidate else origin?.let { URI(it).resolve(candidate) } ?: return null
        if (resolved.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) null else resolved.normalize().toASCIIString()
    } catch (_: Exception) {
        null
    }

    private fun redactOriginForDisplay(origin: String?): String? {
        if (origin.isNullOrBlank()) return null
        return runCatching {
            val parsed = URI(origin)
            val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return@runCatching null
            val host = parsed.host ?: return@runCatching null
            URI(scheme, null, host, parsed.port, null, null, null).toASCIIString() + "/…"
        }.getOrNull() ?: "远程配置地址（路径已隐藏）"
    }

    private fun configIdentityBytes(
        originalCanonical: String,
        kind: ConfigImportKind,
        origin: String?,
    ): ByteArray {
        val stableRemoteOrigin = if (kind == ConfigImportKind.REMOTE_URL) {
            canonicalRemoteOriginForIdentity(origin)
        } else {
            null
        }
        val identity = stableRemoteOrigin?.let { value -> "remote-origin\u0000$value" }
            ?: originalCanonical
        return identity.toByteArray(StandardCharsets.UTF_8)
    }

    private fun canonicalRemoteOriginForIdentity(origin: String?): String? {
        if (origin.isNullOrBlank() || origin.any(Char::isISOControl)) return null
        return runCatching {
            val parsed = URI(origin.trim()).normalize()
            if (!parsed.scheme.equals("https", ignoreCase = true) ||
                parsed.host.isNullOrBlank() ||
                parsed.rawUserInfo != null ||
                parsed.rawFragment != null
            ) {
                return@runCatching null
            }
            val normalizedPort = parsed.port.takeUnless { port -> port == 443 }
            URI(
                "https",
                null,
                parsed.host.lowercase(Locale.ROOT),
                normalizedPort ?: -1,
                parsed.rawPath?.ifEmpty { "/" } ?: "/",
                parsed.rawQuery,
                null,
            ).toASCIIString()
        }.getOrNull()
    }

    private fun extensionText(element: JsonElement?): String = when (element) {
        null, JsonNull -> ""
        is JsonPrimitive -> element.content
        else -> canonical(element)
    }

    private fun scalar(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.content
        else -> null
    }

    private fun integer(element: JsonElement?): Int? = scalar(element)?.toIntOrNull()

    private fun rawObjects(items: List<ExpandedObject>?): List<RawJson> =
        items.orEmpty().map { item -> RawJson(canonical(item.value)) }

    private fun canonical(element: JsonElement): String = canonicalElement(element).toString()

    private fun canonicalElement(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries.sortedBy(Map.Entry<String, JsonElement>::key)
                .associate { (key, value) -> key to canonicalElement(value) },
        )
        is JsonArray -> JsonArray(element.map(::canonicalElement))
        else -> element
    }

    private fun recoverable(
        code: CompatibilityIssueCode,
        path: String,
        message: String,
    ): CompatibilityDiagnostic = CompatibilityDiagnostic(
        code = code,
        severity = CompatibilitySeverity.WARNING,
        jsonPath = path,
        userMessage = message,
        recoverable = true,
    )

    private fun policyDiagnostic(path: String, message: String): CompatibilityDiagnostic = CompatibilityDiagnostic(
        code = CompatibilityIssueCode.POLICY_PRESERVED_NOT_EXECUTED,
        severity = CompatibilitySeverity.INFO,
        jsonPath = path,
        userMessage = message,
        recoverable = true,
    )

    private fun fatal(code: CompatibilityIssueCode, message: String): LegacyConfigImportResult {
        val diagnostic = CompatibilityDiagnostic(
            code = code,
            severity = CompatibilitySeverity.ERROR,
            jsonPath = "$",
            userMessage = message,
            recoverable = false,
        )
        return LegacyConfigImportResult(emptyList(), listOf(diagnostic))
    }

    private fun looksLikeJson(value: String): Boolean = value.startsWith('{') || value.startsWith('[')

    private fun decodeUtf8Strict(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    private fun encodeUtf8Strict(value: String): ByteArray {
        val buffer = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        return ByteArray(buffer.remaining()).also(buffer::get)
    }

    private fun hexToBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun findClosingMarker(bytes: ByteArray): Int {
        for (index in 2 until bytes.lastIndex) {
            if (bytes[index] == '#'.code.toByte() && bytes[index + 1] == '$'.code.toByte()) return index
        }
        return -1
    }

    private fun ascii(bytes: ByteArray): String {
        if (bytes.any { byte -> byte.toInt() !in 0x20..0x7E }) {
            throw ImportFailure(CompatibilityIssueCode.INVALID_2423_AES, "2423 AES 密钥和 IV 必须使用 ASCII 字符。")
        }
        return String(bytes, StandardCharsets.US_ASCII)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private data class ExpandedObject(
        val value: JsonObject,
        val origin: String?,
        val path: String,
    )

    private class ReferenceBudget(var count: Int = 0)

    private class ImportFailure(
        val code: CompatibilityIssueCode,
        message: String,
    ) : IllegalArgumentException(message)
}
