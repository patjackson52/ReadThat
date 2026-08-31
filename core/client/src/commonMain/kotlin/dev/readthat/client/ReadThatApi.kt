package dev.readthat.client

import dev.readthat.comments.domain.CommentNode
import dev.readthat.comments.domain.CommentTree
import dev.readthat.comments.domain.LoadMoreResponse
import dev.readthat.comments.domain.RawComment
import dev.readthat.communities.domain.CommunityDrawerPage
import dev.readthat.communities.domain.CommunityVisitCommand
import dev.readthat.communitydetail.domain.CommunityDetail
import dev.readthat.domain.WireCell
import dev.readthat.domain.WireFeedPage
import dev.readthat.mediafeed.domain.MediaFeedItem
import dev.readthat.mediafeed.domain.MediaFeedMedia
import dev.readthat.mediafeed.domain.MediaFeedPage
import dev.readthat.search.domain.SearchDiscover
import dev.readthat.search.domain.SearchPage
import dev.readthat.search.domain.SearchRequest
import dev.readthat.search.domain.SearchTypeahead
import dev.readthat.shared.CreatedPost
import dev.readthat.shared.LocalPostMedia
import dev.readthat.shared.PostFlair
import dev.readthat.shared.PostHeader
import dev.readthat.shared.PostKind
import dev.readthat.shared.PostMedia
import dev.readthat.shared.Subreddit
import dev.readthat.shared.firstFrameVideoPreviewUrl
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.delay

@Serializable
data class VoteResult(
    val targetType: String = "post",
    val targetId: String,
    val value: Int,
    val score: Int,
    val upvotes: Int = 0,
    val downvotes: Int = 0,
    val version: Int = 0,
)

data class UploadedMedia(val id: String, val contentType: String)

@Serializable
private data class CommunityVisitCommandEnvelope(val commands: List<CommunityVisitCommand>)

class ReadThatApi(private val client: ReadThatClient) {
    suspend fun feed(cursor: String? = null, subreddit: String? = null): WireFeedPage {
        val path = buildString {
            append("/v1/feed?limit=12")
            if (subreddit.isNullOrBlank()) append("&includePromoted=true")
            subreddit?.takeIf(String::isNotBlank)?.let {
                append("&subreddit=").append(encodePathSegment(it.removePrefix("r/")))
            }
            cursor?.let { append("&cursor=").append(encodePathSegment(it)) }
        }
        return client.json.decodeFromJsonElement(lenientFeed(client.requestJson("GET", path)))
    }

    suspend fun votePost(postId: String, value: Int, mutationId: String): VoteResult {
        require(value in -1..1)
        val response = client.requestJson(
            "PUT",
            "/v1/posts/${encodePathSegment(postId)}/vote",
            buildJsonObject {
                put("value", value)
                put("clientMutationId", mutationId)
            },
            requireAuthentication = true,
        )
        return client.json.decodeFromJsonElement(response.jsonObject.getValue("vote"))
    }

    suspend fun post(postId: String): PostHeader {
        val response = client.requestJson("GET", "/v1/posts/${encodePathSegment(postId)}")
        return client.json.decodeFromJsonElement<ApiPostEnvelope>(response).post.toDomain()
    }

    suspend fun postFlairs(subreddit: String): List<PostFlair> {
        val normalized = subreddit.trim().removePrefix("r/").lowercase()
        val response = client.requestJson(
            "GET",
            "/v1/subreddits/${encodePathSegment(normalized)}/flairs",
            requireAuthentication = true,
        )
        return client.json.decodeFromJsonElement<ApiPostFlairEnvelope>(response).flairs
    }

    suspend fun comments(
        postId: String,
        count: Int,
        depth: Int = 10,
        rootCommentId: String? = null,
        focusCommentId: String? = null,
    ): CommentTree {
        require(rootCommentId == null || focusCommentId == null) {
            "Choose either a rooted or focused comment view"
        }
        val path = buildString {
            append("/v1/posts/${encodePathSegment(postId)}/comments?count=$count&depth=$depth")
            rootCommentId?.let { append("&rootCommentId=").append(encodePathSegment(it)) }
            focusCommentId?.let { append("&focusCommentId=").append(encodePathSegment(it)) }
        }
        return client.json.decodeFromJsonElement<ApiCommentTree>(client.requestJson("GET", path)).toDomain()
    }

