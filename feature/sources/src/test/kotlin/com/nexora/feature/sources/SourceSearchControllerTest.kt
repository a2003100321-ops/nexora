package com.nexora.feature.sources

import com.nexora.source.api.AllSourcesSearchRequest
import com.nexora.source.api.AllSourcesSearchSnapshot
import com.nexora.source.api.AllSourcesSearcher
import com.nexora.source.api.ConfigImportKind
import com.nexora.source.api.LegacyConfigFields
import com.nexora.source.api.LegacyConfigId
import com.nexora.source.api.LegacyConfigImportResult
import com.nexora.source.api.LegacyConfigSnapshot
import com.nexora.source.api.LegacyHttpSourceGateway
import com.nexora.source.api.LegacySiteDescriptor
import com.nexora.source.api.LegacySourceKey
import com.nexora.source.api.LegacySourceRepository
import com.nexora.source.api.LegacySourceState
import com.nexora.source.api.PlaybackRequest
import com.nexora.source.api.RawJson
import com.nexora.source.api.SourceAvailability
import com.nexora.source.api.SourceEpisode
import com.nexora.source.api.SourceHome
import com.nexora.source.api.SourceMediaDetail
import com.nexora.source.api.SourceMediaSummary
import com.nexora.source.api.SourceOperationalState
import com.nexora.source.api.SourcePage
import com.nexora.source.api.SourcePlaybackLine
import com.nexora.source.api.SourceResult
import com.nexora.source.api.SourceSearchCapability
import com.nexora.source.api.SourceUserActivation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class SourceSearchControllerTest {
    @Test
    fun waitsForDebounceAndOnlyRunsLatestRapidQuery() = runTest {
        val queries = mutableListOf<String>()
        val searcher = AllSourcesSearcher { request, _ ->
            flow {
                queries += request.keyword
                emit(completedSnapshot(request))
            }
        }
        val controller = controller(searcher = searcher)
        runCurrent()

        controller.updateQuery("旧")
        advanceTimeBy(200)
        controller.updateQuery("较新")
        advanceTimeBy(349)
        runCurrent()
        assertTrue(queries.isEmpty())

        controller.updateQuery("最终")
        advanceTimeBy(349)
        runCurrent()
        assertTrue(queries.isEmpty())
        advanceTimeBy(1)
        runCurrent()

        assertEquals(listOf("最终"), queries)
        assertEquals(SearchRunPhase.COMPLETED, controller.state.value.phase)
    }

    @Test
    fun queryIsBoundedBeforeItReachesAnySource() = runTest {
        val queries = mutableListOf<String>()
        val searcher = AllSourcesSearcher { request, _ ->
            flow {
                queries += request.keyword
                emit(completedSnapshot(request))
            }
        }
        val controller = controller(searcher = searcher)
        runCurrent()

        controller.updateQuery("片".repeat(300))
        advanceTimeBy(350)
        runCurrent()

        assertEquals(256, controller.state.value.query.length)
        assertEquals(256, queries.single().length)
    }

    @Test
    fun sourceSnapshotIsBoundedBeforeItReachesTheSearchRuntime() = runTest {
        var receivedSourceCount = 0
        val searcher = AllSourcesSearcher { request, sources ->
            flow {
                receivedSourceCount = sources.size
                emit(
                    completedSnapshot(request).copy(
                        sourcesTruncated = sources.size > 500,
                    ),
                )
            }
        }
        val sources = (1..600).map { index -> site("bounded-$index", "测试源 $index") }
        val controller = controller(repository = FakeRepository(sources), searcher = searcher)
        runCurrent()

        controller.updateQuery("边界")
        advanceTimeBy(350)
        runCurrent()

        assertEquals(501, receivedSourceCount)
        assertTrue(controller.state.value.sourcesTruncated)
    }

    @Test
    fun cancelStopsActiveSearchAndPendingDebounceDoesNotRestartIt() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var calls = 0
        val searcher = AllSourcesSearcher { request, _ ->
            flow {
                calls += 1
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }
        val controller = controller(searcher = searcher)
        runCurrent()

        controller.updateQuery("取消测试")
        advanceTimeBy(350)
        runCurrent()
        started.await()
        controller.cancelSearch()
        runCurrent()
        assertTrue(cancelled.isCompleted)
        assertEquals(SearchRunPhase.CANCELLED, controller.state.value.phase)

        controller.updateQuery("等待中的新查询")
        advanceTimeBy(100)
        controller.cancelSearch()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun mismatchedSessionSnapshotIsIgnoredEvenIfAProviderEmitsIt() = runTest {
        val source = site("one", "测试源")
        val searcher = AllSourcesSearcher { request, _ ->
            flow {
                emit(
                    completedSnapshot(
                        request.copy(sessionId = com.nexora.source.api.SearchSessionId(-1)),
                        results = listOf(media(source, "stale", "迟到结果")),
                    ),
                )
                delay(10)
                emit(completedSnapshot(request, listOf(media(source, "fresh", "当前结果"))))
            }
        }
        val controller = controller(repository = FakeRepository(listOf(source)), searcher = searcher)
        runCurrent()

        controller.updateQuery("会话校验")
        advanceTimeBy(350)
        runCurrent()
        assertTrue(controller.state.value.results.isEmpty())
        advanceTimeBy(10)
        runCurrent()

        assertEquals(listOf("当前结果"), controller.state.value.results.map { it.title })
    }

    @Test
    fun oldSearchCannotOverwriteAChangedQuery() = runTest {
        val source = site("one", "测试源")
        val searcher = AllSourcesSearcher { request, _ ->
            flow {
                if (request.keyword == "旧查询") delay(1_000) else delay(10)
                emit(
                    completedSnapshot(
                        request,
                        results = listOf(media(source, request.keyword, request.keyword)),
                    ),
                )
            }
        }
        val controller = controller(repository = FakeRepository(listOf(source)), searcher = searcher)
        runCurrent()

        controller.updateQuery("旧查询")
        advanceTimeBy(350)
        runCurrent()
        controller.updateQuery("新查询")
        advanceTimeBy(360)
        runCurrent()
        assertEquals(listOf("新查询"), controller.state.value.results.map { it.title })

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf("新查询"), controller.state.value.results.map { it.title })
    }

    @Test
    fun fatalJvmErrorIsNotConvertedIntoAnOrdinaryUiFailure() = runTest {
        val caught = CompletableDeferred<Throwable>()
        val handler = CoroutineExceptionHandler { _, failure -> caught.complete(failure) }
        val controllerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob() + handler)
        val searcher = AllSourcesSearcher { _, _ -> flow { throw LinkageError("synthetic fatal") } }
        try {
            val controller = SourceSearchController(
                repository = FakeRepository(emptyList()),
                searcher = searcher,
                gateway = DetailOnlyGateway { _, _ -> error("detail must not be called") },
                scope = controllerScope,
                debounceMillis = 350,
            )
            runCurrent()

            controller.updateQuery("致命错误")
            advanceTimeBy(350)
            runCurrent()

            assertIs<LinkageError>(caught.await())
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun lateDetailCannotOverwriteTheMostRecentlySelectedResult() = runTest {
        val source = site("one", "测试源")
        val first = media(source, "first", "第一部")
        val second = media(source, "second", "第二部")
        val searcher = AllSourcesSearcher { request, _ ->
            flow { emit(completedSnapshot(request, results = listOf(first, second))) }
        }
        val gateway = DetailOnlyGateway { site, vodId ->
            if (vodId == "first") {
                withContext(NonCancellable) { delay(1_000) }
            } else {
                delay(10)
            }
            SourceResult.Success(detail(site, vodId))
        }
        val controller = controller(
            repository = FakeRepository(listOf(source)),
            searcher = searcher,
            gateway = gateway,
        )
        runCurrent()
        controller.updateQuery("详情")
        advanceTimeBy(350)
        runCurrent()

        controller.selectResult(first)
        runCurrent()
        controller.selectResult(second)
        advanceTimeBy(10)
        runCurrent()
        val secondLoaded = assertIs<SearchDetailUiState.Loaded>(controller.state.value.detail)
        assertEquals("第二部", secondLoaded.detail.media.title)

        advanceTimeBy(1_000)
        runCurrent()
        val stillSecond = assertIs<SearchDetailUiState.Loaded>(controller.state.value.detail)
        assertEquals("第二部", stillSecond.detail.media.title)
    }

    @Test
    fun posterLoaderAcceptsSignedPublicHttpsAndRejectsUnsafeEndpoints() {
        assertEquals(
            "https://images.invalid/poster.jpg?width=320",
            strictHttpsPosterUrl("https://images.invalid/poster.jpg?width=320"),
        )
        assertEquals(
            "https://images.invalid/poster.jpg?token=signed-value",
            strictHttpsPosterUrl("https://images.invalid/poster.jpg?token=signed-value"),
        )
        assertNull(strictHttpsPosterUrl("http://images.invalid/poster.jpg"))
        assertNull(strictHttpsPosterUrl("https://user:secret@images.invalid/poster.jpg"))
        assertNull(strictHttpsPosterUrl("https://images.invalid/poster.jpg#fragment"))
        assertNull(strictHttpsPosterUrl("https://localhost/poster.jpg"))
        assertNull(strictHttpsPosterUrl("https://192.168.1.8/poster.jpg"))
        assertNull(strictHttpsPosterUrl("https://127.1/poster.jpg"))
        assertNull(strictHttpsPosterUrl("https://2130706433/poster.jpg"))
    }

    private fun TestScope.controller(
        repository: LegacySourceRepository = FakeRepository(emptyList()),
        searcher: AllSourcesSearcher,
        gateway: LegacyHttpSourceGateway = DetailOnlyGateway { _, _ -> error("detail must not be called") },
    ): SourceSearchController = SourceSearchController(
        repository = repository,
        searcher = searcher,
        gateway = gateway,
        scope = backgroundScope,
        debounceMillis = 350,
    )

    private fun completedSnapshot(
        request: AllSourcesSearchRequest,
        results: List<SourceMediaSummary> = emptyList(),
    ): AllSourcesSearchSnapshot = AllSourcesSearchSnapshot(
        sessionId = request.sessionId,
        keyword = request.keyword,
        sources = emptyList(),
        results = results,
        isComplete = true,
    )

    private fun site(key: String, name: String): LegacySiteDescriptor = LegacySiteDescriptor(
        sourceKey = LegacySourceKey("config:$key"),
        legacyKey = key,
        name = name,
        type = 1,
        api = "https://source.invalid/api",
        ext = "",
        jar = "",
        playUrl = "",
        timeoutMillis = 5_000,
        quickSearch = false,
        categories = emptyList(),
        requestHeaders = emptyMap(),
        state = SourceOperationalState(
            searchCapability = SourceSearchCapability.SUPPORTED,
            userActivation = SourceUserActivation.ENABLED,
            availability = SourceAvailability.AVAILABLE,
        ),
        raw = RawJson("{}"),
    )

    private fun media(
        site: LegacySiteDescriptor,
        vodId: String,
        title: String,
    ): SourceMediaSummary = SourceMediaSummary(
        sourceKey = site.sourceKey,
        vodId = vodId,
        title = title,
        year = "2026",
        type = "电影",
        region = "测试",
        poster = "https://images.invalid/$vodId.jpg",
        sourceName = site.name,
    )

    private fun detail(site: LegacySiteDescriptor, vodId: String): SourceMediaDetail = SourceMediaDetail(
        media = media(site, vodId, if (vodId == "first") "第一部" else "第二部"),
        director = "导演",
        actors = "演员",
        description = "简介",
        lines = listOf(
            SourcePlaybackLine(
                name = "线路",
                episodes = listOf(SourceEpisode(name = "第 1 集", playbackId = "secret-playback-id")),
            ),
        ),
    )
}

private class FakeRepository(sites: List<LegacySiteDescriptor>) : LegacySourceRepository {
    private val mutableState = MutableStateFlow(
        LegacySourceState(
            isInitialized = true,
            onboardingCompleted = true,
            configurations = listOf(
                LegacyConfigSnapshot(
                    id = LegacyConfigId("config"),
                    displayName = "测试配置",
                    importKind = ConfigImportKind.PASTED_TEXT,
                    origin = null,
                    originDisplay = null,
                    original = RawJson("{}"),
                    expanded = RawJson("{}"),
                    fields = LegacyConfigFields(
                        spider = null,
                        sites = sites,
                        parses = emptyList(),
                        rules = emptyList(),
                        headers = emptyList(),
                        hosts = emptyList(),
                        flags = emptyList(),
                        danmaku = null,
                        doh = emptyList(),
                        proxy = emptyList(),
                        ads = emptyList(),
                        wallpaper = null,
                        logo = null,
                        notice = null,
                        lives = emptyList(),
                    ),
                    unknownTopLevelFields = emptyMap(),
                    diagnostics = emptyList(),
                ),
            ),
        ),
    )

    override val state: StateFlow<LegacySourceState> = mutableState

    override suspend fun importRemoteUrl(url: String): LegacyConfigImportResult = error("unused")
    override suspend fun importPastedText(text: String): LegacyConfigImportResult = error("unused")
    override suspend fun importLocalFile(fileName: String, text: String): LegacyConfigImportResult = error("unused")
    override suspend fun setSourceEnabled(
        configId: LegacyConfigId,
        sourceKey: LegacySourceKey,
        enabled: Boolean,
    ): Unit = error("unused")

    override suspend fun deleteConfiguration(configId: LegacyConfigId): Unit = error("unused")
    override suspend fun completeOnboardingWithoutImport(): Unit = error("unused")
    override suspend fun clearLastImportDiagnostics(): Unit = error("unused")
}

private class DetailOnlyGateway(
    private val block: suspend (LegacySiteDescriptor, String) -> SourceResult<SourceMediaDetail>,
) : LegacyHttpSourceGateway {
    override suspend fun detail(
        site: LegacySiteDescriptor,
        vodId: String,
    ): SourceResult<SourceMediaDetail> = block(site, vodId)

    override suspend fun home(site: LegacySiteDescriptor): SourceResult<SourceHome> = error("unused")
    override suspend fun category(
        site: LegacySiteDescriptor,
        categoryId: String,
        page: Int,
        filters: Map<String, String>,
    ): SourceResult<SourcePage> = error("unused")

    override suspend fun search(
        site: LegacySiteDescriptor,
        keyword: String,
        page: Int,
        quick: Boolean,
    ): SourceResult<SourcePage> = error("unused")

    override suspend fun playback(
        site: LegacySiteDescriptor,
        flag: String,
        playbackId: String,
    ): SourceResult<PlaybackRequest> = error("unused")
}
