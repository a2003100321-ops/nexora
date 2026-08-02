package com.nexora.player.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

@JvmInline
public value class PlaybackSessionId(public val value: String)

@JvmInline
public value class PlaybackCommandId(public val value: Long)

@JvmInline
public value class PlaybackOperationId(public val value: Long)

@JvmInline
public value class PlaybackGeneration(public val value: Long)

public data class PlaybackSessionRequest(
    val sessionId: PlaybackSessionId,
    val media: PlaybackMediaIdentity,
    val resource: PlaybackResource,
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val cookiePolicy: PlaybackCookiePolicy = PlaybackCookiePolicy.None,
    val line: PlaybackLine = PlaybackLine(),
    val quality: PlaybackQuality? = null,
    val audioTracks: List<PlaybackTrack> = emptyList(),
    val subtitles: List<PlaybackSubtitle> = emptyList(),
    val danmaku: PlaybackDanmaku? = null,
    val drm: PlaybackDrmReservation? = null,
    val source: PlaybackSourceAttribution,
) {
    init {
        require(resource.value.isNotBlank()) { "Playback resource must not be blank." }
        require(headers.keys.none { it.isBlank() }) { "Header names must not be blank." }
    }

    override fun toString(): String = buildString {
        append("PlaybackSessionRequest(sessionId=")
        append(sessionId.value)
        append(", media=")
        append(media)
        append(", resource=<redacted>, headerNames=")
        append(headers.keys.sortedWith(String.CASE_INSENSITIVE_ORDER))
        append(", userAgentPresent=")
        append(userAgent != null)
        append(", cookiePolicy=")
        append(cookiePolicy::class.simpleName)
        append(", line=")
        append(line)
        append(", quality=")
        append(quality?.label)
        append(", source=")
        append(source.sourceName)
        append(')')
    }
}

public data class PlaybackMediaIdentity(
    val videoId: String,
    val title: String,
    val episodeId: String? = null,
    val episodeTitle: String? = null,
    val posterUrl: String? = null,
)

public data class PlaybackResource(
    val value: String,
    val kind: PlaybackResourceKind = PlaybackResourceKind.URL,
)

public enum class PlaybackResourceKind {
    URL,
    OPAQUE_REF,
}

public sealed interface PlaybackCookiePolicy {
    public data object None : PlaybackCookiePolicy

    public data class InMemory(
        val names: Set<String>,
    ) : PlaybackCookiePolicy
}

public data class PlaybackLine(
    val id: String? = null,
    val name: String = "",
    val stable: Boolean = false,
)

public data class PlaybackQuality(
    val id: String,
    val label: String,
    val height: Int? = null,
    val bitrate: Long? = null,
)

public data class PlaybackTrack(
    val id: String,
    val label: String,
    val language: String? = null,
    val selected: Boolean = false,
)

public data class PlaybackSubtitle(
    val id: String,
    val label: String,
    val language: String? = null,
    val format: PlaybackSubtitleFormat,
    val resource: PlaybackResource? = null,
    val selected: Boolean = false,
)

public enum class PlaybackSubtitleFormat {
    EMBEDDED,
    SRT,
    ASS,
    SSA,
    VTT,
    UNKNOWN,
}

public data class PlaybackDanmaku(
    val id: String,
    val enabledByDefault: Boolean = false,
)

public data class PlaybackDrmReservation(
    val scheme: String,
    val licenseServerPresent: Boolean = false,
)

public data class PlaybackSourceAttribution(
    val sourceKey: String,
    val sourceName: String,
    val vodId: String,
    val rawLineId: String? = null,
)

public sealed interface PlaybackState {
    public data object Idle : PlaybackState

    public data class Preparing(
        val request: PlaybackSessionRequest,
        val generation: PlaybackGeneration,
    ) : PlaybackState

    public data class Buffering(
        val request: PlaybackSessionRequest,
        val generation: PlaybackGeneration,
        val positionMs: Long,
        val durationMs: Long? = null,
    ) : PlaybackState

    public data class Playing(
        val request: PlaybackSessionRequest,
        val generation: PlaybackGeneration,
        val positionMs: Long,
        val durationMs: Long? = null,
    ) : PlaybackState

    public data class Paused(
        val request: PlaybackSessionRequest,
        val generation: PlaybackGeneration,
        val positionMs: Long,
        val durationMs: Long? = null,
    ) : PlaybackState

    public data class Completed(
        val request: PlaybackSessionRequest,
        val generation: PlaybackGeneration,
        val durationMs: Long?,
    ) : PlaybackState

    public data class Error(
        val request: PlaybackSessionRequest?,
        val generation: PlaybackGeneration?,
        val code: PlayerErrorCode,
        val userMessage: String,
        val retryable: Boolean,
    ) : PlaybackState

