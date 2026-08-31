package dev.readthat.client

import android.content.Context
import androidx.paging.testing.asSnapshot
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.CacheScope
import dev.readthat.data.db.CachedDocumentEntity
import dev.readthat.data.db.GroupEntity
import dev.readthat.data.db.ItemStateEntity
import dev.readthat.data.db.RemoteKeyEntity
import dev.readthat.domain.WireCell
import dev.readthat.domain.WireGroup
import dev.readthat.networking.HttpResponse
import dev.readthat.networking.HttpTransport
import dev.readthat.observability.PerformanceEvent
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceRecorder
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.shared.PostMedia
import dev.readthat.shared.PostTransitionPreview
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedFeedPagingIntegrationTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `remote mediator commits https response and presents room backed card`() = runTest {
        val transport = RecordingFeedTransport()
        val performanceEvents = mutableListOf<PerformanceEvent>()
        PerformanceTelemetry.install(PerformanceRecorder(performanceEvents::add))
        val client = ReadThatClient(
            configuration = ClientConfiguration("https://api.readthat.test", "test", "android"),
            transport = transport,
            database = database,
            sessionStore = InMemorySessionStore(),
        )
        val repository = OfflineFirstRepository(client, database, backgroundScope)

        val cards = try {
            repository.pagedFeed.asSnapshot()
        } finally {
            PerformanceTelemetry.install(PerformanceRecorder { })
        }

        assertEquals(listOf("post-1"), cards.map(FeedCard::id))
        assertEquals("Shared paging", cards.single().preview.title)
        assertEquals(1, database.feedDao().groupCount())
        assertEquals(null, database.feedDao().remoteKey("feed:home")?.nextCursor)
        assertEquals(1, transport.feedRequests)
        assertTrue(transport.lastUrl.startsWith("https://api.readthat.test/v1/feed?"))
        assertEquals(
            1,
            performanceEvents.single { it.name == PerformanceMetric.SDUI_DROPPED_CELL }.value.toInt(),
        )
    }

    @Test
    fun `background refresh uses shared client and room transaction`() = runTest {
        val transport = RecordingFeedTransport()
        val client = ReadThatClient(
            configuration = ClientConfiguration("https://api.readthat.test", "test", "android"),
            transport = transport,
            database = database,
            sessionStore = InMemorySessionStore(),
        )
        val maintenance = SharedBackgroundMaintenance(
            client = client,
            database = database,
            scope = backgroundScope,
            accountId = "account",
        )

        val page = maintenance.run(
            SharedBackgroundMaintenanceRequest(
                drainMutations = false,
                refreshHomeFeed = true,
            ),
        ).refreshedFeed

        assertEquals(listOf("post-1"), page?.groups?.map(WireGroup::groupId))
        assertEquals(1, database.feedDao().groupCount("account", CacheScope.HOME_FEED_ID))
        assertEquals(1, transport.feedRequests)
        assertTrue(transport.lastUrl.startsWith("https://api.readthat.test/v1/feed?"))
    }

    @Test
    fun `community pager uses isolated room scope cursor and subreddit request`() = runTest {
        val transport = RecordingFeedTransport()
        val client = ReadThatClient(
            configuration = ClientConfiguration("https://api.readthat.test", "test", "android"),
            transport = transport,
            database = database,
            sessionStore = InMemorySessionStore(),
        )
        val repository = OfflineFirstRepository(client, database, backgroundScope)

        val cards = repository.pagedCommunityFeed("r/Kotlin").asSnapshot()
        val feedId = CacheScope.communityFeedId("Kotlin")

        assertEquals(listOf("post-1"), cards.map(FeedCard::id))
        assertEquals(1, database.feedDao().groupCount(feedId = feedId))
        assertEquals(null, database.feedDao().remoteKey(feedId)?.nextCursor)
        assertTrue(transport.lastUrl.contains("subreddit=kotlin"))
    }

    @Test
    fun `legacy community document is promoted into scoped paging room`() = runTest {
        database.cachedDocumentDao().upsert(CachedDocumentEntity(
            accountId = CacheScope.DEFAULT_ACCOUNT_ID,
            cacheKey = "community-feed:kotlin",
            payloadJson = RecordingFeedTransport.LEGACY_FEED_JSON,
            updatedAt = 1L,
        ))
        val transport = RecordingFeedTransport()
        val client = ReadThatClient(
            configuration = ClientConfiguration("https://api.readthat.test", "test", "android"),
            transport = transport,
            database = database,
            sessionStore = InMemorySessionStore(),
        )
        val repository = OfflineFirstRepository(client, database, backgroundScope)

        val cards = repository.pagedCommunityFeed("Kotlin").asSnapshot()

        assertEquals(listOf("post-1"), cards.map(FeedCard::id))
        assertEquals(1, database.feedDao().groupCount(feedId = CacheScope.communityFeedId("kotlin")))
        assertTrue(database.feedDao().syncMetadata(
            CacheScope.DEFAULT_ACCOUNT_ID,
            CacheScope.communityFeedId("kotlin"),
        ) != null)
    }

    @Test
    fun `legacy shell account override owns pager room scope`() = runTest {
        val transport = RecordingFeedTransport()
        val client = ReadThatClient(
            configuration = ClientConfiguration("https://api.readthat.test", "test", "android"),
            transport = transport,
            database = database,
            sessionStore = InMemorySessionStore(),
        )
        val repository = OfflineFirstRepository(
            client = client,
            database = database,
            scope = backgroundScope,
            accountIdOverride = "legacy-viewer",
            maintainGlobalState = false,
        )

        assertEquals(listOf("post-1"), repository.pagedFeed.asSnapshot().map(FeedCard::id))
        assertEquals(1, database.feedDao().groupCount("legacy-viewer", CacheScope.HOME_FEED_ID))
        assertEquals(0, database.feedDao().groupCount(CacheScope.DEFAULT_ACCOUNT_ID, CacheScope.HOME_FEED_ID))
    }

    @Test
    fun `shared media handoff preserves room rank viewer state and cursor`() = runTest {
        val account = "legacy-viewer"
        val feedId = CacheScope.HOME_FEED_ID
        val json = Json { encodeDefaults = true }
        fun group(id: String, media: Boolean) = WireGroup(
            groupId = id,
            cells = buildList {
                add(WireCell.Metadata("$id/metadata", "pics", "now", author = "reader"))
                add(WireCell.Title("$id/title", "title-$id"))
                if (media) add(WireCell.Image(
                    "$id/image", 0xff000000, 1f, "image-$id", "https://example.test/$id.jpg",
                ))
                add(WireCell.ActionBar("$id/actions", score = 3, commentCount = 4))
            },
        )
        fun entity(id: String, position: Int, media: Boolean) = GroupEntity(
            accountId = account,
            feedId = feedId,
            groupId = id,
            sortIndex = position,
            payloadJson = json.encodeToString(WireGroup.serializer(), group(id, media)),
        )
        database.feedDao().upsertGroups(listOf(
            entity("after", 3, true),
            entity("text-only", 1, false),
            entity("before", 0, true),
            entity("anchor", 2, true),
        ))
        database.feedDao().putState(ItemStateEntity(
            "anchor", 42, liked = true, accountId = account,
        ))
        database.feedDao().putRemoteKey(RemoteKeyEntity(feedId, "normal-next", account))
        val client = ReadThatClient(
            ClientConfiguration("https://api.readthat.test", "test", "android"),
            RecordingFeedTransport(),
            database,
            InMemorySessionStore(),
        )
        val repository = OfflineFirstRepository(
            client,
            database,
            backgroundScope,
            accountIdOverride = account,
            maintainGlobalState = false,
        )

        val context = repository.mediaLaunchContext(
            feedId,
            "anchor",
            PostTransitionPreview(
                postId = "anchor",
                title = "anchor",
                media = PostMedia(0xff000000, 1f, isVideo = false),
            ),
        )

        assertEquals(listOf("before", "anchor", "after"), context.items.map { it.postId })
        assertEquals(1, context.anchorIndex)
        assertEquals(42, context.items[1].score)
        assertEquals(1, context.items[1].viewerVote)
        assertEquals("normal-next", context.nextFeedCursor)
        assertEquals(feedId, context.sourceFeedId)
    }
}

