package com.nexora.player.runtime

import com.nexora.player.api.PlaybackGeneration
import com.nexora.player.api.PlaybackMediaIdentity
import com.nexora.player.api.PlaybackResource
import com.nexora.player.api.PlaybackSessionId
import com.nexora.player.api.PlaybackSessionRequest
import com.nexora.player.api.PlaybackSourceAttribution
import com.nexora.player.api.PlaybackState
import com.nexora.player.api.PlayerCommand
import com.nexora.player.api.PlayerErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimePlayerControllerTest {
    @Test
    fun openMovesFromIdleToPreparing() = runTest {
        val engine = FakePlayerEngine()
        val controller = RuntimePlayerController(engine, this)

        val receipt = controller.dispatch(PlayerCommand.Open(request("one")))

        assertTrue(receipt.accepted)
        assertEquals(PlaybackGeneration(1), receipt.generation)
        assertIs<PlaybackState.Preparing>(controller.state.value)
        assertEquals(1, engine.preparedRequests)
        assertEquals(1, engine.playRequests)
    }

    @Test
    fun preparingCanBecomePlaying() = runTest {
        val engine = FakePlayerEngine()
        val controller = RuntimePlayerController(engine, this)
        controller.dispatch(PlayerCommand.Open(request("one")))
        engine.prepared(durationMs = 180_000)

        engine.playing(positionMs = 12_000)
        advanceUntilIdle()

        val state = assertIs<PlaybackState.Playing>(controller.state.value)
        assertEquals(12_000, state.positionMs)
        assertEquals(180_000, state.durationMs)
    }

    @Test
    fun playingCanPause() = runTest {
        val engine = FakePlayerEngine()
        val controller = RuntimePlayerController(engine, this)
        controller.dispatch(PlayerCommand.Open(request("one")))
        engine.playing(positionMs = 30_000)
        advanceUntilIdle()

        controller.dispatch(PlayerCommand.Pause)
        engine.paused(positionMs = 30_500)
        advanceUntilIdle()

        val state = assertIs<PlaybackState.Paused>(controller.state.value)
        assertEquals(30_500, state.positionMs)
        assertEquals(1, engine.pauseRequests)
    }

    @Test
    fun bufferingCanRecoverToPlaying() = runTest {
        val engine = FakePlayerEngine()
        val controller = RuntimePlayerController(engine, this)
        controller.dispatch(PlayerCommand.Open(request("one")))
        engine.playing(positionMs = 10_000)
        advanceUntilIdle()

        engine.buffering(positionMs = 10_500)
        advanceUntilIdle()
        assertIs<PlaybackState.Buffering>(controller.state.value)

        engine.playing(positionMs = 11_000)
        advanceUntilIdle()

        val state = assertIs<PlaybackState.Playing>(controller.state.value)
        assertEquals(11_000, state.positionMs)
    }

    @Test
    fun retryAfterErrorCreatesANewGeneration() = runTest {
        val engine = FakePlayerEngine()
        val controller = RuntimePlayerController(engine, this)
        val original = request("one")
        controller.dispatch(PlayerCommand.Open(original))

        engine.error(PlayerErrorCode.NETWORK, "网络中断", retryable = true)
        advanceUntilIdle()
        assertIs<PlaybackState.Error>(controller.state.value)

        val retry = controller.dispatch(PlayerCommand.Open(original))

        assertTrue(retry.accepted)
        assertEquals(PlaybackGeneration(2), retry.generation)
        assertIs<PlaybackState.Preparing>(controller.state.value)
    }

    @Test
    fun staleCallbackFromOlderPlaybackCannotOverrideNewPlayback() = runTest {
        val engine = FakePlayerEngine()
        val controller = RuntimePlayerController(engine, this)
        val first = controller.dispatch(PlayerCommand.Open(request("one"))).generation!!
        val second = controller.dispatch(PlayerCommand.Open(request("two"))).generation!!

        engine.error(
            code = PlayerErrorCode.DECODER,
            userMessage = "旧请求错误",
            generation = first,
        )
        advanceUntilIdle()

        val state = assertIs<PlaybackState.Preparing>(controller.state.value)
        assertEquals(second, state.generation)
        assertEquals("video-two", state.request.media.videoId)
    }

    @Test
    fun switchPlaybackGenerationDiscardsOldLineResult() = runTest {
        val engine = FakePlayerEngine()
        val controller = RuntimePlayerController(engine, this)
        val first = controller.dispatch(PlayerCommand.Open(request("line-a"))).generation!!
        val second = controller.dispatch(PlayerCommand.SwitchPlayback(request("line-b"))).generation!!

        engine.playing(positionMs = 99_000, generation = first)
        advanceUntilIdle()
        assertIs<PlaybackState.Preparing>(controller.state.value)

        engine.playing(positionMs = 0, generation = second)
        advanceUntilIdle()

        val state = assertIs<PlaybackState.Playing>(controller.state.value)
        assertEquals(second, state.generation)
        assertEquals("video-line-b", state.request.media.videoId)
    }

    @Test
    fun closeRejectsLaterCommands() = runTest {
        val engine = FakePlayerEngine()
        val controller = RuntimePlayerController(engine, this)
        controller.dispatch(PlayerCommand.Open(request("one")))

        val close = controller.dispatch(PlayerCommand.Close)
        val afterClose = controller.dispatch(PlayerCommand.Play)

        assertTrue(close.accepted)
        assertFalse(afterClose.accepted)
        assertEquals("播放器已经关闭，不能再接收命令。", afterClose.reason)
        assertIs<PlaybackState.Released>(controller.state.value)
        assertEquals(1, engine.closeRequests)
    }

    @Test
    fun completedEventMovesToCompletedState() = runTest {
        val engine = FakePlayerEngine()
        val controller = RuntimePlayerController(engine, this)
        controller.dispatch(PlayerCommand.Open(request("one")))

        engine.completed(durationMs = 120_000)
        advanceUntilIdle()

        val state = assertIs<PlaybackState.Completed>(controller.state.value)
        assertEquals(120_000, state.durationMs)
    }

    private fun request(key: String): PlaybackSessionRequest = PlaybackSessionRequest(
        sessionId = PlaybackSessionId("session-$key"),
        media = PlaybackMediaIdentity(
            videoId = "video-$key",
            title = "测试影片 $key",
            episodeId = "episode-$key",
        ),
        resource = PlaybackResource("https://media.example.invalid/$key.m3u8"),
        headers = mapOf("User-Agent" to "NexoraTest"),
        userAgent = "NexoraTest",
        source = PlaybackSourceAttribution(
            sourceKey = "source-$key",
            sourceName = "测试源",
            vodId = "vod-$key",
        ),
    )
}
