package com.nexora.source.api

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class HttpSourceContractsTest {
    @Test
    fun structuredVodIdentityCannotCollideWhenKeysContainColons() {
        val first = SourceVodIdentity(
            sourceKey = LegacySourceKey("a:b"),
            vodId = "c",
        )
        val second = SourceVodIdentity(
            sourceKey = LegacySourceKey("a"),
            vodId = "b:c",
        )

        assertNotEquals(first, second)
    }

    @Test
    fun playbackRequestStringHidesUrlsAndHeaderValues() {
        val request = PlaybackRequest(
            sourceKey = LegacySourceKey("config:site"),
            flag = "线路一",
            url = "https://media.example/video.m3u8?token=top-secret",
            headers = mapOf(
                "Authorization" to "Bearer top-secret",
                "Cookie" to "session=top-secret",
            ),
            resolution = PlaybackResolution.REQUIRES_PARSER,
            parserUrl = "https://parser.example/?token=top-secret",
        )

        val rendered = request.toString()

        assertContains(rendered, "headerNames=[Authorization, Cookie]")
        assertContains(rendered, "url=<已隐藏>")
        assertFalse(rendered.contains("top-secret"))
        assertFalse(rendered.contains("media.example"))
        assertFalse(rendered.contains("parser.example"))
    }
}
