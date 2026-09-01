package dev.readthat.comments.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * WIRE MODEL for the post-detail screen.
 *
 * Note how different this is from the feed's model in `:app`.
 *
 *   Feed:     Group -> [Cell]         two levels, SERVER-DESCRIBED UI, flat-ish
 *   Comments: recursive Comment tree  arbitrary depth, DOMAIN model, client renders
 *
 * That difference is deliberate and mirrors Reddit. The feed is heterogeneous,
 * server-ranked, and gains new unit types constantly — so SDUI pays. Post detail has
 * a fixed, client-known composition where what varies is *data*, not *structure*,
 * and it is interaction-heavy (vote, reply, collapse) — the exact place SDUI is
 * weakest. So comments stay a domain model.
 */
@Serializable
sealed interface CommentNode {
    val id: String

    @Serializable
    @SerialName("comment")
    data class Comment(
        override val id: String,
        /** Stable account handle (for replies, profile navigation, and a11y). */
        val author: String,
        val body: String,
        val score: Int,
        /** Age in minutes — the server's business to compute, the client's to format. */
        val createdAgoMin: Int = 0,
        val children: List<CommentNode> = emptyList(),
        val viewerVote: Int = 0,
        /** Human-facing identity shown in Reddit-style comment chrome. */
        val authorDisplayName: String = author,
        val authorAvatarUrl: String? = null,
        /** Deliberately separate from updatedAt: votes also update comment rows. */
        val isEdited: Boolean = false,
        /**
         * Total live comments structurally below this node, including replies behind load-more
         * cursors. The server maintains this write-side so collapse is an O(1) lookup regardless of
         * the response's count/depth budget. The default keeps local and older cached trees usable.
         */
        val descendantCount: Int = materializedDescendantCount(children),
    ) : CommentNode

    /**
     * A truncation cursor.
     *
     * Emitted by the server wherever the requested count or depth budget ran out.
     * [parentId] is null for a root-level cursor ("load more top-level comments").
     *
     * Reddit's published algorithm inserts exactly this: leftover heap candidates get
     * "grouped by their parent comments" into `load_more` cursors.
     *
     * [childIds] mirrors the real API: Reddit's `more` object carries the ids of the
     * comments it stands in for, and the client sends that id list back to
     * `/api/morechildren`. Carrying the ids is what lets the server stay stateless.
     *
     * Note what is NOT here: **depth**. Render depth is positional — derived by the
     * client during the flatten walk — and a wire copy of it can only agree with or
     * contradict the node's actual position. An earlier revision carried one; it was
     * never read.
     */
    @Serializable
    @SerialName("load_more")
    data class LoadMore(
        override val id: String,
        val parentId: String?,
        val remainingCount: Int,
        val childIds: List<String> = emptyList(),
    ) : CommentNode
}

@Serializable
data class CommentTree(
    val postId: String,
    val roots: List<CommentNode>,
    /** Echoed back so the client can tell which tree size it received. */
    val requestedCount: Int,
    val requestedDepth: Int,
) {
    /** Total Comment nodes (excluding LoadMore cursors), for assertions and telemetry. */
    val commentCount: Int get() = countComments(roots)

    private fun countComments(nodes: List<CommentNode>): Int = nodes.sumOf { node ->
        when (node) {
            is CommentNode.Comment -> 1 + countComments(node.children)
            is CommentNode.LoadMore -> 0
        }
    }
}

/**
 * The raw, unbuilt comment with a parent link and a score — what the server holds
 * before it decides which subset to send.
 */
@Serializable
data class RawComment(
    val id: String,
    val parentId: String?,
    val author: String,
    val body: String,
    val score: Int,
    val createdAgoMin: Int = 0,
    val viewerVote: Int = 0,
    val authorDisplayName: String = author,
    val authorAvatarUrl: String? = null,
    val isEdited: Boolean = false,
    val descendantCount: Int = 0,
)

internal fun materializedDescendantCount(children: List<CommentNode>): Int = children.sumOf { child ->
    when (child) {
        is CommentNode.Comment -> 1 + child.descendantCount
        is CommentNode.LoadMore -> 0
    }
}

internal fun CommentNode.Comment.withChildrenPreservingTotal(
    children: List<CommentNode>,
): CommentNode.Comment = copy(children = children)

/**
 * Wire shape of a load-more response — flat, parent-linked, /api/morechildren style.
 * [cursors] are the replacement cursors for whatever the [limit] still left unsent.
 */
@Serializable
data class LoadMoreResponse(
    val comments: List<RawComment>,
    val cursors: List<CommentNode.LoadMore>,
)

/**
 * The post above the comments. A DOMAIN model, deliberately not the feed's SDUI
 * cells: :comments stays standalone, and the detail screen's composition is
 * client-known. The feed passes only a postId across the module seam.
 */
typealias PostHeader = dev.readthat.shared.PostHeader
typealias PostMedia = dev.readthat.shared.PostMedia