    suspend fun loadMore(postId: String, cursor: CommentNode.LoadMore, limit: Int = 100): LoadMoreResponse {
        val response = client.requestJson(
            "POST",
            "/v1/posts/${encodePathSegment(postId)}/comments/more",
            buildJsonObject {
                put("childIds", buildJsonArray { cursor.childIds.forEach { add(JsonPrimitive(it)) } })
                put("limit", limit)
                put("maxDepth", 10)
            },
        )
        val value = client.json.decodeFromJsonElement<ApiLoadMoreEnvelope>(response)
        return LoadMoreResponse(value.comments.map(ApiRawComment::toDomain), value.cursors.map(ApiLoadMore::toDomain))
    }

    suspend fun createComment(postId: String, parentId: String?, body: String): RawComment {
        val response = client.requestJson(
            "POST",
            "/v1/posts/${encodePathSegment(postId)}/comments",
            buildJsonObject {
                parentId?.let { put("parentId", it) }
                put("body", body.trim())
                put("clientMutationId", platformMutationId("comment"))
            },
            requireAuthentication = true,
        )
        return client.json.decodeFromJsonElement<ApiCommentEnvelope>(response).comment.toDomain()
    }

    suspend fun voteComment(commentId: String, value: Int): VoteResult {
        val response = client.requestJson(
            "PUT",
            "/v1/comments/${encodePathSegment(commentId)}/vote",
            buildJsonObject {
                put("value", value)
                put("clientMutationId", platformMutationId("vote"))
            },
            requireAuthentication = true,
        )
        return client.json.decodeFromJsonElement(response.jsonObject.getValue("vote"))
    }

    suspend fun search(request: SearchRequest, cursor: String? = null, limit: Int = 25): SearchPage {
        val path = buildString {
            append("/v1/search?q=").append(encodePathSegment(request.query))
            append("&type=${request.type.wire}&sort=${request.sort.wire}&time=${request.time.wire}")
            append("&safe=${request.safe}&limit=$limit")
            request.subreddit?.takeIf(String::isNotBlank)?.let {
                append("&subreddit=").append(encodePathSegment(it))
            }
            cursor?.let { append("&cursor=").append(encodePathSegment(it)) }
        }
        return client.json.decodeFromJsonElement(client.requestJson("GET", path))
    }

    suspend fun typeahead(query: String, limit: Int = 8): SearchTypeahead =
        client.json.decodeFromJsonElement(client.requestJson(
            "GET", "/v1/search/typeahead?q=${encodePathSegment(query)}&limit=$limit",
        ))

    suspend fun discover(): SearchDiscover =
        client.json.decodeFromJsonElement(client.requestJson("GET", "/v1/search/discover"))

    suspend fun communityDrawer(cursor: String? = null, limit: Int = 100): CommunityDrawerPage {
        val path = buildString {
            append("/v1/me/community-drawer?limit=").append(limit)
            cursor?.let { append("&cursor=").append(encodePathSegment(it)) }
        }
        return client.json.decodeFromJsonElement(
            client.requestJson("GET", path, requireAuthentication = true),
        )
    }

    suspend fun syncCommunityVisits(commands: List<CommunityVisitCommand>): Set<String> {
        if (commands.isEmpty()) return emptySet()
        val response = client.requestJson(
            "PUT",
            "/v1/me/community-visits",
            client.json.encodeToJsonElement(CommunityVisitCommandEnvelope(commands)),
            requireAuthentication = true,
        )
        return response.jsonObject.getValue("applied").jsonArray
            .mapTo(linkedSetOf()) { it.jsonPrimitive.content }
    }

    suspend fun community(name: String): CommunityDetail {
        val response = client.requestJson("GET", "/v1/subreddits/${encodePathSegment(name.removePrefix("r/"))}")
        return client.json.decodeFromJsonElement<CommunityDetailEnvelope>(response).subreddit
    }

    suspend fun setCommunityJoined(name: String, joined: Boolean): CommunityDetail {
        client.requestJson(
            if (joined) "POST" else "DELETE",
            "/v1/subreddits/${encodePathSegment(name.removePrefix("r/"))}/join",
            requireAuthentication = true,
        )
        return community(name)
    }

    suspend fun createCommunity(
        name: String,
        displayName: String,
        description: String,
        accessType: String,
        mutationId: String = platformMutationUuid("community"),
    ): Subreddit {
        val response = client.requestJson(
            "POST", "/v1/subreddits",
            buildJsonObject {
                put("name", name.trim().removePrefix("r/"))
                put("displayName", displayName.trim())
                put("description", description.trim())
                put("accessType", accessType)
                put("clientMutationId", mutationId)
            },
            requireAuthentication = true,
        )
        return client.json.decodeFromJsonElement(response.jsonObject.getValue("subreddit"))
    }

