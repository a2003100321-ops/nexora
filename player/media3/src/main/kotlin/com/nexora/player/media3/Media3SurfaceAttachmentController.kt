package com.nexora.player.media3

internal data class Media3SurfaceAttachmentSnapshot(
    val attached: Boolean = false,
    val attachCount: Int = 0,
    val detachCount: Int = 0,
)

internal class Media3SurfaceAttachmentController {
    var snapshot: Media3SurfaceAttachmentSnapshot = Media3SurfaceAttachmentSnapshot()
        private set

    fun attach() {
        if (snapshot.attached) return
        snapshot = snapshot.copy(
            attached = true,
            attachCount = snapshot.attachCount + 1,
        )
    }

    fun detach() {
        if (!snapshot.attached) return
        snapshot = snapshot.copy(
            attached = false,
            detachCount = snapshot.detachCount + 1,
        )
    }
}
