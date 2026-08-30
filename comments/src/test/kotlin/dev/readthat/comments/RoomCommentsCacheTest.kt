package dev.readthat.comments

import androidx.test.core.app.ApplicationProvider
import dev.readthat.comments.data.cache.RoomCommentsCache
import dev.readthat.comments.domain.CommentNode
import dev.readthat.comments.domain.CommentTree
import dev.readthat.comments.domain.PostHeader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomCommentsCacheTest {
    private val cache = RoomCommentsCache(ApplicationProvider.getApplicationContext())

    @Test
    fun `thread and header cache are account isolated`() = runTest {
        val postId = "isolation-${System.nanoTime()}"
        val tree = CommentTree(
            postId,
            listOf(CommentNode.Comment(
                "c1",
                "u/a",
                "body",
                4,
                authorDisplayName = "Alice",
                authorAvatarUrl = "https://cdn.example/alice.jpg",
                isEdited = true,
            )),
            requestedCount = 200,
            requestedDepth = 10,
        )
        val header = PostHeader(postId, "title", "u/a", "r/test", 4, 1)

        cache.writeTree("account-a", tree, null)
        cache.writeHeader("account-a", header)

        assertEquals(tree, cache.readTree("account-a", postId, null))
        assertEquals(header, cache.readHeader("account-a", postId))
        assertNull(cache.readTree("account-b", postId, null))
        assertNull(cache.readHeader("account-b", postId))
    }

    @Test
    fun `normalized cache round trips a deeply nested thread without recursion`() = runTest {
        val depth = 1_500
        val postId = "deep-${System.nanoTime()}"
        var node: CommentNode = CommentNode.Comment("c$depth", "u/deep", "bottom", 1)
        for (index in depth - 1 downTo 0) {
            node = CommentNode.Comment("c$index", "u/deep", "body", 1, children = listOf(node))
        }
        val tree = CommentTree(postId, listOf(node), requestedCount = 200, requestedDepth = depth)

        cache.writeTree("deep-account", tree, null)
        val restored = requireNotNull(cache.readTree("deep-account", postId, null))

        var count = 0
        val stack = ArrayDeque(restored.roots)
        while (stack.isNotEmpty()) {
            when (val current = stack.removeLast()) {
                is CommentNode.Comment -> {
                    count++
                    current.children.forEach(stack::addLast)
                }
                is CommentNode.LoadMore -> Unit
            }
        }
        assertEquals(depth + 1, count)
        assertTrue(restored.roots.single() is CommentNode.Comment)
    }

    @Test
    fun `disk cache prunes oldest account scoped threads and headers`() = runTest {
        val account = "bounded-${System.nanoTime()}"
        repeat(202) { index ->
            val postId = "post-${index.toString().padStart(3, '0')}"
            if (index < 102) {
                cache.writeTree(
                    account,
                    CommentTree(
                        postId,
                        listOf(CommentNode.Comment("c$index", "u/a", "body", 1)),
                        requestedCount = 200,
                        requestedDepth = 10,
                    ),
                    null,
                )
            }
            cache.writeHeader(account, PostHeader(postId, "title", "u/a", "r/test", 1, 1))
        }

        assertNull(cache.readTree(account, "post-000", null))
        assertTrue(cache.readTree(account, "post-101", null) != null)
        assertNull(cache.readHeader(account, "post-000"))
        assertTrue(cache.readHeader(account, "post-201") != null)
    }
}
