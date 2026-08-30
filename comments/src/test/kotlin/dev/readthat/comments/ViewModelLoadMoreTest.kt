package dev.readthat.comments

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.readthat.comments.data.CommentsRepository
import dev.readthat.comments.data.FakeCommentsApi
import dev.readthat.comments.domain.CommentNode
import dev.readthat.comments.domain.CommentRow
import dev.readthat.comments.domain.LoadMoreResponse
import dev.readthat.comments.ui.CommentsUiState
import dev.readthat.comments.ui.CommentsViewModel
import dev.readthat.comments.ui.LoadMoreState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The load-more state machine and the collapse-intent split, at the ViewModel layer.
 * All pure JVM: virtual time, no emulator.
 */
class ViewModelLoadMoreTest {

    private val dispatcher = StandardTestDispatcher()

    init { Dispatchers.setMain(dispatcher) }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(
        repo: CommentsRepository,
        handle: SavedStateHandle = SavedStateHandle(mapOf("postId" to "p1")),
    ) = CommentsViewModel(repo, handle, flattenDispatcher = dispatcher)

    private fun runVmTest(block: suspend TestScope.() -> Unit) =
        runTest(dispatcher) { block() }

    private suspend fun app.cash.turbine.ReceiveTurbine<CommentsUiState>.awaitLoaded(): CommentsUiState {
        var s = awaitItem()
        // Two-stage derivation means an emission can briefly pair the NEW tree's
        // totals with the OLD tree's render (the flatten is still on the worker).
        // Wait until the render itself reflects the merged tree, or any row key
        // taken from it may no longer exist — which the VM treats as a no-op tap.
        while (s.totalComments <= 8 || s.isLoadingFull || s.render.visibleCommentCount <= 8) {
            s = awaitItem()
        }
        return s
    }

    private fun CommentsUiState.firstCursor(): CommentRow.LoadMore =
        render.rows.filterIsInstance<CommentRow.LoadMore>().first()

    private class CountingApi(
        var failNextLoadMore: Boolean = false,
        latencyMs: Long = 0,
    ) : FakeCommentsApi(latencyMs = latencyMs) {
        var loadMoreCalls = 0
        override suspend fun loadMore(
            postId: String,
            cursor: CommentNode.LoadMore,
            limit: Int,
            maxDepth: Int,
        ): LoadMoreResponse {
            loadMoreCalls++
            if (failNextLoadMore) {
                failNextLoadMore = false
                throw RuntimeException("boom")
            }
            return super.loadMore(postId, cursor, limit, maxDepth)
        }
    }

