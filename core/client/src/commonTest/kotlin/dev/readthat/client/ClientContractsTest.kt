package dev.readthat.client

import dev.readthat.comments.domain.CommentSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ClientContractsTest {
    @Test
    fun clientSecurityConfigIsReleaseStrictAndHttp3HintsStayOnKnownHttpsOrigins() {
        val release = ClientConfiguration("https://api.readthat.example", "1", "ios")
        val debug = ClientConfiguration(
            "http://127.0.0.1:8787",
            "1",
            "ios",
            allowLocalDevelopmentHttp = true,
        )

        assertTrue(release.enabled)
        assertTrue(release.isKnownHttp3Origin("https://api.readthat.example/v1/feed"))
        assertTrue(release.isKnownHttp3Origin("https://imagedelivery.net/account/image/public"))
        assertFalse(release.isKnownHttp3Origin("https://unrelated.example/image.jpg"))
        assertTrue(debug.enabled)
        assertFalse(debug.isKnownHttp3Origin("http://127.0.0.1:8787/v1/feed"))
        assertFalse(ClientConfiguration("http://api.readthat.example", "1", "ios").enabled)
    }

    @Test
    fun pathSegmentsAreUtf8EncodedWithoutChangingUnreservedCharacters() {
        assertEquals("hello-world_2.0~", encodePathSegment("hello-world_2.0~"))
        assertEquals("caf%C3%A9%2Fnews%3Fx%3D1", encodePathSegment("café/news?x=1"))
    }

    @Test
    fun uuidStrictWireContractsNormalizeLegacyPrefixedMutationIds() {
        assertEquals(
            "403ed117-8b24-4c1f-805d-9c786b5eb26e",
            "community-visit:403ED117-8B24-4C1F-805D-9C786B5EB26E".toWireUuid(),
        )
        assertEquals(
            "403ed117-8b24-4c1f-805d-9c786b5eb26e",
            "403ed117-8b24-4c1f-805d-9c786b5eb26e".toWireUuid(),
        )
        assertFailsWith<IllegalArgumentException> { "community-visit:not-a-uuid".toWireUuid() }
    }

    @Test
    fun clearingAccountStateAlsoSupportsClearingConsistencyBookmark() = runTest {
        val store = InMemorySessionStore()
        store.writeSession(StoredSession("s", "a", "r", 10, 20))
        store.writeBookmark("bookmark")

        store.clearSession()
        store.clearBookmark()

        assertNull(store.readSession())
        assertNull(store.readBookmark())
    }

    @Test
    fun commentDocumentKeysIsolateEverySortAndThreadShape() {
        val rootKeys = CommentSort.entries.map { commentDocumentKey("post-1", sort = it) }

        assertEquals(CommentSort.entries.size, rootKeys.toSet().size)
        assertEquals("comments:v2:post-1:best", commentDocumentKey("post-1"))
        assertEquals(
            "comments:v2:post-1:top:root:comment-1",
            commentDocumentKey("post-1", rootCommentId = "comment-1", sort = CommentSort.Top),
        )
        assertEquals(
            "comments:v2:post-1:qa:focus:comment-2",
            commentDocumentKey("post-1", focusCommentId = "comment-2", sort = CommentSort.Qa),
        )
    }
}
