package dev.readthat.client

import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import dev.readthat.data.db.AccountEntity
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.PendingPostEntity
import dev.readthat.data.db.PendingSubredditEntity
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
class SharedCreationOutboxProcessorIntegrationTest {
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
    fun `legacy Android media command drains through shared publisher`() = runTest {
        activateAccount(ACCOUNT_ID)
        val sessionStore = authenticatedSessionStore()
        val transport = CreationTransport()
        val pending = pendingPost(
            mediaItemsJson = """[
                {
                  "name":"legacy.jpg",
                  "contentType":"image/jpeg",
                  "localPath":"/legacy/photo.jpg",
                  "byteSize":73,
                  "remoteMediaId":"media-legacy"
                }
            ]""".trimIndent(),
        )
        database.postOutboxDao().upsert(pending)

        val result = processor(transport, sessionStore, backgroundScope)
            .processPost(pending.mutationId, terminalAttempt = false)

        assertEquals(SharedCreationOutboxResult.Completed, result)
        assertEquals("post-created", database.postOutboxDao().get(pending.mutationId)?.remotePostId)
        assertTrue(transport.postBody.contains("\"mediaIds\":[\"media-legacy\"]"))
        assertEquals(1, transport.meRequests)
        assertEquals(1, transport.postRequests)
    }

    @Test
    fun `transient server failure remains retryable with same mutation`() = runTest {
        activateAccount(ACCOUNT_ID)
        val sessionStore = authenticatedSessionStore()
        val transport = CreationTransport(postStatus = 503)
        val pending = pendingPost(kind = "Text", mediaItemsJson = "[]")
        database.postOutboxDao().upsert(pending)

        val result = processor(transport, sessionStore, backgroundScope)
            .processPost(pending.mutationId, terminalAttempt = false)

        assertEquals(SharedCreationOutboxResult.Retry, result)
        assertEquals("retrying", database.postOutboxDao().get(pending.mutationId)?.state)
        assertEquals(null, database.postOutboxDao().get(pending.mutationId)?.remotePostId)
        assertTrue(transport.postBody.contains("\"clientMutationId\":\"post-mutation\""))
    }

    @Test
    fun `permanent community failure applies shared terminal policy`() = runTest {
        activateAccount(ACCOUNT_ID)
        val sessionStore = authenticatedSessionStore()
        val transport = CreationTransport(communityStatus = 422)
        val pending = PendingSubredditEntity(
            mutationId = "community-mutation",
            accountId = ACCOUNT_ID,
            name = "shared",
            displayName = "Shared",
            description = "KMP",
            accessType = "public",
            state = "queued",
            remoteSubredditId = null,
            lastError = null,
            createdAt = 1,
        )
        database.subredditOutboxDao().upsertPending(pending)

        val result = processor(transport, sessionStore, backgroundScope)
            .processCommunity(pending.mutationId, terminalAttempt = false)

        assertEquals(SharedCreationOutboxResult.Failed, result)
        assertEquals("failed", database.subredditOutboxDao().get(pending.mutationId)?.state)
        assertEquals(1, transport.communityRequests)
    }

    @Test
    fun `command for inactive account waits without touching network`() = runTest {
        activateAccount("another-account")
        val pending = pendingPost(kind = "Text", mediaItemsJson = "[]")
        database.postOutboxDao().upsert(pending)
        val transport = CreationTransport()

        val result = processor(transport, InMemorySessionStore(), backgroundScope)
            .processPost(pending.mutationId, terminalAttempt = false)

        assertEquals(SharedCreationOutboxResult.WaitingForAccount, result)
        assertEquals("waiting_account", database.postOutboxDao().get(pending.mutationId)?.state)
        assertEquals(0, transport.meRequests + transport.postRequests + transport.communityRequests)
    }

    private fun processor(
        transport: HttpTransport,
        sessionStore: SecureSessionStore,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = SharedCreationOutboxProcessor(
        ReadThatClient(
            ClientConfiguration("https://api.readthat.test", "test", "android"),
            transport,
            database,
            sessionStore,
        ),
        database,
        scope,
    )

    private suspend fun activateAccount(id: String) {
        database.accountDao().activate(AccountEntity(
            id = id,
            username = "reader",
            displayName = "Reader",
            bio = "",
            avatarUrl = null,
            karma = 1,
            createdAt = 1,
            updatedAt = 1,
            lastAuthenticatedAt = 1,
            isActive = true,
        ))
    }

    private suspend fun authenticatedSessionStore() = InMemorySessionStore().also { store ->
        store.writeSession(StoredSession(
            sessionId = "session",
            accessToken = "access",
            refreshToken = "refresh",
            accessExpiresAt = Long.MAX_VALUE,
            refreshExpiresAt = Long.MAX_VALUE,
        ))
    }

    private fun pendingPost(
        kind: String = "Image",
        mediaItemsJson: String,
    ) = PendingPostEntity(
        mutationId = "post-mutation",
        accountId = ACCOUNT_ID,
        subreddit = "readthat",
        kind = kind,
        title = "Shared outbox",
        body = "Body",
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

    private companion object { const val ACCOUNT_ID = "account-1" }
}

private class CreationTransport(
    private val postStatus: Int = 200,
    private val communityStatus: Int = 200,
) : HttpTransport {
    var meRequests = 0
    var postRequests = 0
    var communityRequests = 0
    var postBody = ""

    override suspend fun execute(request: HttpRequest): HttpResponse = when {
        request.url.endsWith("/v1/me") -> {
            meRequests += 1
            response(200, USER_JSON)
        }
        request.url.endsWith("/v1/posts") -> {
            postRequests += 1
            postBody = request.body?.decodeToString().orEmpty()
            response(postStatus, if (postStatus == 200) POST_JSON else ERROR_JSON)
        }
        request.url.endsWith("/v1/subreddits") -> {
            communityRequests += 1
            response(communityStatus, if (communityStatus == 200) COMMUNITY_JSON else ERROR_JSON)
        }
        else -> error("Unexpected request ${request.method} ${request.url}")
    }

    private fun response(status: Int, body: String) = HttpResponse(
        status = status,
        headers = mapOf("content-type" to listOf("application/json")),
        body = body.encodeToByteArray(),
        protocol = "h3",
        sentAtMillis = 1,
        receivedAtMillis = 2,
    )

    private companion object {
        const val USER_JSON = """
            {"user":{"id":"account-1","username":"reader","displayName":"Reader"}}
        """
        const val POST_JSON = """
            {"post":{"id":"post-created","subreddit":"readthat","author":"reader","title":"Shared outbox"}}
        """
        const val COMMUNITY_JSON = """
            {"subreddit":{"id":"community-created","name":"shared","displayName":"Shared","viewerRole":"owner"}}
        """
        const val ERROR_JSON = """{"error":{"message":"Request rejected"}}"""
    }
}
