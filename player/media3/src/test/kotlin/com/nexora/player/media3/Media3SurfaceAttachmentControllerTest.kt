package com.nexora.player.media3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Media3SurfaceAttachmentControllerTest {
    @Test
    fun attachAndDetachAreIdempotent() {
        val controller = Media3SurfaceAttachmentController()

        controller.attach()
        controller.attach()

        assertTrue(controller.snapshot.attached)
        assertEquals(1, controller.snapshot.attachCount)
        assertEquals(0, controller.snapshot.detachCount)

        controller.detach()
        controller.detach()

        assertFalse(controller.snapshot.attached)
        assertEquals(1, controller.snapshot.attachCount)
        assertEquals(1, controller.snapshot.detachCount)
    }
}
