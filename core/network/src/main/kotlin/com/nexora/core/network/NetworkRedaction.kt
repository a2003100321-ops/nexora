package com.nexora.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

public object NetworkRedaction {
    private const val HIDDEN_VALUE = "<已隐藏>"
    private const val INVALID_URL = "<无效地址>"

    public fun redactUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return INVALID_URL
        val host = if (':' in parsed.host) "[${parsed.host}]" else parsed.host
        val defaultPort = when (parsed.scheme) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
        val port = if (parsed.port == defaultPort) "" else ":${parsed.port}"
        return "${parsed.scheme}://$host$port/<已隐藏路径>"
    }

    public fun redactHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { HIDDEN_VALUE }

    public fun headerNames(headers: Map<String, *>): List<String> =
        headers.keys.sortedWith(String.CASE_INSENSITIVE_ORDER)

}
