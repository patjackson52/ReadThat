package dev.readthat.comments.domain

/**
 * Immutable comment-tree edits without recursive calls.
 *
 * Only the path from the edited node to the root is copied. Untouched subtrees
 * keep referential identity, which limits Compose diff work and avoids a stack
 * overflow for pathological threads thousands of levels deep.
 */
object CommentTreeEditor {
    fun insert(
        roots: List<CommentNode>,
        parentId: String?,
        created: CommentNode.Comment,
    ): List<CommentNode> {
        if (parentId == null) return roots + created
        return edit(roots, parentId) { parent ->
            parent.withChildren(parent.children + created)
        }
    }

    fun updateVote(
        roots: List<CommentNode>,
        commentId: String,
        value: Int,
        score: Int,
    ): List<CommentNode> = edit(roots, commentId) { comment ->
        comment.copy(score = score, viewerVote = value)
    }

    fun replace(
        roots: List<CommentNode>,
        commentId: String,
        replacement: CommentNode.Comment,
    ): List<CommentNode> = edit(roots, commentId) { replacement }

    fun remove(roots: List<CommentNode>, commentId: String): List<CommentNode> {
        if (roots.any { it.id == commentId }) return roots.filterNot { it.id == commentId }
        val stack = ArrayDeque<CommentNode>()
        roots.forEach(stack::addLast)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node is CommentNode.Comment) {
                if (node.children.any { it.id == commentId }) {
                    return edit(roots, node.id) { parent ->
                        parent.withChildren(parent.children.filterNot { it.id == commentId })
                    }
                }
                node.children.forEach(stack::addLast)
            }
        }
        return roots
    }

    private inline fun edit(
        roots: List<CommentNode>,
        targetId: String,
        transform: (CommentNode.Comment) -> CommentNode.Comment,
    ): List<CommentNode> {
        val comments = HashMap<String, CommentNode.Comment>()
        val parentOf = HashMap<String, String?>()
        val stack = ArrayDeque<Pair<CommentNode, String?>>()
        roots.forEach { stack.addLast(it to null) }

        while (stack.isNotEmpty()) {
            val (node, parentId) = stack.removeLast()
            if (node is CommentNode.Comment) {
                comments[node.id] = node
                parentOf[node.id] = parentId
                node.children.forEach { stack.addLast(it to node.id) }
            }
        }

        val target = comments[targetId] ?: return roots
        val transformed = transform(target)
        var rebuilt = transformed.withChildren(transformed.children)
        var rebuiltId = targetId

        while (true) {
            val parentId = parentOf[rebuiltId] ?: break
            val parent = comments.getValue(parentId)
            rebuilt = parent.withChildren(
                parent.children.map { child ->
                    if (child.id == rebuiltId) rebuilt else child
                },
            )
            rebuiltId = parentId
        }

        return roots.map { root -> if (root.id == rebuiltId) rebuilt else root }
    }
}
