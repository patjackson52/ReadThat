package dev.readthat.comments

import dev.readthat.comments.data.CommentsRepository
import dev.readthat.comments.data.FakeCommentsApi
import dev.readthat.comments.domain.CommentNode
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repository's TTI contract:
 *
 *  - prefetch and load COALESCE: a tap during an in-flight prefetch must await it,
 *    not race it with a duplicate request (this is part of why Reddit's published
 *    ~40k req/s prefetch bill is survivable)
 *  - a revisited post serves phase 1 from the last full tree with zero fetches —
 *    Reddit's fixed (count, depth) sizes exist to make responses cacheable, so the
 *    client should actually behave like a cache client
 */
class RepositoryCoalescingTest {

    private class CountingApi(latencyMs: Long = 0) : FakeCommentsApi(latencyMs = latencyMs) {
        var fetches = 0
        override suspend fun fetchTree(
            postId: String,
            maxCount: Int,
            maxDepth: Int,
            rootCommentId: String?,
        ) = super.fetchTree(postId, maxCount, maxDepth, rootCommentId).also { fetches++ }
    }

    @Test
    fun `load during in-flight prefetch awaits it instead of double-fetching`() = runTest {
        val api = CountingApi(latencyMs = 50)
        val repo = CommentsRepository(api)

        val prefetch = async { repo.prefetch("p1") }
        runCurrent() // prefetch starts and suspends on its (virtual) network latency
        // Tap-through happens while the prefetch is still on the wire.
        val phases = repo.load("p1").toList()
        prefetch.await()

        assertTrue((phases.first() as CommentsRepository.Phase.Initial).fromPrefetch)
        // one phase-1 fetch (shared) + one phase-2 fetch — never three
        assertEquals(2, api.fetches)
    }

    @Test
    fun `revisiting a post serves the cached full tree with no network`() = runTest {
        val api = CountingApi()
        val repo = CommentsRepository(api)

        repo.load("p1").toList()          // first visit: phase 1 + phase 2
        val fetchesAfterFirst = api.fetches

        val revisit = repo.load("p1").toList()

        assertEquals(fetchesAfterFirst, api.fetches) // zero new fetches
        val initial = revisit.first() as CommentsRepository.Phase.Initial
        // the cached tree is the FULL one — better than a fresh phase 1
        assertEquals(FakeCommentsApi.SECOND_PHASE_COUNT, initial.tree.requestedCount)
    }

    @Test
    fun `loadMore passes through to the api`() = runTest {
        val api = CountingApi()
        val repo = CommentsRepository(api)
        val tree = api.fetchTree("p1", FakeCommentsApi.FIRST_PHASE_COUNT)
        val cursor = sequence {
            val stack = ArrayDeque(tree.roots)
            while (stack.isNotEmpty()) {
                when (val n = stack.removeLast()) {
                    is CommentNode.Comment -> n.children.forEach(stack::addLast)
                    is CommentNode.LoadMore -> yield(n)
                }
            }
        }.first()

        val response = repo.loadMore("p1", cursor, limit = 10)

        assertTrue(response.comments.isNotEmpty())
    }
}