    suspend fun createPost(
        subreddit: String,
        kind: PostKind,
        title: String,
        body: String,
        linkUrl: String,
        mediaIds: List<String> = emptyList(),
        flairId: String? = null,
        mutationId: String = platformMutationId("post"),
    ): CreatedPost {
        val response = client.requestJson(
            "POST", "/v1/posts",
            buildJsonObject {
                put("subreddit", subreddit.trim().removePrefix("r/"))
                put("kind", kind.name.lowercase())
                put("title", title.trim())
                if (body.isNotBlank()) put("body", body.trim())
                if (kind == PostKind.Link) put("url", linkUrl.trim())
                if (kind == PostKind.Image) put("mediaIds", buildJsonArray {
                    mediaIds.forEach { add(JsonPrimitive(it)) }
                })
                if (kind == PostKind.Video) put("mediaId", mediaIds.single())
                flairId?.let { put("flairId", it) }
                put("clientMutationId", mutationId)
            },
            requireAuthentication = true,
        )
        return client.json.decodeFromJsonElement<CreatedPostEnvelope>(response).post.toDomain()
    }

    suspend fun reshare(postId: String, subreddit: String, title: String? = null): CreatedPost {
        val response = client.requestJson(
            "POST", "/v1/posts/${encodePathSegment(postId)}/reshare",
            buildJsonObject {
                put("subreddit", subreddit.trim().removePrefix("r/"))
                title?.trim()?.takeIf(String::isNotBlank)?.let { put("title", it) }
                put("clientMutationId", platformMutationId("reshare"))
            },
            requireAuthentication = true,
        )
        return client.json.decodeFromJsonElement<CreatedPostEnvelope>(response).post.toDomain()
    }

    /** Resumable media transfer reads only one server-sized part into memory at a time. */
    suspend fun uploadMedia(kind: PostKind, media: LocalPostMedia, altText: String): UploadedMedia {
        require(kind == PostKind.Image || kind == PostKind.Video)
        val response = client.requestJson(
            "POST", "/v1/media/uploads",
            buildJsonObject {
                put("kind", kind.name.lowercase())
                put("contentType", media.mimeType)
                put("byteSize", media.byteSize)
                media.width?.let { put("width", it) }
                media.height?.let { put("height", it) }
                if (kind == PostKind.Video) put("durationSeconds", media.durationSeconds ?: 0)
                put("altText", altText.take(2_000))
            },
            requireAuthentication = true,
        )
        val upload = client.json.decodeFromJsonElement<ApiUploadEnvelope>(response).upload
        val completed = if (upload.mode == "single") {
            val bytes = readStagedMedia(media.localPath, 0, media.byteSize.toSafeInt())
            client.requestBytes(
                "PUT", upload.uploadPath, bytes, media.mimeType,
                mapOf("X-Upload-Token" to upload.uploadToken),
            )
        } else {
            val partSize = requireNotNull(upload.partSize)
            var offset = 0L
            var partNumber = 1
            while (offset < media.byteSize) {
                val length = minOf(partSize.toLong(), media.byteSize - offset).toInt()
                val bytes = readStagedMedia(media.localPath, offset, length)
                client.requestBytes(
                    "PUT",
                    upload.uploadPath.replace("{partNumber}", partNumber.toString()),
                    bytes,
                    media.mimeType,
                    mapOf("X-Upload-Token" to upload.uploadToken),
                )
                offset += length
                partNumber++
            }
            client.requestBytes(
                "POST", requireNotNull(upload.completePath), ByteArray(0), "application/json",
                mapOf("X-Upload-Token" to upload.uploadToken),
            )
        }
        if (kind == PostKind.Video) {
            var delivery = client.json.decodeFromJsonElement<ApiMediaEnvelope>(completed).media.delivery
            var polls = 0
            while (delivery.status !in setOf("ready", "error", "not_applicable") && polls < MAX_PROCESSING_POLLS) {
                delay(PROCESSING_POLL_MS)
                val refreshed = client.requestJson(
                    "POST", "/v1/media/uploads/${upload.id}/refresh", requireAuthentication = true,
                )
                delivery = client.json.decodeFromJsonElement<ApiMediaEnvelope>(refreshed).media.delivery
                polls++
            }
        }
        return UploadedMedia(upload.id, media.mimeType)
    }

