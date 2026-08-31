package dev.readthat.client

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
data class StoredSession(
    val sessionId: String,
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long,
)

/** Implementations must use Android Keystore or Apple Keychain for token fields. */
interface SecureSessionStore {
    suspend fun readSession(): StoredSession?
    suspend fun writeSession(session: StoredSession)
    suspend fun clearSession()
    suspend fun readBookmark(): String?
    suspend fun writeBookmark(value: String)
    suspend fun clearBookmark()
}

/** Test/preview store. Production platform graphs deliberately require an explicit secure store. */
class InMemorySessionStore : SecureSessionStore {
    private val mutex = Mutex()
    private var session: StoredSession? = null
    private var bookmark: String? = null

    override suspend fun readSession(): StoredSession? = mutex.withLock { session }
    override suspend fun writeSession(session: StoredSession) = mutex.withLock { this.session = session }
    override suspend fun clearSession() = mutex.withLock { session = null }
    override suspend fun readBookmark(): String? = mutex.withLock { bookmark }
    override suspend fun writeBookmark(value: String) = mutex.withLock { bookmark = value }
    override suspend fun clearBookmark() = mutex.withLock { bookmark = null }
}
