package com.nexora.feature.player

import com.nexora.player.api.PlaybackSessionRequest
import com.nexora.player.api.PlaybackState

public object PlayerSessionResumePolicy {
    public fun shouldOpen(
        currentState: PlaybackState,
        request: PlaybackSessionRequest,
    ): Boolean = currentState.activeSessionId != request.sessionId.value

    public fun restoredPositionMs(currentState: PlaybackState): Long = when (currentState) {
        is PlaybackState.Buffering -> currentState.positionMs
        is PlaybackState.Playing -> currentState.positionMs
        is PlaybackState.Paused -> currentState.positionMs
        is PlaybackState.Completed -> currentState.durationMs ?: 0L
        PlaybackState.Idle,
        is PlaybackState.Preparing,
        is PlaybackState.Error,
        PlaybackState.Released,
        -> 0L
    }.coerceAtLeast(0L)
}

private val PlaybackState.activeSessionId: String?
    get() = when (this) {
        is PlaybackState.Preparing -> request.sessionId.value
        is PlaybackState.Buffering -> request.sessionId.value
        is PlaybackState.Playing -> request.sessionId.value
        is PlaybackState.Paused -> request.sessionId.value
        is PlaybackState.Completed -> request.sessionId.value
        is PlaybackState.Error -> request?.sessionId?.value
        PlaybackState.Idle,
        PlaybackState.Released,
        -> null
    }
