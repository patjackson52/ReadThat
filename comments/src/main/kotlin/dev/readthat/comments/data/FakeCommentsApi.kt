package dev.readthat.comments.data

import dev.readthat.comments.domain.CommentNode
import dev.readthat.comments.domain.CommentTree
import dev.readthat.comments.domain.LoadMoreResponse
import dev.readthat.comments.domain.PostHeader
import dev.readthat.comments.domain.RawComment
import kotlinx.coroutines.delay
import java.util.PriorityQueue
import kotlin.random.Random

/**
 * The in-process comments "server".
 *
 * Implements the tree-construction algorithm Reddit published, because the shape of
 * the output — and specifically *where the LoadMore cursors land* — is the whole
 * reason the two-phase merge is tricky:
 *
 *   1. push all root comments into a max-heap by score
 *   2. pop the highest, attach it under its parent
 *   3. push that comment's children back in as candidates
 *   4. repeat until the requested count is exhausted
 *   5. group whatever is left in the heap by parent into `load_more` cursors
 *
 * Depth is capped (Reddit uses 10) "to limit the computational cost and make it
 * easier to render from a mobile platform UX perspective" — anything deeper becomes
 * a "more replies" cursor instead.
 *
 * The consequence that matters: **a bigger count does not just append.** Because the
 * heap is score-ordered globally, a count=200 tree expands children under comments
 * that were childless leaves in the count=8 tree. That is precisely the auto-
 * expansion flicker.
 */
