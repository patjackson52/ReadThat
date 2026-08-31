package dev.readthat.client

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.readthat.communities.domain.CommunityDrawerSnapshot
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.PendingPostEntity
import dev.readthat.data.db.PendingSubredditEntity
import dev.readthat.media.acquisition.MediaAcquisitionPolicies
import dev.readthat.shared.CreateCommunityDraft
import dev.readthat.shared.CreatePostDraft
import dev.readthat.shared.LocalPostMedia
import dev.readthat.shared.PostFlair
import dev.readthat.shared.PostKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class CreateMode { Post, Community }

data class CreateState(
    val mode: CreateMode = CreateMode.Post,
    val post: CreatePostDraft = CreatePostDraft(),
    val community: CreateCommunityDraft = CreateCommunityDraft(),
    val postFlairs: List<PostFlair> = emptyList(),
    val postFlairsLoading: Boolean = false,
    val communityDrawer: CommunityDrawerSnapshot = CommunityDrawerSnapshot(),
    val communitiesLoading: Boolean = false,
    val communitiesError: String? = null,
    val submitting: Boolean = false,
    val error: String? = null,
)

data class CreationStatusState(
    val post: PendingPostEntity? = null,
    val community: PendingSubredditEntity? = null,
    val retrying: Boolean = false,
    val error: String? = null,
)

sealed interface SharedCreationOutcome {
    val mutationId: String
    val queuedOffline: Boolean

    data class PostQueued(
        override val mutationId: String,
        override val queuedOffline: Boolean,
    ) : SharedCreationOutcome

    data class CommunityQueued(
        override val mutationId: String,
        override val queuedOffline: Boolean,
    ) : SharedCreationOutcome
}

internal interface SharedCreationDataSource {
    val communityDrawer: StateFlow<CommunityDrawerSnapshot>
    suspend fun refreshCommunities(force: Boolean)
    suspend fun postFlairs(subreddit: String): List<PostFlair>
    suspend fun createPost(draft: CreatePostDraft): SharedCreationOutcome.PostQueued
    suspend fun createCommunity(draft: CreateCommunityDraft): SharedCreationOutcome.CommunityQueued
    fun observePost(mutationId: String): Flow<PendingPostEntity?>
    fun observeCommunity(mutationId: String): Flow<PendingSubredditEntity?>
    suspend fun retryPost(mutationId: String)
    suspend fun retryCommunity(mutationId: String)
}

private class OfflineFirstCreationDataSource(
    private val repository: OfflineFirstRepository,
) : SharedCreationDataSource {
    override val communityDrawer: StateFlow<CommunityDrawerSnapshot> = repository.communityDrawer

    override suspend fun refreshCommunities(force: Boolean) = repository.refreshCommunityDrawer(force)
    override suspend fun postFlairs(subreddit: String): List<PostFlair> = repository.postFlairs(subreddit)

    override suspend fun createPost(draft: CreatePostDraft): SharedCreationOutcome.PostQueued {
        val outcome = repository.createPost(draft)
        return SharedCreationOutcome.PostQueued(outcome.mutationId, outcome.queuedOffline)
    }

    override suspend fun createCommunity(draft: CreateCommunityDraft): SharedCreationOutcome.CommunityQueued {
        val outcome = repository.createCommunity(draft)
        return SharedCreationOutcome.CommunityQueued(outcome.mutationId, outcome.queuedOffline)
    }

    override fun observePost(mutationId: String): Flow<PendingPostEntity?> =
        repository.observePendingPost(mutationId)

    override fun observeCommunity(mutationId: String): Flow<PendingSubredditEntity?> =
        repository.observePendingCommunity(mutationId)

    override suspend fun retryPost(mutationId: String) {
        repository.retryPendingPost(mutationId)
    }

    override suspend fun retryCommunity(mutationId: String) {
        repository.retryPendingCommunity(mutationId)
    }
}

/**
 * Shared offline-first composer and creation-status state machine. Platform hosts supply only
 * native media acquisition/preview and background scheduling hints; Room owns every command before
 * a network attempt, so this controller behaves identically across Android and iOS.
 */
