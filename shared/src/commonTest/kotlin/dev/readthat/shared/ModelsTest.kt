package dev.readthat.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelsTest {
    @Test fun authValidationMatchesBackendContract() {
        assertFalse(AuthForm(username = "ab", displayName = "Name", password = "1234567890").canSubmit)
        assertTrue(AuthForm(username = "valid_user", displayName = "Name", password = "1234567890").canSubmit)
    }

    @Test fun voteOptimismReplacesTheExistingVote() {
        assertEquals(VoteSnapshot(score = 8, viewerVote = -1), VoteSnapshot(10, 1).optimistic(-1))
        assertEquals(VoteSnapshot(score = 9, viewerVote = 0), VoteSnapshot(10, 1).optimistic(0))
    }

    @Test fun createPostNormalizesSubredditPrefix() {
        val draft = CreatePostDraft(subreddit = "r/android", title = "Hello", body = "Body")
        assertEquals("android", draft.normalizedSubreddit)
        assertTrue(draft.canSubmit)
        assertTrue(draft.copy(body = "").canSubmit)
        assertFalse(draft.copy(subreddit = "").canSubmit)
    }

    @Test fun createCommunityNormalizesPrefixAndValidatesTheOfflineCommand() {
        val valid = CreateCommunityDraft(
            name = "r/Android_Dev",
            displayName = "Android Dev",
            description = "A community",
            accessType = "restricted",
        )
        assertEquals("android_dev", valid.normalizedName)
        assertTrue(valid.canSubmit)
        assertFalse(valid.copy(name = "ab").canSubmit)
        assertFalse(valid.copy(description = "x".repeat(1_001)).canSubmit)
    }

    @Test fun videoPolicyDisablesAutoplayAndWritesOnMeteredByDefault() {
        val policy = VideoPolicyResolver.resolve(
            settings = AppSettings(),
            connection = ConnectionKind.Metered,
            dataSaverEnabled = false,
            deviceTier = DeviceTier.Standard,
            availableCacheBytes = 10L * 1024 * 1024 * 1024,
        )
        assertFalse(policy.autoplay)
        assertFalse(policy.allowPrefetch)
        assertFalse(policy.writeCache)
        assertEquals(480, policy.maxVideoHeight)
        assertEquals(1_500_000L, policy.preferredPeakBitrate)
    }

    @Test fun videoPolicyAutoplaysAndSizesCacheForUnmeteredHighEndDevice() {
        val policy = VideoPolicyResolver.resolve(
            settings = AppSettings(),
            connection = ConnectionKind.Unmetered,
            dataSaverEnabled = false,
            deviceTier = DeviceTier.HighEnd,
            availableCacheBytes = 100L * 1024 * 1024 * 1024,
        )
        assertTrue(policy.autoplay)
        assertTrue(policy.allowPrefetch)
        assertTrue(policy.writeCache)
        assertEquals(1440, policy.maxVideoHeight)
        assertEquals(384L * 1024 * 1024, policy.cacheBytes)
    }

    @Test fun posterCacheKeySeparatesStaleAndCurrentTransformations() {
        val stale = videoPosterCacheKey("post:1", "https://stream/poster.jpg")
        val current = videoPosterCacheKey("post:1", "https://stream/poster.jpg?time=5s")

        assertTrue(stale.startsWith("post:1:poster:v3:"))
        assertFalse(stale == current)
        assertEquals(current, videoPosterCacheKey("post:1", "https://stream/poster.jpg?time=5s"))
    }
}
