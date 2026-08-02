package com.nexora.player.media3

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.nexora.player.api.Capability
import com.nexora.player.api.PlaybackGeneration
import com.nexora.player.api.PlaybackSessionRequest
import com.nexora.player.api.PlayerEngine
import com.nexora.player.api.PlayerEngineCallback
import com.nexora.player.api.PlayerEngineContext
import com.nexora.player.api.PlayerEngineEvent
import com.nexora.player.api.PlayerErrorCode

@UnstableApi
public class Media3Engine internal constructor(
    private val adapter: Media3PlayerAdapter,
    private val mapper: Media3MediaItemMapper = Media3MediaItemMapper(),
) : PlayerEngine {
    private var callback: PlayerEngineCallback? = null
    private var generation: PlaybackGeneration? = null
    private var released = false

    public constructor(context: Context) : this(ServiceBackedMedia3PlayerAdapter(context.applicationContext))

    override val capabilities: Set<Capability> = setOf(
        Capability.PREPARE,
        Capability.PLAY,
        Capability.PAUSE,
        Capability.SEEK,
        Capability.STOP,
        Capability.BACKGROUND_PLAYBACK,
    )

    init {
        adapter.setListener(
            object : Media3PlayerAdapterListener {
                override fun onPlaybackStateChanged(state: Int) {
                    handlePlaybackState(state)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    handleIsPlaying(isPlaying)
                }

                override fun onPlayerError(error: Media3PlaybackFailure) {
                    emit(
                        PlayerEngineEvent.Error(
                            generation = currentGenerationOrReturn() ?: return,
                            code = mapError(error),
                            userMessage = "播放器加载失败，请稍后重试。",
                            retryable = false,
                        ),
                    )
                }
            },
        )
    }

    override fun setCallback(callback: PlayerEngineCallback?) {
        this.callback = callback
    }

    override suspend fun prepare(request: PlaybackSessionRequest, context: PlayerEngineContext) {
        ensureActive()
        generation = context.generation
        val mapped = mapper.map(request)
        adapter.prepare(mapped)
    }

    override suspend fun play(context: PlayerEngineContext) {
        ensureActive()
        generation = context.generation
        adapter.play()
    }

    override suspend fun pause(context: PlayerEngineContext) {
        ensureActive()
        generation = context.generation
        adapter.pause()
    }

    override suspend fun seekTo(positionMs: Long, context: PlayerEngineContext) {
        ensureActive()
        generation = context.generation
        val safePosition = positionMs.coerceAtLeast(0)
        adapter.seekTo(safePosition)
        emit(PlayerEngineEvent.SeekCompleted(context.generation, safePosition))
    }

    override suspend fun stop(context: PlayerEngineContext) {
        if (released) return
        generation = context.generation
        adapter.stop()
    }

    override suspend fun close() {
        if (released) return
        released = true
        adapter.release()
        adapter.setListener(null)
        callback = null
        generation = null
    }

    private fun handlePlaybackState(state: Int) {
        val activeGeneration = currentGenerationOrReturn() ?: return
        when (state) {
            Player.STATE_BUFFERING -> emit(
                PlayerEngineEvent.Buffering(activeGeneration, adapter.currentPositionMs),
            )
            Player.STATE_READY -> emit(
                PlayerEngineEvent.Prepared(activeGeneration, adapter.durationMs),
            )
            Player.STATE_ENDED -> emit(
                PlayerEngineEvent.Completed(activeGeneration, adapter.durationMs),
            )
            Player.STATE_IDLE -> Unit
        }
    }

    private fun handleIsPlaying(isPlaying: Boolean) {
        val activeGeneration = currentGenerationOrReturn() ?: return
        val positionMs = adapter.currentPositionMs
        if (isPlaying) {
            emit(PlayerEngineEvent.Playing(activeGeneration, positionMs))
        } else {
            emit(PlayerEngineEvent.Paused(activeGeneration, positionMs))
        }
    }

    private fun emit(event: PlayerEngineEvent) {
        if (!released) callback?.onEngineEvent(event)
    }

    private fun currentGenerationOrReturn(): PlaybackGeneration? = generation.takeUnless { released }

    private fun ensureActive() {
        check(!released) { "Media3Engine has already been released." }
    }

    private fun mapError(error: Media3PlaybackFailure): PlayerErrorCode = when (error.errorCode) {
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        -> PlayerErrorCode.NETWORK

        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        -> PlayerErrorCode.DECODER

        androidx.media3.common.PlaybackException.ERROR_CODE_TIMEOUT -> PlayerErrorCode.TIMEOUT
        androidx.media3.common.PlaybackException.ERROR_CODE_REMOTE_ERROR -> PlayerErrorCode.ENGINE_FAILURE
        else -> PlayerErrorCode.UNKNOWN
    }
}
