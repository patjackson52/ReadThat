package dev.readthat.client

import dev.readthat.navigation.AppDestination
import dev.readthat.shared.PostHeader
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RestoredDetailRecoveryPolicyTest {
    private val restored = AppDestination.PostDetail("deleted-post")

    @Test
    fun definitiveMissingRestoredLeafReturnsToItsHistory() {
        assertTrue(shouldRecoverRestoredPostDetail(
            restored = restored,
            current = restored,
            detail = DetailState(
                postId = restored.postId,
                postNotFound = true,
                loading = false,
                error = "Post not found",
            ),
        ))
    }

    @Test
    fun loadingAndOrdinaryNetworkFailuresNeverDiscardRestoredNavigation() {
        assertFalse(shouldRecoverRestoredPostDetail(
            restored = restored,
            current = restored,
            detail = DetailState(postId = restored.postId, loading = true, postNotFound = true),
        ))
        assertFalse(shouldRecoverRestoredPostDetail(
            restored = restored,
            current = restored,
            detail = DetailState(postId = restored.postId, error = "Offline"),
        ))
    }

    @Test
    fun cachedPostRemainsAvailableOfflineEvenAfterServerNotFound() {
        assertFalse(shouldRecoverRestoredPostDetail(
            restored = restored,
            current = restored,
            detail = DetailState(
                postId = restored.postId,
                post = PostHeader(
                    postId = restored.postId,
                    title = "Cached post",
                    subreddit = "offline",
                    author = "reader",
                    score = 1,
                    commentCount = 0,
                ),
                postNotFound = true,
            ),
        ))
    }

    @Test
    fun explicitOrDifferentDestinationIsNeverRecoveredAsTheColdStartLeaf() {
        val missing = DetailState(postId = restored.postId, postNotFound = true)
        assertFalse(shouldRecoverRestoredPostDetail(null, restored, missing))
        assertFalse(shouldRecoverRestoredPostDetail(restored, AppDestination.Feed, missing))
    }
}
