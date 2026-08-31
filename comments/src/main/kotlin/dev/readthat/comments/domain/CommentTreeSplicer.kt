package dev.readthat.comments.domain

/**
 * Splices a flat, parent-linked load-more response into the tree.
 *
 * Two properties worth defending:
 *
 *  1. **The response carries no depth, and none is needed.** Spliced nodes take
 *     their position — and therefore their render depth — from the node they
 *     attach to. The tree is the single source of truth for structure; a wire
 *     depth could only agree with it or contradict it.
 *
 *  2. **This bypasses [CommentTreeMerger] on purpose.** The anti-flicker contract
 *     exists to stop *unrequested* expansion. A load-more tap is the user
 *     explicitly requesting expansion — pop-in here is the desired outcome, and
 *     routing it through the merger would auto-collapse the exact nodes the user
 *     asked to see.
 *
 * Everything is iterative — same rule as [CommentFlattener], for the same
 * pathological-thread reason.
 */
object CommentTreeSplicer {

    /**
     * Returns the tree with [spentCursorId] replaced by the response's nodes.
     * A cursor that no longer exists (e.g. the phase-2 merge already replaced it)
     * returns the tree unchanged — splicing must be safe to lose.
     */
    fun splice(
        tree: CommentTree,
        spentCursorId: String,
        response: LoadMoreResponse,
    ): CommentTree {
        // Locate the spent cursor and build a child->parent-comment map in one walk.
        var spent: CommentNode.LoadMore? = null
        val parentOf = HashMap<String, String?>() // node id -> enclosing comment id
        run {
            val stack = ArrayDeque<Pair<CommentNode, String?>>()
            tree.roots.forEach { stack.addLast(it to null) }
            while (stack.isNotEmpty()) {
                val (node, parent) = stack.removeLast()
                parentOf[node.id] = parent
                when (node) {
                    is CommentNode.Comment -> node.children.forEach { stack.addLast(it to node.id) }
                    is CommentNode.LoadMore -> if (node.id == spentCursorId) spent = node
                }
            }
        }
        val cursor = spent ?: return tree

        // Assemble the flat response into subtrees. Heap-pop order guarantees a
        // parent appears before its children, so a single reverse pass has every
        // child already built by the time its parent needs it.
        val childrenByParent = response.comments.groupBy { it.parentId }
        val cursorsByParent = response.cursors.groupBy { it.parentId }
        val assembled = HashMap<String, CommentNode.Comment>()
        for (i in response.comments.indices.reversed()) {
            val raw = response.comments[i]
            val children = childrenByParent[raw.id].orEmpty().map { assembled.getValue(it.id) } +
                cursorsByParent[raw.id].orEmpty()
            assembled[raw.id] = CommentNode.Comment(
                id = raw.id,
                author = raw.author,
                body = raw.body,
                score = raw.score,
                createdAgoMin = raw.createdAgoMin,
                viewerVote = raw.viewerVote,
                authorDisplayName = raw.authorDisplayName,
                authorAvatarUrl = raw.authorAvatarUrl,
                isEdited = raw.isEdited,
                descendantCount = maxOf(raw.descendantCount, materializedDescendantCount(children)),
                children = children,
            )
        }

        // What stands where the cursor stood: the response's top-level comments
        // (their parent is the cursor's parent) plus any replacement cursors there.
        val replacement: List<CommentNode> =
            childrenByParent[cursor.parentId].orEmpty().map { assembled.getValue(it.id) } +
                cursorsByParent[cursor.parentId].orEmpty()

        fun replaceIn(nodes: List<CommentNode>): List<CommentNode> =
            nodes.flatMap { if (it.id == spentCursorId) replacement else listOf(it) }

        // Root-level cursor: rebuild the root list and stop.
        if (cursor.parentId == null) {
            return tree.copy(roots = replaceIn(tree.roots))
        }

        // Nested cursor: path-copy. Rebuild the direct parent, then copy each
        // ancestor up to the root. Only the spine changes; every untouched subtree
        // keeps its identity (and Compose's diffing benefits from that).
        val byId = HashMap<String, CommentNode.Comment>()
        run {
            val stack = ArrayDeque(tree.roots)
            while (stack.isNotEmpty()) {
                when (val n = stack.removeLast()) {
                    is CommentNode.Comment -> { byId[n.id] = n; n.children.forEach(stack::addLast) }
                    is CommentNode.LoadMore -> Unit
                }
            }
        }

        var childId: String = cursor.parentId
        var rebuilt: CommentNode.Comment =
            byId.getValue(childId).let { it.withChildren(replaceIn(it.children)) }

        while (true) {
            val parentId = parentOf[childId] ?: break
            val parent = byId.getValue(parentId)
            rebuilt = parent.withChildren(
                parent.children.map { if (it.id == childId) rebuilt else it },
            )
            childId = parentId
        }

        return tree.copy(roots = tree.roots.map { if (it.id == childId) rebuilt else it })
    }
}
