package com.nexora.player.media3

import com.nexora.player.api.PlayerBackendDescriptor

public object Media3Backend : PlayerBackendDescriptor {
    override val id: String = "media3"
    override val productionReady: Boolean = false
}