private class RecordingFeedTransport : HttpTransport {
    var feedRequests = 0
    var lastUrl = ""

    override suspend fun execute(request: dev.readthat.networking.HttpRequest): HttpResponse {
        lastUrl = request.url
        if ("/v1/feed?" in request.url) feedRequests += 1
        return HttpResponse(
            status = 200,
            headers = mapOf("content-type" to listOf("application/json")),
            body = FEED_JSON.encodeToByteArray(),
            protocol = "h3",
            sentAtMillis = 10,
            receivedAtMillis = 12,
        )
    }

    companion object {
        const val LEGACY_FEED_JSON = """
            {
              "groups": [
                {
                  "groupId": "post-1",
                  "cells": [
                    {
                      "type": "title",
                      "cellId": "post-1/title",
                      "text": "Shared paging"
                    },
                    {
                      "type": "actionbar",
                      "cellId": "post-1/actions",
                      "score": 7,
                      "commentCount": 2
                    }
                  ]
                }
              ],
              "nextCursor": null
            }
        """

        const val FEED_JSON = """
            {
              "groups": [
                {
                  "groupId": "post-1",
                  "cells": [
                    {
                      "type": "metadata",
                      "cellId": "post-1/metadata",
                      "subreddit": "shared",
                      "postedAgo": "now",
                      "author": "reader"
                    },
                    {
                      "type": "title",
                      "cellId": "post-1/title",
                      "text": "Shared paging"
                    },
                    {
                      "type": "future_recommendation",
                      "cellId": "post-1/future"
                    },
                    {
                      "type": "actionbar",
                      "cellId": "post-1/actions",
                      "score": 7,
                      "commentCount": 2
                    }
                  ]
                }
              ],
              "nextCursor": null
            }
        """
    }
}
