package dev.readthat.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.readthat.data.backend.BackendGraph
import dev.readthat.data.backend.BackendHttpException
import dev.readthat.data.backend.MediaUpload
import dev.readthat.data.db.AppDatabase
import dev.readthat.shared.PostKind
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.readthat.observability.PerformanceEvent
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceOutcome
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry

@Serializable
internal data class PendingMediaUpload(
    val name: String,
    val contentType: String,
    val localPath: String,
    val byteSize: Long,
    val width: Int? = null,
    val height: Int? = null,
    val durationSeconds: Int? = null,
    val remoteMediaId: String? = null,
)

object PostUploadScheduler {
    const val KEY_MUTATION_ID = "mutation_id"

    fun enqueue(context: Context, mutationId: String) {
        enqueue(context, mutationId, ExistingWorkPolicy.KEEP)
    }

    private fun enqueue(context: Context, mutationId: String, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<PostUploadWorker>()
            .setInputData(Data.Builder().putString(KEY_MUTATION_ID, mutationId).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "post-upload:$mutationId",
            policy,
            request,
        )
    }

    suspend fun resumePending(context: Context, accountId: String) {
        AppDatabase.get(context).postOutboxDao().resumable(accountId).forEach { pending ->
            enqueue(context, pending.mutationId)
        }
    }

    /**
     * Cancel the barrier retry/backoff and run only posts targeting the community
     * that just reconciled. REPLACE prevents two workers publishing one command.
     */
    suspend fun releaseCommunityBarrier(context: Context, accountId: String, subreddit: String) {
        AppDatabase.get(context).postOutboxDao()
            .resumableForSubreddit(accountId, subreddit)
            .forEach { pending -> enqueue(context, pending.mutationId, ExistingWorkPolicy.REPLACE) }
    }
}

class PostUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val mutationId = inputData.getString(PostUploadScheduler.KEY_MUTATION_ID)
            ?: return Result.failure()
        val dao = AppDatabase.get(applicationContext).postOutboxDao()
        val database = AppDatabase.get(applicationContext)
        val pending = dao.get(mutationId) ?: return Result.success()
        if (pending.remotePostId != null) return Result.success()
        if (database.accountDao().active()?.id != pending.accountId) {
            dao.updateProgress(mutationId, "waiting_account", pending.mediaId, null)
            return Result.success()
        }

        val community = database.subredditOutboxDao().getByName(pending.accountId, pending.subreddit)
        if (community?.state == "failed") {
            dao.fail(mutationId, "Community creation failed; retry the community before this post")
            return Result.failure()
        }
        if (community != null && community.remoteSubredditId == null) {
            dao.updateProgress(mutationId, "waiting_community", pending.mediaId, null)
            return Result.retry()
        }

        var persistedMediaId = pending.mediaId
        var persistedMediaItems = runCatching {
            mediaJson.decodeFromString<List<PendingMediaUpload>>(pending.mediaItemsJson)
        }.getOrDefault(emptyList())
        if (persistedMediaItems.isEmpty() && pending.localPath != null && pending.contentType != null && pending.byteSize != null) {
            persistedMediaItems = listOf(PendingMediaUpload(
                name = pending.title,
                contentType = requireNotNull(pending.contentType),
                localPath = requireNotNull(pending.localPath),
                byteSize = requireNotNull(pending.byteSize),
                width = pending.width,
                height = pending.height,
                durationSeconds = pending.durationSeconds,
                remoteMediaId = pending.mediaId,
            ))
        }
        return try {
            val backend = BackendGraph.repository(applicationContext)
            val kind = PostKind.entries.first { it.name.equals(pending.kind, ignoreCase = true) }
            if (kind in setOf(PostKind.Image, PostKind.Video)) {
                require(persistedMediaItems.isNotEmpty()) { "Media post has no local media" }
                for (index in persistedMediaItems.indices) {
                    val item = persistedMediaItems[index]
                    if (item.remoteMediaId != null) continue
                    dao.updateMediaProgress(
                        mutationId,
                        "uploading",
                        persistedMediaItems.firstOrNull()?.remoteMediaId,
                        mediaJson.encodeToString(persistedMediaItems),
                        null,
                    )
                    val uploaded = backend.uploadMedia(
                        kind = kind,
                        contentType = item.contentType,
                        byteSize = item.byteSize,
                        openStream = { File(item.localPath).inputStream() },
                        width = item.width,
                        height = item.height,
                        durationSeconds = item.durationSeconds,
                        altText = pending.title,
                    )
                    persistedMediaItems = persistedMediaItems.toMutableList().apply {
                        this[index] = item.copy(remoteMediaId = uploaded.id)
                    }
                    persistedMediaId = persistedMediaItems.first().remoteMediaId
                    dao.updateMediaProgress(
                        mutationId,
                        if (index == persistedMediaItems.lastIndex) "creating" else "uploading",
                        persistedMediaId,
                        mediaJson.encodeToString(persistedMediaItems),
                        null,
                    )
                }
                persistedMediaId = persistedMediaItems.first().remoteMediaId
                dao.updateMediaProgress(
                    mutationId,
                    "creating",
                    persistedMediaId,
                    mediaJson.encodeToString(persistedMediaItems),
                    null,
                )
            }
            val media = persistedMediaItems.map { item ->
                MediaUpload(requireNotNull(item.remoteMediaId), item.contentType)
            }
            val post = backend.createPost(
                subreddit = pending.subreddit,
                kind = kind,
                title = pending.title,
                body = pending.body,
                linkUrl = pending.linkUrl,
                media = media.firstOrNull(),
                mediaItems = media,
                flairId = pending.flairId,
                clientMutationId = mutationId,
            )
            dao.complete(mutationId, post.id)
            PerformanceTelemetry.record(PerformanceEvent(
                name = PerformanceMetric.MUTATION_SERVER_ACK,
                value = (System.currentTimeMillis() - pending.createdAt).toDouble().coerceAtLeast(0.0),
                surface = PerformanceSurface.BACKGROUND,
                attributes = mapOf(
                    "mutation_type" to "post_create",
                    "content_kind" to pending.kind.lowercase(),
                ),
            ))
            persistedMediaItems.map(PendingMediaUpload::localPath).distinct().forEach { File(it).delete() }
            FeedSyncScheduler.enqueueRefresh(applicationContext)
            Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val permanent = error is BackendHttpException &&
                error.status in 400..499 && error.status !in setOf(408, 429)
            if (permanent || runAttemptCount >= MAX_RETRIES) {
                dao.fail(mutationId, error.message ?: "Could not create post")
                PerformanceTelemetry.record(PerformanceEvent(
                    name = PerformanceMetric.MUTATION_SERVER_ACK,
                    value = (System.currentTimeMillis() - pending.createdAt).toDouble().coerceAtLeast(0.0),
                    surface = PerformanceSurface.BACKGROUND,
                    outcome = PerformanceOutcome.FAILURE,
                    attributes = mapOf(
                        "mutation_type" to "post_create",
                        "content_kind" to pending.kind.lowercase(),
                    ),
                ))
                Result.failure()
            } else {
                dao.updateProgress(mutationId, "retrying", persistedMediaId, error.message)
                Result.retry()
            }
        }
    }

    private companion object {
        const val MAX_RETRIES = 5
        val mediaJson = Json { ignoreUnknownKeys = true }
    }
}
