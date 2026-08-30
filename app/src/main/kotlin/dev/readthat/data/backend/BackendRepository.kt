package dev.readthat.data.backend

import android.util.LruCache
import dev.readthat.data.db.CacheScope
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.SubredditEntity
import dev.readthat.shared.CreatedPost
import dev.readthat.shared.PostKind
import dev.readthat.shared.PostFlair
import dev.readthat.shared.Subreddit
import dev.readthat.shared.UserProfile
import dev.readthat.shared.SessionState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.delay
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** Android transport adapter. Domain contracts and validation live in :shared. */
class BackendRepository(
    private val client: BackendClient,
    private val db: AppDatabase,
) {
    private val subredditMemory = LruCache<String, Subreddit>(64)
    private val profileMemory = LruCache<String, UserProfile>(64)
    val enabled: Boolean get() = client.enabled
    val session: StateFlow<SessionState> = client.sessionState
    val activeAccountId: String?
        get() = (session.value as? SessionState.SignedIn)?.user?.id

    suspend fun restoreSession(): UserProfile? = client.restoreSession()
    suspend fun register(username: String, password: String, displayName: String): UserProfile =
        client.register(username, password, displayName)
    suspend fun login(username: String, password: String): UserProfile = client.login(username, password)
    suspend fun logout() = client.logout()
    suspend fun me(): UserProfile = client.currentUser()
    suspend fun getUser(username: String): UserProfile {
        val normalized = username.trim().removePrefix("u/").lowercase()
        profileMemory[normalized]?.let { return it }
        return client.user(normalized).also { profileMemory.put(normalized, it) }
    }
    suspend fun updateProfile(
        displayName: String,
        bio: String,
        avatarMediaId: String?,
        updateAvatar: Boolean,
    ): UserProfile = client.updateProfile(displayName, bio, avatarMediaId, updateAvatar)

    suspend fun createSubreddit(
        name: String,
        displayName: String,
        description: String,
        accessType: String,
        clientMutationId: String,
    ): Subreddit {
        val created = decodeSubreddit(client.requestJson(
            "POST",
            "/v1/subreddits",
            buildJsonObject {
                put("name", name.trim().removePrefix("r/"))
                put("displayName", displayName.trim())
                put("description", description.trim())
                put("accessType", accessType)
                put("clientMutationId", clientMutationId)
            },
            requireAuthentication = true,
        ))
        cacheSubreddit(created)
        return created
    }

    suspend fun getSubreddit(name: String): Subreddit {
        val normalized = name.trim().removePrefix("r/").lowercase()
        val account = accountScope()
        val key = "$account/$normalized"
        subredditMemory[key]?.let { return it }
        db.subredditDao().get(account, normalized)?.toDomain()?.let {
            subredditMemory.put(key, it)
            return it
        }
        return decodeSubreddit(client.requestJson("GET", "/v1/subreddits/$normalized"))
            .also { cacheSubreddit(it) }
    }

    suspend fun getPostFlairs(name: String): List<PostFlair> {
        val normalized = name.trim().removePrefix("r/").lowercase()
        val response = client.requestJson(
            "GET",
            "/v1/subreddits/$normalized/flairs",
            requireAuthentication = true,
        )
        return client.json.decodeFromJsonElement<RepoPostFlairEnvelope>(response).flairs
    }

    fun observeSubreddit(name: String): Flow<Subreddit?> {
        val normalized = name.trim().removePrefix("r/").lowercase()
        return db.subredditDao().observe(accountScope(), normalized).map { it?.toDomain() }
    }

    suspend fun createPost(
        subreddit: String,
        kind: PostKind,
        title: String,
        body: String,
        linkUrl: String,
        media: MediaUpload? = null,
        mediaItems: List<MediaUpload> = emptyList(),
        flairId: String? = null,
        clientMutationId: String = mutationId("post"),
    ): CreatedPost {
        val orderedMedia = mediaItems.ifEmpty { listOfNotNull(media) }
        val response = client.requestJson(
            "POST",
            "/v1/posts",
            buildJsonObject {
                put("subreddit", subreddit.trim().removePrefix("r/"))
                put("kind", kind.name.lowercase())
                put("title", title.trim())
                when (kind) {
                    PostKind.Text -> if (body.isNotBlank()) put("body", body.trim())
                    PostKind.Link -> put("url", linkUrl.trim())
                    PostKind.Image -> put("mediaIds", buildJsonArray {
                        require(orderedMedia.isNotEmpty()) { "Image posts require at least one photo" }
                        orderedMedia.forEach { add(JsonPrimitive(it.id)) }
                    })
                    PostKind.Video -> put("mediaId", requireNotNull(orderedMedia.singleOrNull()).id)
                }
                if (kind != PostKind.Text && body.isNotBlank()) put("body", body.trim())
                flairId?.let { put("flairId", it) }
                put("clientMutationId", clientMutationId)
            },
            requireAuthentication = true,
        )
        return client.json.decodeFromJsonElement<RepoPostEnvelope>(response).post.toShared()
    }

    suspend fun uploadMedia(
        kind: PostKind,
        contentType: String,
        byteSize: Long,
        openStream: () -> InputStream,
        width: Int? = null,
        height: Int? = null,
        durationSeconds: Int? = null,
        altText: String = "",
    ): MediaUpload {
        require(kind == PostKind.Image || kind == PostKind.Video)
        val response = client.requestJson(
            "POST",
            "/v1/media/uploads",
            buildJsonObject {
                put("kind", kind.name.lowercase())
                put("contentType", contentType)
                put("byteSize", byteSize)
                width?.let { put("width", it) }
                height?.let { put("height", it) }
                if (kind == PostKind.Video) put("durationSeconds", durationSeconds ?: 0)
                put("altText", altText)
            },
            requireAuthentication = true,
        )
        val upload = client.json.decodeFromJsonElement<RepoUploadEnvelope>(response).upload
        val completed = if (upload.mode == "single") {
            retryUpload {
                client.requestStream(
                    "PUT",
                    upload.uploadPath,
                    byteSize,
                    contentType,
                    mapOf("X-Upload-Token" to upload.uploadToken),
                    openBody = openStream,
                )
            }
        } else {
            val partSize = requireNotNull(upload.partSize)
            var uploadedBytes = 0L
            var partNumber = 1
            openStream().use { input ->
                while (uploadedBytes < byteSize) {
                    val expected = minOf(partSize.toLong(), byteSize - uploadedBytes).toInt()
                    val part = input.readExactly(expected)
                    retryUpload {
                        client.requestBytes(
                            "PUT",
                            upload.uploadPath.replace("{partNumber}", partNumber.toString()),
                            part,
                            contentType,
                            mapOf("X-Upload-Token" to upload.uploadToken),
                        )
                    }
                    uploadedBytes += part.size
                    partNumber++
                }
            }
            retryUpload {
                client.requestBytes(
                    "POST",
                    requireNotNull(upload.completePath),
                    ByteArray(0),
                    "application/json",
                    mapOf("X-Upload-Token" to upload.uploadToken),
                )
            }
        }
        if (kind == PostKind.Video) {
            var media = client.json.decodeFromJsonElement<RepoMediaEnvelope>(completed).media
            var polls = 0
            while (media.delivery.status !in setOf("ready", "error", "not_applicable") && polls < MAX_PROCESSING_POLLS) {
                delay(PROCESSING_POLL_MS)
                val refreshed = client.requestJson(
                    "POST",
                    "/v1/media/uploads/${upload.id}/refresh",
                    requireAuthentication = true,
                )
                media = client.json.decodeFromJsonElement<RepoMediaEnvelope>(refreshed).media
                polls++
            }
        }
        return MediaUpload(upload.id, contentType)
    }

    suspend fun createComment(postId: String, parentId: String?, body: String): CreatedComment {
        val response = client.requestJson(
            "POST",
            "/v1/posts/$postId/comments",
            buildJsonObject {
                if (parentId == null) put("parentId", null as String?) else put("parentId", parentId)
                put("body", body.trim())
                put("clientMutationId", mutationId("comment"))
            },
            requireAuthentication = true,
        )
        return client.json.decodeFromJsonElement<RepoCommentEnvelope>(response).comment.toCreated()
    }

    suspend fun votePost(postId: String, value: Int): VoteResult = vote("posts", postId, value)
    suspend fun voteComment(commentId: String, value: Int): VoteResult = vote("comments", commentId, value)

    suspend fun reshare(postId: String, subreddit: String, title: String? = null): CreatedPost {
        val response = client.requestJson(
            "POST",
            "/v1/posts/$postId/reshare",
            buildJsonObject {
                put("subreddit", subreddit.trim().removePrefix("r/"))
                title?.trim()?.takeIf(String::isNotBlank)?.let { put("title", it) }
                put("clientMutationId", mutationId("reshare"))
            },
            requireAuthentication = true,
        )
        return client.json.decodeFromJsonElement<RepoPostEnvelope>(response).post.toShared()
    }

    private suspend fun vote(segment: String, id: String, value: Int): VoteResult {
        require(value in -1..1)
        val response = client.requestJson(
            "PUT",
            "/v1/$segment/$id/vote",
            buildJsonObject {
                put("value", value)
                put("clientMutationId", mutationId("vote"))
            },
            requireAuthentication = true,
        )
        val vote = response.jsonObject.getValue("vote")
        return client.json.decodeFromJsonElement(vote)
    }

    private fun decodeSubreddit(response: JsonElement): Subreddit =
        client.json.decodeFromJsonElement(response.jsonObject.getValue("subreddit"))

    private fun accountScope(): String = activeAccountId ?: CacheScope.DEFAULT_ACCOUNT_ID

    private suspend fun cacheSubreddit(subreddit: Subreddit) {
        val account = accountScope()
        val normalized = subreddit.name.lowercase()
        subredditMemory.put("$account/$normalized", subreddit)
        db.subredditDao().upsert(subreddit.toEntity(account, normalized))
    }

    private fun mutationId(prefix: String) = "$prefix:${UUID.randomUUID()}"

    private suspend fun <T> retryUpload(block: suspend () -> T): T {
        var lastError: IOException? = null
        repeat(MAX_UPLOAD_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (error: IOException) {
                if (error is BackendHttpException && error.status in 400..499 && error.status !in setOf(408, 429)) {
                    throw error
                }
                lastError = error
                if (attempt < MAX_UPLOAD_ATTEMPTS - 1) delay(250L shl attempt)
            }
        }
        throw requireNotNull(lastError)
    }

    private fun InputStream.readExactly(byteCount: Int): ByteArray {
        val bytes = ByteArray(byteCount)
        var offset = 0
        while (offset < byteCount) {
            val count = read(bytes, offset, byteCount - offset)
            if (count < 0) error("Selected media ended before its reported size")
            offset += count
        }
        return bytes
    }

    private companion object {
        const val MAX_UPLOAD_ATTEMPTS = 3
        const val MAX_PROCESSING_POLLS = 20
        const val PROCESSING_POLL_MS = 1_000L
    }
}

