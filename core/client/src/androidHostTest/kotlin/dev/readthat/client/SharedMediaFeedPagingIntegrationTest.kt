package dev.readthat.client

import android.content.Context
import androidx.paging.testing.asSnapshot
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import dev.readthat.data.db.AppDatabase
import dev.readthat.mediafeed.domain.MediaFeedItem
import dev.readthat.mediafeed.domain.MediaFeedLaunchContext
import dev.readthat.mediafeed.domain.MediaFeedMedia
import dev.readthat.networking.HttpRequest
import dev.readthat.networking.HttpResponse
import dev.readthat.networking.HttpTransport
import kotlinx.coroutines.test.runTest
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
class SharedMediaFeedPagingIntegrationTest {
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
    fun `normal feed snapshot paints exact order then resumes ranked cursor`() = runTest {
        val transport = RecordingMediaTransport()
        val client = client(transport)
        val launch = MediaFeedLaunchContext(
            snapshotId = "snapshot",
            sourceFeedId = "feed:home",
            anchorPostId = "anchor",
            items = listOf(item("before"), item("anchor"), item("after")),
            anchorIndex = 1,
            continuationCursor = "ranked-feed-v1:next",
        )
        val scope = SharedMediaFeedScope("anchor", snapshotId = launch.snapshotId)
        val cacheTiers = mutableListOf<String>()
        val repository = SharedMediaFeedRepository(
            database = database,
            api = ReadThatApi(client),
            scope = scope,
            launchContext = launch,
            accountId = "viewer",
            onInitialCacheTier = cacheTiers::add,
        )

        val loaded = repository.feed().asSnapshot {
            appendScrollWhile { it.postId != "network-after" }
        }

        assertEquals(1, repository.initialPage)
        assertEquals(
            listOf("before", "anchor", "after", "network-after"),
            loaded.map(MediaFeedItem::postId),
        )
        assertEquals(
            listOf("before", "anchor", "after", "network-after"),
            database.mediaFeedDao().postIds("viewer", scope.databaseId),
        )
        assertEquals(listOf("next"), transport.rankedCursors)
        assertEquals(0, transport.mediaRequests)
        assertEquals(listOf("navigation_seed"), cacheTiers)
    }

    @Test
    fun `cold media feed refresh is room backed and account isolated`() = runTest {
        val transport = RecordingMediaTransport()
        val client = client(transport)
        val scope = SharedMediaFeedScope("anchor")
        val cacheTiers = mutableListOf<String>()
        val repository = SharedMediaFeedRepository(
            database = database,
            api = ReadThatApi(client),
            scope = scope,
            launchContext = null,
            accountId = "signed-in-viewer",
            onInitialCacheTier = cacheTiers::add,
        )

        // Paging's test presenter can briefly report an empty generation as idle before a
        // RemoteMediator publishes Loading. An explicit UI refresh makes this integration
        // assertion wait for the Room transaction and its invalidation to be presented.
        val loaded = repository.feed().asSnapshot { refresh() }

        assertEquals(listOf("anchor", "remote-b"), loaded.map(MediaFeedItem::postId))
        assertEquals(
            listOf("anchor", "remote-b"),
            database.mediaFeedDao().postIds("signed-in-viewer", scope.databaseId),
        )
        assertTrue(database.mediaFeedDao().postIds("anonymous", scope.databaseId).isEmpty())
        assertEquals(2, transport.mediaRequests)
        assertTrue(transport.lastUrl.contains("anchorPostId=anchor"))
        assertEquals(listOf("network", "network"), cacheTiers)
    }

