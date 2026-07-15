package com.nexora.source.runtime.http

import com.nexora.core.network.NetworkFailure
import com.nexora.core.network.NetworkFailureCode
import com.nexora.core.network.NetworkMethod
import com.nexora.core.network.NetworkRequest
import com.nexora.core.network.NetworkResponse
import com.nexora.core.network.NetworkResult
import com.nexora.core.network.SafeHttpTransport
import com.nexora.source.api.LegacySiteDescriptor
import com.nexora.source.api.LegacySourceKey
import com.nexora.source.api.PlaybackResolution
import com.nexora.source.api.RawJson
import com.nexora.source.api.SourceErrorCode
import com.nexora.source.api.SourceOperationalState
import com.nexora.source.api.SourceResult
import com.nexora.source.api.SourceSearchCapability
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Base64
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class DefaultLegacyHttpSourceGatewayTest {
    @Test
    fun typeZeroHomeUsesBareGetAndDecodesXmlCatalog() = runTest {
        val transport = RecordingTransport { _, _ -> success(fixture("type0.xml")) }
        val site = site(type = 0, ext = "should-not-be-sent-on-home", timeoutMillis = 12_345L)

        val result = assertIs<SourceResult.Success<*>>(
            DefaultLegacyHttpSourceGateway(transport).home(site),
        ).value
        val home = assertIs<com.nexora.source.api.SourceHome>(result)

        assertEquals(listOf("电影", "剧集"), home.categories.map { it.name })
        assertEquals("XML 示例影片", home.featured.items.single().title)
        assertEquals(2, home.featured.page)
        assertEquals(5, home.featured.pageCount)
        assertEquals(site.sourceKey, home.featured.items.single().sourceKey)
        assertEquals(NetworkMethod.GET, transport.requests.single().method)
        assertEquals("https://source.example/api", transport.requests.single().url)
        assertEquals(12_345L, transport.requests.single().timeoutMillis)
    }

    @Test
    fun typeZeroCategoryDetailSearchAndLocalPlaybackMatchLegacyMatrix() = runTest {
        val transport = RecordingTransport { _, _ -> success(fixture("type0.xml")) }
        val site = site(type = 0, ext = "xml-extension")
        val gateway = DefaultLegacyHttpSourceGateway(transport)

        val category = assertIs<SourceResult.Success<*>>(
            gateway.category(site, categoryId = "1", page = 4),
        ).value
        assertEquals("XML 示例影片", assertIs<com.nexora.source.api.SourcePage>(category).items.single().title)
        val categoryParameters = queryParameters(transport.requests[0].url)
        assertEquals("videolist", categoryParameters["ac"])
        assertEquals("1", categoryParameters["t"])
        assertEquals("4", categoryParameters["pg"])
        assertEquals("xml-extension", categoryParameters["extend"])

        val detail = assertIs<SourceResult.Success<*>>(gateway.detail(site, "xml-vod-1")).value
        val mediaDetail = assertIs<com.nexora.source.api.SourceMediaDetail>(detail)
        assertEquals("示例导演", mediaDetail.director)
        assertEquals(2, mediaDetail.lines.single().episodes.size)
        assertEquals(
            mapOf("ac" to "videolist", "ids" to "xml-vod-1", "extend" to "xml-extension"),
            queryParameters(transport.requests[1].url),
        )

        assertIs<SourceResult.Success<*>>(gateway.search(site, "测试 词", page = 2, quick = true))
        val searchParameters = queryParameters(transport.requests[2].url)
        assertEquals("测试 词", searchParameters["wd"])
        assertEquals("true", searchParameters["quick"])
        assertEquals("2", searchParameters["pg"])
        assertEquals("xml-extension", searchParameters["extend"])
        assertFalse(searchParameters.containsKey("ac"))

        val playback = assertIs<SourceResult.Success<*>>(
            gateway.playback(site, "线路A", "https://media.example/direct.m3u8"),
        ).value
        assertEquals(PlaybackResolution.DIRECT, assertIs<com.nexora.source.api.PlaybackRequest>(playback).resolution)

        val parserPage = assertIs<com.nexora.source.api.PlaybackRequest>(
            assertIs<SourceResult.Success<*>>(
                gateway.playback(
                    site,
                    "线路A",
                    "https://jx.example/?url=https://media.example/nested.m3u8",
                ),
            ).value,
        )
        assertEquals(PlaybackResolution.REQUIRES_PARSER, parserPage.resolution)
        assertEquals(3, transport.requests.size, "type 0 播放解析不得额外发起网络请求")
    }

    @Test
    fun typeZeroHomeBackfillsPosterFromBatchDetail() = runTest {
        val withoutPoster = """
            <rss><list><video><id>xml-missing-pic</id><name>待补图影片</name></video></list></rss>
        """.trimIndent().toByteArray()
        val withPoster = """
            <rss><list><video><id>xml-missing-pic</id><name>待补图影片</name>
            <pic>https://image.example/backfilled.jpg</pic></video></list></rss>
        """.trimIndent().toByteArray()
        val responses = ArrayDeque(listOf(success(withoutPoster), success(withPoster)))
        val transport = RecordingTransport { _, _ -> responses.removeFirst() }

        val home = assertIs<com.nexora.source.api.SourceHome>(
            assertIs<SourceResult.Success<*>>(
                DefaultLegacyHttpSourceGateway(transport).home(site(type = 0, ext = "xml-ext")),
            ).value,
        )

        assertEquals("https://image.example/backfilled.jpg", home.featured.items.single().poster)
        assertEquals(
            mapOf("ac" to "videolist", "ids" to "xml-missing-pic", "extend" to "xml-ext"),
            queryParameters(transport.requests[1].url),
        )
    }

    @Test
    fun typeZeroHonorsDeclaredLegacyXmlCharset() = runTest {
        val xml = """<?xml version="1.0" encoding="GB18030"?><rss><list><video><id>gbk-1</id><name>旧版中文源</name></video></list></rss>"""
        val transport = RecordingTransport { _, _ -> success(xml.toByteArray(Charset.forName("GB18030"))) }

        val home = assertIs<com.nexora.source.api.SourceHome>(
            assertIs<SourceResult.Success<*>>(
                DefaultLegacyHttpSourceGateway(transport).home(site(type = 0)),
            ).value,
        )

        assertEquals("旧版中文源", home.featured.items.single().title)
    }

    @Test
    fun typeOneJsonFiltersDetailsAndPaginationAreDecoded() = runTest {
        val transport = RecordingTransport { _, _ -> success(fixture("type1.json")) }
        val site = site(type = 1, ext = "type-one-extension")
        val gateway = DefaultLegacyHttpSourceGateway(transport)

        val home = assertIs<com.nexora.source.api.SourceHome>(
            assertIs<SourceResult.Success<*>>(gateway.home(site)).value,
        )
        assertEquals("年份", home.filtersByCategory.getValue("movie").single().name)
        assertEquals("JSON 示例影片", home.featured.items.single().title)
        assertEquals("https://source.example/api", transport.requests[0].url)

        assertIs<SourceResult.Success<*>>(
            gateway.category(site, "movie", 3, linkedMapOf("year" to "2026", "area" to "中国")),
        )
        val categoryParameters = queryParameters(transport.requests[1].url)
        assertEquals("detail", categoryParameters["ac"])
        assertEquals("movie", categoryParameters["t"])
        assertEquals("3", categoryParameters["pg"])
        assertEquals("{\"area\":\"中国\",\"year\":\"2026\"}", categoryParameters["f"])
        assertEquals("type-one-extension", categoryParameters["extend"])

        val detail = assertIs<com.nexora.source.api.SourceMediaDetail>(
            assertIs<SourceResult.Success<*>>(gateway.detail(site, "json-vod-1")).value,
        )
        assertEquals(2, detail.lines.size)
        assertEquals("https://media.example/movie.mp4", detail.lines[1].episodes.single().playbackId)
        assertEquals(
            mapOf("ac" to "detail", "ids" to "json-vod-1", "extend" to "type-one-extension"),
            queryParameters(transport.requests[2].url),
        )

        val parserPlayback = assertIs<com.nexora.source.api.PlaybackRequest>(
            assertIs<SourceResult.Success<*>>(
                gateway.playback(site.copy(playUrl = "https://parser.example/?url="), "线路一", "opaque-id"),
            ).value,
        )
        assertEquals(PlaybackResolution.REQUIRES_PARSER, parserPlayback.resolution)
        assertEquals("https://parser.example/?url=", parserPlayback.parserUrl)
        assertEquals(3, transport.requests.size, "type 1 播放解析不得额外发起网络请求")
    }

    @Test
    fun typeOneSearchBackfillsPosterWithoutDiscardingPrimaryResult() = runTest {
        val primary = """{"list":[{"vod_id":"json-missing-pic","vod_name":"待补图影片"}]}"""
        val detail = """
            {"list":[{"vod_id":"json-missing-pic","vod_name":"待补图影片",
            "vod_pic":"https://image.example/json-backfilled.jpg"}]}
        """.trimIndent()
        val responses = ArrayDeque(listOf(success(primary.toByteArray()), success(detail.toByteArray())))
        val transport = RecordingTransport { _, _ -> responses.removeFirst() }

        val page = assertIs<com.nexora.source.api.SourcePage>(
            assertIs<SourceResult.Success<*>>(
                DefaultLegacyHttpSourceGateway(transport).search(site(type = 1, ext = "json-ext"), "关键词"),
            ).value,
        )

        assertEquals("https://image.example/json-backfilled.jpg", page.items.single().poster)
        assertEquals(
            mapOf("ac" to "detail", "ids" to "json-missing-pic", "extend" to "json-ext"),
            queryParameters(transport.requests[1].url),
        )
    }

    @Test
    fun typeFourUsesBase64FilterExtAndDecodesRemotePlayback() = runTest {
        val responses = ArrayDeque(
            listOf(
                success(fixture("type1.json")),
                success(fixture("type1.json")),
                success(fixture("type1.json")),
                success(fixture("type1.json")),
                success(fixture("type4-player.json")),
            ),
        )
        val transport = RecordingTransport { _, _ -> responses.removeFirst() }
        val base64Extension = "eyJjbGllbnQiOiJuZXhvcmEifQ=="
        val site = site(type = 4, ext = base64Extension)
        val gateway = DefaultLegacyHttpSourceGateway(transport)

        assertIs<SourceResult.Success<*>>(gateway.home(site))
        assertEquals("true", queryParameters(transport.requests[0].url)["filter"])
        assertEquals(base64Extension, queryParameters(transport.requests[0].url)["extend"])

        assertIs<SourceResult.Success<*>>(
            gateway.category(site, "movie", 5, linkedMapOf("year" to "2026")),
        )
        val categoryParameters = queryParameters(transport.requests[1].url)
        val decodedFilter = String(Base64.getUrlDecoder().decode(categoryParameters.getValue("ext")), Charsets.UTF_8)
        assertEquals("{\"year\":\"2026\"}", decodedFilter)
        assertEquals("detail", categoryParameters["ac"])
        assertEquals(base64Extension, categoryParameters["extend"])

        val detail = assertIs<com.nexora.source.api.SourceMediaDetail>(
            assertIs<SourceResult.Success<*>>(gateway.detail(site, "json-vod-1")).value,
        )
        assertEquals("JSON 示例影片", detail.media.title)
        assertEquals("json-vod-1", queryParameters(transport.requests[2].url)["ids"])

        val search = assertIs<com.nexora.source.api.SourcePage>(
            assertIs<SourceResult.Success<*>>(gateway.search(site, "测试", page = 2)).value,
        )
        assertEquals("JSON 示例影片", search.items.single().title)
        assertEquals("测试", queryParameters(transport.requests[3].url)["wd"])
        assertEquals("2", queryParameters(transport.requests[3].url)["pg"])

        val playback = assertIs<com.nexora.source.api.PlaybackRequest>(
            assertIs<SourceResult.Success<*>>(
                gateway.playback(site, "线路请求", "episode-id"),
            ).value,
        )
        assertEquals("https://media.example/type4.m3u8", playback.url)
        assertEquals("远端线路", playback.flag)
        assertEquals(PlaybackResolution.REQUIRES_PARSER, playback.resolution)
        assertEquals("https://source.example/", playback.headers["Referer"])
        assertEquals("episode-id", queryParameters(transport.requests[4].url)["play"])
    }

    @Test
    fun longExtensionSwitchesLegacyCallToFormPost() = runTest {
        val transport = RecordingTransport { _, _ -> success(fixture("type1.json")) }
        val extension = "x".repeat(1_001)
        val site = site(type = 1, ext = extension)

        assertIs<SourceResult.Success<*>>(
            DefaultLegacyHttpSourceGateway(transport).category(site, "movie", 1),
        )

        val request = transport.requests.single()
        assertEquals(NetworkMethod.POST, request.method)
        assertEquals("https://source.example/api", request.url)
        assertEquals(
            "application/x-www-form-urlencoded; charset=utf-8",
            request.body?.contentType,
        )
        val parameters = formParameters(String(requireNotNull(request.body).bytes, Charsets.UTF_8))
        assertEquals(extension, parameters["extend"])
        assertEquals("detail", parameters["ac"])
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun typeFourHttpsRemoteExtensionIsFetchedWithinSameTimeoutBoundary() = runTest {
        val transport = RecordingTransport { _, index ->
            delay(6_000L)
            if (index == 0) success("{\"remote\":true}".toByteArray()) else success(fixture("type1.json"))
        }
        val site = site(type = 4, ext = "https://extension.example/source.json", timeoutMillis = 9_000L)

        val failure = assertIs<SourceResult.Failure>(DefaultLegacyHttpSourceGateway(transport).home(site))

        assertEquals("https://extension.example/source.json", transport.requests[0].url)
        assertEquals(9_000L, transport.requests[0].timeoutMillis)
        assertEquals("{\"remote\":true}", queryParameters(transport.requests[1].url)["extend"])
        assertEquals(SourceErrorCode.NETWORK_FAILURE, failure.error.code)
        assertTrue(failure.error.userMessage.contains("整体超时"))
        assertEquals(9_000L, testScheduler.currentTime)
    }

    @Test
    fun invalidAndCleartextUrlsAreRejectedBeforeTransport() = runTest {
        val transport = RecordingTransport { _, _ -> error("transport must not be called") }
        val gateway = DefaultLegacyHttpSourceGateway(transport)

        val cleartext = assertIs<SourceResult.Failure>(gateway.home(site(type = 1, api = "http://source.example/api")))
        val userInfo = assertIs<SourceResult.Failure>(
            gateway.home(site(type = 1, api = "https://token@source.example/api")),
        )
        val cleartextExt = assertIs<SourceResult.Failure>(
            gateway.home(site(type = 4, ext = "http://extension.example/config")),
        )
        val localhost = assertIs<SourceResult.Failure>(
            gateway.home(site(type = 1, api = "https://localhost/api")),
        )
        val privateAddress = assertIs<SourceResult.Failure>(
            gateway.home(site(type = 1, api = "https://192.168.1.8/api")),
        )

        assertEquals(SourceErrorCode.INVALID_URL, cleartext.error.code)
        assertEquals(SourceErrorCode.INVALID_URL, userInfo.error.code)
        assertEquals(SourceErrorCode.INVALID_URL, cleartextExt.error.code)
        assertEquals(SourceErrorCode.SECURITY_REJECTED, localhost.error.code)
        assertEquals(SourceErrorCode.SECURITY_REJECTED, privateAddress.error.code)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun maliciousXmlDoctypeAndXxeAreRejected() = runTest {
        val transport = RecordingTransport { _, _ -> success(fixture("malicious-doctype.xml")) }

        val failure = assertIs<SourceResult.Failure>(
            DefaultLegacyHttpSourceGateway(transport).home(site(type = 0)),
        )

        assertEquals(SourceErrorCode.SECURITY_REJECTED, failure.error.code)
        assertTrue(failure.error.userMessage.contains("不安全 XML"))
    }

    @Test
    fun deeplyNestedJsonIsRejectedBeforeDeserializer() = runTest {
        val nested = "{\"next\":".repeat(65) + "0" + "}".repeat(65)
        val transport = RecordingTransport { _, _ -> success(nested.toByteArray()) }

        val failure = assertIs<SourceResult.Failure>(
            DefaultLegacyHttpSourceGateway(transport).home(site(type = 1)),
        )

        assertEquals(SourceErrorCode.INVALID_RESPONSE, failure.error.code)
        assertTrue(failure.error.userMessage.contains("嵌套层级过深"))
    }

    @Test
    fun cancellationFromTransportPropagatesToCaller() = runTest {
        val transport = SafeHttpTransport { throw CancellationException("cancel test") }

        assertFailsWith<CancellationException> {
            DefaultLegacyHttpSourceGateway(transport).search(site(type = 1), "关键词")
        }
    }

    @Test
    fun networkFailureIsAChineseSingleSourceError() = runTest {
        val transport = RecordingTransport { _, _ ->
            NetworkResult.Failure(
                NetworkFailure(
                    code = NetworkFailureCode.TIMEOUT,
                    userMessage = "raw transport message",
                    redactedUrl = "https://source.example/…",
                ),
            )
        }

        val failure = assertIs<SourceResult.Failure>(
            DefaultLegacyHttpSourceGateway(transport).search(site(type = 1), "关键词"),
        )

        assertEquals(SourceErrorCode.NETWORK_FAILURE, failure.error.code)
        assertTrue(failure.error.retryable)
        assertEquals("数据源请求超时，请稍后重试。", failure.error.userMessage)
    }

    @Test
    fun unsafePlayerHeadersAreRejected() = runTest {
        val body = """{"url":"https://media.example/video.m3u8","header":{"Cookie":"ok\r\nInjected: yes"}}"""
        val transport = RecordingTransport { _, _ -> success(body.toByteArray()) }

        val failure = assertIs<SourceResult.Failure>(
            DefaultLegacyHttpSourceGateway(transport).playback(site(type = 4), "线路", "id"),
        )

        assertEquals(SourceErrorCode.SECURITY_REJECTED, failure.error.code)
    }

    @Test
    fun legacyPlayerUrlArrayAndStringifiedHeadersAreSupported() = runTest {
        val body = """
            {
              "url":["高清","https://media.example/selected.m3u8"],
              "header":"{\"Referer\":\"https://source.example/\"}",
              "parse":0
            }
        """.trimIndent()
        val transport = RecordingTransport { _, _ -> success(body.toByteArray()) }

        val playback = assertIs<com.nexora.source.api.PlaybackRequest>(
            assertIs<SourceResult.Success<*>>(
                DefaultLegacyHttpSourceGateway(transport).playback(site(type = 4), "线路", "id"),
            ).value,
        )

        assertEquals("https://media.example/selected.m3u8", playback.url)
        assertEquals("https://source.example/", playback.headers["Referer"])
        assertEquals(PlaybackResolution.DIRECT, playback.resolution)
    }

    private fun site(
        type: Int,
        api: String = "https://source.example/api",
        ext: String = "",
        timeoutMillis: Long = 15_000L,
    ): LegacySiteDescriptor = LegacySiteDescriptor(
        sourceKey = LegacySourceKey("config:type-$type"),
        legacyKey = "type-$type",
        name = "测试源 $type",
        type = type,
        api = api,
        ext = ext,
        jar = "",
        playUrl = "",
        timeoutMillis = timeoutMillis,
        quickSearch = true,
        categories = emptyList(),
        requestHeaders = mapOf("User-Agent" to "Nexora-Test"),
        state = SourceOperationalState(searchCapability = SourceSearchCapability.SUPPORTED),
        raw = RawJson("{}"),
    )

    private fun fixture(name: String): ByteArray = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("http-source/$name"),
    ) { "missing fixture $name" }.use { it.readBytes() }

    private fun success(body: ByteArray, statusCode: Int = 200): NetworkResult.Success = NetworkResult.Success(
        NetworkResponse(
            statusCode = statusCode,
            headers = mapOf("Content-Type" to listOf("application/octet-stream")),
            body = body,
            redactedUrl = "https://source.example/…",
        ),
    )

    private fun queryParameters(url: String): Map<String, String> =
        formParameters(URI(url).rawQuery.orEmpty())

    private fun formParameters(value: String): Map<String, String> = value
        .split('&')
        .filter(String::isNotEmpty)
        .associate { entry ->
            val parts = entry.split('=', limit = 2)
            decode(parts[0]) to decode(parts.getOrElse(1) { "" })
        }

    private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())

    private class RecordingTransport(
        private val responder: suspend (NetworkRequest, Int) -> NetworkResult,
    ) : SafeHttpTransport {
        val requests: MutableList<NetworkRequest> = mutableListOf()

        override suspend fun execute(request: NetworkRequest): NetworkResult {
            requests += request
            return responder(request, requests.lastIndex)
        }
    }
}
