package com.nexora.feature.player

import com.nexora.player.api.PlaybackCommandId
import com.nexora.player.api.PlaybackOperationId
import com.nexora.player.api.PlaybackSessionRequest
import com.nexora.player.api.PlaybackState
import com.nexora.player.api.PlayerCommand
import com.nexora.player.api.PlayerCommandReceipt
import com.nexora.player.api.PlayerController
import com.nexora.player.api.PlayerEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerControllerCallTest {
    @Test
    fun controllerReceivesOpenPlayPauseSeekAndStopCommands() = runTest {
        val controller = RecordingPlayerController()
        val request = TestPlaybackRequests.sample()

        controller.dispatch(PlayerCommand.Open(request))
        controller.dispatch(PlayerCommand.Pause)
        controller.dispatch(PlayerCommand.Play)
        controller.dispatch(PlayerCommand.SeekTo(1_000))
        controller.dispatch(PlayerCommand.Stop)

        assertEquals(
            listOf(
                "open:sample-session",
                "pause",
                "play",
                "seek:1000",
                "stop",
            ),
            controller.commands,
        )
    }

    private class RecordingPlayerController : PlayerController {
        val commands: MutableList<String> = mutableListOf()
        override val state: MutableStateFlow<PlaybackState> = MutableStateFlow(PlaybackState.Idle)
        override val events: Flow<PlayerEvent> = emptyFlow()
        override val capabilities = emptySet<com.nexora.player.api.Capability>()

        override suspend fun dispatch(command: PlayerCommand): PlayerCommandReceipt {
            commands += when (command) {
                is PlayerCommand.Open -> "open:${command.request.sessionId.value}"
                PlayerCommand.Pause -> "pause"
                PlayerCommand.Play -> "play"
                is PlayerCommand.SeekTo -> "seek:${command.positionMs}"
                PlayerCommand.Stop -> "stop"
                PlayerCommand.Close -> "close"
                is PlayerCommand.SwitchPlayback -> "switch:${command.request.sessionId.value}"
            }
            return PlayerCommandReceipt(
                commandId = PlaybackCommandId(commands.size.toLong()),
                operationId = PlaybackOperationId(commands.size.toLong()),
                generation = null,
                accepted = true,
            )
        }
    }
}
