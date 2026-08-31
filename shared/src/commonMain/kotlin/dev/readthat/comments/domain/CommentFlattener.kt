package dev.readthat.comments.domain

/**
 * A single row in the rendered list.
 *
 * [renderDepth] drives indentation. [collapsedDescendants] is what the collapsed-comment
 * affordance shows.
 */
sealed interface CommentRow {
    val key: String
    /** Depth on THIS SCREEN — always relative to this screen's root, always derived
     *  by the walk below. Distinct from structural depth, which is the server's
     *  business and never crosses the wire. */
    val renderDepth: Int

    data class Comment(
        override val key: String,
        override val renderDepth: Int,
        val author: String,
        val authorDisplayName: String,
        val authorAvatarUrl: String?,
        val body: String,
        /** Numeric values retained for shared presentation policies such as comment sorting. */
        val score: Int = 0,
        val scoreLabel: String,
        val viewerVote: Int = 0,
        val createdAgoMinutes: Int = 0,
        val ageLabel: String,
        val isEdited: Boolean,
        val isCollapsed: Boolean,
        val hasChildren: Boolean,
        /** Number of hidden descendants; 0 unless collapsed. */
        val collapsedDescendants: Int,
    ) : CommentRow

    data class LoadMore(
        override val key: String,
        override val renderDepth: Int,
        val label: String,
        val parentId: String?,
    ) : CommentRow

    /**
     * The depth-cap affordance. NOT a load-more: tapping this NAVIGATES to a fresh
     * screen re-rooted at [parentId] (Reddit's permalink behavior — depth restarts
     * at 0 there). Load-more expands in place; this changes context. Different
     * intent, different row type, different metric.
     */
    data class ContinueThread(
        override val key: String,
        override val renderDepth: Int,
        val parentId: String?,
    ) : CommentRow
}

data class CommentRenderList(
    val rows: List<CommentRow>,
    val visibleCommentCount: Int,
    val hiddenByCollapse: Int,
)

/**
 * FLATTENING, take two — and a genuinely harder problem than the feed's.
 *
 * The feed flattens two fixed levels (Group -> Cell). This flattens an arbitrarily
 * deep recursive tree into a list, while honouring:
 *
 *   - **depth**, carried onto each row for indentation
 *   - **collapse**, which must hide an entire *subtree*, not just one node
 *   - **LoadMore cursors**, which sit at the depth of the children they stand in for
 *   - **stable keys**, same requirement as the feed — duplicate keys crash LazyColumn
 *
 * Collapse is the interesting one. A collapsed comment stays visible (you need
 * something to tap to expand it) but every descendant disappears, and the node
 * reports how many it swallowed.
 *
 * Implemented iteratively with an explicit stack rather than recursively: comment
 * threads on Reddit can be thousands deep in pathological cases, and a recursive
 * walk on the main thread is a StackOverflowError waiting to happen.
 */
object CommentFlattener {

    /** Render-depth threshold at which a cursor becomes "continue this thread". */
    const val CONTINUE_THREAD_DEPTH = 10

    fun flatten(
        tree: CommentTree,
        collapsedIds: Set<String> = emptySet(),
        continueThreadDepth: Int = CONTINUE_THREAD_DEPTH,
    ): CommentRenderList {
        val rows = ArrayList<CommentRow>()
        var visible = 0
        var hidden = 0

        // Explicit stack of (node, depth), pushed in reverse so siblings pop in order.
        val stack = ArrayDeque<Pair<CommentNode, Int>>()
        for (node in tree.roots.asReversed()) stack.addLast(node to 0)

        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()

            when (node) {
                is CommentNode.Comment -> {
                    val collapsed = node.id in collapsedIds
                    // Authored during the server's existing bottom-up heap-tree assembly. Reading
                    // it here keeps a collapse O(1) instead of walking the hidden subtree on the
                    // client; flattening can stop as soon as it reaches the collapsed boundary.
                    val descendants = if (collapsed) node.descendantCount.coerceAtLeast(0) else 0

                    rows += CommentRow.Comment(
                        key = node.id,
                        renderDepth = depth,
                        author = node.author,
                        authorDisplayName = node.authorDisplayName,
                        authorAvatarUrl = node.authorAvatarUrl,
                        body = node.body,
                        score = node.score,
                        scoreLabel = compactScore(node.score),
                        viewerVote = node.viewerVote,
                        createdAgoMinutes = node.createdAgoMin,
                        ageLabel = compactAge(node.createdAgoMin),
                        isEdited = node.isEdited,
                        isCollapsed = collapsed,
                        // "Has children" means has real *comment* children. A node
                        // whose only child is a LoadMore cursor is a leaf on screen —
                        // and it is exactly the node that sprouts replies when the
                        // count=200 tree lands. Keeping this consistent with
                        // CommentTreeMerger's definition is what makes the
                        // anti-flicker logic correct.
                        hasChildren = node.children.any { it is CommentNode.Comment },
                        collapsedDescendants = descendants,
                    )
                    visible++

                    if (collapsed) {
                        hidden += descendants
                    } else {
                        for (child in node.children.asReversed()) {
                            stack.addLast(child to depth + 1)
                        }
                    }
                }

                is CommentNode.LoadMore -> {
                    // At the render-depth threshold the cursor stops being an
                    // expand-in-place and becomes a navigation. The SERVER's depth
                    // cap (what gets sent) and this threshold (what gets indented)
                    // are separate knobs owned by separate sides — they merely both
                    // default to 10.
                    if (depth >= continueThreadDepth) {
                        rows += CommentRow.ContinueThread(
                            key = node.id,
                            renderDepth = depth,
                            parentId = node.parentId,
                        )
                    } else {
                        rows += CommentRow.LoadMore(
                            key = node.id,
                            renderDepth = depth,
                            label = if (node.parentId == null) {
                                "Load ${node.remainingCount} more comments"
                            } else {
                                "${node.remainingCount} more replies"
                            },
                            parentId = node.parentId,
                        )
                    }
                }
            }
        }

        return CommentRenderList(
            rows = rows,
            visibleCommentCount = visible,
            hiddenByCollapse = hidden,
        )
    }

    fun compactAge(minutes: Int): String = when {
        minutes < 60 -> "${minutes}m"
        minutes < 60 * 24 -> "${minutes / 60}h"
        else -> "${minutes / (60 * 24)}d"
    }

    fun compactScore(n: Int): String = when {
        n >= 1_000 -> {
            val v = kotlin.math.round(n / 100.0) / 10.0
            (if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()) + "k"
        }
        else -> n.toString()
    }
}