    suspend fun mediaFeed(
        cursor: String? = null,
        anchorPostId: String? = null,
        subreddit: String? = null,
    ): MediaFeedPage {
        val path = buildString {
            append("/v1/feeds/media?limit=8")
            cursor?.let { append("&cursor=").append(encodePathSegment(it)) }
            anchorPostId?.let { append("&anchorPostId=").append(encodePathSegment(it)) }
            subreddit?.let { append("&subreddit=").append(encodePathSegment(it.removePrefix("r/"))) }
        }
        val envelope = client.json.decodeFromJsonElement<ApiMediaFeedEnvelope>(client.requestJson("GET", path))
        return MediaFeedPage(
            envelope.items.map(ApiPost::toMediaFeedItem), envelope.nextCursor,
            envelope.snapshotAt, envelope.anchorIncluded,
        )
    }

    private fun lenientFeed(element: JsonElement): JsonElement {
        val root = element.jsonObject
        val groups = root.getValue("groups").jsonArray.map { groupElement ->
            val group = groupElement.jsonObject
            val cells = group.getValue("cells").jsonArray.map { cellElement ->
                val cell = cellElement.jsonObject
                val type = cell["type"]?.jsonPrimitive?.content.orEmpty()
                if (type in KNOWN_CELL_TYPES) cell else buildJsonObject {
                    put("type", "unknown")
                    put("cellId", cell["cellId"]?.jsonPrimitive?.content ?: "unknown")
                    put("typeName", type.ifBlank { "missing_discriminator" })
                }
            }
            JsonObject(group.toMutableMap().apply { put("cells", JsonArray(cells)) })
        }
        return JsonObject(root.toMutableMap().apply { put("groups", JsonArray(groups)) })
    }

    private companion object {
        val KNOWN_CELL_TYPES = setOf(
            "metadata", "title", "text", "image", "image_carousel", "video", "link",
            "actionbar", "announcement", "ad_header", "ad_title", "ad_media", "ad_summary",
            "ad_related_posts", "ad_actionbar", "unknown",
        )
        const val MAX_PROCESSING_POLLS = 20
        const val PROCESSING_POLL_MS = 1_000L
    }
}

private fun Long.toSafeInt(): Int {
    require(this in 0..Int.MAX_VALUE.toLong()) { "Single upload is too large" }
    return toInt()
}

@Serializable private data class ApiUploadEnvelope(val upload: ApiUpload)
@Serializable private data class ApiUpload(
    val id: String,
    val mode: String,
    val uploadToken: String,
    val partSize: Int? = null,
    val uploadPath: String,
    val completePath: String? = null,
)
@Serializable private data class ApiMediaEnvelope(val media: ApiUploadedMedia)
@Serializable private data class ApiUploadedMedia(val delivery: ApiDelivery)
@Serializable private data class ApiDelivery(val status: String)

@Serializable private data class CommunityDetailEnvelope(val subreddit: CommunityDetail)
@Serializable private data class ApiPostEnvelope(val post: ApiPost)
@Serializable private data class ApiPostFlairEnvelope(val flairs: List<PostFlair>)
@Serializable private data class ApiMediaFeedEnvelope(
    val items: List<ApiPost>,
    val nextCursor: String? = null,
    val snapshotAt: Long,
    val anchorIncluded: Boolean = false,
)

@Serializable
private data class ApiPost(
    val id: String,
    val subreddit: String,
    val author: String,
    val title: String,
    val body: String? = null,
    val score: Int,
    val commentCount: Int,
    val viewerVote: Int = 0,
    val kind: String = "text",
    val url: String? = null,
    val media: ApiMedia? = null,
    val mediaItems: List<ApiMedia> = emptyList(),
    val createdAt: Long = 0,
    val subredditAvatarUrl: String? = null,
    val flair: PostFlair? = null,
) {
    private fun domainMedia(): List<PostMedia> = mediaItems.ifEmpty { listOfNotNull(media) }
        .mapIndexed { index, item -> item.toDomain("post:$id:media:$index") }

    fun toDomain(): PostHeader {
        val gallery = domainMedia()
        return PostHeader(
            id, title, "u/$author", "r/$subreddit", score, commentCount, body,
            gallery.firstOrNull(), viewerVote, kind, url, gallery, flair,
        )
    }

    fun toMediaFeedItem(): MediaFeedItem {
        val gallery = domainMedia()
        val first = gallery.firstOrNull() ?: error("Media feed post $id has no media")
        return MediaFeedItem(
            id, author, subreddit, title, body, score, commentCount, viewerVote, kind,
            createdAt, media = MediaFeedMedia.from(first), communityAvatarUrl = subredditAvatarUrl,
            mediaItems = gallery.map(MediaFeedMedia::from), flair = flair,
        )
    }
}

