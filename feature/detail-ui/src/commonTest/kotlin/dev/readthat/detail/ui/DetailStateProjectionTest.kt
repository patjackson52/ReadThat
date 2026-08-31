package dev.readthat.detail.ui

import dev.readthat.client.CommentLoadState
import dev.readthat.client.DetailState
import dev.readthat.client.SharedDetailUiState
import dev.readthat.comments.domain.CommentRenderList
import dev.readthat.comments.domain.CommentTree
import dev.readthat.communitydetail.domain.CommunityDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetailStateProjectionTest {
    private val emptyRender = CommentRenderList(emptyList(), 0, 0)

    @Test
    fun mapsSharedLoadingCacheAndMutationState() {
        val projected = SharedDetailUiState(
            detail = DetailState(
                postId = "post-1",
                refreshingComments = true,
                initialCacheTier = "room",
                commentLoadStates = mapOf(
                    "loading" to CommentLoadState.Loading,
                    "error" to CommentLoadState.Error,
                ),
                submittingComment = true,
                error = "offline",
                focusedCommentId = "focused",
                rootCommentId = "root",
            ),
            render = emptyRender,
            canMutate = false,
        ).toDetailUiState()

        assertTrue(projected.isLoadingInitial)
        assertFalse(projected.isLoadingFull)
        assertTrue(projected.servedFromPrefetch)
        assertTrue(projected.isSubmittingComment)
        assertEquals("offline", projected.interactionError)
        assertEquals("focused", projected.focusedCommentId)
        assertEquals("root", projected.rootCommentId)
        assertFalse(projected.canMutate)
        assertEquals(DetailLoadMoreState.Loading, projected.loadMoreStates["loading"])
        assertEquals(DetailLoadMoreState.Error, projected.loadMoreStates["error"])
    }

    @Test
    fun distinguishesBackgroundRefreshFromInitialLoad() {
        val projected = DetailState(
            comments = CommentTree("post-1", emptyList(), 40, 8),
            refreshingComments = true,
        ).toDetailUiState(emptyRender, canMutate = true)

        assertFalse(projected.isLoadingInitial)
        assertTrue(projected.isLoadingFull)
        assertFalse(projected.servedFromPrefetch)
    }

    @Test
    fun projectsCommunityMembershipForEitherHost() {
        assertNull(DetailState().toDetailCommunityHeader())

        val header = requireNotNull(DetailState(
            community = CommunityDetail(
                id = "community-1",
                name = "kotlin",
                displayName = "Kotlin",
                description = "",
                accessType = "public",
                viewerRole = "subscriber",
                subscriberCount = 42,
                avatarUrl = "https://images.example/kotlin.png",
                updatedAt = 1L,
            ),
            communityMembershipChanging = true,
        ).toDetailCommunityHeader())

        assertEquals("https://images.example/kotlin.png", header.avatarUrl)
        assertTrue(header.isMember)
        assertTrue(header.canJoin)
        assertTrue(header.membershipChanging)
    }
}
