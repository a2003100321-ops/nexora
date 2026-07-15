package com.nexora.source.runtime.http

import com.nexora.source.api.LegacySiteDescriptor
import com.nexora.source.api.SourceCategory
import com.nexora.source.api.SourceEpisode
import com.nexora.source.api.SourceErrorCode
import com.nexora.source.api.SourceFilter
import com.nexora.source.api.SourceFilterOption
import com.nexora.source.api.SourceHome
import com.nexora.source.api.SourceMediaDetail
import com.nexora.source.api.SourceMediaSummary
import com.nexora.source.api.SourcePage
import com.nexora.source.api.SourcePlaybackLine
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.w3c.dom.Element
import org.w3c.dom.Node

internal class LegacyPayloadDecoder {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    suspend fun decodeHome(site: LegacySiteDescriptor, bytes: ByteArray): SourceHome {
        val guard = CancellationGuard(currentCoroutineContext())
        val payload = decode(site, bytes, guard)
        val categories = applyCategoryPolicy(payload.categories, site.categories)
        return SourceHome(
            categories = categories,
            filtersByCategory = payload.filtersByCategory
                .filterKeys { categoryId -> categories.any { it.id == categoryId } },
            featured = payload.page,
        )
    }

    suspend fun decodePage(site: LegacySiteDescriptor, bytes: ByteArray): SourcePage =
        decode(site, bytes, CancellationGuard(currentCoroutineContext())).page

    suspend fun decodeDetail(site: LegacySiteDescriptor, bytes: ByteArray): SourceMediaDetail {
        val payload = decode(site, bytes, CancellationGuard(currentCoroutineContext()))
        val detail = payload.details.firstOrNull()
            ?: throw PayloadDecodeException(
                SourceErrorCode.INVALID_RESPONSE,
                "数据源没有返回影片详情。",
            )
        return detail
    }

    suspend fun decodePlayer(site: LegacySiteDescriptor, bytes: ByteArray): DecodedPlayer {
        val guard = CancellationGuard(currentCoroutineContext())
        val root = parseJsonObject(bytes, guard)
        remoteError(root)?.let { message ->
            throw PayloadDecodeException(SourceErrorCode.INVALID_RESPONSE, message)
        }
        val url = root.string("url")
            ?: root["url"].firstUrl()
            ?: throw PayloadDecodeException(
                SourceErrorCode.INVALID_RESPONSE,
                "数据源返回的播放地址为空。",
            )
        return DecodedPlayer(
            url = url.trim(),
            flag = root.string("flag").orEmpty(),
            headers = decodePlayerHeaders(root["header"], guard).also(::validatePlayerHeaders),
            parse = root.int("parse") ?: 0,
            jx = root.int("jx") ?: 0,
            parserUrl = root.string("playUrl")?.trim()?.takeIf(String::isNotEmpty),
            format = root.string("format")?.trim()?.takeIf(String::isNotEmpty),
        )
    }

    private fun decode(
        site: LegacySiteDescriptor,
        bytes: ByteArray,
        guard: CancellationGuard,
    ): DecodedPayload = when (site.type) {
        0 -> decodeXml(site, bytes, guard)
        1, 4 -> decodeJson(site, bytes, guard)
        else -> throw PayloadDecodeException(
            SourceErrorCode.UNSUPPORTED_TYPE,
            "暂不支持 type ${site.type} 数据源。",
        )
    }

    private fun decodeJson(
        site: LegacySiteDescriptor,
        bytes: ByteArray,
        guard: CancellationGuard,
    ): DecodedPayload {
        val root = parseJsonObject(bytes, guard)
        remoteError(root)?.let { message ->
            throw PayloadDecodeException(SourceErrorCode.INVALID_RESPONSE, message)
        }
        val categories = root.array("class").take(MAX_CATEGORIES).mapNotNull { item ->
            guard.check()
            val category = item as? JsonObject ?: return@mapNotNull null
            val id = category.string("type_id") ?: category.string("id") ?: return@mapNotNull null
            val name = category.string("type_name") ?: category.string("name") ?: return@mapNotNull null
            SourceCategory(id = id.trim(), name = name.trim())
        }.filter { category -> category.id.isNotEmpty() && category.name.isNotEmpty() }

        val details = root.array("list").take(MAX_ITEMS).mapNotNull { item ->
            guard.check()
            (item as? JsonObject)?.toDetail(site, guard)
        }
        return DecodedPayload(
            categories = categories,
            filtersByCategory = decodeFilters(root["filters"], guard),
            page = SourcePage(
                items = details.map(SourceMediaDetail::media),
                page = root.positiveInt("page") ?: 1,
                pageCount = root.positiveInt("pagecount") ?: 1,
                total = root.nonNegativeInt("total"),
                pageSize = root.positiveInt("limit") ?: root.positiveInt("pagesize"),
            ),
            details = details,
        )
    }

