package com.nexora.source.runtime.search

import com.nexora.source.api.LegacyHttpSourceGateway
import com.nexora.source.api.LegacySiteDescriptor
import com.nexora.source.api.LegacySourceKey
import com.nexora.source.api.PerSourceSearchState
import com.nexora.source.api.PlaybackRequest
import com.nexora.source.api.RawJson
import com.nexora.source.api.SearchSessionId
import com.nexora.source.api.SourceAvailability
import com.nexora.source.api.SourceError
import com.nexora.source.api.SourceErrorCode
import com.nexora.source.api.SourceHome
import com.nexora.source.api.SourceMediaDetail
import com.nexora.source.api.SourceMediaSummary
import com.nexora.source.api.SourceOperationalState
import com.nexora.source.api.SourcePage
import com.nexora.source.api.SourceResult
import com.nexora.source.api.SourceSearchCapability
import com.nexora.source.api.SourceUserActivation
import com.nexora.source.api.AllSourcesSearchRequest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class DefaultAllSourcesSearcherTest {
    @Test
    fun keepsSourceOrderAndSameTitleResultsWhileForcingTrustedOrigin() = runTest {
        val first = site("first", "第一源")
        val second = site("second", "第二源")
        val invoked = mutableListOf<LegacySourceKey>()
        val gateway = SearchOnlyGateway { source, _, _, _ ->
            invoked += source.sourceKey
            if (source.sourceKey == first.sourceKey) delay(100) else delay(10)
            SourceResult.Success(
                SourcePage(
                    items = listOf(
                        media(
                            sourceKey = LegacySourceKey("forged"),
                            vodId = source.legacyKey,
                            sourceName = "伪造来源",
                        ),
                    ),
                ),
            )
        }
        val disabled = site("disabled", "停用源", activation = SourceUserActivation.DISABLED)
        val unsupportedSearch = site(
            "unsupported",
            "不支持搜索",
            searchCapability = SourceSearchCapability.UNSUPPORTED,
        )
        val plugin = site("plugin", "插件源", type = 3)

        val snapshots = DefaultAllSourcesSearcher(gateway).search(
            request = request("同名影片"),
            sources = listOf(first, second, disabled, unsupportedSearch, plugin),
        ).toList()

        assertEquals(3, snapshots.size)
        assertTrue(snapshots.first().sources.all { progress -> progress.state == PerSourceSearchState.Loading })
        val completed = snapshots.last()
        assertTrue(completed.isComplete)
        assertEquals(listOf(first.sourceKey, second.sourceKey), completed.sources.map { it.sourceKey })
        assertEquals(listOf(first.sourceKey, second.sourceKey), completed.results.map { it.sourceKey })
        assertEquals(listOf("第一源", "第二源"), completed.results.map { it.sourceName })
        assertEquals(listOf("同名影片", "同名影片"), completed.results.map { it.title })
        assertEquals(setOf(first.sourceKey, second.sourceKey), invoked.toSet())
    }

    @Test
    fun timeoutFailureAndThrownExceptionAreIsolatedFromSuccessfulSources() = runTest {
        val timeout = site("timeout", "超时源", timeoutMillis = 50)
        val failed = site("failed", "失败源")
        val thrown = site("thrown", "异常源")
        val success = site("success", "成功源")
        val gateway = SearchOnlyGateway { source, _, _, _ ->
            when (source.sourceKey) {
                timeout.sourceKey -> {
                    delay(1_000)
                    SourceResult.Success(SourcePage(emptyList()))
                }

                failed.sourceKey -> SourceResult.Failure(
                    SourceError(
                        sourceKey = LegacySourceKey("forged"),
                        code = SourceErrorCode.HTTP_ERROR,
                        userMessage = "服务器暂时不可用。",
                        retryable = true,
                    ),
                )

                thrown.sourceKey -> error("secret-token-must-not-surface")
                else -> SourceResult.Success(SourcePage(listOf(media(success.sourceKey, "ok", "成功源"))))
            }
        }

        val completed = DefaultAllSourcesSearcher(gateway).search(
            request("测试"),
            listOf(timeout, failed, thrown, success),
        ).toList().last()

        assertTrue(completed.isComplete)
        assertEquals(listOf("ok"), completed.results.map { it.vodId })
        val states = completed.sources.associate { it.sourceKey to it.state }
        assertIs<PerSourceSearchState.Failure>(states.getValue(timeout.sourceKey)).also { state ->
            assertTrue(state.error.userMessage.contains("超时"))
        }
        assertIs<PerSourceSearchState.Failure>(states.getValue(failed.sourceKey)).also { state ->
            assertEquals(failed.sourceKey, state.error.sourceKey)
        }
        assertIs<PerSourceSearchState.Failure>(states.getValue(thrown.sourceKey)).also { state ->
            assertTrue(!state.error.userMessage.contains("secret-token"))
        }
        assertIs<PerSourceSearchState.Success>(states.getValue(success.sourceKey))
    }

    @Test
    fun cancellingCollectorCancelsEveryActiveSourceRequest() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val gateway = SearchOnlyGateway { _, _, _, _ ->
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val searcher = DefaultAllSourcesSearcher(gateway)

        val collection = backgroundScope.launch {
            searcher.search(request("取消"), listOf(site("one", "One"))).collect()
        }
        started.await()
        collection.cancelAndJoin()

        assertTrue(cancelled.isCompleted)
    }

    @Test
    fun concurrentRequestsNeverExceedConfiguredLimit() = runTest {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val gateway = SearchOnlyGateway { source, _, _, _ ->
            val now = active.incrementAndGet()
            maximum.updateAndGet { previous -> maxOf(previous, now) }
            try {
                delay(100)
                SourceResult.Success(SourcePage(listOf(media(source.sourceKey, source.legacyKey, source.name))))
            } finally {
                active.decrementAndGet()
            }
        }
        val sources = (1..12).map { index -> site("source-$index", "源 $index") }

        val completed = DefaultAllSourcesSearcher(gateway, maxParallelism = 6).search(
            request("并发"),
            sources,
        ).toList().last()

        assertEquals(6, maximum.get())
        assertEquals(12, completed.results.size)
        assertTrue(completed.isComplete)
    }

    @Test
    fun publicRuntimeEntryPointBoundsKeywordBeforeCallingAGateway() = runTest {
        var receivedKeyword = ""
        val source = site("bounded-query", "查询边界源")
        val gateway = SearchOnlyGateway { _, keyword, _, _ ->
            receivedKeyword = keyword
            SourceResult.Success(SourcePage(emptyList()))
        }

        val completed = DefaultAllSourcesSearcher(gateway).search(
            request("搜".repeat(300)),
            listOf(source),
        ).toList().last()

        assertEquals(256, receivedKeyword.length)
        assertEquals(receivedKeyword, completed.keyword)
    }

    @Test
    fun largeSourceSetUsesTheBoundedWorkerPool() = runTest {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val gateway = SearchOnlyGateway { source, _, _, _ ->
            val now = active.incrementAndGet()
            maximum.updateAndGet { previous -> maxOf(previous, now) }
            try {
                delay(1)
                SourceResult.Success(SourcePage(listOf(media(source.sourceKey, source.legacyKey, source.name))))
            } finally {
                active.decrementAndGet()
            }
        }
        val sources = (1..600).map { index -> site("bulk-$index", "批量源 $index") }

        val completed = DefaultAllSourcesSearcher(gateway, maxParallelism = 6).search(
            request("批量"),
            sources,
        ).toList().last()

        assertEquals(6, maximum.get())
        assertEquals(500, completed.results.size)
        assertEquals(500, completed.sources.size)
        assertTrue(completed.sourcesTruncated)
        assertTrue(completed.isComplete)
    }

    @Test
    fun perSourceAndAggregateResultLimitsAreReported() = runTest {
        val gateway = SearchOnlyGateway { source, _, _, _ ->
            SourceResult.Success(
                SourcePage(
                    (1..1_000).map { index -> media(source.sourceKey, "${source.legacyKey}-$index", source.name) },
                ),
            )
        }
        val sources = (1..50).map { index -> site("result-$index", "结果源 $index") }

        val completed = DefaultAllSourcesSearcher(gateway).search(request("上限"), sources).toList().last()

        assertEquals(2_000, completed.results.size)
        assertTrue(completed.resultsTruncated)
        completed.sources.forEach { progress ->
            val success = assertIs<PerSourceSearchState.Success>(progress.state)
            assertEquals(50, success.itemCount)
            assertTrue(success.truncated)
        }
    }

    @Test
    fun fatalJvmErrorsAreNotConvertedIntoOrdinarySourceFailures() = runTest {
        val gateway = SearchOnlyGateway { _, _, _, _ ->
            throw LinkageError("synthetic fatal error")
        }

        assertFailsWith<LinkageError> {
            DefaultAllSourcesSearcher(gateway).search(
                request("fatal"),
                listOf(site("fatal", "Fatal")),
            ).toList()
        }
    }

    private fun request(keyword: String): AllSourcesSearchRequest = AllSourcesSearchRequest(
        sessionId = SearchSessionId(7),
        keyword = keyword,
    )

    private fun site(
        key: String,
        name: String,
        type: Int = 1,
        activation: SourceUserActivation = SourceUserActivation.ENABLED,
        searchCapability: SourceSearchCapability = SourceSearchCapability.SUPPORTED,
        timeoutMillis: Long = 5_000,
    ): LegacySiteDescriptor = LegacySiteDescriptor(
        sourceKey = LegacySourceKey("config:$key"),
        legacyKey = key,
        name = name,
        type = type,
        api = "https://$key.invalid/api",
        ext = "",
        jar = "",
        playUrl = "",
        timeoutMillis = timeoutMillis,
        quickSearch = false,
        categories = emptyList(),
        requestHeaders = emptyMap(),
        state = SourceOperationalState(
            searchCapability = searchCapability,
            userActivation = activation,
            availability = SourceAvailability.AVAILABLE,
        ),
        raw = RawJson("{}"),
    )

    private fun media(
        sourceKey: LegacySourceKey,
        vodId: String,
        sourceName: String,
    ): SourceMediaSummary = SourceMediaSummary(
        sourceKey = sourceKey,
        vodId = vodId,
        title = "同名影片",
        year = "2026",
        type = "电影",
        region = "测试地区",
        poster = "https://poster.invalid/$vodId.jpg",
        sourceName = sourceName,
    )
}

private class SearchOnlyGateway(
    private val block: suspend (
        LegacySiteDescriptor,
        String,
        Int,
        Boolean,
    ) -> SourceResult<SourcePage>,
) : LegacyHttpSourceGateway {
    override suspend fun search(
        site: LegacySiteDescriptor,
        keyword: String,
        page: Int,
        quick: Boolean,
    ): SourceResult<SourcePage> = block(site, keyword, page, quick)

    override suspend fun home(site: LegacySiteDescriptor): SourceResult<SourceHome> =
        error("home must not be called")

    override suspend fun category(
        site: LegacySiteDescriptor,
        categoryId: String,
        page: Int,
        filters: Map<String, String>,
    ): SourceResult<SourcePage> = error("category must not be called")

    override suspend fun detail(
        site: LegacySiteDescriptor,
        vodId: String,
    ): SourceResult<SourceMediaDetail> = error("detail must not be called")

    override suspend fun playback(
        site: LegacySiteDescriptor,
        flag: String,
        playbackId: String,
    ): SourceResult<PlaybackRequest> = error("playback must not be called")
}
