package com.nexora.feature.player

import com.nexora.player.api.PlaybackGeneration
import com.nexora.player.api.PlaybackState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerSessionResumePolicyTest {
    @Test
    fun sameSessionDoesNotRestartPlaybackAfterRotation() {
        val request = TestPlaybackRequests.sample()
        val state = PlaybackState.Playing(
            request = request,
            generation = PlaybackGeneration(1),
            positionMs = 42_000,
            durationMs = 120_000,
        )

        assertFalse(PlayerSessionResumePolicy.shouldOpen(state, request))
        assertEquals(42_000, PlayerSessionResumePolicy.restoredPositionMs(state))
    }

    @Test
    fun idleStateOpensRequestedSession() {
        assertTrue(
            PlayerSessionResumePolicy.shouldOpen(
                currentState = PlaybackState.Idle,
                request = TestPlaybackRequests.sample(),
            ),
        )
    }
}
