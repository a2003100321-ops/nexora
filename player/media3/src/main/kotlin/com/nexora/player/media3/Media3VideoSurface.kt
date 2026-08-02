package com.nexora.player.media3

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
@Composable
public fun Media3VideoSurface(
    modifier: Modifier = Modifier,
) {
    val attachmentController = remember { Media3SurfaceAttachmentController() }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                player = context.acquirePlayerForSurface()
                attachmentController.attach()
            }
        },
        update = { view ->
            if (view.player == null) {
                view.player = view.context.acquirePlayerForSurface()
            }
        },
        onRelease = { view ->
            view.player = null
            attachmentController.detach()
        },
    )
}

@OptIn(UnstableApi::class)
private fun Context.acquirePlayerForSurface(): androidx.media3.common.Player =
    Media3SessionPlayerOwner.acquireSurfacePlayer(applicationContext)
