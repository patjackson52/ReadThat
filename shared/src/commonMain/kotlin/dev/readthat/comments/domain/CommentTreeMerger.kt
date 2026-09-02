package dev.readthat.comments.domain

/**
 * THE TWO-PHASE MERGE — and the flicker bug that comes with it.
 *
 * Reddit's post-detail screen makes two requests:
 *
 *   1. count=8,   depth=10   -> render immediately, user starts reading
 *   2. count=200, depth=10   -> merge in behind them
 *
 * The published catch, in their words: *"comment trees with different counts will be
 * built with a different number of expanded child comments. So when the 200-count
 * fetch completes, the user will suddenly see a bunch of child comments expanding
 * automatically. This leads to a jarring UX."*
 *
 * Reddit fixed it server-side, by making the number of uncollapsed child comments
 * identical across both tree sizes. This models the client-side half of the same
 * contract:
 *
 *   **Anything already on screen keeps its position and its expansion state.**
 *   The larger tree may only ADD — deeper replies under already-expanded parents,
 *   new roots at the bottom, and replaced LoadMore cursors. It may never
 *   auto-expand something the small tree showed collapsed, and it may never
 *   reorder what the user is already looking at.
 */
object CommentTreeMerger {

    data class MergeResult(
        val tree: CommentTree,
        /**
         * Ids the MERGER decided to collapse — nodes that were childless on screen
         * and would have visibly popped open. These are anti-flicker artifacts, not
         * user intent: the caller keeps them in a separate set, never persists them,
         * and never announces them as "Collapsed" to accessibility services.
         */
        val autoCollapsedIds: Set<String>,
        val addedComments: Int,
    )

    /**
     * @param existing the already-rendered small tree (may be null on first load)
     * @param incoming the larger tree
     * @param collapsedIds ids the *user* has explicitly collapsed — always preserved
     */
    fun merge(
        existing: CommentTree?,
        incoming: CommentTree,
        @Suppress("UNUSED_PARAMETER") collapsedIds: Set<String> = emptySet(),
    ): MergeResult {
        // A sort change is a different ranked snapshot, not phase two of the
        // tree already on screen. Preserving the old root order here would
        // silently turn every non-default sort back into the previous one.
        if (existing == null || existing.sort != incoming.sort) {
            return MergeResult(incoming, emptySet(), incoming.commentCount)
        }

        // Every comment the user could already see, and whether it was a leaf on
        // screen. A node that was a childless leaf in the small tree but gains
        // children in the large tree is exactly the auto-expansion case.
        val previouslyVisible = HashMap<String, Boolean>() // id -> hadChildrenOnScreen
        collectVisible(existing.roots, previouslyVisible)

        val autoExpanded = LinkedHashSet<String>()
        markAutoExpansions(incoming.roots, previouslyVisible, autoExpanded)

        // Ordering rule: roots the user already saw keep their original order and sit
        // first; genuinely new roots append below.
        val existingRootOrder = existing.roots.mapNotNull { (it as? CommentNode.Comment)?.id }
        val incomingById = incoming.roots
            .filterIsInstance<CommentNode.Comment>()
            .associateBy { it.id }

        val orderedRoots = ArrayList<CommentNode>(incoming.roots.size)
        for (id in existingRootOrder) incomingById[id]?.let { orderedRoots += it }
        for (node in incoming.roots) {
            when (node) {
                is CommentNode.Comment -> if (node.id !in existingRootOrder) orderedRoots += node
                is CommentNode.LoadMore -> orderedRoots += node
            }
        }

        val merged = incoming.copy(roots = orderedRoots)

        return MergeResult(
            tree = merged,
            autoCollapsedIds = autoExpanded,
            addedComments = merged.commentCount - existing.commentCount,
        )
    }

    private fun collectVisible(nodes: List<CommentNode>, into: MutableMap<String, Boolean>) {
        val stack = ArrayDeque<CommentNode>()
        nodes.forEach(stack::addLast)
        while (stack.isNotEmpty()) {
            when (val n = stack.removeLast()) {
                is CommentNode.Comment -> {
                    into[n.id] = n.children.any { it is CommentNode.Comment }
                    n.children.forEach(stack::addLast)
                }
                is CommentNode.LoadMore -> Unit
            }
        }
    }

    /**
     * Find nodes that were visible-and-childless before but have children now.
     * Those are the ones that would visibly pop open on merge, so they get collapsed.
     */
    private fun markAutoExpansions(
        nodes: List<CommentNode>,
        previouslyVisible: Map<String, Boolean>,
        into: MutableSet<String>,
    ) {
        val stack = ArrayDeque<CommentNode>()
        nodes.forEach(stack::addLast)
        while (stack.isNotEmpty()) {
            when (val n = stack.removeLast()) {
                is CommentNode.Comment -> {
                    val hadChildrenBefore = previouslyVisible[n.id]
                    val hasChildrenNow = n.children.any { it is CommentNode.Comment }
                    if (hadChildrenBefore == false && hasChildrenNow) into += n.id
                    n.children.forEach(stack::addLast)
                }
                is CommentNode.LoadMore -> Unit
            }
        }
    }
}
