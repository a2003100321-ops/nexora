package com.nexora.source.runtime.http

import com.nexora.core.network.NetworkBody
import com.nexora.core.network.NetworkFailureCode
import com.nexora.core.network.NetworkMethod
import com.nexora.core.network.NetworkRequest
import com.nexora.core.network.NetworkResponse
import com.nexora.core.network.NetworkResult
import com.nexora.core.network.NetworkTransports
import com.nexora.core.network.SafeHttpTransport
import com.nexora.source.api.LegacyHttpSourceGateway
import com.nexora.source.api.LegacySiteDescriptor
import com.nexora.source.api.PlaybackRequest
import com.nexora.source.api.PlaybackResolution
import com.nexora.source.api.SourceAvailability
import com.nexora.source.api.SourceError
import com.nexora.source.api.SourceErrorCode
import com.nexora.source.api.SourceHome
import com.nexora.source.api.SourceMediaDetail
import com.nexora.source.api.SourcePage
import com.nexora.source.api.SourceResult
import com.nexora.source.api.SourceSearchCapability
import com.nexora.source.api.SourceUserActivation
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

public class DefaultLegacyHttpSourceGateway(
    private val transport: SafeHttpTransport = NetworkTransports.create(),
) : LegacyHttpSourceGateway {
    private val decoder = LegacyPayloadDecoder()

    override suspend fun home(site: LegacySiteDescriptor): SourceResult<SourceHome> {
        preflight(site)?.let { return it }
        return withOperationDeadline(site) {
            if (site.type == TYPE_FOUR) {
                withResolvedExt(site) { resolvedExt ->
                    executeAndDecode(
                        site = site,
                        request = callRequest(site, linkedMapOf("filter" to "true"), resolvedExt),
                        decode = { response -> decoder.decodeHome(site, response.body) },
                    )
                }
            } else {
                val request = directGetRequest(site) ?: return@withOperationDeadline invalidUrl(site)
                executeHomeWithPosterBackfill(site, request, site.ext)
            }
        }
    }

    override suspend fun category(
        site: LegacySiteDescriptor,
        categoryId: String,
        page: Int,
        filters: Map<String, String>,
    ): SourceResult<SourcePage> {
        preflight(site)?.let { return it }
        if (!validTextArgument(categoryId, MAX_IDENTIFIER_LENGTH) || page < 1) {
            return invalidRequest(site, "分类标识或页码无效。")
        }
        val filterJson = encodeFilters(filters) ?: return invalidRequest(site, "筛选条件过多或格式无效。")
        return withOperationDeadline(site) {
            withResolvedExt(site) { resolvedExt ->
                val parameters = linkedMapOf<String, String>()
                if (site.type == TYPE_ONE && filters.isNotEmpty()) parameters["f"] = filterJson
                if (site.type == TYPE_FOUR) {
                    parameters["ext"] = Base64.getUrlEncoder().encodeToString(filterJson.toByteArray(Charsets.UTF_8))
                }
                parameters["ac"] = actionFor(site.type)
                parameters["t"] = categoryId.trim()
                parameters["pg"] = page.toString()
                executePageWithPosterBackfill(
                    site = site,
                    request = callRequest(site, parameters, resolvedExt),
                    resolvedExt = resolvedExt,
                )
            }
        }
    }

    override suspend fun detail(
        site: LegacySiteDescriptor,
        vodId: String,
    ): SourceResult<SourceMediaDetail> {
        preflight(site)?.let { return it }
        if (!validTextArgument(vodId, MAX_PLAYBACK_ID_LENGTH)) {
            return invalidRequest(site, "影片标识为空或格式无效。")
        }
        return withOperationDeadline(site) {
            withResolvedExt(site) { resolvedExt ->
                executeAndDecode(
                    site = site,
                    request = callRequest(
                        site,
                        linkedMapOf(
                            "ac" to actionFor(site.type),
                            "ids" to vodId.trim(),
                        ),
                        resolvedExt,
                    ),
                    decode = { response -> decoder.decodeDetail(site, response.body) },
                )
            }
        }
    }

    override suspend fun search(
        site: LegacySiteDescriptor,
        keyword: String,
        page: Int,
        quick: Boolean,
    ): SourceResult<SourcePage> {
        preflight(site, requireSearch = true)?.let { return it }
        if (!validTextArgument(keyword, MAX_SEARCH_LENGTH) || page < 1) {
            return invalidRequest(site, "搜索关键词或页码无效。")
        }
        return withOperationDeadline(site) {
            withResolvedExt(site) { resolvedExt ->
                val parameters = linkedMapOf(
                    "wd" to keyword.trim(),
                    "quick" to quick.toString(),
                    "extend" to "",
                )
                if (page != 1) parameters["pg"] = page.toString()
                executePageWithPosterBackfill(
                    site = site,
                    request = callRequest(site, parameters, resolvedExt),
                    resolvedExt = resolvedExt,
                )
            }
        }
    }

    override suspend fun playback(
        site: LegacySiteDescriptor,
        flag: String,
        playbackId: String,
    ): SourceResult<PlaybackRequest> {
        preflight(site)?.let { return it }
        if (!validTextArgument(flag, MAX_IDENTIFIER_LENGTH) ||
            !validTextArgument(playbackId, MAX_PLAYBACK_ID_LENGTH)
        ) {
            return invalidRequest(site, "播放线路或播放标识无效。")
        }
        return withOperationDeadline(site) {
            if (site.type != TYPE_FOUR) {
                if (!validPlaybackHeaders(site.requestHeaders)) {
                    return@withOperationDeadline securityFailure(
                        site,
                        "数据源配置包含不安全的播放请求头，已拒绝使用。",
                    )
                }
                val direct = isDirectMediaAddress(playbackId) && site.playUrl.isBlank()
                return@withOperationDeadline SourceResult.Success(
                    PlaybackRequest(
                        sourceKey = site.sourceKey,
                        flag = flag.trim(),
                        url = playbackId.trim(),
                        headers = site.requestHeaders,
                        resolution = if (direct) PlaybackResolution.DIRECT else PlaybackResolution.REQUIRES_PARSER,
                        parserUrl = site.playUrl.trim().takeIf(String::isNotEmpty),
                    ),
                )
            }
            withResolvedExt(site) { resolvedExt ->
                executeAndDecode(
                    site = site,
                    request = callRequest(
                        site,
                        linkedMapOf(
                            "play" to playbackId.trim(),
                            "flag" to flag.trim(),
                        ),
                        resolvedExt,
                    ),
                ) { response ->
                    val decoded = decoder.decodePlayer(site, response.body)
                    val playbackHeaders = decoded.headers.ifEmpty { site.requestHeaders }
                    if (!validPlaybackHeaders(playbackHeaders)) {
                        throw PayloadDecodeException(
                            SourceErrorCode.SECURITY_REJECTED,
                            "数据源返回了不安全的播放请求头，已拒绝使用。",
                        )
                    }
                    PlaybackRequest(
                        sourceKey = site.sourceKey,
                        flag = decoded.flag.ifEmpty { flag.trim() },
                        url = decoded.url,
                        headers = playbackHeaders,
                        resolution = if (decoded.parse == 1 || decoded.jx == 1) {
                            PlaybackResolution.REQUIRES_PARSER
                        } else {
                            PlaybackResolution.DIRECT
                        },
                        parserUrl = decoded.parserUrl,
                        format = decoded.format,
                    )
                }
            }
        }
    }

    private suspend fun <T> withOperationDeadline(
        site: LegacySiteDescriptor,
        block: suspend () -> SourceResult<T>,
    ): SourceResult<T> = withTimeoutOrNull(safeTimeout(site)) { block() }
        ?: failure(
            site,
            SourceErrorCode.NETWORK_FAILURE,
            "数据源本次操作整体超时，请稍后重试。",
            retryable = true,
        )

    private suspend fun executeHomeWithPosterBackfill(
        site: LegacySiteDescriptor,
        request: NetworkRequest,
        resolvedExt: String,
    ): SourceResult<SourceHome> {
        val result = executeAndDecode(site, request) { response -> decoder.decodeHome(site, response.body) }
        if (result !is SourceResult.Success) return result
        val featured = backfillMissingPosters(site, result.value.featured, resolvedExt)
        return SourceResult.Success(result.value.copy(featured = featured))
    }

    private suspend fun executePageWithPosterBackfill(
        site: LegacySiteDescriptor,
        request: NetworkRequest?,
        resolvedExt: String,
    ): SourceResult<SourcePage> {
        val result = executeAndDecode(site, request) { response -> decoder.decodePage(site, response.body) }
        if (result !is SourceResult.Success) return result
        return SourceResult.Success(backfillMissingPosters(site, result.value, resolvedExt))
    }

    private suspend fun backfillMissingPosters(
        site: LegacySiteDescriptor,
        page: SourcePage,
        resolvedExt: String,
    ): SourcePage {
        if (site.type == TYPE_FOUR || page.items.none { it.poster.isBlank() }) return page
        val ids = page.items.asSequence()
            .filter { it.poster.isBlank() }
            .map { it.vodId.trim() }
            .filter { validTextArgument(it, MAX_IDENTIFIER_LENGTH) }
            .distinct()
            .take(MAX_POSTER_BACKFILL_ITEMS)
            .toList()
        if (ids.isEmpty()) return page
        val request = callRequest(
            site,
            linkedMapOf(
                "ac" to actionFor(site.type),
                "ids" to ids.joinToString(","),
            ),
            resolvedExt,
        ) ?: return page
        val details = executeAndDecode(site, request) { response -> decoder.decodePage(site, response.body) }
        if (details !is SourceResult.Success) return page
        val postersById = details.value.items
            .filter { it.poster.isNotBlank() }
            .associate { it.vodId to it.poster }
        if (postersById.isEmpty()) return page
        return page.copy(
            items = page.items.map { item ->
                if (item.poster.isBlank()) {
                    postersById[item.vodId]?.let { poster -> item.copy(poster = poster) } ?: item
                } else {
                    item
                }
            },
        )
    }

    private suspend fun <T> withResolvedExt(
        site: LegacySiteDescriptor,
        block: suspend (String) -> SourceResult<T>,
    ): SourceResult<T> {
        if (site.type != TYPE_FOUR || !looksLikeAbsoluteUrl(site.ext)) {
            if (site.ext.toByteArray(Charsets.UTF_8).size > MAX_EXTENSION_BYTES) {
                return invalidRequest(site, "数据源扩展参数过大。")
            }
            return block(site.ext)
        }
        if (!isValidHttpsEndpoint(site.ext)) return invalidUrl(site, "扩展配置地址必须使用有效的 HTTPS。")
        if (isForbiddenLocalEndpoint(site.ext)) {
            return securityFailure(site, "扩展配置地址指向本机或私有网络，已按安全策略拒绝。")
        }
        val request = NetworkRequest(
            url = site.ext,
            method = NetworkMethod.GET,
            timeoutMillis = safeTimeout(site),
            maxResponseBytes = MAX_REMOTE_EXT_BYTES.toLong(),
        )
        return when (val result = transport.execute(request)) {
            is NetworkResult.Failure -> networkFailure(site, result)
            is NetworkResult.Success -> {
                if (result.response.statusCode !in 200..299) return httpFailure(site, result.response.statusCode)
                val content = decodeUtf8OrNull(result.response.body)
                    ?: return failure(
                        site,
                        SourceErrorCode.INVALID_RESPONSE,
                        "远程扩展配置不是有效的 UTF-8 文本。",
                        retryable = false,
                    )
                val trimmed = content.trim()
                if (trimmed.toByteArray(Charsets.UTF_8).size > MAX_EXTENSION_BYTES) {
                    return invalidRequest(site, "远程扩展配置过大。")
                }
                block(trimmed)
            }
        }
    }

    private suspend fun <T> executeAndDecode(
        site: LegacySiteDescriptor,
        request: NetworkRequest?,
        decode: suspend (NetworkResponse) -> T,
    ): SourceResult<T> {
        if (request == null) return invalidRequest(site, "数据源请求参数过长或格式无效。")
        currentCoroutineContext().ensureActive()
        return when (val result = transport.execute(request)) {
            is NetworkResult.Failure -> networkFailure(site, result)
            is NetworkResult.Success -> {
                if (result.response.statusCode !in 200..299) return httpFailure(site, result.response.statusCode)
                try {
                    currentCoroutineContext().ensureActive()
                    val decoded = decode(result.response)
                    currentCoroutineContext().ensureActive()
                    SourceResult.Success(decoded)
                } catch (error: PayloadDecodeException) {
                    failure(
                        site = site,
                        code = error.code,
                        message = error.message ?: "数据源返回的内容无法解析。",
                        retryable = false,
                    )
                }
            }
        }
    }

    private fun directGetRequest(site: LegacySiteDescriptor): NetworkRequest? {
        if (!isValidHttpsEndpoint(site.api)) return null
        return NetworkRequest(
            url = site.api,
            method = NetworkMethod.GET,
            headers = site.requestHeaders,
            timeoutMillis = safeTimeout(site),
            maxResponseBytes = MAX_RESPONSE_BYTES,
        )
    }

    private fun callRequest(
        site: LegacySiteDescriptor,
        originalParameters: LinkedHashMap<String, String>,
        resolvedExt: String,
    ): NetworkRequest? {
        if (!isValidHttpsEndpoint(site.api)) return null
        if (resolvedExt.toByteArray(Charsets.UTF_8).size > MAX_EXTENSION_BYTES) return null
        val parameters = LinkedHashMap(originalParameters)
        if (resolvedExt.isNotEmpty()) parameters["extend"] = resolvedExt
        return if (resolvedExt.length <= POST_EXTENSION_THRESHOLD) {
            val url = appendQuery(site.api, parameters) ?: return null
            NetworkRequest(
                url = url,
                method = NetworkMethod.GET,
                headers = site.requestHeaders,
                timeoutMillis = safeTimeout(site),
                maxResponseBytes = MAX_RESPONSE_BYTES,
            )
        } else {
            NetworkRequest(
                url = site.api,
                method = NetworkMethod.POST,
                headers = site.requestHeaders,
                body = NetworkBody(
                    bytes = encodeForm(parameters).toByteArray(Charsets.UTF_8),
                    contentType = FORM_CONTENT_TYPE,
                ),
                timeoutMillis = safeTimeout(site),
                maxResponseBytes = MAX_RESPONSE_BYTES,
            )
        }
    }

    private fun preflight(
        site: LegacySiteDescriptor,
        requireSearch: Boolean = false,
    ): SourceResult.Failure? = when {
        site.state.userActivation == SourceUserActivation.DISABLED -> failure(
            site,
            SourceErrorCode.USER_DISABLED,
            "该数据源已被用户停用。",
            retryable = false,
        )

        site.state.availability != SourceAvailability.AVAILABLE -> failure(
            site,
            SourceErrorCode.SOURCE_UNAVAILABLE,
            if (site.state.availability == SourceAvailability.TEMPORARILY_UNAVAILABLE) {
                "该数据源暂时不可用，请稍后重试。"
            } else {
                "该数据源加载失败，请检查配置。"
            },
            retryable = site.state.availability == SourceAvailability.TEMPORARILY_UNAVAILABLE,
        )

        site.type !in SUPPORTED_TYPES -> failure(
            site,
            SourceErrorCode.UNSUPPORTED_TYPE,
            "暂不支持 type ${site.type} 数据源。",
            retryable = false,
        )

        requireSearch && site.state.searchCapability == SourceSearchCapability.UNSUPPORTED -> failure(
            site,
            SourceErrorCode.SEARCH_UNSUPPORTED,
            "该数据源本身不支持搜索。",
            retryable = false,
        )

        !isValidHttpsEndpoint(site.api) -> invalidUrl(site)

        isForbiddenLocalEndpoint(site.api) -> securityFailure(
            site,
            "数据源地址指向本机或私有网络，已按安全策略拒绝。",
        )

        else -> null
    }

    private fun networkFailure(
        site: LegacySiteDescriptor,
        result: NetworkResult.Failure,
    ): SourceResult.Failure = when (result.failure.code) {
        NetworkFailureCode.INVALID_URL -> invalidUrl(site)
        NetworkFailureCode.INVALID_REQUEST,
        NetworkFailureCode.INVALID_HEADER,
        -> invalidRequest(site, "数据源请求参数或请求头无效。")

        NetworkFailureCode.TIMEOUT -> failure(
            site,
            SourceErrorCode.NETWORK_FAILURE,
            "数据源请求超时，请稍后重试。",
            retryable = true,
        )

        NetworkFailureCode.TLS_VALIDATION_FAILED -> failure(
            site,
            SourceErrorCode.SECURITY_REJECTED,
            "数据源的 TLS 证书验证失败，已拒绝连接。",
            retryable = false,
        )

        NetworkFailureCode.REDIRECT_REJECTED,
        NetworkFailureCode.TOO_MANY_REDIRECTS,
        -> failure(
            site,
            SourceErrorCode.SECURITY_REJECTED,
            "数据源返回了不安全或过多的重定向，已停止请求。",
            retryable = false,
        )

        NetworkFailureCode.RESPONSE_TOO_LARGE -> failure(
            site,
            SourceErrorCode.INVALID_RESPONSE,
            "数据源返回的内容过大，已停止接收。",
            retryable = false,
        )

        NetworkFailureCode.CONNECTION_FAILED -> failure(
            site,
            SourceErrorCode.NETWORK_FAILURE,
            "无法连接数据源，请检查网络后重试。",
            retryable = true,
        )
    }

    private fun httpFailure(site: LegacySiteDescriptor, statusCode: Int): SourceResult.Failure = failure(
        site = site,
        code = SourceErrorCode.HTTP_ERROR,
        message = "数据源服务器返回 HTTP $statusCode。",
        retryable = statusCode == 408 || statusCode == 429 || statusCode >= 500,
    )

    private fun invalidUrl(
        site: LegacySiteDescriptor,
        message: String = "数据源地址无效，仅支持 HTTPS。",
    ): SourceResult.Failure = failure(
        site,
        SourceErrorCode.INVALID_URL,
        message,
        retryable = false,
    )

    private fun invalidRequest(site: LegacySiteDescriptor, message: String): SourceResult.Failure = failure(
        site,
        SourceErrorCode.INVALID_REQUEST,
        message,
        retryable = false,
    )

    private fun securityFailure(site: LegacySiteDescriptor, message: String): SourceResult.Failure = failure(
        site,
        SourceErrorCode.SECURITY_REJECTED,
        message,
        retryable = false,
    )

    private fun failure(
        site: LegacySiteDescriptor,
        code: SourceErrorCode,
        message: String,
        retryable: Boolean,
    ): SourceResult.Failure = SourceResult.Failure(
        SourceError(
            sourceKey = site.sourceKey,
            code = code,
            userMessage = message,
            retryable = retryable,
        ),
    )

    private fun actionFor(type: Int): String = if (type == TYPE_ZERO) "videolist" else "detail"

    private fun safeTimeout(site: LegacySiteDescriptor): Long =
        site.timeoutMillis.coerceIn(MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS)

    private fun encodeFilters(filters: Map<String, String>): String? {
        if (filters.size > MAX_FILTER_COUNT) return null
        if (filters.any { (key, value) ->
                !validTextArgument(key, MAX_FILTER_PART_LENGTH) ||
                    !validTextArgument(value, MAX_FILTER_PART_LENGTH)
            }
        ) {
            return null
        }
        val encoded = JsonObject(
            filters.toSortedMap().mapValues { (_, value) -> JsonPrimitive(value.trim()) },
        ).toString()
        return encoded.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_FILTER_JSON_BYTES }
    }

    private fun appendQuery(url: String, parameters: Map<String, String>): String? {
        if (parameters.isEmpty()) return url
        val encoded = parameters.entries.joinToString("&") { (key, value) ->
            "${encodeQueryPart(key)}=${encodeQueryPart(value)}"
        }
        val separator = if (URI(url).rawQuery.isNullOrEmpty()) "?" else "&"
        val result = "$url$separator$encoded"
        return result.takeIf { it.length <= MAX_URL_LENGTH }
    }

    private fun encodeForm(parameters: Map<String, String>): String = parameters.entries.joinToString("&") {
        (key, value) -> "${encodeFormPart(key)}=${encodeFormPart(value)}"
    }

    private fun encodeQueryPart(value: String): String = encodeFormPart(value).replace("+", "%20")

    private fun encodeFormPart(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun isValidHttpsEndpoint(value: String): Boolean = runCatching {
        if (value.length > MAX_URL_LENGTH || value.any(Char::isISOControl)) return@runCatching false
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            uri.rawFragment == null
    }.getOrDefault(false)

    private fun looksLikeAbsoluteUrl(value: String): Boolean = ABSOLUTE_URL.matches(value.trim())

    private fun validTextArgument(value: String, maxLength: Int): Boolean =
        value.isNotBlank() && value.length <= maxLength && value.none(Char::isISOControl)

    private fun validPlaybackHeaders(headers: Map<String, String>): Boolean =
        headers.size <= MAX_PLAYER_HEADERS && headers.all { (name, value) ->
            name.isNotEmpty() &&
                name.length <= MAX_HEADER_NAME_LENGTH &&
                name.all { character -> character.code in 0x21..0x7e && character !in HEADER_SEPARATORS } &&
                value.length <= MAX_HEADER_VALUE_LENGTH &&
                value.all { character -> character == '\t' || !character.isISOControl() }
        }

    private fun decodeUtf8OrNull(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .removePrefix("\uFEFF")
    }.getOrNull()

    private fun isDirectMediaAddress(value: String): Boolean {
        val candidate = value.trim()
        val lower = candidate.lowercase()
        if ("url=http" in lower || "v=http" in lower || ".html" in lower) return false
        return runCatching {
            val uri = URI(candidate)
            if (uri.scheme?.lowercase() !in DIRECT_MEDIA_SCHEMES || uri.host.isNullOrBlank()) {
                return@runCatching false
            }
            val path = uri.path.orEmpty()
            DIRECT_MEDIA_PATH.matches(path) || path.contains("/video/tos", ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun isForbiddenLocalEndpoint(value: String): Boolean = runCatching {
        val host = URI(value).host.orEmpty().trim().trim('[', ']').trimEnd('.').lowercase()
        if (host == "localhost" || host.endsWith(".localhost")) return@runCatching true
        parseIpv4(host)?.let(::isForbiddenAddress)?.let { return@runCatching it }
        if (':' in host) {
            val address = InetAddress.getByName(host)
            return@runCatching isForbiddenAddress(address) || host.startsWith("fc") || host.startsWith("fd")
        }
        false
    }.getOrDefault(true)

    private fun parseIpv4(host: String): InetAddress? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = parts.map { part -> part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null }
            .map { it.toByte() }
            .toByteArray()
        return InetAddress.getByAddress(bytes)
    }

    private fun isForbiddenAddress(address: InetAddress): Boolean =
        address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress ||
            address.address.let { bytes ->
                bytes.size == 4 && (bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xc0) == 64
            }

    private companion object {
        private const val TYPE_ZERO: Int = 0
        private const val TYPE_ONE: Int = 1
        private const val TYPE_FOUR: Int = 4
        private const val POST_EXTENSION_THRESHOLD: Int = 1_000
        private const val MAX_SEARCH_LENGTH: Int = 256
        private const val MAX_IDENTIFIER_LENGTH: Int = 512
        private const val MAX_PLAYBACK_ID_LENGTH: Int = 8_192
        private const val MAX_FILTER_COUNT: Int = 64
        private const val MAX_FILTER_PART_LENGTH: Int = 1_024
        private const val MAX_FILTER_JSON_BYTES: Int = 8 * 1_024
        private const val MAX_PLAYER_HEADERS: Int = 64
        private const val MAX_HEADER_NAME_LENGTH: Int = 128
        private const val MAX_HEADER_VALUE_LENGTH: Int = 8_192
        private const val MAX_URL_LENGTH: Int = 16_384
        private const val MAX_EXTENSION_BYTES: Int = 2 * 1024 * 1024
        private const val MAX_REMOTE_EXT_BYTES: Int = 2 * 1024 * 1024
        private const val MAX_POSTER_BACKFILL_ITEMS: Int = 50
        private const val MAX_RESPONSE_BYTES: Long = 4L * 1024L * 1024L
        private const val MIN_TIMEOUT_MILLIS: Long = 1_000L
        private const val MAX_TIMEOUT_MILLIS: Long = 60_000L
        private const val FORM_CONTENT_TYPE: String = "application/x-www-form-urlencoded; charset=utf-8"
        private val SUPPORTED_TYPES: Set<Int> = setOf(TYPE_ZERO, TYPE_ONE, TYPE_FOUR)
        private val ABSOLUTE_URL: Regex = Regex("^[A-Za-z][A-Za-z0-9+.-]*://.*$")
        private val DIRECT_MEDIA_SCHEMES: Set<String> = setOf("http", "https", "rtmp")
        private val DIRECT_MEDIA_PATH: Regex = Regex(
            ".*\\.(?:m3u8|mp4|mkv|flv|mp3|m4a|aac|mpd)",
            RegexOption.IGNORE_CASE,
        )
        private const val HEADER_SEPARATORS: String = "()<>@,;:\\\"/[]?={} \t"
    }
}
