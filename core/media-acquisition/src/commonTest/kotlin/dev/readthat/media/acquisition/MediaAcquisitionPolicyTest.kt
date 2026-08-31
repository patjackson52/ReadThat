package dev.readthat.media.acquisition

import dev.readthat.shared.LocalPostMedia
import dev.readthat.shared.PostKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class MediaAcquisitionPolicyTest {
    @Test
    fun stableIdentifiersAndPostKindsResolveToOnePolicy() {
        assertSame(MediaAcquisitionPolicies.image, MediaAcquisitionPolicies.forPostKind(PostKind.Image))
        assertSame(MediaAcquisitionPolicies.video, MediaAcquisitionPolicies.forPostKind(PostKind.Video))
        assertEquals(MediaAcquisitionPolicies.camera, MediaAcquisitionPolicies.forIdentifier("CAMERA"))
        assertSame(MediaAcquisitionPolicies.avatar, MediaAcquisitionPolicies.forIdentifier("avatar"))
        assertNull(MediaAcquisitionPolicies.forIdentifier("future"))
        assertFailsWith<IllegalArgumentException> {
            MediaAcquisitionPolicies.forPostKind(PostKind.Text)
        }
    }

    @Test
    fun nativeResultsAreValidatedBeforeEnteringSharedState() {
        val image = LocalPostMedia(
            name = "photo.jpg",
            mimeType = "image/jpeg",
            localPath = "/private/photo.jpg",
            byteSize = 1_024,
            width = 100,
            height = 50,
        )
        assertSame(image, MediaAcquisitionPolicies.image.validate(image))

        assertFailsWith<IllegalArgumentException> {
            MediaAcquisitionPolicies.image.validate(image.copy(mimeType = "video/mp4"))
        }
        assertFailsWith<IllegalArgumentException> {
            MediaAcquisitionPolicies.image.validate(
                image.copy(byteSize = MediaAcquisitionPolicies.image.maximumBytesPerItem + 1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MediaAcquisitionPolicies.image.validate(image.copy(width = 0))
        }
    }

    @Test
    fun avatarPolicyIsSingleItemSmallerAndDimensionBounded() {
        val avatar = LocalPostMedia(
            name = "avatar.png",
            mimeType = "image/png",
            localPath = "/private/avatar.png",
            byteSize = 1_024,
            width = 400,
            height = 400,
        )
        assertSame(avatar, MediaAcquisitionPolicies.avatar.validate(avatar))
        assertEquals(1, MediaAcquisitionPolicies.avatar.maximumItems)

        assertFailsWith<IllegalArgumentException> {
            MediaAcquisitionPolicies.avatar.validate(avatar.copy(width = 20_001))
        }
        assertFailsWith<IllegalArgumentException> {
            MediaAcquisitionPolicies.avatar.validate(avatar.copy(width = null))
        }
        assertFailsWith<IllegalArgumentException> {
            MediaAcquisitionPolicies.avatar.validate(
                avatar.copy(byteSize = MediaAcquisitionPolicies.avatar.maximumBytesPerItem + 1),
            )
        }
    }
}
