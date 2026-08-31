package dev.readthat.client

import dev.readthat.communities.domain.CommunityDrawerSnapshot
import dev.readthat.data.db.PendingPostEntity
import dev.readthat.data.db.PendingSubredditEntity
import dev.readthat.shared.CreateCommunityDraft
import dev.readthat.shared.CreatePostDraft
import dev.readthat.shared.PostFlair
import dev.readthat.shared.LocalPostMedia
import dev.readthat.shared.PostKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SharedCreationControllerTest {
    @Test
    fun `post draft normalization and submission are shared and reset atomically`() = runTest {
        val source = FakeCreationDataSource()
        val controllerJob = SupervisorJob()
        val controller = SharedCreationController(source, CoroutineScope(coroutineContext + controllerJob))
        var queued: SharedCreationOutcome? = null

        controller.beginPost(" r/kotlin ")
        controller.setPostTitle("A shared composer")
        controller.setPostBody("Room first")
        controller.submit { queued = it }
        advanceUntilIdle()

        assertEquals("kotlin", source.postDraft?.normalizedSubreddit)
        assertEquals("A shared composer", source.postDraft?.title)
        assertEquals(source.postOutcome, queued)
        assertEquals(CreateMode.Post, controller.create.value.mode)
        assertEquals("", controller.create.value.post.title)
        assertFalse(controller.create.value.submitting)
        assertNull(controller.create.value.error)
        controllerJob.cancel()
    }

    @Test
    fun `invalid community never reaches persistence and valid community emits typed outcome`() = runTest {
        val source = FakeCreationDataSource()
        val controllerJob = SupervisorJob()
        val controller = SharedCreationController(source, CoroutineScope(coroutineContext + controllerJob))
        var queued: SharedCreationOutcome? = null

        controller.beginCommunity()
        controller.setCommunityName("no")
        controller.setCommunityDisplayName("Too short")
        controller.submit { queued = it }
        advanceUntilIdle()
        assertEquals(0, source.communityCreates)
        assertNull(queued)

        controller.setCommunityName("shared_kmp")
        controller.setCommunityDisplayName("Shared KMP")
        controller.setCommunityAccess("private")
        controller.submit { queued = it }
        advanceUntilIdle()

        assertEquals(1, source.communityCreates)
        assertEquals("shared_kmp", source.communityDraft?.normalizedName)
        assertEquals("private", source.communityDraft?.accessType)
        assertEquals(source.communityOutcome, queued)
        controllerJob.cancel()
    }

    @Test
    fun `status observation and retry failure stay in one lifecycle neutral state`() = runTest {
        val source = FakeCreationDataSource(retryError = IllegalStateException("Still offline"))
        val controllerJob = SupervisorJob()
        val controller = SharedCreationController(source, CoroutineScope(coroutineContext + controllerJob))
        val pending = pendingPost()
        source.postStatus.value = pending

        controller.observePendingPost(pending.mutationId)
        advanceUntilIdle()
        assertEquals(pending, controller.status.value.post)

        controller.retryPendingPost(pending.mutationId)
        assertTrue(controller.status.value.retrying)
        advanceUntilIdle()

        assertFalse(controller.status.value.retrying)
        assertEquals("Still offline", controller.status.value.error)
        assertEquals(1, source.postRetries)
        controllerJob.cancel()
    }

    @Test
    fun `native media results are revalidated by shared creation policy`() = runTest {
        val controllerJob = SupervisorJob()
        val controller = SharedCreationController(
            FakeCreationDataSource(),
            CoroutineScope(coroutineContext + controllerJob),
        )
        controller.beginPost("kotlin")
        controller.setPostKind(PostKind.Image)

        controller.addPickedMedia(listOf(LocalPostMedia(
            name = "not-an-image.mp4",
            mimeType = "video/mp4",
            localPath = "/private/not-an-image.mp4",
            byteSize = 1_024,
        )))
        advanceUntilIdle()

        assertTrue(controller.create.value.post.localMediaItems.isEmpty())
        assertEquals("The selected file is not a supported image", controller.create.value.error)
        controllerJob.cancel()
    }
}

private class FakeCreationDataSource(
    private val retryError: Throwable? = null,
) : SharedCreationDataSource {
    override val communityDrawer: StateFlow<CommunityDrawerSnapshot> =
        MutableStateFlow(CommunityDrawerSnapshot())
    val postStatus = MutableStateFlow<PendingPostEntity?>(null)
    val communityStatus = MutableStateFlow<PendingSubredditEntity?>(null)
    val postOutcome = SharedCreationOutcome.PostQueued("post-mutation", queuedOffline = true)
    val communityOutcome = SharedCreationOutcome.CommunityQueued("community-mutation", queuedOffline = true)
    var postDraft: CreatePostDraft? = null
    var communityDraft: CreateCommunityDraft? = null
    var communityCreates = 0
    var postRetries = 0

    override suspend fun refreshCommunities(force: Boolean) = Unit
    override suspend fun postFlairs(subreddit: String): List<PostFlair> = emptyList()

    override suspend fun createPost(draft: CreatePostDraft): SharedCreationOutcome.PostQueued {
        postDraft = draft
        return postOutcome
    }

    override suspend fun createCommunity(draft: CreateCommunityDraft): SharedCreationOutcome.CommunityQueued {
        communityCreates += 1
        communityDraft = draft
        return communityOutcome
    }

    override fun observePost(mutationId: String): Flow<PendingPostEntity?> = postStatus
    override fun observeCommunity(mutationId: String): Flow<PendingSubredditEntity?> = communityStatus

    override suspend fun retryPost(mutationId: String) {
        postRetries += 1
        retryError?.let { throw it }
    }

    override suspend fun retryCommunity(mutationId: String) {
        retryError?.let { throw it }
    }
}

private fun pendingPost() = PendingPostEntity(
    mutationId = "post-mutation",
    accountId = "account",
    subreddit = "kotlin",
    kind = "Text",
    title = "Shared state",
    body = "Offline first",
    linkUrl = "",
    localPath = null,
    contentType = null,
    byteSize = null,
    width = null,
    height = null,
    durationSeconds = null,
    mediaId = null,
    state = "queued",
    remotePostId = null,
    lastError = null,
    createdAt = 1L,
)
