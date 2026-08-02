package com.nexora.player.media3

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.nexora.core.network.NetworkPolicy
import com.nexora.player.api.PlaybackCookiePolicy
import com.nexora.player.api.PlaybackResourceKind
import com.nexora.player.api.PlaybackSessionRequest
import java.net.URI
import java.net.URISyntaxException

public fun interface PlaybackCookieProvider {
    public fun cookieHeader(request: PlaybackSessionRequest): String?
}

public fun interface PlaybackCredentialProvider {
    public fun credentialHeaders(request: PlaybackSessionRequest): Map<String, String>
}

public data class Media3DataSourceRequest(
    val url: String,
    val requestHeaders: Map<String, String>,
    val timeoutMillis: Int,
)

public class Media3PlaybackSecurityPolicy(
    private val networkPolicy: NetworkPolicy = NetworkPolicy(),
    private val cookieProvider: PlaybackCookieProvider = PlaybackCookieProvider { null },
    private val credentialProvider: PlaybackCredentialProvider = PlaybackCredentialProvider { emptyMap() },
) {
    public fun buildDataSourceRequest(request: PlaybackSessionRequest): Media3DataSourceRequest {
        require(request.resource.kind == PlaybackResourceKind.URL) {
            "M4.2a only accepts validated HTTPS playback URLs."
        }
        val uri = parseHttpsUri(request.resource.value)
        val requestHeaders = sanitizeRequestHeaders(request.headers)
        val credentialHeaders = sanitizeCredentialHeaders(credentialProvider.credentialHeaders(request))
        val cookieHeaders = cookieHeader(request)
        return Media3DataSourceRequest(
            url = uri.toASCIIString(),
            requestHeaders = requestHeaders + credentialHeaders + cookieHeaders,
            timeoutMillis = minOf(networkPolicy.maxRequestTimeoutMillis, 30_000L).toInt(),
        )
    }

    private fun parseHttpsUri(value: String): URI {
        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            throw IllegalArgumentException("播放地址无效。")
        }
        require(uri.scheme == "https") { "播放地址必须使用 HTTPS。" }
        require(!uri.host.isNullOrBlank()) { "播放地址缺少主机名。" }
        require(uri.userInfo == null) { "播放地址不能包含用户名或密码。" }
        return uri
    }

    private fun sanitizeRequestHeaders(headers: Map<String, String>): Map<String, String> =
        sanitize(headers, allowedPlaybackHeaders)

    private fun sanitizeCredentialHeaders(headers: Map<String, String>): Map<String, String> =
        sanitize(headers, allowedCredentialHeaders)

    private fun cookieHeader(request: PlaybackSessionRequest): Map<String, String> {
        if (request.cookiePolicy !is PlaybackCookiePolicy.InMemory) return emptyMap()
        val value = cookieProvider.cookieHeader(request)?.trim().orEmpty()
        if (value.isBlank()) return emptyMap()
        require(isHeaderValue(value)) { "Cookie 包含非法字符。" }
        return mapOf("Cookie" to value)
    }

    private fun sanitize(
        headers: Map<String, String>,
        allowedNames: Set<String>,
    ): Map<String, String> = buildMap {
        headers.forEach { (name, value) ->
            val normalized = name.trim()
            val lower = normalized.lowercase()
            require(lower in allowedNames) { "播放器请求头不在白名单内：$normalized" }
            require(isHeaderName(normalized) && isHeaderValue(value)) { "播放器请求头包含非法字符。" }
            put(canonicalHeaderName(lower), value)
        }
    }

    private companion object {
        private val allowedPlaybackHeaders = setOf(
            "accept",
            "accept-language",
            "range",
            "referer",
            "origin",
            "user-agent",
        )

        private val allowedCredentialHeaders = setOf(
            "authorization",
            "x-api-key",
        )

        private fun isHeaderName(value: String): Boolean =
            value.isNotBlank() &&
                value.all { character ->
                    character.code in 0x21..0x7e && character !in "()<>@,;:\\\"/[]?={} \t"
                }

        private fun isHeaderValue(value: String): Boolean =
            value.none { it == '\r' || it == '\n' || it == '\u0000' }

        private fun canonicalHeaderName(lowercase: String): String = when (lowercase) {
            "accept" -> "Accept"
            "accept-language" -> "Accept-Language"
            "authorization" -> "Authorization"
            "cookie" -> "Cookie"
            "origin" -> "Origin"
            "range" -> "Range"
            "referer" -> "Referer"
            "user-agent" -> "User-Agent"
            "x-api-key" -> "X-Api-Key"
            else -> lowercase
        }
    }
}

@UnstableApi
internal class SecureMedia3DataSourceFactory(
    private val dataSourceRequest: Media3DataSourceRequest,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(false)
            .setConnectTimeoutMs(dataSourceRequest.timeoutMillis)
            .setReadTimeoutMs(dataSourceRequest.timeoutMillis)
            .setDefaultRequestProperties(dataSourceRequest.requestHeaders)
            .createDataSource()
}
