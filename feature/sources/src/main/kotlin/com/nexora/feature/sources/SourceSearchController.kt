package com.nexora.feature.sources

import com.nexora.source.api.AllSourcesSearchRequest
import com.nexora.source.api.AllSourcesSearchSnapshot
import com.nexora.source.api.AllSourcesSearcher
import com.nexora.source.api.LegacyHttpSourceGateway
import com.nexora.source.api.LegacySiteDescriptor
import com.nexora.source.api.LegacySourceRepository
import com.nexora.source.api.PerSourceSearchProgress
import com.nexora.source.api.PerSourceSearchState
import com.nexora.source.api.SearchSessionId
import com.nexora.source.api.SourceError
import com.nexora.source.api.SourceErrorCode
import com.nexora.source.api.SourceMediaDetail
import com.nexora.source.api.SourceMediaSummary
import com.nexora.source.api.SourceResult
import com.nexora.source.api.SourceSearchCapability
import com.nexora.source.api.SourceUserActivation
import com.nexora.source.api.SourceVodIdentity
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal enum class SearchRunPhase {
    IDLE,
    DEBOUNCING,
    RUNNING,
    COMPLETED,
    CANCELLED,
}

internal sealed interface SearchDetailUiState {
    data object None : SearchDetailUiState

    data class Loading(
        val identity: SourceVodIdentity,
        val title: String,
    ) : SearchDetailUiState

    data class Loaded(val detail: SourceMediaDetail) : SearchDetailUiState

    data class Failed(
        val identity: SourceVodIdentity,
        val title: String,
        val userMessage: String,
    ) : SearchDetailUiState
}

