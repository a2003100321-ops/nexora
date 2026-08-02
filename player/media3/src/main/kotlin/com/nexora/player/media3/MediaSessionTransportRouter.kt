package com.nexora.player.media3

internal sealed interface MediaSessionTransportCommand {
    data object Play : MediaSessionTransportCommand
    data object Pause : MediaSessionTransportCommand
    data class SeekTo(val positionMs: Long) : MediaSessionTransportCommand
    data object Stop : MediaSessionTransportCommand
    data object SkipPrevious : MediaSessionTransportCommand
    data object SkipNext : MediaSessionTransportCommand
}

internal interface MediaSessionTransport {
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun stop()
    fun skipToPrevious()
    fun skipToNext()
}

internal class MediaSessionTransportRouter(
    private val transport: MediaSessionTransport,
) {
    fun dispatch(command: MediaSessionTransportCommand) {
        when (command) {
            MediaSessionTransportCommand.Play -> transport.play()
            MediaSessionTransportCommand.Pause -> transport.pause()
            is MediaSessionTransportCommand.SeekTo -> transport.seekTo(command.positionMs.coerceAtLeast(0))
            MediaSessionTransportCommand.Stop -> transport.stop()
            MediaSessionTransportCommand.SkipPrevious -> transport.skipToPrevious()
            MediaSessionTransportCommand.SkipNext -> transport.skipToNext()
        }
    }
}
