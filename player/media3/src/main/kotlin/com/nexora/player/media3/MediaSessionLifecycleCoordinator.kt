package com.nexora.player.media3

import com.nexora.player.api.PlaybackGeneration
import com.nexora.player.api.PlaybackSessionId
import com.nexora.player.api.PlaybackState

internal data class MediaSessionPlaybackSnapshot(
    val sessionId: PlaybackSessionId,
    val title: String,
    val generation: PlaybackGeneration,
    val positionMs: Long,
    val playing: Boolean,
)

internal data class MediaSessionLifecycleStatus(
    val serviceActive: Boolean = true,
    val appInForeground: Boolean = true,
    val restorePending: Boolean = false,
    val snapshot: MediaSessionPlaybackSnapshot? = null,
)

internal class MediaSessionLifecycleCoordinator {
    var status: MediaSessionLifecycleStatus = MediaSessionLifecycleStatus()
        private set

    fun onPlaybackStateChanged(state: PlaybackState) {
        val snapshot = state.toSnapshot() ?: return
        status = status.copy(snapshot = snapshot, restorePending = false)
    }

    fun onAppEnteredBackground() {
        status = status.copy(appInForeground = false)
    }

    fun onAppResumed() {
        status = status.copy(appInForeground = true)
    }

    fun onServiceDestroyed() {
        status = status.copy(serviceActive = false, restorePending = status.snapshot != null)
    }

    fun onServiceRecreated(): MediaSessionPlaybackSnapshot? {
        val snapshot = status.snapshot
        status = status.copy(serviceActive = true, restorePending = false)
        return snapshot
    }

    private fun PlaybackState.toSnapshot(): MediaSessionPlaybackSnapshot? = when (this) {
        is PlaybackState.Buffering -> MediaSessionPlaybackSnapshot(
            sessionId = request.sessionId,
            title = request.media.title,
            generation = generation,
            positionMs = positionMs,
            playing = true,
        )

        is PlaybackState.Playing -> MediaSessionPlaybackSnapshot(
            sessionId = request.sessionId,
            title = request.media.title,
            generation = generation,
            positionMs = positionMs,
            playing = true,
        )

        is PlaybackState.Paused -> MediaSessionPlaybackSnapshot(
            sessionId = request.sessionId,
            title = request.media.title,
            generation = generation,
            positionMs = positionMs,
            playing = false,
        )

        is PlaybackState.Completed -> MediaSessionPlaybackSnapshot(
            sessionId = request.sessionId,
            title = request.media.title,
            generation = generation,
            positionMs = durationMs ?: 0,
            playing = false,
        )

        else -> null
    }
}
