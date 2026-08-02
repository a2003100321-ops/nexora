package com.nexora.player.media3

import com.nexora.player.api.PlaybackGeneration
import com.nexora.player.api.PlaybackMediaIdentity
import com.nexora.player.api.PlaybackResource
import com.nexora.player.api.PlaybackSessionId
import com.nexora.player.api.PlaybackSessionRequest
import com.nexora.player.api.PlaybackSourceAttribution
import com.nexora.player.api.PlaybackState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MediaSessionLifecycleCoordinatorTest {
    @Test
    fun appEnteringBackgroundKeepsPlaybackSnapshotAvailable() {
        val coordinator = MediaSessionLifecycleCoordinator()

        coordinator.onPlaybackStateChanged(PlaybackState.Playing(request(), PlaybackGeneration(4), 42_000))
        coordinator.onAppEnteredBackground()

        assertFalse(coordinator.status.appInForeground)
        assertTrue(coordinator.status.serviceActive)
        assertEquals(42_000, coordinator.status.snapshot?.positionMs)
        assertTrue(coordinator.status.snapshot?.playing == true)
    }

    @Test
    fun appResumeDoesNotDiscardPlaybackSnapshot() {
        val coordinator = MediaSessionLifecycleCoordinator()

        coordinator.onPlaybackStateChanged(PlaybackState.Paused(request(), PlaybackGeneration(4), 12_000))
        coordinator.onAppEnteredBackground()
        coordinator.onAppResumed()

        assertTrue(coordinator.status.appInForeground)
        assertEquals(12_000, coordinator.status.snapshot?.positionMs)
        assertFalse(coordinator.status.snapshot?.playing ?: true)
    }

    @Test
    fun serviceDestroyedMarksRestorePendingWhenSnapshotExists() {
        val coordinator = MediaSessionLifecycleCoordinator()

        coordinator.onPlaybackStateChanged(PlaybackState.Playing(request(), PlaybackGeneration(4), 42_000))
        coordinator.onServiceDestroyed()

        assertFalse(coordinator.status.serviceActive)
        assertTrue(coordinator.status.restorePending)
    }

    @Test
    fun serviceRecreatedReturnsSnapshotForStateRecovery() {
        val coordinator = MediaSessionLifecycleCoordinator()

        coordinator.onPlaybackStateChanged(PlaybackState.Playing(request(), PlaybackGeneration(4), 42_000))
        coordinator.onServiceDestroyed()
        val snapshot = coordinator.onServiceRecreated()

        assertTrue(coordinator.status.serviceActive)
        assertFalse(coordinator.status.restorePending)
        assertNotNull(snapshot)
        assertEquals(PlaybackSessionId("session"), snapshot.sessionId)
        assertEquals("Example", snapshot.title)
        assertEquals(42_000, snapshot.positionMs)
    }

    private fun request(): PlaybackSessionRequest = PlaybackSessionRequest(
        sessionId = PlaybackSessionId("session"),
        media = PlaybackMediaIdentity(videoId = "vod", title = "Example"),
        resource = PlaybackResource("https://media.example.invalid/movie.mp4"),
        source = PlaybackSourceAttribution(
            sourceKey = "source",
            sourceName = "Source",
            vodId = "vod",
        ),
    )
}
