package com.nexora.feature.player

import com.nexora.player.api.PlaybackGeneration
import com.nexora.player.api.PlaybackMediaIdentity
import com.nexora.player.api.PlaybackResource
import com.nexora.player.api.PlaybackSessionId
import com.nexora.player.api.PlaybackSessionRequest
import com.nexora.player.api.PlaybackSourceAttribution
import com.nexora.player.api.PlaybackState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerScreenStateTest {
    @Test
    fun mapsPlayingStateToPauseControl() {
        val state = PlaybackState.Playing(
            request = request(),
            generation = PlaybackGeneration(1),
            positionMs = 65_000,
            durationMs = 120_000,
        ).toPlayerScreenState()

        assertEquals("Example", state.title)
        assertEquals("播放中", state.statusText)
        assertEquals("01:05", state.positionText)
        assertEquals("02:00", state.durationText)
        assertEquals("暂停", state.playPauseLabel)
        assertTrue(state.playPauseEnabled)
    }

    @Test
    fun mapsCompletedStateWithDuration() {
        val state = PlaybackState.Completed(request(), PlaybackGeneration(1), 3_665_000)
            .toPlayerScreenState()

        assertEquals("播放完成", state.statusText)
        assertEquals("1:01:05", state.durationText)
        assertEquals(1f, state.progressFraction)
        assertTrue(state.seekEnabled)
    }

    @Test
    fun mapsReleasedStateToDisabledControl() {
        val state = PlaybackState.Released.toPlayerScreenState()

        assertEquals("播放器已关闭", state.statusText)
        assertFalse(state.playPauseEnabled)
    }

    private fun request(): PlaybackSessionRequest = PlaybackSessionRequest(
        sessionId = PlaybackSessionId("test-session"),
        media = PlaybackMediaIdentity(videoId = "video", title = "Example"),
        resource = PlaybackResource("https://media.example.invalid/movie.mp4"),
        source = PlaybackSourceAttribution("source", "Source", "video"),
    )
}