    private fun decodeXml(
        site: LegacySiteDescriptor,
        bytes: ByteArray,
        guard: CancellationGuard,
    ): DecodedPayload {
        if (DOCTYPE.containsMatchIn(bytes.toString(Charsets.ISO_8859_1))) {
            throw PayloadDecodeException(
                SourceErrorCode.SECURITY_REJECTED,
                "数据源返回了包含 DOCTYPE 的不安全 XML，已拒绝解析。",
            )
        }
        val document = try {
            secureDocumentBuilderFactory().newDocumentBuilder().apply {
                setEntityResolver { _, _ -> throw SecurityException("External entities are disabled") }
            }.parse(ByteArrayInputStream(bytes))
        } catch (_: SecurityException) {
            throw PayloadDecodeException(
                SourceErrorCode.SECURITY_REJECTED,
                "数据源返回了包含外部实体的不安全 XML，已拒绝解析。",
            )
        } catch (_: Exception) {
            throw PayloadDecodeException(
                SourceErrorCode.INVALID_RESPONSE,
                "数据源返回的 XML 格式无效。",
            )
        }
        val root = document.documentElement
        if (!root.tagName.equals("rss", ignoreCase = true)) {
            throw PayloadDecodeException(
                SourceErrorCode.INVALID_RESPONSE,
                "数据源返回的 XML 缺少 rss 根节点。",
            )
        }
        val categories = root.directChild("class")
            ?.directChildren("ty")
            .orEmpty()
            .take(MAX_CATEGORIES)
            .mapNotNull { element ->
                guard.check()
                val id = element.getAttribute("id").trim()
                val name = element.textContent.orEmpty().trim()
                if (id.isEmpty() || name.isEmpty()) null else SourceCategory(id, name)
            }
        val list = root.directChild("list")
        val details = list?.directChildren("video").orEmpty().take(MAX_ITEMS).mapNotNull { video ->
            guard.check()
            video.toDetail(site, guard)
        }
        return DecodedPayload(
            categories = categories,
            filtersByCategory = emptyMap(),
            page = SourcePage(
                items = details.map(SourceMediaDetail::media),
                page = list?.positiveIntAttribute("page") ?: 1,
                pageCount = list?.positiveIntAttribute("pagecount") ?: 1,
                total = list?.nonNegativeIntAttribute("recordcount"),
                pageSize = list?.positiveIntAttribute("pagesize"),
            ),
            details = details,
        )
    }

    private fun parseJsonObject(bytes: ByteArray, guard: CancellationGuard): JsonObject {
        val text = decodeUtf8(bytes)
        enforceJsonDepth(text, guard)
        val element = try {
            json.parseToJsonElement(text)
        } catch (_: Exception) {
            throw PayloadDecodeException(
                SourceErrorCode.INVALID_RESPONSE,
                "数据源返回的 JSON 格式无效。",
            )
        }
        return element as? JsonObject ?: throw PayloadDecodeException(
            SourceErrorCode.INVALID_RESPONSE,
            "数据源返回的内容不是 JSON 对象。",
        )
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .removePrefix("\uFEFF")
    } catch (_: Exception) {
        throw PayloadDecodeException(
            SourceErrorCode.INVALID_RESPONSE,
            "数据源返回的内容不是有效的 UTF-8 文本。",
        )
    }

