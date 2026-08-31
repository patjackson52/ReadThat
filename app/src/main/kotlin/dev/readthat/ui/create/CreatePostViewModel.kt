package dev.readthat.ui.create

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.readthat.data.backend.BackendGraph
import dev.readthat.data.community.CommunityGraph
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.AndroidDatabaseProvider
import dev.readthat.data.db.PendingPostEntity
import dev.readthat.data.sync.PostUploadScheduler
import dev.readthat.data.sync.PendingMediaUpload
import dev.readthat.media.acquisition.MediaAcquisitionPolicies
import dev.readthat.media.acquisition.stageAndroidMediaSelection
import dev.readthat.shared.CreatePostDraft
import dev.readthat.shared.LocalPostMedia
import dev.readthat.shared.PostKind
import dev.readthat.shared.PostFlair
import dev.readthat.communities.domain.CommunityDrawerSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.performanceTimer
import java.io.File
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CreatePostViewModel(app: Application, initialSubreddit: String = "") : AndroidViewModel(app) {
    private val backend = BackendGraph.repository(app)
    private val mutableDraft = MutableStateFlow(CreatePostDraft(subreddit = initialSubreddit))
    val draft: StateFlow<CreatePostDraft> = mutableDraft.asStateFlow()
    private val communityRepository = backend.activeAccountId?.let { CommunityGraph.repository(app, it) }
    private val emptyCommunities = MutableStateFlow(CommunityDrawerSnapshot())
    val communities: StateFlow<CommunityDrawerSnapshot> = communityRepository?.snapshot ?: emptyCommunities
    private val mutableCommunityLoading = MutableStateFlow(false)
    val communityLoading: StateFlow<Boolean> = mutableCommunityLoading.asStateFlow()
    private val mutableCommunityError = MutableStateFlow<String?>(null)
    val communityError: StateFlow<String?> = mutableCommunityError.asStateFlow()
    private val mutableFlairs = MutableStateFlow<List<PostFlair>>(emptyList())
    val flairs: StateFlow<List<PostFlair>> = mutableFlairs.asStateFlow()
    private val mutableFlairsLoading = MutableStateFlow(false)
    val flairsLoading: StateFlow<Boolean> = mutableFlairsLoading.asStateFlow()
    private var flairJob: Job? = null

    init {
        refreshCommunities()
        initialSubreddit.takeIf(String::isNotBlank)?.let(::loadFlairs)
    }

    fun setSubreddit(value: String) {
        val normalized = value.trim().removePrefix("r/")
        if (normalized.equals(mutableDraft.value.normalizedSubreddit, ignoreCase = true)) return
        update { copy(subreddit = normalized, flair = null, error = null) }
        loadFlairs(normalized)
    }

    fun refreshCommunities() {
        val repository = communityRepository ?: return
        if (mutableCommunityLoading.value) return
        mutableCommunityLoading.value = true
        mutableCommunityError.value = null
        viewModelScope.launch {
            runCatching { repository.refresh(force = true) }
                .onFailure { mutableCommunityError.value = it.message ?: "Could not load communities" }
            mutableCommunityLoading.value = false
        }
    }

    fun setFlair(flair: PostFlair?) = update { copy(flair = flair, error = null) }

    private fun loadFlairs(subreddit: String) {
        flairJob?.cancel()
        mutableFlairs.value = emptyList()
        mutableFlairsLoading.value = subreddit.isNotBlank()
        if (subreddit.isBlank()) return
        flairJob = viewModelScope.launch {
            runCatching { backend.getPostFlairs(subreddit) }
                .onSuccess { options ->
                    if (mutableDraft.value.normalizedSubreddit.equals(subreddit, ignoreCase = true)) {
                        mutableFlairs.value = options
                    }
                }
                .onFailure { error ->
                    if (mutableDraft.value.normalizedSubreddit.equals(subreddit, ignoreCase = true)) {
                        update { copy(error = error.message ?: "Could not load post flair") }
                    }
                }
            mutableFlairsLoading.value = false
        }
    }
    fun setKind(value: PostKind) {
        if (mutableDraft.value.submitting) return
        if (mutableDraft.value.kind == value) return
        deleteDraftFiles(mutableDraft.value)
        update {
            copy(
                kind = value,
                localMediaName = null,
                localMediaMimeType = null,
                localMediaPath = null,
                localMediaByteSize = null,
                mediaWidth = null,
                mediaHeight = null,
                mediaDurationSeconds = null,
                localMediaItems = emptyList(),
                preparingMedia = false,
                error = null,
            )
        }
    }
    fun setTitle(value: String) = update { if (value.length <= 300) copy(title = value, error = null) else this }
    fun setBody(value: String) = update { if (value.length <= 40_000) copy(body = value, error = null) else this }
    fun setLink(value: String) = update { copy(linkUrl = value, error = null) }

    fun selectMedia(uri: Uri) {
        if (mutableDraft.value.submitting) return
        prepareSelectedMedia(listOf(uri), mutableDraft.value.kind)
    }

    fun selectImages(uris: List<Uri>) {
        if (mutableDraft.value.submitting) return
        if (uris.isEmpty()) return
        if (uris.size > MAX_PHOTOS) {
            update { copy(error = "Choose up to $MAX_PHOTOS photos") }
            return
        }
        prepareSelectedMedia(uris, PostKind.Image, append = false)
    }

    fun addImages(uris: List<Uri>) {
        if (mutableDraft.value.submitting || mutableDraft.value.preparingMedia || uris.isEmpty()) return
        val total = mutableDraft.value.localMediaItems.size + uris.size
        if (total > MAX_PHOTOS) {
            update { copy(error = "Choose up to $MAX_PHOTOS photos") }
            return
        }
        prepareSelectedMedia(uris, PostKind.Image, append = true)
    }

    /** Accepts the already validated durable result from the shared native acquisition seam. */
    fun addCapturedMedia(media: LocalPostMedia) {
        if (mutableDraft.value.submitting || mutableDraft.value.preparingMedia) {
            File(media.localPath).delete()
            return
        }
        val existing = mutableDraft.value.localMediaItems
        val imagePolicy = MediaAcquisitionPolicies.image
        if (existing.size >= imagePolicy.maximumItems) {
            File(media.localPath).delete()
            update { copy(error = "Choose up to ${imagePolicy.maximumItems} photos") }
            return
        }
        val accepted = runCatching { MediaAcquisitionPolicies.camera.validate(media) }
            .getOrElse { error ->
                File(media.localPath).delete()
                update { copy(error = error.message ?: "Could not save the captured photo") }
                return
            }
        if (mutableDraft.value.kind != PostKind.Image) {
            File(accepted.localPath).delete()
            return
        }
        val combined = existing + accepted
        val first = combined.first()
        update {
            copy(
                localMediaName = first.name,
                localMediaMimeType = first.mimeType,
                localMediaPath = first.localPath,
                localMediaByteSize = first.byteSize,
                mediaWidth = first.width,
                mediaHeight = first.height,
                mediaDurationSeconds = first.durationSeconds,
                localMediaItems = combined,
                preparingMedia = false,
                error = null,
            )
        }
    }

    fun reportError(message: String) = update { copy(preparingMedia = false, error = message) }

    fun removeMediaAt(index: Int) {
        val current = mutableDraft.value
        val removed = current.localMediaItems.getOrNull(index) ?: return
        File(removed.localPath).delete()
        val remaining = current.localMediaItems.toMutableList().apply { removeAt(index) }
        val first = remaining.firstOrNull()
        update {
            copy(
                localMediaName = first?.name,
                localMediaMimeType = first?.mimeType,
                localMediaPath = first?.localPath,
                localMediaByteSize = first?.byteSize,
                mediaWidth = first?.width,
                mediaHeight = first?.height,
                mediaDurationSeconds = first?.durationSeconds,
                localMediaItems = remaining,
                error = null,
            )
        }
    }

    private fun prepareSelectedMedia(uris: List<Uri>, selectedKind: PostKind, append: Boolean = false) {
        update { copy(preparingMedia = true, error = null) }
        val existing = if (append) mutableDraft.value.localMediaItems else emptyList()
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    stageAndroidMediaSelection(getApplication(), uris, selectedKind)
                }
            }
                .onSuccess { mediaItems ->
                    if (mutableDraft.value.kind != selectedKind) {
                        mediaItems.forEach { File(it.localPath).delete() }
                    } else {
                        if (!append) deleteDraftFiles(mutableDraft.value)
                        val combined = existing + mediaItems
                        val first = combined.first()
                        update {
                            copy(
                                localMediaName = first.name,
                                localMediaMimeType = first.mimeType,
                                localMediaPath = first.localPath,
                                localMediaByteSize = first.byteSize,
                                mediaWidth = first.width,
                                mediaHeight = first.height,
                                mediaDurationSeconds = first.durationSeconds,
                                localMediaItems = combined,
                                preparingMedia = false,
                                error = null,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    update { copy(preparingMedia = false, error = error.message ?: "Could not read the selected file") }
                }
        }
    }

    fun submit(onQueued: (mutationId: String) -> Unit) {
        val snapshot = mutableDraft.value
        if (!snapshot.canSubmit) return
        val localTimer = performanceTimer()
        update { copy(submitting = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val mutationId = UUID.randomUUID().toString()
                val accountId = backend.activeAccountId ?: error("Sign in before creating a post")
                val dao = AndroidDatabaseProvider.get(getApplication()).postOutboxDao()
                val pendingMedia = snapshot.localMediaItems.map { media ->
                    PendingMediaUpload(
                        name = media.name,
                        contentType = media.mimeType,
                        localPath = media.localPath,
                        byteSize = media.byteSize,
                        width = media.width,
                        height = media.height,
                        durationSeconds = media.durationSeconds,
                    )
                }
                dao.upsert(PendingPostEntity(
                    mutationId = mutationId,
                    accountId = accountId,
                    subreddit = snapshot.normalizedSubreddit,
                    kind = snapshot.kind.name,
                    title = snapshot.title,
                    body = snapshot.body,
                    linkUrl = snapshot.linkUrl,
                    localPath = snapshot.localMediaPath,
                    contentType = snapshot.localMediaMimeType,
                    byteSize = snapshot.localMediaByteSize,
                    width = snapshot.mediaWidth,
                    height = snapshot.mediaHeight,
                    durationSeconds = snapshot.mediaDurationSeconds,
                    mediaId = null,
                    state = "queued",
                    remotePostId = null,
                    lastError = null,
                    createdAt = System.currentTimeMillis(),
                    mediaItemsJson = Json.encodeToString(pendingMedia),
                    flairId = snapshot.flair?.id,
                    flairText = snapshot.flair?.text,
                    flairBackgroundColor = snapshot.flair?.backgroundColor,
                    flairTextColor = snapshot.flair?.textColor,
                ))
                PerformanceTelemetry.duration(
                    PerformanceMetric.MUTATION_LOCAL_COMMIT,
                    localTimer,
                    surface = PerformanceSurface.CREATE_POST,
                    attributes = mapOf(
                        "mutation_type" to "post_create",
                        "cache_tier" to "room",
                        "content_kind" to snapshot.kind.name.lowercase(),
                    ),
                )
                // Room owns the command before WorkManager sees it. A process
                // death or scheduler hiccup is recovered by resumePending().
                runCatching { PostUploadScheduler.enqueue(getApplication(), mutationId) }
                mutationId
            }.onSuccess { mutationId ->
                mutableDraft.value = CreatePostDraft(subreddit = snapshot.subreddit)
                onQueued(mutationId)
            }.onFailure { error ->
                update { copy(submitting = false, error = error.message ?: "Could not create post") }
            }
        }
    }

    private inline fun update(block: CreatePostDraft.() -> CreatePostDraft) {
        mutableDraft.value = mutableDraft.value.block()
    }

    private fun deleteDraftFiles(draft: CreatePostDraft) {
        (draft.localMediaItems.map(LocalPostMedia::localPath) + listOfNotNull(draft.localMediaPath))
            .distinct()
            .forEach { File(it).delete() }
    }

    private companion object {
        val MAX_PHOTOS get() = MediaAcquisitionPolicies.image.maximumItems
    }
}
