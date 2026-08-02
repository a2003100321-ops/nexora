package com.nexora.player.media3

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.nexora.player.api.Capability
import com.nexora.player.api.PlaybackCommandId
import com.nexora.player.api.PlaybackGeneration
import com.nexora.player.api.PlaybackMediaIdentity
import com.nexora.player.api.PlaybackOperationId
import com.nexora.player.api.PlaybackResource
import com.nexora.player.api.PlaybackSessionId
import com.nexora.player.api.PlaybackSessionRequest
import com.nexora.player.api.PlaybackSourceAttribution
import com.nexora.player.api.PlayerEngine
import com.nexora.player.api.PlayerEngineCallback
import com.nexora.player.api.PlayerEngineContext
import com.nexora.player.api.PlayerEngineEvent
import com.nexora.player.api.PlayerErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

@UnstableApi
class Media3EngineTest {
    @Test
    fun fakeEngineAndMedia3EngineShareTheMinimalPlayerEngineContract() = runTest {
        val fakeEngine = ContractFakeEngine()
        val fakeHarness = ContractHarness(
            engine = fakeEngine,
            markReady = fakeEngine::ready,
            markPlaying = fakeEngine::playing,
            markPaused = fakeEngine::paused,
            markSeekCompleted = fakeEngine::seekCompleted,
        )
        val media3Adapter = FakeMedia3PlayerAdapter()
        val media3Harness = ContractHarness(
            engine = testEngine(media3Adapter),
            markReady = media3Adapter::ready,
            markPlaying = { media3Adapter.playing() },
            markPaused = { media3Adapter.paused() },
            markSeekCompleted = { media3Adapter.seekCompleted(7_000) },
        )

        listOf(fakeHarness, media3Harness).forEach { harness ->
            harness.engine.setCallback(harness.callback)

            harness.engine.prepare(request("contract.mp4"), context())
            harness.markReady()
            harness.engine.play(context())
            harness.markPlaying()
            harness.engine.pause(context())
            harness.markPaused()
            harness.engine.seekTo(7_000, context())
            harness.markSeekCompleted()
            harness.engine.close()

            assertIs<PlayerEngineEvent.Prepared>(harness.events[0])
            assertIs<PlayerEngineEvent.Playing>(harness.events[1])
            assertIs<PlayerEngineEvent.Paused>(harness.events[2])
            assertIs<PlayerEngineEvent.SeekCompleted>(harness.events[3])
        }
    }

    @Test
    fun prepareBuildsMp4MediaItem() = runTest {
        val adapter = FakeMedia3PlayerAdapter()
        val engine = testEngine(adapter)

        engine.prepare(request("movie.mp4"), context())

        assertEquals(MimeTypes.VIDEO_MP4, adapter.mappedItem?.mimeType)
        assertEquals("https://media.example.invalid/movie.mp4", adapter.mappedItem?.dataSourceRequest?.url)
    }

    @Test
    fun prepareBuildsHlsMediaItem() = runTest {
        val adapter = FakeMedia3PlayerAdapter()
        val engine = testEngine(adapter)

        engine.prepare(request("playlist.m3u8"), context())

        assertEquals(MimeTypes.APPLICATION_M3U8, adapter.mappedItem?.mimeType)
    }

    @Test
    fun prepareBuildsDashMediaItem() = runTest {
        val adapter = FakeMedia3PlayerAdapter()
        val engine = testEngine(adapter)

        engine.prepare(request("manifest.mpd"), context())

        assertEquals(MimeTypes.APPLICATION_MPD, adapter.mappedItem?.mimeType)
    }

    @Test
    fun errorMappingConvertsMedia3ExceptionToNexoraErrorCode() = runTest {
        val adapter = FakeMedia3PlayerAdapter()
        val events = mutableListOf<PlayerEngineEvent>()
        val engine = testEngine(adapter)
        engine.setCallback { events += it }
        engine.prepare(request("movie.mp4"), context())

        adapter.fail(androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)

        val error = assertIs<PlayerEngineEvent.Error>(events.single())
        assertEquals(PlayerErrorCode.NETWORK, error.code)
        assertEquals(PlaybackGeneration(1), error.generation)
    }

    @Test
    fun releaseStopsCallbacksAndRejectsLaterCommands() = runTest {
        val adapter = FakeMedia3PlayerAdapter()
        val events = mutableListOf<PlayerEngineEvent>()
        val engine = testEngine(adapter)
        engine.setCallback { events += it }
        engine.prepare(request("movie.mp4"), context())

        engine.close()
        adapter.ready()

        assertTrue(adapter.released)
        assertTrue(events.isEmpty())
        assertFailsWith<IllegalStateException> {
            engine.play(context())
        }
    }

    @Test
    fun securityRejectsCleartextUrlAndUnsafeHeadersBeforeDataSourceReceivesThem() = runTest {
        val adapter = FakeMedia3PlayerAdapter()
        val engine = testEngine(adapter)

        assertFailsWith<IllegalArgumentException> {
            engine.prepare(request("http://media.example.invalid/movie.mp4", absolute = true), context())
        }
        assertFailsWith<IllegalArgumentException> {
            engine.prepare(
                request("movie.mp4", headers = mapOf("Host" to "media.example.invalid")),
                context(),
            )
        }
        assertNull(adapter.mappedItem)
    }