@Serializable
private data class ApiMedia(
    val id: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationSeconds: Int? = null,
    val contentType: String? = null,
    val url: String? = null,
    val zoomUrl: String? = null,
    val altText: String = "",
    val hlsUrl: String? = null,
    val dashUrl: String? = null,
    val posterUrl: String? = null,
    val fallbackUrl: String? = null,
    val deliveryStatus: String = "not_applicable",
    val processingProgress: Int = 0,
    val cacheKey: String? = null,
) {
    fun toDomain(fallbackCacheKey: String) = PostMedia(
        placeholderColor = 0xff23386b,
        aspectRatio = if (width != null && height != null && height > 0) width.toFloat() / height else 16f / 9f,
        isVideo = contentType?.startsWith("video/") == true,
        durationSeconds = durationSeconds,
        url = url,
        altText = altText,
        hlsUrl = hlsUrl,
        dashUrl = dashUrl,
        posterUrl = firstFrameVideoPreviewUrl(posterUrl),
        fallbackUrl = fallbackUrl ?: url,
        deliveryStatus = deliveryStatus,
        processingProgress = processingProgress,
        cacheKey = cacheKey ?: id?.let { if (contentType?.startsWith("video/") == true) "video:$it" else "image:$it" }
            ?: fallbackCacheKey,
        mediaId = id,
        width = width,
        height = height,
        zoomUrl = zoomUrl ?: url,
    )
}

@Serializable
private data class ApiCommentTree(
    val postId: String,
    val roots: List<ApiCommentNode>,
    val requestedCount: Int,
    val requestedDepth: Int,
) {
    fun toDomain() = CommentTree(postId, roots.map(ApiCommentNode::toDomain), requestedCount, requestedDepth)
}

@Serializable private sealed interface ApiCommentNode {
    val id: String
    fun toDomain(): CommentNode
}

@Serializable @SerialName("comment")
private data class ApiComment(
    override val id: String,
    val author: String,
    val body: String,
    val score: Int,
    val viewerVote: Int = 0,
    val createdAgoMin: Int,
    val displayName: String = author,
    val avatarUrl: String? = null,
    val isEdited: Boolean = false,
    val descendantCount: Int = 0,
    val children: List<ApiCommentNode> = emptyList(),
) : ApiCommentNode {
    override fun toDomain() = CommentNode.Comment(
        id = id,
        author = author,
        body = body,
        score = score,
        createdAgoMin = createdAgoMin,
        children = children.map(ApiCommentNode::toDomain),
        viewerVote = viewerVote,
        authorDisplayName = displayName,
        authorAvatarUrl = avatarUrl,
        isEdited = isEdited,
        descendantCount = descendantCount,
    )
}

@Serializable @SerialName("load_more")
private data class ApiLoadMore(
    override val id: String,
    val parentId: String?,
    val remainingCount: Int,
    val childIds: List<String> = emptyList(),
) : ApiCommentNode {
    override fun toDomain() = CommentNode.LoadMore(id, parentId, remainingCount, childIds)
}

@Serializable
private data class ApiRawComment(
    val id: String,
    val parentId: String?,
    val author: String,
    val body: String,
    val score: Int,
    val viewerVote: Int = 0,
    val createdAgoMin: Int,
    val displayName: String = author,
    val avatarUrl: String? = null,
    val isEdited: Boolean = false,
    val descendantCount: Int = 0,
) {
    fun toDomain() = RawComment(
        id = id,
        parentId = parentId,
        author = author,
        body = body,
        score = score,
        createdAgoMin = createdAgoMin,
        viewerVote = viewerVote,
        authorDisplayName = displayName,
        authorAvatarUrl = avatarUrl,
        isEdited = isEdited,
        descendantCount = descendantCount,
    )
}

@Serializable private data class ApiLoadMoreEnvelope(
    val comments: List<ApiRawComment>, val cursors: List<ApiLoadMore>,
)
@Serializable private data class ApiCommentEnvelope(val comment: ApiRawComment)

@Serializable private data class CreatedPostEnvelope(val post: CreatedPostDto)
@Serializable private data class CreatedPostDto(
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
    fun toDomain() = CreatedPost(id, subreddit, author, title, body, url, score, commentCount, viewerVote, flair)
}
