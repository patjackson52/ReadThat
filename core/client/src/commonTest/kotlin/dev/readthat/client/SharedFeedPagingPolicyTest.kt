package dev.readthat.client

import dev.readthat.data.db.CacheScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedFeedPagingPolicyTest {
    @Test
    fun communityFeedScopesMatchAndroidAndNormalizeRouteNames() {
        assertEquals("feed:subreddit:kotlin", CacheScope.communityFeedId(" r/Kotlin "))
    }

    @Test
    fun pagingWindowIsBoundedAndPrefetchesBeforeTheEnd() {
        val config = sharedFeedPagingConfig()

        assertEquals(20, config.pageSize)
        assertEquals(10, config.prefetchDistance)
        assertEquals(20, config.initialLoadSize)
        assertEquals(200, config.maxSize)
        assertFalse(config.enablePlaceholders)
    }

    @Test
    fun emptyOrStaleRoomCacheRefreshesButFreshRowsRenderImmediately() {
        val now = 1_000_000L

        assertTrue(shouldLaunchFeedRefresh(0, now, now))
        assertFalse(shouldLaunchFeedRefresh(25, now - 59_999L, now))
        assertTrue(shouldLaunchFeedRefresh(25, now - 60_000L, now))
    }
}
