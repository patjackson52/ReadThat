package dev.readthat.client

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidSecureSessionStoreTest {
    private val session = StoredSession("session", "access", "refresh", 10L, 20L)

    @Test
    fun `legacy session and bookmark migrate without clearing rollback envelope`() = runTest {
        val current = PlaintextEnvelopeStore()
        val legacy = PlaintextEnvelopeStore().apply {
            sessionEnvelope = sessionJson(session)
            bookmark = "legacy-bookmark"
        }
        val store = AndroidSecureSessionStore(current, legacy)

        assertEquals(session, store.readSession())
        assertEquals(sessionJson(session), current.sessionEnvelope)
        assertEquals("legacy-bookmark", current.bookmark)
        assertEquals(sessionJson(session), legacy.sessionEnvelope)
    }

    @Test
    fun `corrupt current envelope recovers from valid legacy session`() = runTest {
        val current = PlaintextEnvelopeStore().apply { sessionEnvelope = "not-json" }
        val legacy = PlaintextEnvelopeStore().apply { sessionEnvelope = sessionJson(session) }
        val store = AndroidSecureSessionStore(current, legacy)

        assertEquals(session, store.readSession())
        assertEquals(sessionJson(session), current.sessionEnvelope)
    }

    @Test
    fun `writes and clears both stores for upgrade and rollback safety`() = runTest {
        val current = PlaintextEnvelopeStore()
        val legacy = PlaintextEnvelopeStore()
        val store = AndroidSecureSessionStore(current, legacy)

        store.writeSession(session)
        store.writeBookmark("bookmark")
        assertEquals(sessionJson(session), current.sessionEnvelope)
        assertEquals(sessionJson(session), legacy.sessionEnvelope)
        assertEquals("bookmark", current.bookmark)
        assertEquals("bookmark", legacy.bookmark)

        store.clearSession()
        store.clearBookmark()
        assertNull(current.sessionEnvelope)
        assertNull(legacy.sessionEnvelope)
        assertNull(current.bookmark)
        assertNull(legacy.bookmark)
    }
}

private class PlaintextEnvelopeStore : AndroidSessionEnvelopeStore {
    var sessionEnvelope: String? = null
    var bookmark: String? = null

    override fun readSessionEnvelope(): String? = sessionEnvelope
    override fun writeSessionEnvelope(value: String) { sessionEnvelope = value }
    override fun clearSessionEnvelope() { sessionEnvelope = null }
    override fun readBookmark(): String? = bookmark
    override fun writeBookmark(value: String) { bookmark = value }
    override fun clearBookmark() { bookmark = null }
    override fun encrypt(cleartext: String): String = cleartext
    override fun decrypt(encoded: String): String = encoded
}

private fun sessionJson(session: StoredSession): String =
    """{"sessionId":"${session.sessionId}","accessToken":"${session.accessToken}","refreshToken":"${session.refreshToken}","accessExpiresAt":${session.accessExpiresAt},"refreshExpiresAt":${session.refreshExpiresAt}}"""
