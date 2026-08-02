package com.nexora.player.media3

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.nexora.player.api.PlaybackSessionRequest
import java.net.URI

internal class Media3MediaItemMapper(
    private val securityPolicy: Media3PlaybackSecurityPolicy = Media3PlaybackSecurityPolicy(),
    private val mediaItemFactory: Media3MediaItemFactory = DefaultMedia3MediaItemFactory,
) {
    public fun map(request: PlaybackSessionRequest): Media3MappedItem {
        val dataSourceRequest = securityPolicy.buildDataSourceRequest(request)
        val mimeType = inferMimeType(dataSourceRequest.url)
        val mediaItem = mediaItemFactory.create(
            mediaId = request.sessionId.value,
            url = dataSourceRequest.url,
            mimeType = mimeType,
        )
        return Media3MappedItem(
            mediaItem = mediaItem,
            dataSourceRequest = dataSourceRequest,
            mimeType = mimeType,
        )
    }

    private fun inferMimeType(url: String): String? {
        val path = URI(url).path.lowercase()
        return when {
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            path.endsWith(".mp4") || path.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
            else -> null
        }
    }
}

internal data class Media3MappedItem(
    val mediaItem: MediaItem,
    val dataSourceRequest: Media3DataSourceRequest,
    val mimeType: String?,
)

internal fun interface Media3MediaItemFactory {
    fun create(mediaId: String, url: String, mimeType: String?): MediaItem
}

private object DefaultMedia3MediaItemFactory : Media3MediaItemFactory {
    override fun create(mediaId: String, url: String, mimeType: String?): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(url)
            .setMimeType(mimeType)
            .build()
}
