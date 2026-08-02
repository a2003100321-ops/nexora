package com.nexora.feature.player

import com.nexora.player.api.PlaybackState
import com.nexora.player.api.PlayerCommand

public enum class PlayerScreenLifecycleEvent {
    Enter,
    Back,
    ConfigurationChanged,
    AppBackgrounded,
    AppResumed,
}

public data class PlayerScreenLifecycleSnapshot(
    val entered: Boolean = false,
    val appInForeground: Boolean = true,
    val closedByBack: Boolean = false,
    val lastState: PlaybackState = PlaybackState.Idle,
)

public class PlayerLifecycleCoordinator {
    public var snapshot: PlayerScreenLifecycleSnapshot = PlayerScreenLifecycleSnapshot()
        private set

    public fun onStateChanged(state: PlaybackState) {
        snapshot = snapshot.copy(lastState = state)
    }

    public fun onEvent(event: PlayerScreenLifecycleEvent): PlayerCommand? {
        snapshot = when (event) {
            PlayerScreenLifecycleEvent.Enter -> snapshot.copy(entered = true, closedByBack = false)
            PlayerScreenLifecycleEvent.Back -> snapshot.copy(closedByBack = true)
            PlayerScreenLifecycleEvent.ConfigurationChanged -> snapshot.copy(entered = true)
            PlayerScreenLifecycleEvent.AppBackgrounded -> snapshot.copy(appInForeground = false)
            PlayerScreenLifecycleEvent.AppResumed -> snapshot.copy(appInForeground = true)
        }
        return when (event) {
            PlayerScreenLifecycleEvent.Back -> PlayerCommand.Stop
            PlayerScreenLifecycleEvent.Enter,
            PlayerScreenLifecycleEvent.ConfigurationChanged,
            PlayerScreenLifecycleEvent.AppBackgrounded,
            PlayerScreenLifecycleEvent.AppResumed,
            -> null
        }
    }
}
