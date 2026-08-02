package com.nexora.player.media3

import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession

@UnstableApi
internal object Media3SessionPlayerOwner {
    private val lock = Any()
    private var adapter: ExoPlayerMedia3PlayerAdapter? = null
    private var mediaSession: MediaSession? = null

    fun acquireAdapter(context: Context): ExoPlayerMedia3PlayerAdapter {
        val appContext = context.applicationContext
        val current = synchronized(lock) {
            adapter ?: ExoPlayerMedia3PlayerAdapter(appContext).also { adapter = it }
        }
        appContext.startNexoraMediaSessionService()
        return current
    }

    fun createSession(service: NexoraMediaSessionService): MediaSession = synchronized(lock) {
        val currentAdapter = adapter ?: ExoPlayerMedia3PlayerAdapter(service.applicationContext)
            .also { adapter = it }
        mediaSession ?: MediaSession.Builder(service, currentAdapter.sessionPlayer)
            .build()
            .also { mediaSession = it }
    }

    fun currentSession(): MediaSession? = synchronized(lock) { mediaSession }

    fun stopService(context: Context) {
        context.applicationContext.stopService(
            Intent(context.applicationContext, NexoraMediaSessionService::class.java),
        )
    }

    fun releaseFromService(session: MediaSession?) {
        synchronized(lock) {
            if (session != null && session == mediaSession) {
                mediaSession?.release()
                mediaSession = null
                adapter?.release()
                adapter = null
            }
        }
    }

    private fun Context.startNexoraMediaSessionService() {
        val intent = Intent(this, NexoraMediaSessionService::class.java)
        runCatching {
            startService(intent)
        }
    }
}
