package com.nexora.source.api

import kotlinx.coroutines.flow.Flow

@JvmInline
public value class SearchSessionId(public val value: Long)

public data class AllSourcesSearchRequest(
    val sessionId: SearchSessionId,
    val keyword: String,
    val page: Int = 1,
)

public sealed interface PerSourceSearchState {
    public data object Loading : PerSourceSearchState

    public data class Success(
        val itemCount: Int,
        val truncated: Boolean = false,
    ) : PerSourceSearchState

    public data class Failure(val error: SourceError) : PerSourceSearchState
}

public data class PerSourceSearchProgress(
    val sourceKey: LegacySourceKey,
    val sourceName: String,
    val state: PerSourceSearchState,
)

public data class AllSourcesSearchSnapshot(
    val sessionId: SearchSessionId,
    val keyword: String,
    val sources: List<PerSourceSearchProgress>,
    val results: List<SourceMediaSummary>,
    val isComplete: Boolean,
    val resultsTruncated: Boolean = false,
    val sourcesTruncated: Boolean = false,
)

public fun interface AllSourcesSearcher {
    public fun search(
        request: AllSourcesSearchRequest,
        sources: List<LegacySiteDescriptor>,
    ): Flow<AllSourcesSearchSnapshot>
}