    private fun JsonObject.toDetail(site: LegacySiteDescriptor, guard: CancellationGuard): SourceMediaDetail? {
        val id = string("vod_id")?.trim().orEmpty()
        val title = string("vod_name")?.trim().orEmpty()
        if (id.isEmpty() || title.isEmpty()) return null
        val from = string("vod_play_from").orEmpty()
        val urls = string("vod_play_url").orEmpty()
        return SourceMediaDetail(
            media = SourceMediaSummary(
                sourceKey = site.sourceKey,
                vodId = id,
                title = title,
                year = string("vod_year")?.trim().orEmpty(),
                type = string("type_name")?.trim().orEmpty(),
                region = string("vod_area")?.trim().orEmpty(),
                poster = string("vod_pic")?.trim().orEmpty(),
                sourceName = site.name,
                remarks = string("vod_remarks")?.trim().orEmpty(),
            ),
            director = string("vod_director")?.trim().orEmpty(),
            actors = string("vod_actor")?.trim().orEmpty(),
            description = string("vod_content")?.trim().orEmpty(),
            lines = decodeJsonLines(from, urls, guard),
        )
    }

    private fun Element.toDetail(site: LegacySiteDescriptor, guard: CancellationGuard): SourceMediaDetail? {
        val id = directText("id")
        val title = directText("name")
        if (id.isEmpty() || title.isEmpty()) return null
        val lines = directChild("dl")?.directChildren("dd").orEmpty().take(MAX_LINES).mapNotNull { line ->
            guard.check()
            val name = line.getAttribute("flag").trim()
            val episodes = decodeEpisodes(line.textContent.orEmpty(), guard)
            if (name.isEmpty() || episodes.isEmpty()) null else SourcePlaybackLine(name, episodes)
        }
        return SourceMediaDetail(
            media = SourceMediaSummary(
                sourceKey = site.sourceKey,
                vodId = id,
                title = title,
                year = directText("year"),
                type = directText("type"),
                region = directText("area"),
                poster = directText("pic"),
                sourceName = site.name,
                remarks = directText("note"),
            ),
            director = directText("director"),
            actors = directText("actor"),
            description = directText("des"),
            lines = lines,
        )
    }

    private fun decodeJsonLines(
        from: String,
        urls: String,
        guard: CancellationGuard,
    ): List<SourcePlaybackLine> {
        val names = from.split("$$$")
        val values = urls.split("$$$")
        return names.take(MAX_LINES).mapIndexedNotNull { index, rawName ->
            guard.check()
            val name = rawName.trim()
            val episodes = values.getOrNull(index)?.let { decodeEpisodes(it, guard) }.orEmpty()
            if (name.isEmpty() || episodes.isEmpty()) null else SourcePlaybackLine(name, episodes)
        }
    }

    private fun decodeEpisodes(value: String, guard: CancellationGuard): List<SourceEpisode> = value
        .split('#')
        .take(MAX_EPISODES_PER_LINE)
        .mapIndexedNotNull { index, rawEpisode ->
            guard.check()
            val episode = rawEpisode.trim()
            if (episode.isEmpty()) return@mapIndexedNotNull null
            val parts = episode.split('$', limit = 2)
            val playbackId = parts.getOrNull(1)?.trim().orEmpty().ifEmpty { episode }
            if (playbackId.isEmpty()) return@mapIndexedNotNull null
            val fallbackName = (index + 1).toString().padStart(2, '0')
            val name = parts.getOrNull(1)?.let { parts.first().trim().ifEmpty { fallbackName } } ?: fallbackName
            SourceEpisode(name = name, playbackId = playbackId)
        }
        .distinctBy(SourceEpisode::playbackId)

