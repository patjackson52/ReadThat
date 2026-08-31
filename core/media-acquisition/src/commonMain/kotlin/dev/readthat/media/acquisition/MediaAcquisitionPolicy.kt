package dev.readthat.media.acquisition

import dev.readthat.shared.LocalPostMedia
import dev.readthat.shared.PostKind

/**
 * Cross-platform product policy for native media acquisition.
 *
 * Hosts retain the system picker and metadata decoder appropriate to the device. This contract is
 * the single source of truth for what may enter shared draft/outbox state after that native step.
 */
data class MediaAcquisitionPolicy(
    val identifier: String,
    val postKind: PostKind,
    val maximumItems: Int,
    val maximumBytesPerItem: Long,
    val maximumPixelDimension: Int? = null,
    val acceptedMimePrefix: String,
    val defaultMimeType: String,
    val defaultFileExtension: String,
    val tooManyItemsMessage: String,
    val tooLargeMessage: String,
    val maximumDimensionMessage: String? = null,
) {
    init {
        require(identifier.isNotBlank())
        require(postKind == PostKind.Image || postKind == PostKind.Video)
        require(maximumItems > 0)
        require(maximumBytesPerItem > 0)
        require(maximumPixelDimension == null || maximumPixelDimension > 0)
        require(acceptedMimePrefix.endsWith('/'))
        require(defaultMimeType.startsWith(acceptedMimePrefix))
        require(defaultFileExtension.isNotBlank())
        require(tooManyItemsMessage.isNotBlank())
        require(tooLargeMessage.isNotBlank())
        require((maximumPixelDimension == null) == (maximumDimensionMessage == null))
    }

    fun acceptsMimeType(mimeType: String): Boolean =
        mimeType.trim().lowercase().startsWith(acceptedMimePrefix)

    /** Rejects malformed native results before they become durable shared draft state. */
    fun validate(media: LocalPostMedia): LocalPostMedia = media.also {
        require(it.name.isNotBlank()) { "The selected media has no file name" }
        require(it.localPath.isNotBlank()) { "The selected media is unavailable" }
        require(acceptsMimeType(it.mimeType)) {
            if (postKind == PostKind.Image) "The selected file is not a supported image"
            else "The selected file is not a supported video"
        }
        require(it.byteSize in 1..maximumBytesPerItem) {
            if (it.byteSize <= 0L) "The selected media is empty" else tooLargeMessage
        }
        it.width?.let { width -> require(width > 0) { "The selected media has an invalid width" } }
        it.height?.let { height -> require(height > 0) { "The selected media has an invalid height" } }
        maximumPixelDimension?.let { maximum ->
            val width = requireNotNull(it.width) { "The selected image could not be decoded" }
            val height = requireNotNull(it.height) { "The selected image could not be decoded" }
            require(width <= maximum && height <= maximum) { maximumDimensionMessage!! }
        }
        it.durationSeconds?.let { duration ->
            require(duration >= 0) { "The selected media has an invalid duration" }
        }
    }
}

/** Stable identifiers are also used by the thin Kotlin/Swift picker bridge. */
object MediaAcquisitionPolicies {
    val image = MediaAcquisitionPolicy(
        identifier = "image",
        postKind = PostKind.Image,
        maximumItems = 20,
        maximumBytesPerItem = 20L * MIB,
        acceptedMimePrefix = "image/",
        defaultMimeType = "image/jpeg",
        defaultFileExtension = "jpg",
        tooManyItemsMessage = "Choose up to 20 photos",
        tooLargeMessage = "Choose a photo smaller than 20 MB",
    )

    val video = MediaAcquisitionPolicy(
        identifier = "video",
        postKind = PostKind.Video,
        maximumItems = 1,
        maximumBytesPerItem = 100L * MIB,
        acceptedMimePrefix = "video/",
        defaultMimeType = "video/mp4",
        defaultFileExtension = "mp4",
        tooManyItemsMessage = "Choose one video",
        tooLargeMessage = "Choose a video smaller than 100 MB",
    )

    /** Camera capture produces one image and otherwise follows the image validation contract. */
    val camera = image.copy(
        identifier = "camera",
        maximumItems = 1,
        tooManyItemsMessage = "Choose one photo",
    )

    /** Profile photos are intentionally smaller and dimension-bounded than post galleries. */
    val avatar = image.copy(
        identifier = "avatar",
        maximumItems = 1,
        maximumBytesPerItem = 10L * MIB,
        maximumPixelDimension = 20_000,
        tooManyItemsMessage = "Choose one profile photo",
        tooLargeMessage = "Choose an image smaller than 10 MB",
        maximumDimensionMessage = "Choose an image no larger than 20,000 pixels per side",
    )

    fun forPostKind(kind: PostKind): MediaAcquisitionPolicy = when (kind) {
        PostKind.Image -> image
        PostKind.Video -> video
        else -> throw IllegalArgumentException("Choose an image or video post type first")
    }

    fun forIdentifier(identifier: String): MediaAcquisitionPolicy? = when (identifier.lowercase()) {
        image.identifier -> image
        video.identifier -> video
        camera.identifier -> camera
        avatar.identifier -> avatar
        else -> null
    }

    private const val MIB = 1_048_576L
}
