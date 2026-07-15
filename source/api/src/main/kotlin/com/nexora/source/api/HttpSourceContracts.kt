package com.nexora.source.api

public data class SourceCategory(
    val id: String,
    val name: String,
)

public data class SourceFilterOption(
    val name: String,
    val value: String,
)

public data class SourceFilter(
    val key: String,
    val name: String,
    val initialValue: String?,
    val options: List<SourceFilterOption>,
)

public data class SourceVodIdentity(
    val sourceKey: LegacySourceKey,
    val vodId: String,
)

public data class SourceMediaSummary(
    val sourceKey: LegacySourceKey,
    val vodId: String,
    val title: String,
    val year: String,
    val type: String,
    val region: String,
    val poster: String,
    val sourceName: String,
    val remarks: String = "",
) {
    public val identity: SourceVodIdentity
        get() = SourceVodIdentity(sourceKey = sourceKey, vodId = vodId)
}

public data class SourcePage(
    val items: List<SourceMediaSummary>,
    val page: Int = 1,
    val pageCount: Int = 1,
    val total: Int? = null,
    val pageSize: Int? = null,
)

public data class SourceHome(
    val categories: List<SourceCategory>,
    val filtersByCategory: Map<String, List<SourceFilter>>,
    val featured: SourcePage,
)

public data class SourceEpisode(
    val name: String,
    val playbackId: String,
)

public data class SourcePlaybackLine(
    val name: String,
    val episodes: List<SourceEpisode>,
)

public data class SourceMediaDetail(
    val media: SourceMediaSummary,
    val director: String,
    val actors: String,
    val description: String,
    val lines: List<SourcePlaybackLine>,
)

public enum class PlaybackResolution {
    DIRECT,
    REQUIRES_PARSER,
}

public data class PlaybackRequest(
    val sourceKey: LegacySourceKey,
    val flag: String,
    val url: String,
    val headers: Map<String, String>,
    val resolution: PlaybackResolution,
    val parserUrl: String? = null,
    val format: String? = null,
) {
    override fun toString(): String = buildString {
        append("PlaybackRequest(sourceKey=")
        append(sourceKey)
        append(", flag=")
        append(flag)
        append(", url=<已隐藏>")
        append(", headerNames=")
        append(headers.keys.sortedWith(String.CASE_INSENSITIVE_ORDER))
        append(", resolution=")
        append(resolution)
        append(", parserUrlPresent=")
        append(parserUrl != null)
        append(", format=")
        append(format)
        append(')')
    }
}

public enum class SourceErrorCode {
    USER_DISABLED,
    SOURCE_UNAVAILABLE,
    SEARCH_UNSUPPORTED,
    UNSUPPORTED_TYPE,
    INVALID_REQUEST,
    INVALID_URL,
    NETWORK_FAILURE,
    HTTP_ERROR,
    INVALID_RESPONSE,
    SECURITY_REJECTED,
}

public data class SourceError(
    val sourceKey: LegacySourceKey,
    val code: SourceErrorCode,
    val userMessage: String,
    val retryable: Boolean,
)

public sealed interface SourceResult<out T> {
    public data class Success<T>(val value: T) : SourceResult<T>

    public data class Failure(val error: SourceError) : SourceResult<Nothing>
}

public interface LegacyHttpSourceGateway {
    public suspend fun home(site: LegacySiteDescriptor): SourceResult<SourceHome>

    public suspend fun category(
        site: LegacySiteDescriptor,
        categoryId: String,
        page: Int,
        filters: Map<String, String> = emptyMap(),
    ): SourceResult<SourcePage>

    public suspend fun detail(
        site: LegacySiteDescriptor,
        vodId: String,
    ): SourceResult<SourceMediaDetail>

    public suspend fun search(
        site: LegacySiteDescriptor,
        keyword: String,
        page: Int = 1,
        quick: Boolean = false,
    ): SourceResult<SourcePage>

    public suspend fun playback(
        site: LegacySiteDescriptor,
        flag: String,
        playbackId: String,
    ): SourceResult<PlaybackRequest>
}