    private fun decodeFilters(
        element: JsonElement?,
        guard: CancellationGuard,
    ): Map<String, List<SourceFilter>> {
        val root = element as? JsonObject ?: return emptyMap()
        return buildMap {
            root.entries.take(MAX_CATEGORIES).forEach { (categoryId, rawFilters) ->
                guard.check()
                val filters = (rawFilters as? JsonArray).orEmpty().take(MAX_FILTERS_PER_CATEGORY)
                    .mapNotNull { rawFilter ->
                    guard.check()
                    val filter = rawFilter as? JsonObject ?: return@mapNotNull null
                    val key = filter.string("key")?.trim().orEmpty()
                    if (key.isEmpty()) return@mapNotNull null
                    val options = filter.array("value").take(MAX_FILTER_OPTIONS).mapNotNull { rawOption ->
                        guard.check()
                        val option = rawOption as? JsonObject ?: return@mapNotNull null
                        val value = option.string("v")?.trim().orEmpty()
                        val name = option.string("n")?.trim().orEmpty()
                        if (value.isEmpty() || name.isEmpty()) null else SourceFilterOption(name, value)
                    }
                    SourceFilter(
                        key = key,
                        name = filter.string("name")?.trim().orEmpty().ifEmpty { key },
                        initialValue = filter.string("init")?.trim()?.takeIf(String::isNotEmpty),
                        options = options,
                    )
                }
                if (filters.isNotEmpty()) put(categoryId, filters)
            }
        }
    }

    private fun remoteError(root: JsonObject): String? {
        if (!root.containsKey("code") || root.int("code") != 0) return null
        return root.string("msg")?.trim()?.takeIf(String::isNotEmpty)?.let { message ->
            "数据源返回错误：${message.take(MAX_REMOTE_MESSAGE_LENGTH)}"
        }
    }

    private fun enforceJsonDepth(text: String, guard: CancellationGuard) {
        var depth = 0
        var inString = false
        var escaped = false
        text.forEachIndexed { index, character ->
            if (index % CANCELLATION_CHECK_INTERVAL == 0) guard.check()
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth += 1
                        if (depth > MAX_JSON_DEPTH) throw PayloadDecodeException(
                            SourceErrorCode.INVALID_RESPONSE,
                            "数据源返回的 JSON 嵌套层级过深。",
                        )
                    }

                    '}', ']' -> {
                        depth -= 1
                        if (depth < 0) throw PayloadDecodeException(
                            SourceErrorCode.INVALID_RESPONSE,
                            "数据源返回的 JSON 结构无效。",
                        )
                    }
                }
            }
        }
        if (inString || depth != 0) throw PayloadDecodeException(
            SourceErrorCode.INVALID_RESPONSE,
            "数据源返回的 JSON 结构不完整。",
        )
    }

    private fun validatePlayerHeaders(headers: Map<String, String>) {
        val valid = headers.size <= MAX_PLAYER_HEADERS && headers.all { (name, value) ->
            name.isNotEmpty() &&
                name.length <= MAX_HEADER_NAME_LENGTH &&
                name.all { character -> character.code in 0x21..0x7e && character !in HEADER_SEPARATORS } &&
                value.length <= MAX_HEADER_VALUE_LENGTH &&
                value.all { character -> character == '\t' || !character.isISOControl() }
        }
        if (!valid) throw PayloadDecodeException(
            SourceErrorCode.SECURITY_REJECTED,
            "数据源返回了不安全的播放请求头，已拒绝使用。",
        )
    }

    private fun decodePlayerHeaders(
        element: JsonElement?,
        guard: CancellationGuard,
    ): Map<String, String> {
        val objectValue = when (element) {
            is JsonObject -> element
            is JsonPrimitive -> {
                val nested = element.contentOrNullCompat()?.takeIf { it.length <= MAX_NESTED_HEADER_JSON_LENGTH }
                    ?: return emptyMap()
                enforceJsonDepth(nested, guard)
                runCatching { json.parseToJsonElement(nested) as? JsonObject }.getOrNull()
            }

            else -> null
        } ?: return emptyMap()
        return objectValue.mapNotNull { (name, value) ->
            guard.check()
            (value as? JsonPrimitive)?.contentOrNullCompat()?.let { name to it }
        }.toMap()
    }

    private fun applyCategoryPolicy(
        categories: List<SourceCategory>,
        configuredNames: List<String>,
    ): List<SourceCategory> {
        if (configuredNames.isEmpty()) return categories
        val byName = categories.associateBy(SourceCategory::name)
        val configured = configuredNames.mapNotNull(byName::get)
        return configured.ifEmpty { categories }
    }

    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            runCatching { setAttribute(ACCESS_EXTERNAL_DTD, "") }
            runCatching { setAttribute(ACCESS_EXTERNAL_SCHEMA, "") }
        }

    private companion object {
        private const val MAX_REMOTE_MESSAGE_LENGTH: Int = 160
        private const val MAX_JSON_DEPTH: Int = 64
        private const val MAX_CATEGORIES: Int = 500
        private const val MAX_ITEMS: Int = 1_000
        private const val MAX_LINES: Int = 100
        private const val MAX_EPISODES_PER_LINE: Int = 1_000
        private const val MAX_FILTERS_PER_CATEGORY: Int = 100
        private const val MAX_FILTER_OPTIONS: Int = 500
        private const val MAX_PLAYER_HEADERS: Int = 64
        private const val MAX_HEADER_NAME_LENGTH: Int = 128
        private const val MAX_HEADER_VALUE_LENGTH: Int = 8_192
        private const val MAX_NESTED_HEADER_JSON_LENGTH: Int = 64 * 1_024
        private const val CANCELLATION_CHECK_INTERVAL: Int = 4_096
        private const val ACCESS_EXTERNAL_DTD: String = "http://javax.xml.XMLConstants/property/accessExternalDTD"
        private const val ACCESS_EXTERNAL_SCHEMA: String =
            "http://javax.xml.XMLConstants/property/accessExternalSchema"
        private val DOCTYPE: Regex = Regex("<!DOCTYPE", RegexOption.IGNORE_CASE)
        private const val HEADER_SEPARATORS: String = "()<>@,;:\\\"/[]?={} \t"
    }
}

