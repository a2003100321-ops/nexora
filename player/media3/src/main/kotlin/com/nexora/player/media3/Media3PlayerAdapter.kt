package com.nexora.player.media3

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

internal interface Media3PlayerAdapter {
    val currentPositionMs: Long
    val durationMs: Long?

    fun setListener(listener: Media3PlayerAdapterListener?)

    fun prepare(mappedItem: Media3MappedItem)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun stop()

    fun release()
}

internal interface Media3PlayerAdapterListener {
    fun onPlaybackStateChanged(state: Int)

    fun onIsPlayingChanged(isPlaying: Boolean)

    fun onPlayerError(error: Media3PlaybackFailure)
}

internal data class Media3PlaybackFailure(
    val errorCode: Int,
)

@UnstableApi
internal class ExoPlayerMedia3PlayerAdapter(
    context: Context,
) : Media3PlayerAdapter {
    private val player = ExoPlayer.Builder(context).build()
    private var listener: Media3PlayerAdapterListener? = null

    init {
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    listener?.onPlaybackStateChanged(playbackState)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    listener?.onIsPlayingChanged(isPlaying)
                }

                override fun onPlayerError(error: PlaybackException) {
                    listener?.onPlayerError(Media3PlaybackFailure(error.errorCode))
                }
            },
        )
    }

    override val currentPositionMs: Long
        get() = player.currentPosition

    override val durationMs: Long?
        get() = player.duration.takeUnless { it == androidx.media3.common.C.TIME_UNSET }

    override fun setListener(listener: Media3PlayerAdapterListener?) {
        this.listener = listener
    }

    override fun prepare(mappedItem: Media3MappedItem) {
        val mediaSourceFactory = DefaultMediaSourceFactory(
            SecureMedia3DataSourceFactory(mappedItem.dataSourceRequest),
        )
        player.setMediaSource(mediaSourceFactory.createMediaSource(mappedItem.mediaItem))
        player.prepare()
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    override fun stop() {
        player.stop()
        player.clearMediaItems()
    }

    override fun release() {
        player.release()
    }
}
