package com.nexora.player.runtime

import com.nexora.player.api.Capability
import com.nexora.player.api.PlaybackCommandId
import com.nexora.player.api.PlaybackGeneration
import com.nexora.player.api.PlaybackOperationId
import com.nexora.player.api.PlaybackSessionRequest
import com.nexora.player.api.PlaybackState
import com.nexora.player.api.PlayerCommand
import com.nexora.player.api.PlayerCommandReceipt
import com.nexora.player.api.PlayerController
import com.nexora.player.api.PlayerEngine
import com.nexora.player.api.PlayerEngineContext
import com.nexora.player.api.PlayerEngineEvent
import com.nexora.player.api.PlayerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public class RuntimePlayerController(
    private val engine: PlayerEngine,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) : PlayerController {
    private val reducerMutex = Mutex()
    private val mutableState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    private val mutableEvents = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 64)
    private var commandSequence = 0L
    private var operationSequence = 0L
    private var generationSequence = 0L
    private var activeGeneration: PlaybackGeneration? = null
    private var activeRequest: PlaybackSessionRequest? = null
    private var activeDurationMs: Long? = null
    private var closed = false

    override val state: StateFlow<PlaybackState> = mutableState
    override val events: Flow<PlayerEvent> = mutableEvents
    override val capabilities: Set<Capability> = engine.capabilities

    init {
        engine.setCallback { event ->
            scope.launch {
                handleEngineEvent(event)
            }
        }
    }

    override suspend fun dispatch(command: PlayerCommand): PlayerCommandReceipt = reducerMutex.withLock {
        val commandId = nextCommandId()
        if (closed && command !is PlayerCommand.Close) {
            val rejected = PlayerCommandReceipt(
                commandId = commandId,
                operationId = null,
                generation = null,
                accepted = false,
                reason = "播放器已经关闭，不能再接收命令。",
            )
            emit(
                PlayerEvent.CommandRejected(
                    commandId = commandId,
                    operationId = null,
                    reason = rejected.reason.orEmpty(),
                ),
            )
            return@withLock rejected
        }

        when (command) {
            is PlayerCommand.Open -> open(command.request, command.autoPlay, commandId)
            is PlayerCommand.SwitchPlayback -> switchPlayback(command.request, commandId)
            PlayerCommand.Play -> callEngine(commandId) { context -> engine.play(context) }
            PlayerCommand.Pause -> callEngine(commandId) { context -> engine.pause(context) }
            is PlayerCommand.SeekTo -> callEngine(commandId) { context ->
                engine.seekTo(command.positionMs.coerceAtLeast(0), context)
            }
            PlayerCommand.Stop -> stop(commandId)
            PlayerCommand.Close -> close(commandId)
        }
    }

    private suspend fun open(
        request: PlaybackSessionRequest,
        autoPlay: Boolean,
        commandId: PlaybackCommandId,
    ): PlayerCommandReceipt {
        val operationId = nextOperationId()
        val generation = nextGeneration()
        activeGeneration = generation
        activeRequest = request
        activeDurationMs = null
        setState(PlaybackState.Preparing(request, generation), commandId, operationId, generation)
        emit(PlayerEvent.CommandAccepted(commandId, operationId, generation))
        engine.prepare(request, PlayerEngineContext(commandId, operationId, generation))
        if (autoPlay) {
            engine.play(PlayerEngineContext(commandId, operationId, generation))
        }
        return accepted(commandId, operationId, generation)
    }

    private suspend fun switchPlayback(
        request: PlaybackSessionRequest,
        commandId: PlaybackCommandId,
    ): PlayerCommandReceipt {
        val operationId = nextOperationId()
        val generation = nextGeneration()
        activeGeneration = generation
        activeRequest = request
        activeDurationMs = null
        setState(PlaybackState.Preparing(request, generation), commandId, operationId, generation)
        emit(PlayerEvent.CommandAccepted(commandId, operationId, generation))
        engine.prepare(request, PlayerEngineContext(commandId, operationId, generation))
        return accepted(commandId, operationId, generation)
    }

    private suspend fun callEngine(
        commandId: PlaybackCommandId,
        block: suspend (PlayerEngineContext) -> Unit,
    ): PlayerCommandReceipt {
        val generation = activeGeneration ?: return reject(commandId, "当前没有可操作的播放会话。")
        val operationId = nextOperationId()
        emit(PlayerEvent.CommandAccepted(commandId, operationId, generation))
        block(PlayerEngineContext(commandId, operationId, generation))
        return accepted(commandId, operationId, generation)
    }

    private suspend fun stop(commandId: PlaybackCommandId): PlayerCommandReceipt {
        val generation = activeGeneration
        val operationId = nextOperationId()
        emit(PlayerEvent.CommandAccepted(commandId, operationId, generation))
        if (generation != null) {
            engine.stop(PlayerEngineContext(commandId, operationId, generation))
        }
        activeGeneration = null
        activeRequest = null
        activeDurationMs = null
        setState(PlaybackState.Idle, commandId, operationId, generation)
        return accepted(commandId, operationId, generation)
    }

    private suspend fun close(commandId: PlaybackCommandId): PlayerCommandReceipt {
        val operationId = nextOperationId()
        val generation = activeGeneration
        closed = true
        activeGeneration = null
        activeRequest = null
        activeDurationMs = null
        emit(PlayerEvent.CommandAccepted(commandId, operationId, generation))
        engine.close()
        setState(PlaybackState.Released, commandId, operationId, generation)
        return accepted(commandId, operationId, generation)
    }

    private suspend fun reject(commandId: PlaybackCommandId, reason: String): PlayerCommandReceipt {
        emit(PlayerEvent.CommandRejected(commandId, null, reason))
        return PlayerCommandReceipt(
            commandId = commandId,
            operationId = null,
            generation = null,
            accepted = false,
            reason = reason,
        )
    }

    private suspend fun handleEngineEvent(event: PlayerEngineEvent) = reducerMutex.withLock {
        if (closed) {
            emit(PlayerEvent.EngineCallbackDiscarded(event.generation, activeGeneration, "播放器已经关闭。"))
            return@withLock
        }
        val generation = activeGeneration
        if (generation != event.generation) {
            emit(PlayerEvent.EngineCallbackDiscarded(event.generation, generation, "回调已过期。"))
            return@withLock
        }
        val request = activeRequest
        if (request == null) {
            emit(PlayerEvent.EngineCallbackDiscarded(event.generation, generation, "当前没有播放请求。"))
            return@withLock
        }

        val nextState = when (event) {
            is PlayerEngineEvent.Prepared -> {
                activeDurationMs = event.durationMs
                PlaybackState.Buffering(
                    request = request,
                    generation = event.generation,
                    positionMs = currentPositionMs(),
                    durationMs = activeDurationMs,
                )
            }
            is PlayerEngineEvent.Buffering -> PlaybackState.Buffering(
                request = request,
                generation = event.generation,
                positionMs = event.positionMs,
                durationMs = activeDurationMs,
            )
            is PlayerEngineEvent.Playing -> PlaybackState.Playing(
                request = request,
                generation = event.generation,
                positionMs = event.positionMs,
                durationMs = activeDurationMs,
            )
            is PlayerEngineEvent.Paused -> PlaybackState.Paused(
                request = request,
                generation = event.generation,
                positionMs = event.positionMs,
                durationMs = activeDurationMs,
            )
            is PlayerEngineEvent.SeekCompleted -> stateAfterSeek(request, event)
            is PlayerEngineEvent.Completed -> {
                activeDurationMs = event.durationMs
                PlaybackState.Completed(request, event.generation, event.durationMs)
            }
            is PlayerEngineEvent.Error -> PlaybackState.Error(
                request = request,
                generation = event.generation,
                code = event.code,
                userMessage = event.userMessage,
                retryable = event.retryable,
            )
        }
        setState(nextState, commandId = null, operationId = null, generation = event.generation)
    }

    private fun stateAfterSeek(
        request: PlaybackSessionRequest,
        event: PlayerEngineEvent.SeekCompleted,
    ): PlaybackState = when (mutableState.value) {
        is PlaybackState.Paused -> PlaybackState.Paused(
            request = request,
            generation = event.generation,
            positionMs = event.positionMs,
            durationMs = activeDurationMs,
        )
        else -> PlaybackState.Playing(
            request = request,
            generation = event.generation,
            positionMs = event.positionMs,
            durationMs = activeDurationMs,
        )
    }

    private fun currentPositionMs(): Long = when (val snapshot = mutableState.value) {
        is PlaybackState.Buffering -> snapshot.positionMs
        is PlaybackState.Playing -> snapshot.positionMs
        is PlaybackState.Paused -> snapshot.positionMs
        else -> 0L
    }

    private suspend fun setState(
        state: PlaybackState,
        commandId: PlaybackCommandId?,
        operationId: PlaybackOperationId?,
        generation: PlaybackGeneration?,
    ) {
        mutableState.value = state
        emit(PlayerEvent.StateChanged(state, commandId, operationId, generation))
    }

    private suspend fun emit(event: PlayerEvent) {
        mutableEvents.emit(event)
    }

    private fun nextCommandId(): PlaybackCommandId = PlaybackCommandId(++commandSequence)

    private fun nextOperationId(): PlaybackOperationId = PlaybackOperationId(++operationSequence)

    private fun nextGeneration(): PlaybackGeneration = PlaybackGeneration(++generationSequence)

    private fun accepted(
        commandId: PlaybackCommandId,
        operationId: PlaybackOperationId,
        generation: PlaybackGeneration?,
    ): PlayerCommandReceipt = PlayerCommandReceipt(
        commandId = commandId,
        operationId = operationId,
        generation = generation,
        accepted = true,
    )
}
