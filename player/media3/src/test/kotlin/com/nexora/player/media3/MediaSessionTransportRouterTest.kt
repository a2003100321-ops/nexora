package com.nexora.player.media3

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaSessionTransportRouterTest {
    @Test
    fun routesMediaSessionTransportCommands() {
        val transport = RecordingTransport()
        val router = MediaSessionTransportRouter(transport)

        router.dispatch(MediaSessionTransportCommand.Play)
        router.dispatch(MediaSessionTransportCommand.Pause)
        router.dispatch(MediaSessionTransportCommand.SeekTo(-1))
        router.dispatch(MediaSessionTransportCommand.Stop)
        router.dispatch(MediaSessionTransportCommand.SkipPrevious)
        router.dispatch(MediaSessionTransportCommand.SkipNext)

        assertEquals(
            listOf("play", "pause", "seek:0", "stop", "previous", "next"),
            transport.calls,
        )
    }

    private class RecordingTransport : MediaSessionTransport {
        val calls: MutableList<String> = mutableListOf()

        override fun play() {
            calls += "play"
        }

        override fun pause() {
            calls += "pause"
        }

        override fun seekTo(positionMs: Long) {
            calls += "seek:$positionMs"
        }

        override fun stop() {
            calls += "stop"
        }

        override fun skipToPrevious() {
            calls += "previous"
        }

        override fun skipToNext() {
            calls += "next"
        }
    }
}
