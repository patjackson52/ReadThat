package dev.readthat.detail.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import dev.readthat.image.ui.PlatformImageKind
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedPlatformPostDetailScreenTest {
    @Test
    fun commentIconDefaultsAreIdenticalWithoutHostInjection() {
        val icons = DetailIcons()

        assertEquals(Icons.Default.FilterList, icons.filter)
        assertEquals(Icons.AutoMirrored.Outlined.Reply, icons.reply)
        assertEquals(Icons.Outlined.ArrowUpward, icons.upvote)
        assertEquals(Icons.Outlined.ChatBubbleOutline, icons.comments)
    }

    @Test
    fun detailIdentityImagesUseAvatarDecodeVariantOnEveryHost() {
        val request = detailAvatarImageRequest(
            url = "https://cdn.example/community.png",
            cacheKey = "community:42",
        )

        assertEquals(PlatformImageKind.Avatar, request.kind)
        assertEquals("avatar:community:42", request.decodedCacheKey)
    }
}