private class CancellationGuard(private val context: CoroutineContext) {
    fun check() {
        context.ensureActive()
    }
}

internal data class DecodedPlayer(
    val url: String,
    val flag: String,
    val headers: Map<String, String>,
    val parse: Int,
    val jx: Int,
    val parserUrl: String?,
    val format: String?,
)

internal class PayloadDecodeException(
    val code: SourceErrorCode,
    message: String,
) : IllegalArgumentException(message)

private data class DecodedPayload(
    val categories: List<SourceCategory>,
    val filtersByCategory: Map<String, List<SourceFilter>>,
    val page: SourcePage,
    val details: List<SourceMediaDetail>,
)

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNullCompat()

private fun JsonPrimitive.contentOrNullCompat(): String? = runCatching { content }.getOrNull()

private fun JsonObject.int(key: String): Int? = string(key)?.trim()?.toIntOrNull()

private fun JsonObject.positiveInt(key: String): Int? = int(key)?.takeIf { it > 0 }

private fun JsonObject.nonNegativeInt(key: String): Int? = int(key)?.takeIf { it >= 0 }

private fun JsonObject.array(key: String): JsonArray = get(key) as? JsonArray ?: JsonArray(emptyList())

private fun JsonElement?.firstUrl(): String? = when (this) {
    is JsonArray -> if (size >= 2 && getOrNull(1) is JsonPrimitive) {
        getOrNull(1).firstUrl()
    } else {
        firstNotNullOfOrNull { child -> child.firstUrl() }
    }

    is JsonObject -> {
        val values = get("values") as? JsonArray
        val position = int("position")?.coerceAtLeast(0) ?: 0
        string("url") ?: string("v") ?: values?.getOrNull(position).firstUrl()
    }

    is JsonPrimitive -> contentOrNullCompat()
    else -> null
}

private fun Element.directChild(name: String): Element? = childElements().firstOrNull { child ->
    child.tagName.equals(name, ignoreCase = true)
}

private fun Element.directChildren(name: String): List<Element> = childElements().filter { child ->
    child.tagName.equals(name, ignoreCase = true)
}

private fun Element.directText(name: String): String = directChild(name)?.textContent.orEmpty().trim()

private fun Element.childElements(): List<Element> = buildList {
    val nodes = childNodes
    for (index in 0 until nodes.length) {
        val node = nodes.item(index)
        if (node.nodeType == Node.ELEMENT_NODE) add(node as Element)
    }
}

private fun Element.positiveIntAttribute(name: String): Int? =
    getAttribute(name).trim().toIntOrNull()?.takeIf { it > 0 }

private fun Element.nonNegativeIntAttribute(name: String): Int? =
    getAttribute(name).trim().toIntOrNull()?.takeIf { it >= 0 }
