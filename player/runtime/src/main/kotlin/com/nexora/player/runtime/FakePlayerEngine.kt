package com.nexora.player.runtime

import com.nexora.player.api.Capability
import com.nexora.player.api.PlaybackGeneration
import com.nexora.player.api.PlaybackSessionRequest
import com.nexora.player.api.PlayerEngine
import com.nexora.player.api.PlayerEngineCallback
import com.nexora.player.api.PlayerEngineContext
import com.nexora.player.api.PlayerEngineEvent
import com.nexora.player.api.PlayerErrorCode

public class FakePlayerEngine : PlayerEngine {
    private var callback: PlayerEngineCallback? = null
    private var lastContext: PlayerEngineContext? = null

    public var preparedRequests: Int = 0
        private set
    public var playRequests: Int = 0
        private set
    public var pauseRequests: Int = 0
        private set
    public var seekRequests: Int = 0
        private set
    public var stopRequests: Int = 0
        private set
    public var closeRequests: Int = 0
        private set

    override val capabilities: Set<Capability> = setOf(
        Capability.PREPARE,
        Capability.PLAY,
        Capability.PAUSE,
        Capability.SEEK,
        Capability.STOP,
    )

    public val latestGeneration: PlaybackGeneration?
        get() = lastContext?.generation

    override fun setCallback(callback: PlayerEngineCallback?) {
        this.callback = callback
    }

    override suspend fun prepare(request: PlaybackSessionRequest, context: PlayerEngineContext) {
        preparedRequests += 1
        lastContext = context
    }

    override suspend fun play(context: PlayerEngineContext) {
        playRequests += 1
        lastContext = context
    }

    override suspend fun pause(context: PlayerEngineContext) {
        pauseRequests += 1
        lastContext = context
    }

    override suspend fun seekTo(positionMs: Long, context: PlayerEngineContext) {
        seekRequests += 1
        lastContext = context
    }

    override suspend fun stop(context: PlayerEngineContext) {
        stopRequests += 1
        lastContext = context
    }

    override suspend fun close() {
        closeRequests += 1
    }

    public fun prepared(generation: PlaybackGeneration = requireGeneration(), durationMs: Long? = null) {
        callback?.onEngineEvent(PlayerEngineEvent.Prepared(generation, durationMs))
    }

    public fun buffering(positionMs: Long, generation: PlaybackGeneration = requireGeneration()) {
        callback?.onEngineEvent(PlayerEngineEvent.Buffering(generation, positionMs))
    }

    public fun playing(positionMs: Long = 0, generation: PlaybackGeneration = requireGeneration()) {
        callback?.onEngineEvent(PlayerEngineEvent.Playing(generation, positionMs))
    }

    public fun paused(positionMs: Long = 0, generation: PlaybackGeneration = requireGeneration()) {
        callback?.onEngineEvent(PlayerEngineEvent.Paused(generation, positionMs))
    }

    public fun seekCompleted(positionMs: Long, generation: PlaybackGeneration = requireGeneration()) {
        callback?.onEngineEvent(PlayerEngineEvent.SeekCompleted(generation, positionMs))
    }

    public fun completed(durationMs: Long? = null, generation: PlaybackGeneration = requireGeneration()) {
        callback?.onEngineEvent(PlayerEngineEvent.Completed(generation, durationMs))
    }

    public fun error(
        code: PlayerErrorCode = PlayerErrorCode.ENGINE_FAILURE,
        userMessage: String = "测试播放器错误",
        retryable: Boolean = true,
        generation: PlaybackGeneration = requireGeneration(),
    ) {
        callback?.onEngineEvent(PlayerEngineEvent.Error(generation, code, userMessage, retryable))
    }

    private fun requireGeneration(): PlaybackGeneration = checkNotNull(lastContext?.generation) {
        "FakePlayerEngine has not received a generation yet."
    }
}
