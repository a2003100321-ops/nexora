package com.nexora.player.media3

import androidx.media3.common.Player
import com.nexora.player.api.PlayerBackendDescriptor

public object Media3Backend : PlayerBackendDescriptor {
    override val id: String = "media3"
    override val productionReady: Boolean = false

    internal fun recognizes(player: Player): Boolean = player.applicationLooper.thread.isAlive
}
