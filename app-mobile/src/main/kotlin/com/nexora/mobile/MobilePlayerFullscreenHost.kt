package com.nexora.mobile

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.nexora.feature.player.PlayerFullscreenReducer
import com.nexora.feature.player.PlayerFullscreenState
import com.nexora.feature.player.PlayerRoute
import com.nexora.player.api.PlaybackSessionRequest
import com.nexora.player.api.PlayerController

@Composable
internal fun MobilePlayerFullscreenHost(
    controller: PlayerController,
    request: PlaybackSessionRequest,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    videoRenderer: @Composable BoxScope.() -> Unit,
) {
    val activity = LocalContext.current.findActivity()
    var isFullscreen by rememberSaveable { mutableStateOf(false) }
    val fullscreenState = if (isFullscreen) {
        PlayerFullscreenReducer.enterFullscreen()
    } else {
        PlayerFullscreenReducer.exitFullscreen()
    }

    ApplyRequestedOrientation(activity, fullscreenState)

    BackHandler(enabled = isFullscreen) {
        isFullscreen = false
    }

    PlayerRoute(
        controller = controller,
        request = request,
        onBack = {
            if (isFullscreen) {
                isFullscreen = false
            } else {
                onBack()
            }
        },
        modifier = modifier,
        isFullscreen = isFullscreen,
        onFullscreen = {
            isFullscreen = !isFullscreen
        },
        videoRenderer = videoRenderer,
    )
}

@Composable
private fun ApplyRequestedOrientation(
    activity: Activity?,
    fullscreenState: PlayerFullscreenState,
) {
    SideEffect {
        activity?.requestedOrientation = if (fullscreenState.isFullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    DisposableEffect(activity) {
        if (activity == null) return@DisposableEffect onDispose {}
        onDispose {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