data class MediaUpload(val id: String, val contentType: String)

private fun Subreddit.toEntity(accountId: String, normalizedName: String) = SubredditEntity(
    accountId = accountId,
    id = id,
    name = normalizedName,
    displayName = displayName,
    description = description,
    accessType = accessType,
    viewerRole = viewerRole,
    subscriberCount = subscriberCount,
    updatedAt = System.currentTimeMillis(),
)

private fun SubredditEntity.toDomain() = Subreddit(
    id = id,
    name = name,
    displayName = displayName,
    description = description,
    accessType = accessType,
    viewerRole = viewerRole,
    subscriberCount = subscriberCount,
)

@Serializable
data class VoteResult(
    val targetType: String,
    val targetId: String,
    val value: Int,
    val score: Int,
    val upvotes: Int = 0,
    val downvotes: Int = 0,
    val version: Int = 0,
)

@Serializable
data class CreatedComment(
    val id: String,
    val postId: String,
    val parentId: String?,
    val author: String,
    val body: String,
    val score: Int,
    val viewerVote: Int,
    val createdAt: Long,
)

@Serializable
private data class RepoUploadEnvelope(val upload: RepoUpload)

@Serializable
private data class RepoUpload(
    val id: String,
    val mode: String,
    val uploadToken: String,
    val partSize: Int?,
    val uploadPath: String,
    val completePath: String?,
)

@Serializable private data class RepoMediaEnvelope(val media: RepoMedia)
@Serializable private data class RepoMedia(val delivery: RepoDelivery)
@Serializable private data class RepoDelivery(val status: String)

@Serializable private data class RepoCommentEnvelope(val comment: RepoComment)

@Serializable
private data class RepoComment(
    val id: String,
    val postId: String,
    val parentId: String?,
    val author: String,
    val body: String,
    val score: Int,
    val viewerVote: Int,
    val createdAt: Long,
) {
    fun toCreated() = CreatedComment(id, postId, parentId, author, body, score, viewerVote, createdAt)
}

@Serializable private data class RepoPostEnvelope(val post: RepoCreatedPost)

@Serializable private data class RepoPostFlairEnvelope(val flairs: List<PostFlair>)

@Serializable
private data class RepoCreatedPost(
    val id: String,
    val subreddit: String,
    val author: String,
    val title: String,
    val body: String? = null,
    val url: String? = null,
    val score: Int = 0,
    val commentCount: Int = 0,
    val viewerVote: Int = 0,
    val flair: PostFlair? = null,
) {
    fun toShared() = CreatedPost(id, subreddit, author, title, body, url, score, commentCount, viewerVote, flair)
}
