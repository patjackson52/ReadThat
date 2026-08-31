package dev.readthat.client

import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import dev.readthat.data.db.AppDatabase
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
class SharedProfileRepositoryIntegrationTest {
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
    fun `profile survives repository recreation in account scoped Room cache`() = runTest {
        val transport = RecordingProfileTransport()
        val client = ReadThatClient(
            ClientConfiguration("https://api.readthat.test", "test", "android"),
            transport,
            database,
            InMemorySessionStore(),
        )

        val first = OfflineFirstRepository(
            client,
            database,
            backgroundScope,
            accountIdOverride = "account-a",
            maintainGlobalState = false,
        )
        assertEquals("Reader", first.user("reader").displayName)
        assertEquals(1, transport.requests)

        val recreated = OfflineFirstRepository(
            client,
            database,
            backgroundScope,
            accountIdOverride = "account-a",
            maintainGlobalState = false,
        )
        assertEquals("Reader", recreated.user("reader").displayName)
        assertEquals(1, transport.requests)

        val otherAccount = OfflineFirstRepository(
            client,
            database,
            backgroundScope,
            accountIdOverride = "account-b",
            maintainGlobalState = false,
        )
        otherAccount.user("reader")
        assertEquals(2, transport.requests)
    }

    @Test
    fun `forced refresh falls back to durable profile while offline`() = runTest {
        val transport = RecordingProfileTransport()
        val client = ReadThatClient(
            ClientConfiguration("https://api.readthat.test", "test", "android"),
            transport,
            database,
            InMemorySessionStore(),
        )
        val repository = OfflineFirstRepository(
            client,
            database,
            backgroundScope,
            accountIdOverride = "account",
            maintainGlobalState = false,
        )

        repository.user("reader")
        transport.offline = true

        assertEquals("Reader", repository.user("reader", force = true).displayName)
        assertTrue("A forced refresh must attempt the network", transport.requests >= 2)
    }
}

private class RecordingProfileTransport : HttpTransport {
    var requests = 0
    var offline = false

    override suspend fun execute(request: HttpRequest): HttpResponse {
        require(request.url == "https://api.readthat.test/v1/users/reader")
        requests += 1
        if (offline) error("Offline")
        return HttpResponse(
            status = 200,
            headers = mapOf("content-type" to listOf("application/json")),
            body = PROFILE_JSON.encodeToByteArray(),
            protocol = "h3",
            sentAtMillis = 1,
            receivedAtMillis = 2,
        )
    }

    private companion object {
        const val PROFILE_JSON = """
            {
              "user": {
                "id": "user-1",
                "username": "reader",
                "displayName": "Reader",
                "bio": "Offline first",
                "karma": 42
              }
            }
        """
    }
}
