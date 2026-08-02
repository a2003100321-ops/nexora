package com.nexora.feature.player

import com.nexora.player.api.PlaybackMediaIdentity
import com.nexora.player.api.PlaybackResource
import com.nexora.player.api.PlaybackSessionId
import com.nexora.player.api.PlaybackSessionRequest
import com.nexora.player.api.PlaybackSourceAttribution

internal object TestPlaybackRequests {
    fun sample(): PlaybackSessionRequest = PlaybackSessionRequest(
        sessionId = PlaybackSessionId("sample-session"),
        media = PlaybackMediaIdentity(videoId = "sample-video", title = "Sample Video"),
        resource = PlaybackResource("https://media.example.invalid/sample.mp4"),
        source = PlaybackSourceAttribution(
            sourceKey = "sample",
            sourceName = "Sample",
            vodId = "sample-video",
        ),
    )
}
