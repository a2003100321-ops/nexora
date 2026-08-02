package com.nexora.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nexora.player.api.PlaybackSessionRequest
import com.nexora.player.api.PlaybackState
import com.nexora.player.api.PlayerCommand
import com.nexora.player.api.PlayerController
import kotlinx.coroutines.launch

@Composable
public fun PlayerRoute(
    controller: PlayerController,
    request: PlaybackSessionRequest,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    videoRenderer: @Composable BoxScope.() -> Unit = { BasicVideoSurfacePlaceholder() },
) {
    val playbackState by controller.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(request.sessionId) {
        if (playbackState.activeSessionId != request.sessionId.value) {
            controller.dispatch(PlayerCommand.Open(request, autoPlay = true))
        }
    }

    PlayerScreen(
        state = playbackState.toPlayerScreenState(),
        onBack = {
            scope.launch {
                controller.dispatch(PlayerCommand.Stop)
                onBack()
            }
        },
        onPlayPause = {
            scope.launch {
                val command = if (playbackState.isPlaying) {
                    PlayerCommand.Pause
                } else {
                    PlayerCommand.Play
                }
                controller.dispatch(command)
            }
        },
        onSeek = { positionMs ->
            scope.launch {
                controller.dispatch(PlayerCommand.SeekTo(positionMs))
            }
        },
        onFullscreen = {
            // M4.3a only exposes the control. Orientation/fullscreen behavior is reserved for a later phase.
        },
        modifier = modifier,
        videoRenderer = videoRenderer,
    )
}

@Composable
public fun PlayerScreen(
    state: PlayerScreenState,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    videoRenderer: @Composable BoxScope.() -> Unit = { BasicVideoSurfacePlaceholder() },
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color.Black,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onBack) {
                    Text(text = "返回")
                }
                Text(
                    text = state.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 220.dp)
                    .background(Color(0xFF101010))
                    .semantics { contentDescription = "视频渲染区域" },
                contentAlignment = Alignment.Center,
                content = videoRenderer,
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = state.statusText,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = state.progressFraction,
                    onValueChange = { fraction ->
                        onSeek((fraction * state.durationMs.coerceAtLeast(0)).toLong())
                    },
                    enabled = state.seekEnabled,
                    modifier = Modifier.semantics { contentDescription = "播放进度条" },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(text = state.positionText, color = Color.White)
                    Text(text = state.durationText, color = Color.White)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onPlayPause,
                        enabled = state.playPauseEnabled,
                        modifier = Modifier.widthIn(min = 112.dp),
                    ) {
                        Text(text = state.playPauseLabel)
                    }
                    Button(
                        onClick = onFullscreen,
                        modifier = Modifier.widthIn(min = 112.dp),
                    ) {
                        Text(text = "全屏")
                    }
                }
            }
        }
    }
}

@Composable
private fun BasicVideoSurfacePlaceholder() {
    Text(
        text = "Nexora Player",
        color = Color.White,
        style = MaterialTheme.typography.headlineMedium,
    )
}

private val PlaybackState.isPlaying: Boolean
    get() = this is PlaybackState.Playing || this is PlaybackState.Buffering

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
