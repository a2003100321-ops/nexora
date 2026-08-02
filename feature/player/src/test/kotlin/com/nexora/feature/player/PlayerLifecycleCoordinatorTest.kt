package com.nexora.feature.player

import com.nexora.player.api.PlaybackGeneration
import com.nexora.player.api.PlaybackMediaIdentity
import com.nexora.player.api.PlaybackResource
import com.nexora.player.api.PlaybackSessionId
import com.nexora.player.api.PlaybackSessionRequest
import com.nexora.player.api.PlaybackSourceAttribution
import com.nexora.player.api.PlaybackState
import com.nexora.player.api.PlayerCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerLifecycleCoordinatorTest {
    @Test
    fun backReturnsStopCommand() {
        val coordinator = PlayerLifecycleCoordinator()

        val command = coordinator.onEvent(PlayerScreenLifecycleEvent.Back)

        assertEquals(PlayerCommand.Stop, command)
        assertTrue(coordinator.snapshot.closedByBack)
    }

    @Test
    fun configurationChangeKeepsPlaybackState() {
        val coordinator = PlayerLifecycleCoordinator()
        val state = PlaybackState.Playing(request(), PlaybackGeneration(1), 12_000)

        coordinator.onStateChanged(state)
        val command = coordinator.onEvent(PlayerScreenLifecycleEvent.ConfigurationChanged)

        assertNull(command)
        assertEquals(state, coordinator.snapshot.lastState)
        assertTrue(coordinator.snapshot.entered)
    }

    @Test
    fun backgroundAndResumeDoNotStopPlayback() {
        val coordinator = PlayerLifecycleCoordinator()

        assertNull(coordinator.onEvent(PlayerScreenLifecycleEvent.AppBackgrounded))
        assertFalse(coordinator.snapshot.appInForeground)
        assertNull(coordinator.onEvent(PlayerScreenLifecycleEvent.AppResumed))
        assertTrue(coordinator.snapshot.appInForeground)
    }

    private fun request(): PlaybackSessionRequest = PlaybackSessionRequest(
        sessionId = PlaybackSessionId("test-session"),
        media = PlaybackMediaIdentity(videoId = "video", title = "Example"),
        resource = PlaybackResource("https://media.example.invalid/movie.mp4"),
        source = PlaybackSourceAttribution("source", "Source", "video"),
    )
}
