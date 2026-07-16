package com.nexora.source.runtime.search

import com.nexora.source.api.AllSourcesSearchRequest
import com.nexora.source.api.AllSourcesSearchSnapshot
import com.nexora.source.api.AllSourcesSearcher
import com.nexora.source.api.LegacyHttpSourceGateway
import com.nexora.source.api.LegacySiteDescriptor
import com.nexora.source.api.LegacySourceKey
import com.nexora.source.api.PerSourceSearchProgress
import com.nexora.source.api.PerSourceSearchState
import com.nexora.source.api.SourceError
import com.nexora.source.api.SourceErrorCode
import com.nexora.source.api.SourceMediaSummary
import com.nexora.source.api.SourceResult
import com.nexora.source.api.SourceSearchCapability
import com.nexora.source.api.SourceUserActivation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

public class DefaultAllSourcesSearcher(
    private val gateway: LegacyHttpSourceGateway,
    maxParallelism: Int = DEFAULT_MAX_PARALLELISM,
) : AllSourcesSearcher {
    private val parallelism = maxParallelism.coerceIn(1, MAX_PARALLELISM)
    private val semaphore = Semaphore(parallelism)

    override fun search(
        request: AllSourcesSearchRequest,
        sources: List<LegacySiteDescriptor>,
    ): Flow<AllSourcesSearchSnapshot> = channelFlow {
        val boundedRequest = request.copy(keyword = request.keyword.take(MAX_QUERY_CHARS))
        val boundedEligible = sources
            .asSequence()
            .filter { site -> site.state.userActivation == SourceUserActivation.ENABLED }
            .filter { site -> site.state.searchCapability == SourceSearchCapability.SUPPORTED }
            .filter { site -> site.type in SUPPORTED_HTTP_TYPES }
            .distinctBy { site -> site.sourceKey }
            .take(MAX_SEARCH_SOURCES + 1)
            .toList()
        val sourcesTruncated = boundedEligible.size > MAX_SEARCH_SOURCES
        val eligible = boundedEligible.take(MAX_SEARCH_SOURCES)
        val sourceOrder = eligible.map { site -> site.sourceKey }
        val sourceNames = eligible.associate { site -> site.sourceKey to safeSourceName(site) }
        val states = eligible.associate { site ->
            site.sourceKey to PerSourceSearchState.Loading as PerSourceSearchState
        }.toMutableMap()
        val resultsBySource = linkedMapOf<LegacySourceKey, List<SourceMediaSummary>>()

        suspend fun emitSnapshot() {
            val orderedResults = sourceOrder.asSequence()
                .flatMap { sourceKey -> resultsBySource[sourceKey].orEmpty().asSequence() }
                .take(MAX_AGGREGATE_RESULTS + 1)
                .toList()
            send(
                AllSourcesSearchSnapshot(
                    sessionId = boundedRequest.sessionId,
                    keyword = boundedRequest.keyword,
                    sources = sourceOrder.map { sourceKey ->
                        PerSourceSearchProgress(
                            sourceKey = sourceKey,
                            sourceName = sourceNames.getValue(sourceKey),
                            state = states.getValue(sourceKey),
                        )
                    },
                    results = orderedResults.take(MAX_AGGREGATE_RESULTS),
                    isComplete = states.values.none { state -> state == PerSourceSearchState.Loading },
                    resultsTruncated = orderedResults.size > MAX_AGGREGATE_RESULTS,
                    sourcesTruncated = sourcesTruncated,
                ),
            )
        }

        emitSnapshot()
        if (eligible.isEmpty()) return@channelFlow

        coroutineScope {
            val work = Channel<LegacySiteDescriptor>(capacity = parallelism)
            val completions = Channel<SourceCompletion>(capacity = parallelism)
            launch {
                try {
                    eligible.forEach { site -> work.send(site) }
                } finally {
                    work.close()
                }
            }
            repeat(minOf(parallelism, eligible.size)) {
                launch {
                    for (site in work) {
                        completions.send(searchOne(site, boundedRequest))
                    }
                }
            }

            repeat(eligible.size) {
                val completion = completions.receive()
                when (completion) {
                    is SourceCompletion.Succeeded -> {
                        resultsBySource[completion.sourceKey] = completion.items
                        states[completion.sourceKey] = PerSourceSearchState.Success(
                            itemCount = completion.items.size,
                            truncated = completion.truncated,
                        )
                    }

                    is SourceCompletion.Failed -> {
                        resultsBySource.remove(completion.sourceKey)
                        states[completion.sourceKey] = PerSourceSearchState.Failure(completion.error)
                    }
                }
                emitSnapshot()
            }
        }
    }

    private suspend fun searchOne(
        site: LegacySiteDescriptor,
        request: AllSourcesSearchRequest,
    ): SourceCompletion = try {
        semaphore.withPermit {
            withTimeout(site.timeoutMillis.coerceIn(MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS)) {
                when (
                    val result = gateway.search(
                        site = site,
                        keyword = request.keyword,
                        page = request.page,
                        quick = site.quickSearch,
                    )
                ) {
                    is SourceResult.Success -> {
                        val items = result.value.items
                        SourceCompletion.Succeeded(
                            sourceKey = site.sourceKey,
                            items = items.take(MAX_RESULTS_PER_SOURCE).map { item ->
                                item.copy(
                                    sourceKey = site.sourceKey,
                                    sourceName = safeSourceName(site),
                                )
                            },
                            truncated = items.size > MAX_RESULTS_PER_SOURCE,
                        )
                    }

                    is SourceResult.Failure -> SourceCompletion.Failed(
                        sourceKey = site.sourceKey,
                        error = result.error.copy(sourceKey = site.sourceKey),
                    )
                }
            }
        }
    } catch (_: TimeoutCancellationException) {
        SourceCompletion.Failed(
            sourceKey = site.sourceKey,
            error = SourceError(
                sourceKey = site.sourceKey,
                code = SourceErrorCode.NETWORK_FAILURE,
                userMessage = "数据源搜索超时，请稍后重试。",
                retryable = true,
            ),
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        SourceCompletion.Failed(
            sourceKey = site.sourceKey,
            error = SourceError(
                sourceKey = site.sourceKey,
                code = SourceErrorCode.NETWORK_FAILURE,
                userMessage = "数据源搜索失败，其他数据源仍会继续。",
                retryable = true,
            ),
        )
    }

    private fun safeSourceName(site: LegacySiteDescriptor): String = site.name
        .filterNot(Char::isISOControl)
        .trim()
        .take(MAX_SOURCE_NAME_LENGTH)
        .ifEmpty { "未命名数据源" }

    private sealed interface SourceCompletion {
        val sourceKey: LegacySourceKey

        data class Succeeded(
            override val sourceKey: LegacySourceKey,
            val items: List<SourceMediaSummary>,
            val truncated: Boolean,
        ) : SourceCompletion

        data class Failed(
            override val sourceKey: LegacySourceKey,
            val error: SourceError,
        ) : SourceCompletion
    }

    private companion object {
        private const val DEFAULT_MAX_PARALLELISM: Int = 6
        private const val MAX_PARALLELISM: Int = 16
        private const val MIN_TIMEOUT_MILLIS: Long = 1L
        private const val MAX_TIMEOUT_MILLIS: Long = 60_000L
        private const val MAX_SOURCE_NAME_LENGTH: Int = 120
        private const val MAX_QUERY_CHARS: Int = 256
        private const val MAX_SEARCH_SOURCES: Int = 500
        private const val MAX_RESULTS_PER_SOURCE: Int = 50
        private const val MAX_AGGREGATE_RESULTS: Int = 2_000
        private val SUPPORTED_HTTP_TYPES: Set<Int> = setOf(0, 1, 4)
    }
}
