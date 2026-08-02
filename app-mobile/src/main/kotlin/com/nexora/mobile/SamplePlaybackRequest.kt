package com.nexora.mobile

import com.nexora.player.api.PlaybackLine
import com.nexora.player.api.PlaybackMediaIdentity
import com.nexora.player.api.PlaybackQuality
import com.nexora.player.api.PlaybackResource
import com.nexora.player.api.PlaybackSessionId
import com.nexora.player.api.PlaybackSessionRequest
import com.nexora.player.api.PlaybackSourceAttribution

internal fun samplePlaybackRequest(): PlaybackSessionRequest = PlaybackSessionRequest(
    sessionId = PlaybackSessionId("m4-3a-sample-session"),
    media = PlaybackMediaIdentity(
        videoId = "m4-3a-sample",
        title = "Nexora 基础播放器测试",
        episodeId = "sample-episode-1",
        episodeTitle = "测试片段",
        posterUrl = null,
    ),
    resource = PlaybackResource("https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"),
    headers = emptyMap(),
    userAgent = "Nexora/0.1.0-dev",
    line = PlaybackLine(
        id = "sample-mp4",
        name = "HTTPS MP4 示例",
        stable = true,
    ),
    quality = PlaybackQuality(
        id = "sample-720p",
        label = "720p",
        height = 720,
    ),
    source = PlaybackSourceAttribution(
        sourceKey = "nexora-sample",
        sourceName = "Nexora 内置测试源",
        vodId = "m4-3a-sample",
        rawLineId = "sample-mp4",
    ),
)
