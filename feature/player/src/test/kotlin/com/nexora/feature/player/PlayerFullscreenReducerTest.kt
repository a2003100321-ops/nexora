package com.nexora.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerFullscreenReducerTest {
    @Test
    fun toggleEntersAndExitsLandscapeFullscreen() {
        val fullscreen = PlayerFullscreenReducer.toggle(PlayerFullscreenState())

        assertTrue(fullscreen.isFullscreen)
        assertEquals(PlayerRequestedOrientation.Landscape, fullscreen.requestedOrientation)

        val portrait = PlayerFullscreenReducer.toggle(fullscreen)

        assertFalse(portrait.isFullscreen)
        assertEquals(PlayerRequestedOrientation.Portrait, portrait.requestedOrientation)
    }

    @Test
    fun backExitsFullscreenBeforeLeavingPlayer() {
        val result = PlayerFullscreenReducer.onBack(PlayerFullscreenReducer.enterFullscreen())

        assertEquals(PlayerFullscreenReducer.exitFullscreen(), result)
        assertNull(PlayerFullscreenReducer.onBack(PlayerFullscreenState()))
    }
}