    @Test
    fun `transient loading flags never re-run the flatten`() = runVmTest {
        val vm = vm(CommentsRepository(FakeCommentsApi(latencyMs = 10)))
        vm.uiState.test {
            // Capture the state where phase 1 has rendered but phase 2 is in flight.
            var mid = awaitItem()
            while (mid.isEmpty || !mid.isLoadingFull) mid = awaitItem()
            val renderDuring = mid.render

            var done = awaitItem()
            while (done.isLoadingFull) done = awaitItem()

            // Same tree + same collapse set across the isLoadingFull flip that ended
            // phase 2? Then the render must be the SAME INSTANCE — flag churn is not
            // allowed to re-walk the tree. (The instance changes only when the merge
            // lands a bigger tree, which is a different emission.)
            if (done.totalComments == mid.totalComments) {
                assertSame(renderDuring, done.render)
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `tapping a cursor splices new rows in and clears its loading state`() = runVmTest {
        val vm = vm(CommentsRepository(FakeCommentsApi()))
        vm.uiState.test {
            val loaded = awaitLoaded()
            val cursor = loaded.firstCursor()
            val commentsBefore = loaded.render.visibleCommentCount

            vm.loadMore(cursor.key)

            // Done = the cursor row is gone and no load-more is in flight. Row COUNT
            // is the wrong predicate: a 1-child cursor replaced by 1 comment is a
            // legitimate net-zero row change.
            var after = awaitItem()
            while (after.render.rows.any { it.key == cursor.key } || after.loadMoreStates.isNotEmpty()) {
                after = awaitItem()
            }
            assertTrue(after.render.visibleCommentCount > commentsBefore)
            // no duplicate keys — splice + flatten stayed consistent
            assertEquals(after.render.rows.size, after.render.rows.map { it.key }.toSet().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `double-tapping a cursor issues exactly one request`() = runVmTest {
        val api = CountingApi(latencyMs = 50)
        val vm = vm(CommentsRepository(api))
        vm.uiState.test {
            val loaded = awaitLoaded()
            val cursor = loaded.firstCursor()

            vm.loadMore(cursor.key)
            vm.loadMore(cursor.key) // double-tap — the norm, not the exception

            // Drive the scheduler to quiescence instead of counting emissions —
            // unrelated emissions (e.g. the header arriving) can interleave.
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(1, api.loadMoreCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `viewport prefetch progressively expands a nearby cursor`() = runVmTest {
        val api = CountingApi()
        val vm = vm(CommentsRepository(api))
        vm.uiState.test {
            val loaded = awaitLoaded()
            val cursor = loaded.firstCursor()
            val rowIndex = loaded.render.rows.indexOfFirst { it.key == cursor.key }

            // LazyColumn index zero is the post header.
            vm.onViewport(rowIndex + 1, rowIndex + 1)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, api.loadMoreCalls)
            assertFalse(vm.uiState.value.render.rows.any { it.key == cursor.key })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `failed loadMore lands in Error and a retry tap succeeds`() = runVmTest {
        val api = CountingApi(failNextLoadMore = true)
        val vm = vm(CommentsRepository(api))
        vm.uiState.test {
            val loaded = awaitLoaded()
            val cursor = loaded.firstCursor()

            vm.loadMore(cursor.key)
            var errored = awaitItem()
            while (errored.loadMoreStates[cursor.key] !is LoadMoreState.Error) {
                errored = awaitItem()
            }
            assertNotNull(errored.loadMoreStates[cursor.key])

            vm.loadMore(cursor.key) // retry from Error must not be deduped away
            var after = awaitItem()
            while (after.render.rows.any { it.key == cursor.key }) after = awaitItem()
            assertEquals(2, api.loadMoreCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `viewport prefetch never turns an error into an automatic retry loop`() = runVmTest {
        val api = CountingApi(failNextLoadMore = true)
        val vm = vm(CommentsRepository(api))
        vm.uiState.test {
            val loaded = awaitLoaded()
            val cursor = loaded.firstCursor()
            vm.loadMore(cursor.key)
            var errored = awaitItem()
            while (errored.loadMoreStates[cursor.key] !is LoadMoreState.Error) errored = awaitItem()
            val rowIndex = errored.render.rows.indexOfFirst { it.key == cursor.key }

            vm.onViewport(rowIndex + 1, rowIndex + 1)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, api.loadMoreCalls)
            assertTrue(vm.uiState.value.loadMoreStates[cursor.key] is LoadMoreState.Error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadMore during phase-2 is ignored - the serialize decision`() = runVmTest {
        val api = CountingApi(latencyMs = 50)
        val vm = vm(CommentsRepository(api))
        vm.uiState.test {
            // Phase 1 rendered, phase 2 still in flight.
            var mid = awaitItem()
            while (mid.isEmpty || !mid.isLoadingFull) mid = awaitItem()
            val cursor = mid.render.rows.filterIsInstance<CommentRow.LoadMore>().firstOrNull()

            if (cursor != null) vm.loadMore(cursor.key)

            var done = awaitItem()
            while (done.isLoadingFull) done = awaitItem()
            // The tap during the merge window did nothing — no request, no state.
            assertEquals(0, api.loadMoreCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `user collapse persists through SavedStateHandle to a recreated ViewModel`() = runVmTest {
        val handle = SavedStateHandle(mapOf("postId" to "p1"))
        val repo = CommentsRepository(FakeCommentsApi())
        val first = CommentsViewModel(repo, handle, flattenDispatcher = dispatcher)
        var targetKey = ""
        first.uiState.test {
            val loaded = awaitLoaded()
            // Deliberately pick a node the MERGER did not touch: an auto-collapsed
            // target would make the first tap clear the artifact instead of
            // recording user intent.
            targetKey = loaded.render.rows
                .filterIsInstance<CommentRow.Comment>()
                .first { it.hasChildren && !it.isCollapsed }.key
            first.toggleCollapse(targetKey)
            var collapsed = awaitItem()
            while (!(collapsed.render.rows.first { it.key == targetKey } as CommentRow.Comment).isCollapsed) {
                collapsed = awaitItem()
            }
            cancelAndIgnoreRemainingEvents()
        }

        // Process death: new VM, same handle.
        val second = CommentsViewModel(repo, handle, flattenDispatcher = dispatcher)
        second.uiState.test {
            var s = awaitItem()
            while (s.render.rows.none { it.key == targetKey }) s = awaitItem()
            val row = s.render.rows.first { it.key == targetKey } as CommentRow.Comment
            assertTrue("user's collapse survived process death", row.isCollapsed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `expanding an auto-collapsed node clears the artifact but is not user intent`() = runVmTest {
        val handle = SavedStateHandle(mapOf("postId" to "p1"))
        val vm = CommentsViewModel(
            CommentsRepository(FakeCommentsApi(latencyMs = 10)), handle,
            flattenDispatcher = dispatcher,
        )
        vm.uiState.test {
            val loaded = awaitLoaded()
            // The merge auto-collapsed the leaves that gained children. Find one:
            // collapsed on screen, but never touched by the user.
            val auto = loaded.render.rows.filterIsInstance<CommentRow.Comment>()
                .firstOrNull { it.isCollapsed }
            if (auto != null) {
                vm.toggleCollapse(auto.key) // user expands it
                var after = awaitItem()
                while ((after.render.rows.first { it.key == auto.key } as CommentRow.Comment).isCollapsed) {
                    after = awaitItem()
                }
                // …and the handle records NO user collapse — artifacts are not intent.
                val persisted: List<String>? = handle["collapsedIds"]
                assertTrue(persisted.isNullOrEmpty())
                assertFalse((after.render.rows.first { it.key == auto.key } as CommentRow.Comment).isCollapsed)
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `expanding a comment opens direct children and collapses grandchildren`() = runVmTest {
        val vm = vm(CommentsRepository(FakeCommentsApi(latencyMs = 10)))
        vm.uiState.test {
            var expanded = awaitLoaded()
            if (expanded.render.hiddenByCollapse > 0) {
                vm.expandAll()
                do {
                    expanded = awaitItem()
                } while (expanded.render.hiddenByCollapse > 0)
            }

            val rows = expanded.render.rows
            val parentIndex = rows.indices.first { index ->
                val parent = rows[index] as? CommentRow.Comment ?: return@first false
                rows.drop(index + 1)
                    .takeWhile { it.renderDepth > parent.renderDepth }
                    .any { it.renderDepth == parent.renderDepth + 2 }
            }
            val parent = rows[parentIndex] as CommentRow.Comment
            val parentSubtree = rows.drop(parentIndex + 1)
                .takeWhile { it.renderDepth > parent.renderDepth }
            val directChild = parentSubtree.filterIsInstance<CommentRow.Comment>()
                .first { it.renderDepth == parent.renderDepth + 1 }
            val grandchild = parentSubtree.filterIsInstance<CommentRow.Comment>()
                .first { it.renderDepth == parent.renderDepth + 2 }

            vm.toggleCollapse(parent.key)
            var collapsed = awaitItem()
            while (!(collapsed.render.rows.first { it.key == parent.key } as CommentRow.Comment).isCollapsed) {
                collapsed = awaitItem()
            }

            vm.toggleCollapse(parent.key)
            var reopened = awaitItem()
            while (
                (reopened.render.rows.first { it.key == parent.key } as CommentRow.Comment).isCollapsed ||
                reopened.render.rows.none { it.key == directChild.key } ||
                reopened.render.rows.none { it.key == grandchild.key }
            ) {
                reopened = awaitItem()
            }

            val reopenedDirect = reopened.render.rows.first { it.key == directChild.key }
                as CommentRow.Comment
            val reopenedGrandchild = reopened.render.rows.first { it.key == grandchild.key }
                as CommentRow.Comment
            assertFalse("the next reply level is expanded", reopenedDirect.isCollapsed)
            assertTrue("the following reply level is a collapse boundary", reopenedGrandchild.isCollapsed)

            val grandchildIndex = reopened.render.rows.indexOfFirst { it.key == grandchild.key }
            assertTrue(
                "a collapsed grandchild hides its own subtree",
                reopened.render.rows.drop(grandchildIndex + 1)
                    .takeWhile { it.renderDepth > reopenedGrandchild.renderDepth }
                    .isEmpty(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }
}

class ThreadDetailModeTest {
    private val dispatcher = StandardTestDispatcher()
    init { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `a rootCommentId nav arg loads the re-rooted subtree at depth zero`() =
        runTest(dispatcher) {
            val api = FakeCommentsApi()
            // find a parent with comment children from the full tree
            val full = api.fetchTree("p1", maxCount = 200)
            val parent = sequence {
                val stack = ArrayDeque(full.roots)
                while (stack.isNotEmpty()) {
                    when (val n = stack.removeLast()) {
                        is CommentNode.Comment -> {
                            if (n.children.any { it is CommentNode.Comment }) yield(n)
                            n.children.forEach(stack::addLast)
                        }
                        is CommentNode.LoadMore -> Unit
                    }
                }
            }.first()

            val vm = CommentsViewModel(
                CommentsRepository(api),
                SavedStateHandle(mapOf("postId" to "p1", "rootCommentId" to parent.id)),
                flattenDispatcher = dispatcher,
            )
            vm.uiState.test {
                var s = awaitItem()
                while (s.isEmpty || s.isLoadingInitial) s = awaitItem()
                // re-rooted: first row at renderDepth 0, and it is one of parent's children
                assertEquals(0, s.render.rows.first().renderDepth)
                val childIds = parent.children
                    .filterIsInstance<CommentNode.Comment>().map { it.id }.toSet()
                assertTrue(s.render.rows.first().key in childIds)
                // single-phase: no "loading full" refinement pass on a permalink screen
                assertFalse(s.isLoadingFull)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