    public data object Released : PlaybackState
}

public sealed interface PlayerCommand {
    public data class Open(
        val request: PlaybackSessionRequest,
        val autoPlay: Boolean = true,
    ) : PlayerCommand

    public data object Play : PlayerCommand

    public data object Pause : PlayerCommand

    public data class SeekTo(
        val positionMs: Long,
    ) : PlayerCommand

    public data class SwitchPlayback(
        val request: PlaybackSessionRequest,
    ) : PlayerCommand

    public data object Stop : PlayerCommand

    public data object Close : PlayerCommand
}

public sealed interface PlayerEvent {
    public val commandId: PlaybackCommandId?
    public val operationId: PlaybackOperationId?
    public val generation: PlaybackGeneration?

    public data class CommandAccepted(
        override val commandId: PlaybackCommandId,
        override val operationId: PlaybackOperationId,
        override val generation: PlaybackGeneration?,
    ) : PlayerEvent

    public data class CommandRejected(
        override val commandId: PlaybackCommandId,
        override val operationId: PlaybackOperationId?,
        val reason: String,
    ) : PlayerEvent {
        override val generation: PlaybackGeneration? = null
    }

    public data class StateChanged(
        val state: PlaybackState,
        override val commandId: PlaybackCommandId?,
        override val operationId: PlaybackOperationId?,
        override val generation: PlaybackGeneration?,
    ) : PlayerEvent

    public data class EngineCallbackDiscarded(
        override val generation: PlaybackGeneration,
        val activeGeneration: PlaybackGeneration?,
        val reason: String,
    ) : PlayerEvent {
        override val commandId: PlaybackCommandId? = null
        override val operationId: PlaybackOperationId? = null
    }
}

public enum class PlayerErrorCode {
    INVALID_REQUEST,
    UNSUPPORTED_MEDIA,
    NETWORK,
    DECODER,
    TIMEOUT,
    ENGINE_FAILURE,
    CLOSED,
    UNKNOWN,
}

public enum class Capability {
    PREPARE,
    PLAY,
    PAUSE,
    SEEK,
    STOP,
    QUALITY_SWITCH,
    AUDIO_TRACK_SWITCH,
    SUBTITLE_SWITCH,
    PLAYBACK_SPEED,
    VOLUME,
    BACKGROUND_PLAYBACK,
}

public data class PlayerCommandReceipt(
    val commandId: PlaybackCommandId,
    val operationId: PlaybackOperationId?,
    val generation: PlaybackGeneration?,
    val accepted: Boolean,
    val reason: String? = null,
)

public interface PlayerController {
    public val state: StateFlow<PlaybackState>
    public val events: Flow<PlayerEvent>
    public val capabilities: Set<Capability>

    public suspend fun dispatch(command: PlayerCommand): PlayerCommandReceipt
}

public data class PlayerEngineContext(
    val commandId: PlaybackCommandId,
    val operationId: PlaybackOperationId,
    val generation: PlaybackGeneration,
)

public sealed interface PlayerEngineEvent {
    public val generation: PlaybackGeneration

    public data class Prepared(
        override val generation: PlaybackGeneration,
        val durationMs: Long? = null,
    ) : PlayerEngineEvent

    public data class Buffering(
        override val generation: PlaybackGeneration,
        val positionMs: Long,
    ) : PlayerEngineEvent

    public data class Playing(
        override val generation: PlaybackGeneration,
        val positionMs: Long,
    ) : PlayerEngineEvent

    public data class Paused(
        override val generation: PlaybackGeneration,
        val positionMs: Long,
    ) : PlayerEngineEvent

    public data class SeekCompleted(
        override val generation: PlaybackGeneration,
        val positionMs: Long,
    ) : PlayerEngineEvent

    public data class Completed(
        override val generation: PlaybackGeneration,
        val durationMs: Long? = null,
    ) : PlayerEngineEvent

    public data class Error(
        override val generation: PlaybackGeneration,
        val code: PlayerErrorCode,
        val userMessage: String,
        val retryable: Boolean,
    ) : PlayerEngineEvent
}

public fun interface PlayerEngineCallback {
    public fun onEngineEvent(event: PlayerEngineEvent)
}

public interface PlayerEngine {
    public val capabilities: Set<Capability>

    public fun setCallback(callback: PlayerEngineCallback?)

    public suspend fun prepare(request: PlaybackSessionRequest, context: PlayerEngineContext)

    public suspend fun play(context: PlayerEngineContext)

    public suspend fun pause(context: PlayerEngineContext)

    public suspend fun seekTo(positionMs: Long, context: PlayerEngineContext)

    public suspend fun stop(context: PlayerEngineContext)

    public suspend fun close()
}
