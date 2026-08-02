package com.nexora.player.media3

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@UnstableApi
public class NexoraMediaSessionService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        mediaSession = Media3SessionPlayerOwner.createSession(this)
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaSession? = mediaSession

    override fun onDestroy() {
        val session = mediaSession
        mediaSession = null
        Media3SessionPlayerOwner.releaseFromService(session)
        super.onDestroy()
    }
}
