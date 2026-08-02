package com.nexora.feature.player

public enum class PlayerRequestedOrientation {
    Portrait,
    Landscape,
}

public data class PlayerFullscreenState(
    val isFullscreen: Boolean = false,
    val requestedOrientation: PlayerRequestedOrientation = PlayerRequestedOrientation.Portrait,
)

public object PlayerFullscreenReducer {
    public fun enterFullscreen(): PlayerFullscreenState = PlayerFullscreenState(
        isFullscreen = true,
        requestedOrientation = PlayerRequestedOrientation.Landscape,
    )

    public fun exitFullscreen(): PlayerFullscreenState = PlayerFullscreenState(
        isFullscreen = false,
        requestedOrientation = PlayerRequestedOrientation.Portrait,
    )

    public fun toggle(current: PlayerFullscreenState): PlayerFullscreenState =
        if (current.isFullscreen) exitFullscreen() else enterFullscreen()

    public fun onBack(current: PlayerFullscreenState): PlayerFullscreenState? =
        if (current.isFullscreen) exitFullscreen() else null
}