open class FakeCommentsApi(
    private val latencyMs: Long = 0,
    private val seed: Int = 7,
    private val rootCount: Int = 12,
    private val maxChildrenPerComment: Int = 4,
    private val totalComments: Int = 220,
    /**
     * COHERENCE SEAM. The comments "server" must answer about the same posts the
     * feed shows, so the app injects the feed's canonical catalog here. Header
     * data comes from it, and the generated tree is sized by its commentCount —
     * a post the feed says has 37 comments must not sprout 220.
     */
    private val postCatalog: (String) -> PostHeader? = { null },
) : CommentsRemoteSource {
    /** Per-post corpus: each post gets its own deterministic tree. */
    private class PostData(val raw: List<RawComment>) {
        val byParent: Map<String?, List<RawComment>> = raw.groupBy { it.parentId }
        val byId: Map<String, RawComment> = raw.associateBy { it.id }
    }

    private val perPost = java.util.concurrent.ConcurrentHashMap<String, PostData>()

    private fun dataFor(postId: String): PostData = perPost.getOrPut(postId) {
        val budget = postCatalog(postId)?.commentCount?.coerceAtMost(totalComments)
            ?: totalComments
        PostData(
            generate(
                // stable per-post mix so post_1 and post_2 get different trees,
                // deterministically
                seed = seed + (postId.hashCode() and 0xffff),
                rootCount = rootCount,
                maxChildren = maxChildrenPerComment,
                total = budget,
            ).withTotalDescendantCounts(),
        )
    }

    /** Test hook: which (count, depth) pairs were requested. */
    private val _requests = mutableListOf<Pair<Int, Int>>()
    val requests: List<Pair<Int, Int>> get() = _requests.toList()

    /**
     * [rootCommentId] is the permalink / "continue this thread" fetch: the tree is
     * built from that comment's CHILDREN, re-rooted at depth 0 — matching Reddit,
     * where a deep permalink renders its target at the left margin. The client
     * cannot derive absolute depth on that screen and does not need to.
     */
    override suspend fun fetchTree(
        postId: String,
        maxCount: Int,
        maxDepth: Int,
        rootCommentId: String?,
    ): CommentTree {
        if (latencyMs > 0) delay(latencyMs)
        _requests += maxCount to maxDepth
        return buildTree(postId, maxCount, maxDepth, rootCommentId)
    }

    /**
     * Synchronous, zero-latency catalog lookup. The catalog is data the client
     * effectively already has (the feed rendered this post), so the detail screen
     * may use it on its FIRST frame — a header that arrives one RTT later shoves
     * the whole comment list down, animated. Null when the catalog doesn't know
     * the post; callers fall back to [fetchPostHeader].
     */
    override fun peekPostHeader(postId: String): PostHeader? = postCatalog(postId)

    override suspend fun fetchPostHeader(postId: String): PostHeader {
        if (latencyMs > 0) delay(latencyMs)
        return postCatalog(postId) ?: PostHeader(
            postId = postId,
            title = "What tradeoff did your team get wrong before getting it right?",
            author = "u/op_${postId}",
            subreddit = "r/ExperiencedDevs",
            score = 4_182,
            commentCount = dataFor(postId).raw.size,
        )
    }

    /**
     * The /api/morechildren analogue.
     *
     * Stateless on purpose: the cursor carries [CommentNode.LoadMore.childIds], so the
     * server needs no memory of what this client already has — it seeds the same
     * max-heap with exactly those ids (at their true structural depths) and continues
     * the selection where fetchTree left off. Leftovers become fresh cursors, exactly
     * as in the initial build.
     *
     * The response is FLAT and parent-linked — each comment names its own parent —
     * because the returned nodes can span several parents and depths. This is the
     * payload shape that makes render depth underivable from the response alone:
     * the splicer must re-derive it from the tree it splices into.
     */
    override suspend fun loadMore(
        postId: String,
        cursor: CommentNode.LoadMore,
        limit: Int,
        maxDepth: Int,
    ): LoadMoreResponse {
        if (latencyMs > 0) delay(latencyMs)

        val data = dataFor(postId)
        val heap = PriorityQueue<Candidate>(compareByDescending<Candidate> { it.raw.score }
            .thenBy { it.raw.id })
        cursor.childIds.mapNotNull { data.byId[it] }
            .forEach { heap += Candidate(it, depth = structuralDepth(data, it)) }

        val out = ArrayList<RawComment>(limit)
        val cursors = ArrayList<CommentNode.LoadMore>()

        while (heap.isNotEmpty() && out.size < limit) {
            val candidate = heap.poll()
            out += candidate.raw

            val children = data.byParent[candidate.raw.id].orEmpty()
            if (candidate.depth + 1 <= maxDepth) {
                children.forEach { heap += Candidate(it, candidate.depth + 1) }
            } else if (children.isNotEmpty()) {
                // Depth budget hit again — same rule as the initial build.
                cursors += CommentNode.LoadMore(
                    id = "more_${candidate.raw.id}",
                    parentId = candidate.raw.id,
                    remainingCount = children.size,
                    childIds = children.map { it.id },
                )
            }
        }

        // Count budget hit: whatever is still in the heap becomes cursors grouped by
        // parent — the recursive continuation of the cursor the client just spent.
        val leftoverByParent = LinkedHashMap<String?, MutableList<String>>()
        while (heap.isNotEmpty()) {
            val c = heap.poll()
            leftoverByParent.getOrPut(c.raw.parentId) { mutableListOf() } += c.raw.id
        }
        for ((parentId, ids) in leftoverByParent) {
            cursors += CommentNode.LoadMore(
                id = "more_${parentId ?: "root"}",
                parentId = parentId,
                remainingCount = ids.size,
                childIds = ids,
            )
        }

        return LoadMoreResponse(
            comments = out,
            cursors = cursors,
        )
    }

    /** True depth of a raw comment — the server derives it by walking the parent chain. */
    private fun structuralDepth(data: PostData, raw: RawComment): Int {
        var depth = 0
        var parent = raw.parentId
        while (parent != null) {
            depth++
            parent = data.byId[parent]?.parentId
        }
        return depth
    }

    private fun buildTree(
        postId: String,
        maxCount: Int,
        maxDepth: Int,
        rootCommentId: String? = null,
    ): CommentTree {
        val data = dataFor(postId)
        // Max-heap on score. Ties broken by id so the output is deterministic.
        val heap = PriorityQueue<Candidate>(compareByDescending<Candidate> { it.raw.score }
            .thenBy { it.raw.id })

        data.byParent[rootCommentId].orEmpty().forEach { heap += Candidate(it, depth = 0) }

        val selected = LinkedHashMap<String, Selected>()
        var taken = 0

        while (heap.isNotEmpty() && taken < maxCount) {
            val candidate = heap.poll()
            selected[candidate.raw.id] = Selected(candidate.raw, candidate.depth)
            taken++

            val children = data.byParent[candidate.raw.id].orEmpty()
            if (candidate.depth + 1 <= maxDepth) {
                children.forEach { heap += Candidate(it, candidate.depth + 1) }
            } else if (children.isNotEmpty()) {
                // Depth budget hit: everything below becomes a "more replies" cursor.
                selected[candidate.raw.id] = selected.getValue(candidate.raw.id)
                    .copy(truncatedChildIds = children.map { it.id })
            }
        }

        // Whatever is left in the heap becomes load_more cursors grouped by parent.
        // The ids are kept, not just counted — the cursor carries them (see
        // CommentNode.LoadMore.childIds) so a later loadMore call can name exactly
        // what it wants, the way /api/morechildren does.
        val leftoverByParent = HashMap<String?, MutableList<String>>()
        while (heap.isNotEmpty()) {
            val c = heap.poll()
            leftoverByParent.getOrPut(c.raw.parentId) { mutableListOf() } += c.raw.id
        }

        return CommentTree(
            postId = postId,
            roots = assemble(data, rootCommentId, 0, selected, leftoverByParent),
            requestedCount = maxCount,
            requestedDepth = maxDepth,
        )
    }

    private fun assemble(
        data: PostData,
        parentId: String?,
        depth: Int,
        selected: Map<String, Selected>,
        leftover: Map<String?, List<String>>,
    ): List<CommentNode> {
        val nodes = ArrayList<CommentNode>()

        data.byParent[parentId].orEmpty()
            .filter { selected.containsKey(it.id) }
            .sortedWith(compareByDescending<RawComment> { it.score }.thenBy { it.id })
            .forEach { rawChild ->
                val sel = selected.getValue(rawChild.id)
                val children = assemble(data, rawChild.id, depth + 1, selected, leftover).toMutableList()

                if (sel.truncatedChildIds.isNotEmpty()) {
                    children += CommentNode.LoadMore(
                        id = "more_${rawChild.id}",
                        parentId = rawChild.id,
                        remainingCount = sel.truncatedChildIds.size,
                        childIds = sel.truncatedChildIds,
                    )
                }

                nodes += CommentNode.Comment(
                    id = rawChild.id,
                    author = rawChild.author,
                    authorDisplayName = rawChild.authorDisplayName,
                    authorAvatarUrl = rawChild.authorAvatarUrl,
                    isEdited = rawChild.isEdited,
                    body = rawChild.body,
                    score = rawChild.score,
                    createdAgoMin = rawChild.createdAgoMin,
                    descendantCount = rawChild.descendantCount,
                    children = children,
                )
            }

        leftover[parentId]?.takeIf { it.isNotEmpty() }?.let { remaining ->
            nodes += CommentNode.LoadMore(
                id = "more_${parentId ?: "root"}",
                parentId = parentId,
                remainingCount = remaining.size,
                childIds = remaining,
            )
        }

        return nodes
    }

    private fun generate(
        seed: Int,
        rootCount: Int,
        maxChildren: Int,
        total: Int,
    ): List<RawComment> {
        val rng = Random(seed)
        val out = ArrayList<RawComment>(total)
        var n = 0

        fun make(parentId: String?): RawComment {
            val id = "c${n++}"
            return RawComment(
                id = id,
                parentId = parentId,
                author = "u/user${id.drop(1)}",
                authorDisplayName = "User ${id.drop(1)}",
                body = BODIES[rng.nextInt(BODIES.size)],
                score = rng.nextInt(1, 9_000),
                // Hash-derived, NOT an rng draw: consuming an extra draw here would
                // shift every downstream score and silently reshape the whole tree.
                createdAgoMin = 2 + (id.hashCode() and 0x7fffffff) % (60 * 24 * 3),
                isEdited = n % 7 == 0,
            )
        }

        val roots = List(rootCount) { make(null) }
        out += roots

        // Breadth-first growth so the tree has real depth without exploding.
        var frontier = roots
        while (out.size < total && frontier.isNotEmpty()) {
            val next = ArrayList<RawComment>()
            for (parent in frontier) {
                if (out.size >= total) break
                repeat(rng.nextInt(0, maxChildren + 1)) {
                    if (out.size < total) {
                        val child = make(parent.id)
                        out += child
                        next += child
                    }
                }
            }
            frontier = next
        }
        return out
    }

    private data class Candidate(val raw: RawComment, val depth: Int)

    private data class Selected(
        val raw: RawComment,
        val depth: Int,
        val truncatedChildIds: List<String> = emptyList(),
    )

    /** Generated rows are parent-before-child, so one reverse pass computes full subtree totals. */
    private fun List<RawComment>.withTotalDescendantCounts(): List<RawComment> {
        val counts = HashMap<String, Int>(size)
        for (index in indices.reversed()) {
            val raw = this[index]
            val count = counts[raw.id] ?: 0
            counts[raw.id] = count
            raw.parentId?.let { parentId ->
                counts[parentId] = (counts[parentId] ?: 0) + 1 + count
            }
        }
        return map { it.copy(descendantCount = counts[it.id] ?: 0) }
    }

    companion object {
        const val DEFAULT_MAX_DEPTH = 10

        /** The two sizes Reddit's clients request. */
        const val FIRST_PHASE_COUNT = 8
        const val SECOND_PHASE_COUNT = 200

        private val BODIES = listOf(
            "This is exactly the tradeoff I was going to mention.",
            "Source? The **published design** is in this [engineering write-up](https://www.reddit.com/r/RedditEng/).",
            "Depth is capped at 10 for a reason — try rendering deeper on a low-end device.",
            "The heap ordering is what makes the merge non-trivial.\n\n- Keep stable ids\n- Merge in the background\n  - Preserve visible branches",
            "Agreed, but only for the first page.",
            "> Measure the first frame before optimizing.\n\nCounterpoint: the **cache hit rate** matters more than the tree size.",
        )
    }
}