    @Test
    fun securityAcceptsCookieAndCredentialProvidersThroughPolicy() = runTest {
        val adapter = FakeMedia3PlayerAdapter()
        val mapper = Media3MediaItemMapper(
            securityPolicy = Media3PlaybackSecurityPolicy(
                cookieProvider = PlaybackCookieProvider { "session=redacted" },
                credentialProvider = PlaybackCredentialProvider { mapOf("Authorization" to "Bearer redacted") },
            ),
            mediaItemFactory = FakeMediaItemFactory,
        )
        val engine = Media3Engine(adapter, mapper)

        engine.prepare(
            request(
                "movie.mp4",
                cookiePolicy = com.nexora.player.api.PlaybackCookiePolicy.InMemory(setOf("session")),
            ),
            context(),
        )

        assertEquals("session=redacted", adapter.mappedItem?.dataSourceRequest?.requestHeaders?.get("Cookie"))
        assertEquals("Bearer redacted", adapter.mappedItem?.dataSourceRequest?.requestHeaders?.get("Authorization"))
    }

    private fun testEngine(adapter: FakeMedia3PlayerAdapter): Media3Engine = Media3Engine(
        adapter = adapter,
        mapper = Media3MediaItemMapper(mediaItemFactory = FakeMediaItemFactory),
    )

    private fun context(): PlayerEngineContext = PlayerEngineContext(
        commandId = PlaybackCommandId(1),
        operationId = PlaybackOperationId(1),
        generation = PlaybackGeneration(1),
    )

    private fun request(
        path: String,
        absolute: Boolean = false,
        headers: Map<String, String> = mapOf("User-Agent" to "NexoraTest"),
        cookiePolicy: com.nexora.player.api.PlaybackCookiePolicy =
            com.nexora.player.api.PlaybackCookiePolicy.None,
    ): PlaybackSessionRequest {
        val url = if (absolute) path else "https://media.example.invalid/$path"
        return PlaybackSessionRequest(
            sessionId = PlaybackSessionId("session-$path"),
            media = PlaybackMediaIdentity(
                videoId = "video-$path",
                title = "Test $path",
            ),
            resource = PlaybackResource(url),
            headers = headers,
            cookiePolicy = cookiePolicy,
            source = PlaybackSourceAttribution(
                sourceKey = "source",
                sourceName = "Source",
                vodId = "vod",
            ),
        )
    }
}

@UnstableApi
private class FakeMedia3PlayerAdapter : Media3PlayerAdapter {
    private var listener: Media3PlayerAdapterListener? = null
    var mappedItem: Media3MappedItem? = null
        private set
    var released: Boolean = false
        private set
    var playRequests: Int = 0
        private set
    var pauseRequests: Int = 0
        private set
    var seekRequests: Int = 0
        private set

    override var currentPositionMs: Long = 0
    override var durationMs: Long? = 120_000

    override fun setListener(listener: Media3PlayerAdapterListener?) {
        this.listener = listener
    }

    override fun prepare(mappedItem: Media3MappedItem) {
        this.mappedItem = mappedItem
    }

    override fun play() {
        playRequests += 1
    }

    override fun pause() {
        pauseRequests += 1
    }

    override fun seekTo(positionMs: Long) {
        seekRequests += 1
        currentPositionMs = positionMs
    }

    override fun stop() = Unit

    override fun release() {
        released = true
    }

    fun ready() {
        listener?.onPlaybackStateChanged(Player.STATE_READY)
    }

    fun playing(positionMs: Long = currentPositionMs) {
        currentPositionMs = positionMs
        listener?.onIsPlayingChanged(true)
    }

    fun paused(positionMs: Long = currentPositionMs) {
        currentPositionMs = positionMs
        listener?.onIsPlayingChanged(false)
    }

    fun seekCompleted(positionMs: Long) {
        currentPositionMs = positionMs
        listener?.onIsPlayingChanged(true)
    }

    fun fail(errorCode: Int) {
        listener?.onPlayerError(Media3PlaybackFailure(errorCode))
    }
}

private object FakeMediaItemFactory : Media3MediaItemFactory {
    override fun create(
        mediaId: String,
        url: String,
        mimeType: String?,
        title: String,
        artworkUrl: String?,
    ): MediaItem = MediaItem.EMPTY
}

private class ContractFakeEngine : PlayerEngine {
    private var callback: PlayerEngineCallback? = null
    private var generation: PlaybackGeneration = PlaybackGeneration(1)

    override val capabilities: Set<Capability> = setOf(
        Capability.PREPARE,
        Capability.PLAY,
        Capability.PAUSE,
        Capability.SEEK,
        Capability.STOP,
    )

    override fun setCallback(callback: PlayerEngineCallback?) {
        this.callback = callback
    }

    override suspend fun prepare(request: PlaybackSessionRequest, context: PlayerEngineContext) {
        generation = context.generation
    }

    override suspend fun play(context: PlayerEngineContext) {
        generation = context.generation
    }

    override suspend fun pause(context: PlayerEngineContext) {
        generation = context.generation
    }

    override suspend fun seekTo(positionMs: Long, context: PlayerEngineContext) {
        generation = context.generation
    }

    override suspend fun stop(context: PlayerEngineContext) = Unit

    override suspend fun close() {
        callback = null
    }

    fun ready() {
        callback?.onEngineEvent(PlayerEngineEvent.Prepared(generation, 120_000))
    }

    fun playing() {
        callback?.onEngineEvent(PlayerEngineEvent.Playing(generation, 0))
    }

    fun paused() {
        callback?.onEngineEvent(PlayerEngineEvent.Paused(generation, 0))
    }

    fun seekCompleted() {
        callback?.onEngineEvent(PlayerEngineEvent.SeekCompleted(generation, 7_000))
    }
}

@UnstableApi
private class ContractHarness(
    val engine: PlayerEngine,
    val markReady: () -> Unit,
    val markPlaying: () -> Unit,
    val markPaused: () -> Unit,
    val markSeekCompleted: () -> Unit,
) {
    val events: MutableList<PlayerEngineEvent> = mutableListOf()
    val callback: PlayerEngineCallback = PlayerEngineCallback { events += it }
}
