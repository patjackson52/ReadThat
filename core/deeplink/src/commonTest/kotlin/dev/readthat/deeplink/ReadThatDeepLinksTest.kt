package dev.readthat.deeplink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadThatDeepLinksTest {
    private val postId = "610466c0-544f-518b-b536-4973bcfe8af9"
    private val commentId = "59a7588e-832b-4286-a3b4-edb1f40cc561"

    @Test
    fun parsesCanonicalPostAndCommentLinks() {
        assertEquals(
            ReadThatDeepLink.Post(postId),
            ReadThatDeepLinks.parse("${ReadThatDeepLinks.PRODUCTION_ORIGIN}/post/$postId"),
        )
        assertEquals(
            ReadThatDeepLink.Comment(postId, commentId),
            ReadThatDeepLinks.parse(
                "${ReadThatDeepLinks.PRODUCTION_ORIGIN}/post/$postId/comment/$commentId",
            ),
        )
    }

    @Test
    fun acceptsExistingCommentFragmentAndQueryPermalinks() {
        val root = "${ReadThatDeepLinks.PRODUCTION_ORIGIN}/post/$postId"
        assertEquals(
            ReadThatDeepLink.Comment(postId, commentId),
            ReadThatDeepLinks.parse("$root#comment-$commentId"),
        )
        assertEquals(
            ReadThatDeepLink.Comment(postId, commentId),
            ReadThatDeepLinks.parse("$root?commentId=$commentId"),
        )
    }

    @Test
    fun parsesCustomSchemeLinks() {
        assertEquals(
            ReadThatDeepLink.Post(postId),
            ReadThatDeepLinks.parse("readthat://post/$postId"),
        )
        assertEquals(
            ReadThatDeepLink.Comment(postId, commentId),
            ReadThatDeepLinks.parse("readthat://comment/$postId/$commentId"),
        )
        assertEquals(
            ReadThatDeepLink.Comment(postId, commentId),
            ReadThatDeepLinks.parse("readthat://post/$postId/comment/$commentId"),
        )
    }

    @Test
    fun rejectsLookalikeHostsCredentialsPortsAndUnsupportedPaths() {
        assertNull(ReadThatDeepLinks.parse("https://${ReadThatDeepLinks.PRODUCTION_HOST}.evil.test/post/$postId"))
        assertNull(ReadThatDeepLinks.parse("https://evil@${ReadThatDeepLinks.PRODUCTION_HOST}/post/$postId"))
        assertNull(ReadThatDeepLinks.parse("https://${ReadThatDeepLinks.PRODUCTION_HOST}:444/post/$postId"))
        assertNull(ReadThatDeepLinks.parse("${ReadThatDeepLinks.PRODUCTION_ORIGIN}/r/readthateng"))
        assertNull(ReadThatDeepLinks.parse("http://${ReadThatDeepLinks.PRODUCTION_HOST}/post/$postId"))
    }

    @Test
    fun rejectsEncodedOrMalformedIdentifiers() {
        assertNull(ReadThatDeepLinks.parse("${ReadThatDeepLinks.PRODUCTION_ORIGIN}/post/%2e%2e"))
        assertNull(ReadThatDeepLinks.parse("${ReadThatDeepLinks.PRODUCTION_ORIGIN}/post/$postId/comment/not/a/comment"))
        assertNull(ReadThatDeepLinks.parse("${ReadThatDeepLinks.PRODUCTION_ORIGIN}/post/$postId#comment-"))
        assertNull(ReadThatDeepLinks.parse("javascript://post/$postId"))
    }

    @Test
    fun buildersRoundTrip() {
        val postUrl = ReadThatDeepLinks.postUrl(postId)
        val commentUrl = ReadThatDeepLinks.commentUrl(postId, commentId)
        assertEquals(ReadThatDeepLink.Post(postId), ReadThatDeepLinks.parse(postUrl))
        assertEquals(ReadThatDeepLink.Comment(postId, commentId), ReadThatDeepLinks.parse(commentUrl))
    }

    @Test
    fun inboxRetainsUntilMatchingConsumption() {
        val inbox = DeepLinkInbox()
        val target = ReadThatDeepLink.Comment(postId, commentId)
        assertTrue(inbox.offerUrl(ReadThatDeepLinks.commentUrl(postId, commentId)))
        assertEquals(target, inbox.pending.value)

        inbox.consume(ReadThatDeepLink.Post(postId))
        assertEquals(target, inbox.pending.value)
        inbox.consume(target)
        assertNull(inbox.pending.value)
        assertFalse(inbox.offerUrl("https://example.com/post/$postId"))
    }
}