    @Test
    fun `launch seed remains usable without a continuation request`() = runTest {
        val transport = RecordingMediaTransport()
        val launch = MediaFeedLaunchContext(
            snapshotId = "snapshot",
            sourceFeedId = "feed:home",
            anchorPostId = "anchor",
            items = listOf(item("before"), item("anchor"), item("after")),
            anchorIndex = 1,
            continuationCursor = null,
        )
        val scope = SharedMediaFeedScope("anchor", snapshotId = launch.snapshotId)
        val repository = SharedMediaFeedRepository(
            database = database,
            api = ReadThatApi(client(transport)),
            scope = scope,
            launchContext = launch,
            accountId = "viewer",
        )

        val loaded = repository.feed().asSnapshot()

        assertEquals(listOf("before", "anchor", "after"), loaded.map(MediaFeedItem::postId))
        assertEquals(
            listOf("before", "anchor", "after"),
            database.mediaFeedDao().postIds("viewer", scope.databaseId),
        )
        assertEquals(0, transport.mediaRequests)
        assertTrue(transport.rankedCursors.isEmpty())
    }

    private fun client(transport: HttpTransport) = ReadThatClient(
        ClientConfiguration("https://api.readthat.test", "test", "android"),
        transport,
        database,
        InMemorySessionStore(),
    )

    private fun item(id: String) = MediaFeedItem(
        postId = id,
        author = "reader",
        subreddit = "pics",
        title = "title-$id",
        score = 3,
        commentCount = 2,
        kind = "image",
        media = MediaFeedMedia(
            mediaId = "media-$id",
            placeholderColor = 0xff000000,
            aspectRatio = 1f,
            isVideo = false,
            url = "https://example.test/$id.jpg",
            cacheKey = "image:$id",
        ),
    )
}

private class RecordingMediaTransport : HttpTransport {
    var mediaRequests = 0
    var lastUrl = ""
    val rankedCursors = mutableListOf<String?>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        lastUrl = request.url
        val body = when {
            "/v1/feeds/media?" in request.url -> {
                mediaRequests += 1
                MEDIA_JSON
            }
            "/v1/feed?" in request.url -> {
                rankedCursors += request.url.substringAfter("cursor=", "").ifBlank { null }
                RANKED_JSON
            }
            else -> error("Unexpected request ${request.method} ${request.url}")
        }
        return HttpResponse(
            status = 200,
            headers = mapOf("content-type" to listOf("application/json")),
            body = body.encodeToByteArray(),
            protocol = "h3",
            sentAtMillis = 1,
            receivedAtMillis = 2,
        )
    }

    private companion object {
        const val MEDIA_JSON = """
            {
              "items": [
                {
                  "id": "anchor", "subreddit": "pics", "author": "reader",
                  "title": "anchor", "score": 3, "commentCount": 2, "kind": "image",
                  "media": {
                    "id": "media-anchor", "url": "https://example.test/anchor.jpg",
                    "width": 100, "height": 100, "contentType": "image/jpeg"
                  }
                },
                {
                  "id": "remote-b", "subreddit": "pics", "author": "reader",
                  "title": "remote-b", "score": 4, "commentCount": 1, "kind": "image",
                  "media": {
                    "id": "media-b", "url": "https://example.test/b.jpg",
                    "width": 100, "height": 100, "contentType": "image/jpeg"
                  }
                }
              ],
              "nextCursor": null, "snapshotAt": 1, "anchorIncluded": true
            }
        """

        const val RANKED_JSON = """
            {
              "groups": [
                {
                  "groupId": "network-after",
                  "cells": [
                    {
                      "type": "metadata", "cellId": "network-after/metadata",
                      "subreddit": "pics", "postedAgo": "now", "author": "reader"
                    },
                    {
                      "type": "title", "cellId": "network-after/title", "text": "network-after"
                    },
                    {
                      "type": "image", "cellId": "network-after/image",
                      "placeholderColor": 4278190080, "aspectRatio": 1.0,
                      "altText": "network-after image",
                      "url": "https://example.test/network-after.jpg",
                      "cacheKey": "image:network-after"
                    },
                    {
                      "type": "actionbar", "cellId": "network-after/actions",
                      "score": 5, "commentCount": 2
                    }
                  ]
                }
              ],
              "nextCursor": null
            }
        """
    }
}
