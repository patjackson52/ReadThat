package dev.readthat.client

import dev.readthat.data.db.PendingPostEntity
import dev.readthat.shared.LocalPostMedia
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class PendingMediaDescriptorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun currentSharedDescriptorRoundTrips() {
        val expected = PendingMediaDescriptor(
            LocalPostMedia("photo.jpg", "image/jpeg", "/shared/photo.jpg", 41, 8, 6),
            remoteMediaId = "media-current",
        )
        val pending = pending(json.encodeToString(listOf(expected)))

        assertEquals(listOf(expected), decodePendingMediaDescriptors(pending, json))
    }

    @Test
    fun matureAndroidDescriptorRemainsDrainable() {
        val pending = pending(
            """[
                {
                  "name":"legacy.jpg",
                  "contentType":"image/jpeg",
                  "localPath":"/legacy/photo.jpg",
                  "byteSize":73,
                  "width":12,
                  "height":9,
                  "remoteMediaId":"media-legacy"
                }
            ]""".trimIndent(),
        )

        val decoded = decodePendingMediaDescriptors(pending, json).single()
        assertEquals("legacy.jpg", decoded.media.name)
        assertEquals("image/jpeg", decoded.media.mimeType)
        assertEquals("/legacy/photo.jpg", decoded.media.localPath)
        assertEquals(73L, decoded.media.byteSize)
        assertEquals("media-legacy", decoded.remoteMediaId)
    }

    @Test
    fun scalarCoverColumnsRemainTheFinalUpgradeFallback() {
        val pending = pending("[]").copy(
            localPath = "/oldest/photo.jpg",
            contentType = "image/jpeg",
            byteSize = 99,
            width = 4,
            height = 3,
            mediaId = "media-scalar",
        )

        val decoded = decodePendingMediaDescriptors(pending, json).single()
        assertEquals("image/jpeg", decoded.media.mimeType)
        assertEquals("/oldest/photo.jpg", decoded.media.localPath)
        assertEquals("media-scalar", decoded.remoteMediaId)
    }

    private fun pending(mediaItemsJson: String) = PendingPostEntity(
        mutationId = "mutation",
        accountId = "account",
        subreddit = "readthat",
        kind = "Image",
        title = "Title",
        body = "",
        linkUrl = "",
        localPath = null,
        contentType = null,
        byteSize = null,
        width = null,
        height = null,
        durationSeconds = null,
        mediaId = null,
        state = "queued",
        remotePostId = null,
        lastError = null,
        createdAt = 1,
        mediaItemsJson = mediaItemsJson,
    )
}