internal data class SourceSearchUiState(
    val query: String = "",
    val phase: SearchRunPhase = SearchRunPhase.IDLE,
    val activeSessionId: SearchSessionId? = null,
    val sources: List<PerSourceSearchProgress> = emptyList(),
    val results: List<SourceMediaSummary> = emptyList(),
    val resultsTruncated: Boolean = false,
    val sourcesTruncated: Boolean = false,
    val detail: SearchDetailUiState = SearchDetailUiState.None,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal class SourceSearchController(
    private val repository: LegacySourceRepository,
    private val searcher: AllSourcesSearcher,
    private val gateway: LegacyHttpSourceGateway,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = SEARCH_DEBOUNCE_MILLIS,
) {
    private val generation = AtomicLong()
    private val control = MutableStateFlow(SearchControl())
    private val mutableState = MutableStateFlow(SourceSearchUiState())
    private var activeSites: Map<com.nexora.source.api.LegacySourceKey, LegacySiteDescriptor> = emptyMap()
    private var detailJob: Job? = null
    private var detailGeneration: Long = 0

    val state: StateFlow<SourceSearchUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            control.flatMapLatest(::searchFlow).collect(::acceptSearchEmission)
        }
    }

    fun updateQuery(value: String) {
        val acceptedValue = value.take(MAX_QUERY_CHARS)
        if (acceptedValue == mutableState.value.query) return
        cancelDetail()
        val normalized = acceptedValue.trim()
        val sessionId = SearchSessionId(generation.incrementAndGet())
        mutableState.value = SourceSearchUiState(
            query = acceptedValue,
            phase = if (normalized.isEmpty()) SearchRunPhase.IDLE else SearchRunPhase.DEBOUNCING,
            activeSessionId = sessionId,
        )
        control.value = SearchControl(
            query = normalized,
            sessionId = sessionId,
            shouldRun = normalized.isNotEmpty(),
            immediate = normalized.isEmpty(),
        )
    }

    fun retry() {
        val normalized = mutableState.value.query.trim()
        if (normalized.isEmpty()) return
        cancelDetail()
        val sessionId = SearchSessionId(generation.incrementAndGet())
        mutableState.value = mutableState.value.copy(
            phase = SearchRunPhase.RUNNING,
            activeSessionId = sessionId,
            sources = emptyList(),
            results = emptyList(),
            detail = SearchDetailUiState.None,
        )
        control.value = SearchControl(
            query = normalized,
            sessionId = sessionId,
            shouldRun = true,
            immediate = true,
        )
    }

    fun cancelSearch() {
        cancelDetail()
        val current = mutableState.value
        val sessionId = SearchSessionId(generation.incrementAndGet())
        mutableState.value = current.copy(
            phase = if (current.query.isBlank()) SearchRunPhase.IDLE else SearchRunPhase.CANCELLED,
            activeSessionId = sessionId,
            sources = current.sources.filterNot { progress ->
                progress.state == PerSourceSearchState.Loading
            },
            detail = SearchDetailUiState.None,
        )
        control.value = SearchControl(
            query = current.query.trim(),
            sessionId = sessionId,
            shouldRun = false,
            immediate = true,
        )
    }

    fun selectResult(result: SourceMediaSummary) {
        val site = activeSites[result.sourceKey] ?: run {
            mutableState.value = mutableState.value.copy(
                detail = SearchDetailUiState.Failed(
                    identity = result.identity,
                    title = result.title,
                    userMessage = "找不到该结果对应的数据源，请重新搜索。",
                ),
            )
            return
        }
        detailJob?.cancel()
        val requestGeneration = ++detailGeneration
        val searchSession = mutableState.value.activeSessionId
        mutableState.value = mutableState.value.copy(
            detail = SearchDetailUiState.Loading(result.identity, result.title),
        )
        detailJob = scope.launch {
            val next = try {
                when (val response = gateway.detail(site, result.vodId)) {
                    is SourceResult.Success -> SearchDetailUiState.Loaded(
                        response.value.copy(
                            media = response.value.media.copy(
                                sourceKey = site.sourceKey,
                                sourceName = safeSourceName(site),
                            ),
                        ),
                    )

                    is SourceResult.Failure -> SearchDetailUiState.Failed(
                        identity = result.identity,
                        title = result.title,
                        userMessage = response.error.userMessage,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                SearchDetailUiState.Failed(
                    identity = result.identity,
                    title = result.title,
                    userMessage = "详情加载失败，请稍后重试。",
                )
            }
            if (requestGeneration == detailGeneration &&
                searchSession == mutableState.value.activeSessionId
            ) {
                mutableState.value = mutableState.value.copy(detail = next)
            }
        }
    }

    fun closeDetail() {
        cancelDetail()
        mutableState.value = mutableState.value.copy(detail = SearchDetailUiState.None)
    }

    private fun searchFlow(next: SearchControl): Flow<SearchEmission> {
        if (!next.shouldRun || next.query.isEmpty()) return flow {
            emit(SearchEmission.Stopped(next.sessionId))
        }
        return flow {
            if (!next.immediate) delay(debounceMillis)
            val sites = repository.state.value.configurations
                .asSequence()
                .flatMap { configuration -> configuration.fields.sites.asSequence() }
                .filter { site -> site.state.userActivation == SourceUserActivation.ENABLED }
                .filter { site -> site.state.searchCapability == SourceSearchCapability.SUPPORTED }
                .filter { site -> site.type in SUPPORTED_HTTP_TYPES }
                .distinctBy { site -> site.sourceKey }
                .take(MAX_SEARCH_SOURCES_WITH_SENTINEL)
                .toList()
            emit(SearchEmission.Started(next.sessionId, sites))
            emitAll(
                searcher.search(
                    request = AllSourcesSearchRequest(
                        sessionId = next.sessionId,
                        keyword = next.query,
                    ),
                    sources = sites,
                ).map { snapshot -> SearchEmission.Snapshot(snapshot) },
            )
        }.catch { failure ->
            if (failure is CancellationException) throw failure
            if (failure !is Exception) throw failure
            emit(SearchEmission.Failed(next.sessionId))
        }
    }

    private fun acceptSearchEmission(emission: SearchEmission) {
        when (emission) {
            is SearchEmission.Stopped -> Unit
            is SearchEmission.Started -> {
                if (emission.sessionId != mutableState.value.activeSessionId) return
                activeSites = emission.sites.associateBy { site -> site.sourceKey }
                mutableState.value = mutableState.value.copy(
                    phase = SearchRunPhase.RUNNING,
                    sources = emptyList(),
                    results = emptyList(),
                    detail = SearchDetailUiState.None,
                )
            }

            is SearchEmission.Snapshot -> {
                val snapshot = emission.value
                if (snapshot.sessionId != mutableState.value.activeSessionId) return
                mutableState.value = mutableState.value.copy(
                    phase = if (snapshot.isComplete) {
                        SearchRunPhase.COMPLETED
                    } else {
                        SearchRunPhase.RUNNING
                    },
                    sources = snapshot.sources,
                    results = snapshot.results,
                    resultsTruncated = snapshot.resultsTruncated,
                    sourcesTruncated = snapshot.sourcesTruncated,
                )
            }

            is SearchEmission.Failed -> {
                if (emission.sessionId != mutableState.value.activeSessionId) return
                mutableState.value = mutableState.value.copy(
                    phase = SearchRunPhase.COMPLETED,
                    sources = mutableState.value.sources.map { progress ->
                        if (progress.state != PerSourceSearchState.Loading) return@map progress
                        progress.copy(
                            state = PerSourceSearchState.Failure(
                                SourceError(
                                    sourceKey = progress.sourceKey,
                                    code = SourceErrorCode.NETWORK_FAILURE,
                                    userMessage = "搜索任务失败，请重试。",
                                    retryable = true,
                                ),
                            ),
                        )
                    },
                )
            }
        }
    }

    private fun cancelDetail() {
        detailGeneration += 1
        detailJob?.cancel()
        detailJob = null
    }

    private fun safeSourceName(site: LegacySiteDescriptor): String = site.name
        .filterNot(Char::isISOControl)
        .trim()
        .take(120)
        .ifEmpty { "未命名数据源" }

    private data class SearchControl(
        val query: String = "",
        val sessionId: SearchSessionId = SearchSessionId(0),
        val shouldRun: Boolean = false,
        val immediate: Boolean = true,
    )

    private sealed interface SearchEmission {
        data class Stopped(val sessionId: SearchSessionId) : SearchEmission

        data class Started(
            val sessionId: SearchSessionId,
            val sites: List<LegacySiteDescriptor>,
        ) : SearchEmission

        data class Snapshot(val value: AllSourcesSearchSnapshot) : SearchEmission

        data class Failed(val sessionId: SearchSessionId) : SearchEmission
    }

    private companion object {
        private const val SEARCH_DEBOUNCE_MILLIS: Long = 350L
        private const val MAX_QUERY_CHARS: Int = 256
        private const val MAX_SEARCH_SOURCES_WITH_SENTINEL: Int = 501
        private val SUPPORTED_HTTP_TYPES: Set<Int> = setOf(0, 1, 4)
    }
}