class SharedCreationController internal constructor(
    private val source: SharedCreationDataSource,
    private val coroutineScope: CoroutineScope,
) {
    constructor(
        client: ReadThatClient,
        database: AppDatabase,
        coroutineScope: CoroutineScope,
        accountId: String? = null,
    ) : this(
        OfflineFirstCreationDataSource(
            OfflineFirstRepository(
                client = client,
                database = database,
                scope = coroutineScope,
                accountIdOverride = accountId,
                maintainGlobalState = false,
            ),
        ),
        coroutineScope,
    )

    private val mutableCreate = MutableStateFlow(CreateState())
    val create: StateFlow<CreateState> = mutableCreate.asStateFlow()

    private val mutableStatus = MutableStateFlow(CreationStatusState())
    val status: StateFlow<CreationStatusState> = mutableStatus.asStateFlow()

    private var flairJob: Job? = null
    private var communitiesJob: Job? = null
    private var submissionJob: Job? = null
    private var statusJob: Job? = null

    init {
        coroutineScope.launch {
            source.communityDrawer.collect { drawer ->
                mutableCreate.value = mutableCreate.value.copy(communityDrawer = drawer)
            }
        }
    }

    fun beginPost(initialSubreddit: String = "") {
        val normalized = initialSubreddit.trim().removePrefix("r/")
        if (normalized.isNotBlank() && mutableCreate.value.post.normalizedSubreddit.isBlank()) {
            updatePost { copy(subreddit = normalized) }
            loadPostFlairs(normalized)
        }
        setMode(CreateMode.Post)
        refreshCommunities()
    }

    fun beginCommunity() = setMode(CreateMode.Community)

    fun setMode(mode: CreateMode) {
        if (mutableCreate.value.submitting) return
        mutableCreate.value = mutableCreate.value.copy(mode = mode, error = null)
    }

    fun setPostKind(kind: PostKind) {
        val current = mutableCreate.value
        if (current.submitting || current.post.kind == kind) return
        current.post.localMediaItems.forEach(::deleteInBackground)
        mutableCreate.value = current.copy(
            post = current.post.withMedia(emptyList()).copy(kind = kind, preparingMedia = false, error = null),
            error = null,
        )
    }

    fun setPostCommunity(value: String) {
        val normalized = value.trim().removePrefix("r/")
        val current = mutableCreate.value.post
        if (normalized.equals(current.normalizedSubreddit, ignoreCase = true)) {
            if (normalized.isNotBlank() && mutableCreate.value.postFlairs.isEmpty()) loadPostFlairs(normalized)
            return
        }
        updatePost { copy(subreddit = normalized, flair = null, error = null) }
        loadPostFlairs(normalized)
    }

    fun setPostFlair(value: PostFlair?) = updatePost { copy(flair = value, error = null) }
    fun setPostTitle(value: String) = updatePost { copy(title = value.take(TITLE_LIMIT), error = null) }
    fun setPostBody(value: String) = updatePost { copy(body = value.take(BODY_LIMIT), error = null) }
    fun setPostLink(value: String) = updatePost { copy(linkUrl = value, error = null) }

    fun addPickedMedia(items: List<LocalPostMedia>) {
        if (items.isEmpty()) return
        val state = mutableCreate.value
        if (state.submitting) {
            items.forEach(::deleteInBackground)
            return
        }
        val draft = state.post
        val policy = runCatching { MediaAcquisitionPolicies.forPostKind(draft.kind) }.getOrNull()
        val accepted = when (draft.kind) {
            PostKind.Image -> draft.localMediaItems + items
            PostKind.Video -> items
            else -> emptyList()
        }
        val error = when {
            policy == null ->
                "Choose an image or video post type first"
            accepted.size > policy.maximumItems -> policy.tooManyItemsMessage
            else -> accepted.firstNotNullOfOrNull { media ->
                runCatching { policy.validate(media) }.exceptionOrNull()?.message
            }
        }
        if (error != null) {
            items.forEach(::deleteInBackground)
            mutableCreate.value = state.copy(error = error, post = draft.copy(error = error))
            return
        }
        if (draft.kind == PostKind.Video) {
            draft.localMediaItems.forEach(::deleteInBackground)
            items.drop(1).forEach(::deleteInBackground)
        }
        mutableCreate.value = state.copy(post = draft.withMedia(accepted).copy(error = null), error = null)
    }

    fun removePickedMedia(index: Int) {
        val state = mutableCreate.value
        if (state.submitting) return
        val removed = state.post.localMediaItems.getOrNull(index) ?: return
        deleteInBackground(removed)
        val remaining = state.post.localMediaItems.toMutableList().apply { removeAt(index) }
        mutableCreate.value = state.copy(post = state.post.withMedia(remaining).copy(error = null), error = null)
    }

    fun reportError(value: String) {
        if (mutableCreate.value.submitting) return
        mutableCreate.value = mutableCreate.value.copy(
            error = value,
            post = mutableCreate.value.post.copy(error = value),
        )
    }

    fun setCommunityName(value: String) = updateCommunity {
        copy(name = value.removePrefix("r/").take(COMMUNITY_NAME_LIMIT), error = null)
    }

    fun setCommunityDisplayName(value: String) = updateCommunity {
        copy(displayName = value.take(COMMUNITY_DISPLAY_NAME_LIMIT), error = null)
    }

    fun setCommunityDescription(value: String) = updateCommunity {
        copy(description = value.take(COMMUNITY_DESCRIPTION_LIMIT), error = null)
    }

    fun setCommunityAccess(value: String) {
        if (value !in COMMUNITY_ACCESS_TYPES) return
        updateCommunity { copy(accessType = value, error = null) }
    }

    fun refreshCommunities(force: Boolean = true) {
        if (communitiesJob?.isActive == true) return
        mutableCreate.value = mutableCreate.value.copy(communitiesLoading = true, communitiesError = null)
        communitiesJob = coroutineScope.launch {
            try {
                source.refreshCommunities(force)
                mutableCreate.value = mutableCreate.value.copy(communitiesLoading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableCreate.value = mutableCreate.value.copy(
                    communitiesLoading = false,
                    communitiesError = error.message ?: "Could not load communities",
                )
            }
        }
    }

    fun submit(onQueued: (SharedCreationOutcome) -> Unit) {
        if (submissionJob?.isActive == true || mutableCreate.value.submitting) return
        val snapshot = mutableCreate.value
        val canSubmit = when (snapshot.mode) {
            CreateMode.Post -> snapshot.post.canSubmit
            CreateMode.Community -> snapshot.community.canSubmit
        }
        if (!canSubmit) return
        mutableCreate.value = snapshot.copy(submitting = true, error = null)
        submissionJob = coroutineScope.launch {
            try {
                val outcome = when (snapshot.mode) {
                    CreateMode.Post -> source.createPost(snapshot.post)
                    CreateMode.Community -> source.createCommunity(snapshot.community)
                }
                mutableCreate.value = when (snapshot.mode) {
                    CreateMode.Post -> CreateState(mode = CreateMode.Post)
                    CreateMode.Community -> CreateState(mode = CreateMode.Community)
                }
                onQueued(outcome)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableCreate.value = mutableCreate.value.copy(
                    submitting = false,
                    error = error.message ?: "Unable to create",
                )
            }
        }
    }

    fun observePendingPost(mutationId: String) {
        statusJob?.cancel()
        mutableStatus.value = CreationStatusState()
        statusJob = coroutineScope.launch {
            source.observePost(mutationId).collect { pending ->
                mutableStatus.value = mutableStatus.value.copy(post = pending, community = null)
            }
        }
    }

    fun observePendingCommunity(mutationId: String) {
        statusJob?.cancel()
        mutableStatus.value = CreationStatusState()
        statusJob = coroutineScope.launch {
            source.observeCommunity(mutationId).collect { pending ->
                mutableStatus.value = mutableStatus.value.copy(post = null, community = pending)
            }
        }
    }

    fun retryPendingPost(mutationId: String) = retry { source.retryPost(mutationId) }
    fun retryPendingCommunity(mutationId: String) = retry { source.retryCommunity(mutationId) }

    fun closeStatus() {
        statusJob?.cancel()
        statusJob = null
        mutableStatus.value = CreationStatusState()
    }

    fun discardDraft() {
        if (submissionJob?.isActive == true) return
        mutableCreate.value.post.localMediaItems.forEach(::deleteInBackground)
        mutableCreate.value = CreateState(mode = mutableCreate.value.mode)
    }

    suspend fun stagedMediaBytes(media: LocalPostMedia): ByteArray {
        require(media.byteSize in 1..Int.MAX_VALUE.toLong()) { "Staged media size is invalid" }
        return readStagedMedia(media.localPath, 0L, media.byteSize.toInt())
    }

    private fun retry(block: suspend () -> Unit) {
        if (mutableStatus.value.retrying) return
        mutableStatus.value = mutableStatus.value.copy(retrying = true, error = null)
        coroutineScope.launch {
            try {
                block()
                mutableStatus.value = mutableStatus.value.copy(retrying = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableStatus.value = mutableStatus.value.copy(
                    retrying = false,
                    error = error.message ?: "Still waiting for a network connection",
                )
            }
        }
    }

    private fun loadPostFlairs(subreddit: String) {
        flairJob?.cancel()
        mutableCreate.value = mutableCreate.value.copy(
            postFlairs = emptyList(),
            postFlairsLoading = subreddit.isNotBlank(),
        )
        if (subreddit.isBlank()) return
        flairJob = coroutineScope.launch {
            try {
                val options = source.postFlairs(subreddit)
                if (mutableCreate.value.post.normalizedSubreddit.equals(subreddit, ignoreCase = true)) {
                    mutableCreate.value = mutableCreate.value.copy(
                        postFlairs = options,
                        postFlairsLoading = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (mutableCreate.value.post.normalizedSubreddit.equals(subreddit, ignoreCase = true)) {
                    mutableCreate.value = mutableCreate.value.copy(
                        postFlairsLoading = false,
                        error = error.message ?: "Could not load post flair",
                    )
                }
            }
        }
    }

    private fun updatePost(transform: CreatePostDraft.() -> CreatePostDraft) {
        if (mutableCreate.value.submitting) return
        mutableCreate.value = mutableCreate.value.copy(
            post = mutableCreate.value.post.transform(),
            error = null,
        )
    }

    private fun updateCommunity(transform: CreateCommunityDraft.() -> CreateCommunityDraft) {
        if (mutableCreate.value.submitting) return
        mutableCreate.value = mutableCreate.value.copy(
            community = mutableCreate.value.community.transform(),
            error = null,
        )
    }

    private fun deleteInBackground(media: LocalPostMedia) {
        coroutineScope.launch { runCatching { deleteStagedMedia(media.localPath) } }
    }

    private companion object {
        const val TITLE_LIMIT = 300
        const val BODY_LIMIT = 40_000
        const val COMMUNITY_NAME_LIMIT = 21
        const val COMMUNITY_DISPLAY_NAME_LIMIT = 100
        const val COMMUNITY_DESCRIPTION_LIMIT = 1_000
        val COMMUNITY_ACCESS_TYPES = setOf("public", "restricted", "private")
    }
}

/** Focused lifecycle owner used by mature Android routes. */
class SharedCreationViewModel(
    client: ReadThatClient,
    database: AppDatabase,
    accountId: String,
    initialSubreddit: String = "",
    mode: CreateMode = CreateMode.Post,
    pendingPostId: String? = null,
    pendingCommunityId: String? = null,
    private val onCreationQueued: (SharedCreationOutcome) -> Unit = {},
) : ViewModel() {
    private val controller = SharedCreationController(client, database, viewModelScope, accountId)
    val create: StateFlow<CreateState> = controller.create
    val status: StateFlow<CreationStatusState> = controller.status

    init {
        require(pendingPostId == null || pendingCommunityId == null) {
            "A creation ViewModel cannot observe two mutation types"
        }
        when {
            pendingPostId != null -> controller.observePendingPost(pendingPostId)
            pendingCommunityId != null -> controller.observePendingCommunity(pendingCommunityId)
            mode == CreateMode.Community -> controller.beginCommunity()
            else -> controller.beginPost(initialSubreddit)
        }
    }

    fun setMode(value: CreateMode) = controller.setMode(value)
    fun setPostKind(value: PostKind) = controller.setPostKind(value)
    fun setPostCommunity(value: String) = controller.setPostCommunity(value)
    fun setPostFlair(value: PostFlair?) = controller.setPostFlair(value)
    fun setPostTitle(value: String) = controller.setPostTitle(value)
    fun setPostBody(value: String) = controller.setPostBody(value)
    fun setPostLink(value: String) = controller.setPostLink(value)
    fun addPickedMedia(items: List<LocalPostMedia>) = controller.addPickedMedia(items)
    fun removePickedMedia(index: Int) = controller.removePickedMedia(index)
    fun reportError(value: String) = controller.reportError(value)
    fun setCommunityName(value: String) = controller.setCommunityName(value)
    fun setCommunityDisplayName(value: String) = controller.setCommunityDisplayName(value)
    fun setCommunityDescription(value: String) = controller.setCommunityDescription(value)
    fun setCommunityAccess(value: String) = controller.setCommunityAccess(value)
    fun refreshCommunities() = controller.refreshCommunities()

    fun submit(onQueued: (SharedCreationOutcome) -> Unit) = controller.submit { outcome ->
        onCreationQueued(outcome)
        onQueued(outcome)
    }

    fun retryPost(mutationId: String) = controller.retryPendingPost(mutationId)
    fun retryCommunity(mutationId: String) = controller.retryPendingCommunity(mutationId)
    fun discardDraft() = controller.discardDraft()
    suspend fun stagedMediaBytes(media: LocalPostMedia): ByteArray = controller.stagedMediaBytes(media)

    override fun onCleared() {
        controller.closeStatus()
        super.onCleared()
    }
}

private fun CreatePostDraft.withMedia(items: List<LocalPostMedia>): CreatePostDraft {
    val first = items.firstOrNull()
    return copy(
        localMediaName = first?.name,
        localMediaMimeType = first?.mimeType,
        localMediaPath = first?.localPath,
        localMediaByteSize = first?.byteSize,
        mediaWidth = first?.width,
        mediaHeight = first?.height,
        mediaDurationSeconds = first?.durationSeconds,
        localMediaItems = items,
    )
}
