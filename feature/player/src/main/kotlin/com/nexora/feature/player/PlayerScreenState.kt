package com.nexora.feature.player

import com.nexora.player.api.PlaybackState

public data class PlayerScreenState(
    val title: String,
    val statusText: String,
    val positionMs: Long,
    val durationMs: Long,
    val positionText: String,
    val durationText: String,
    val progressFraction: Float,
    val playPauseLabel: String,
    val playPauseEnabled: Boolean,
    val seekEnabled: Boolean,
)

public fun PlaybackState.toPlayerScreenState(): PlayerScreenState {
    val request = when (this) {
        is PlaybackState.Preparing -> request
        is PlaybackState.Buffering -> request
        is PlaybackState.Playing -> request
        is PlaybackState.Paused -> request
        is PlaybackState.Completed -> request
        is PlaybackState.Error -> request
        PlaybackState.Idle,
        PlaybackState.Released,
        -> null
    }
    val positionMs = when (this) {
        is PlaybackState.Buffering -> positionMs
        is PlaybackState.Playing -> positionMs
        is PlaybackState.Paused -> positionMs
        is PlaybackState.Completed -> durationMs ?: 0L
        else -> 0L
    }.coerceAtLeast(0L)
    val durationMs = when (this) {
        is PlaybackState.Buffering -> durationMs
        is PlaybackState.Playing -> durationMs
        is PlaybackState.Paused -> durationMs
        is PlaybackState.Completed -> durationMs
        else -> null
    } ?: 0L

    val playable = this !is PlaybackState.Released
    val playing = this is PlaybackState.Playing || this is PlaybackState.Buffering
    val status = when (this) {
        PlaybackState.Idle -> "等待播放"
        is PlaybackState.Preparing -> "准备播放"
        is PlaybackState.Buffering -> "缓冲中"
        is PlaybackState.Playing -> "播放中"
        is PlaybackState.Paused -> "已暂停"
        is PlaybackState.Completed -> "播放完成"
        is PlaybackState.Error -> userMessage
        PlaybackState.Released -> "播放器已关闭"
    }

    return PlayerScreenState(
        title = request?.media?.title.orEmpty().ifBlank { "Nexora 播放器" },
        statusText = status,
        positionMs = positionMs,
        durationMs = durationMs,
        positionText = formatDuration(positionMs),
        durationText = if (durationMs > 0) formatDuration(durationMs) else "--:--",
        progressFraction = if (durationMs > 0) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        },
        playPauseLabel = if (playing) "暂停" else "播放",
        playPauseEnabled = playable,
        seekEnabled = durationMs > 0,
    )
}

internal fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1_000).coerceAtLeast(0)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
