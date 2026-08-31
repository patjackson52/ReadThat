package dev.readthat.data.backend

import android.content.Context
import dev.readthat.comments.data.CommentsRemoteSource
import dev.readthat.comments.data.CommentVoteResult
import dev.readthat.comments.domain.CommentNode
import dev.readthat.comments.domain.CommentTree
import dev.readthat.comments.domain.LoadMoreResponse
import dev.readthat.comments.domain.PostHeader
import dev.readthat.comments.domain.PostMedia
import dev.readthat.comments.domain.RawComment
import dev.readthat.data.FeedRemoteSource
import dev.readthat.data.PostVoteResult
import dev.readthat.domain.WireFeedPage
import dev.readthat.domain.WireGroup
import dev.readthat.domain.WireCell
import dev.readthat.mediafeed.data.MediaFeedRemoteSource
import dev.readthat.mediafeed.domain.MediaFeedItem
import dev.readthat.mediafeed.domain.MediaFeedMedia
import dev.readthat.mediafeed.domain.MediaFeedPage
import dev.readthat.observability.PerformanceEvent
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceOutcome
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.PerformanceUnit
import dev.readthat.observability.performanceTimer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class HttpFeedRemoteSource(
    private val client: BackendClient,
    private val subreddit: String? = null,
) : FeedRemoteSource {
    override suspend fun loadPage(cursor: String?): WireFeedPage {
        val timer = performanceTimer()
        val query = buildString {
            append("/v1/feed?limit=12")
            if (subreddit.isNullOrBlank()) append("&includePromoted=true")
            subreddit?.takeIf(String::isNotBlank)?.let {
                append("&subreddit=")
                append(URLEncoder.encode(it.removePrefix("r/"), StandardCharsets.UTF_8.name()))
            }
            if (cursor != null) {
                append("&cursor=")
                append(URLEncoder.encode(cursor, StandardCharsets.UTF_8.name()))
            }
        }
        return try {
            val response = client.requestJson("GET", query)
            if (cursor == null) PerformanceTelemetry.duration(
                PerformanceMetric.FEED_INITIAL_FETCH,
                timer,
                surface = PerformanceSurface.FEED,
                attributes = mapOf("phase" to "page_1"),
            )
            val responseBytes = response.toString().encodeToByteArray().size.toDouble()
            PerformanceTelemetry.record(PerformanceEvent(
                name = PerformanceMetric.FEED_QUERY_RESPONSE_SIZE,
                value = responseBytes,
                unit = PerformanceUnit.BYTE,
                surface = PerformanceSurface.FEED,
                attributes = mapOf(
                    "phase" to if (cursor == null) "page_1" else "next_page",
                ),
            ))
            client.json.decodeFromJsonElement(lenientFeed(response))
        } catch (error: Throwable) {
            if (cursor == null) PerformanceTelemetry.duration(
                PerformanceMetric.FEED_INITIAL_FETCH,
                timer,
                surface = PerformanceSurface.FEED,
                outcome = PerformanceOutcome.FAILURE,
                attributes = mapOf("phase" to "page_1"),
            )
            throw error
        }
    }

    override suspend fun votePost(
        postId: String,
        value: Int,
        clientMutationId: String,
    ): PostVoteResult {
        val response = client.requestJson(
            "PUT",
            "/v1/posts/$postId/vote",
            buildJsonObject {
                put("value", value)
                put("clientMutationId", clientMutationId)
            },
            requireAuthentication = true,
        )
        val vote = response.jsonObject.getValue("vote").jsonObject
        return PostVoteResult(
            score = vote.getValue("score").jsonPrimitive.content.toInt(),
            liked = vote.getValue("value").jsonPrimitive.content.toInt() == 1,
            value = vote.getValue("value").jsonPrimitive.content.toInt(),
        )
    }

    /** Map future server cell discriminators to the client's explicit Unknown cell. */
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
            "actionbar", "announcement", "ad_header", "ad_title", "ad_media",
            "ad_summary", "ad_related_posts", "ad_actionbar", "unknown",
        )
    }
}

class HttpMediaFeedRemoteSource(
    private val client: BackendClient,
) : MediaFeedRemoteSource {
    override fun continuationCursorFromFeed(feedCursor: String): String =
        LEGACY_FEED_CURSOR_PREFIX + feedCursor

    override suspend fun loadPage(
        cursor: String?,
        anchorPostId: String?,
        subreddit: String?,
    ): MediaFeedPage {
        val fallbackCursor = cursor?.removePrefix(LEGACY_FEED_CURSOR_PREFIX)
            ?.takeIf { cursor.startsWith(LEGACY_FEED_CURSOR_PREFIX) }
        if (fallbackCursor != null) {
            return loadLegacyFeedPage(fallbackCursor, anchorPostId, subreddit)
        }
        val path = buildString {
            append("/v1/feeds/media?limit=8")
            subreddit?.takeIf(String::isNotBlank)?.let {
                append("&subreddit=")
                append(URLEncoder.encode(it.removePrefix("r/"), StandardCharsets.UTF_8.name()))
            }
            anchorPostId?.takeIf(String::isNotBlank)?.let {
                append("&anchorPostId=")
                append(URLEncoder.encode(it, StandardCharsets.UTF_8.name()))
            }
            cursor?.let {
                append("&cursor=")
                append(URLEncoder.encode(it, StandardCharsets.UTF_8.name()))
            }
        }
        val envelope = try {
            client.json.decodeFromJsonElement<ApiMediaFeedEnvelope>(client.requestJson("GET", path))
        } catch (error: BackendHttpException) {
            // Staggered rollout compatibility only: keep the typed endpoint primary and
            // project the existing ranked SDUI feed only while an older Worker lacks it.
            if (error.status != 404 || cursor != null) throw error
            return loadLegacyFeedPage(null, anchorPostId, subreddit)
        }
        return MediaFeedPage(
            items = envelope.items.map(ApiPost::toMediaFeedItem),
            nextCursor = envelope.nextCursor,
            snapshotAt = envelope.snapshotAt,
            anchorIncluded = envelope.anchorIncluded,
        )
    }

    private suspend fun loadLegacyFeedPage(
        initialCursor: String?,
        anchorPostId: String?,
        subreddit: String?,
    ): MediaFeedPage {
        val source = HttpFeedRemoteSource(client, subreddit)
        val items = mutableListOf<MediaFeedItem>()
        var cursor = initialCursor
        var pagesRead = 0
        do {
            val page = source.loadPage(cursor)
            items += page.groups.mapNotNull(WireGroup::toMediaFeedItemOrNull)
            cursor = page.nextCursor
            pagesRead += 1
        } while (items.size < LEGACY_PAGE_SIZE && cursor != null && pagesRead < LEGACY_MAX_PAGES)

        val uniqueItems = items.distinctBy(MediaFeedItem::postId).toMutableList()
        val anchorIndex = uniqueItems.indexOfFirst { it.postId == anchorPostId }
        val anchorIncluded = anchorIndex >= 0
        if (anchorIndex > 0) uniqueItems.add(0, uniqueItems.removeAt(anchorIndex))
        return MediaFeedPage(
            items = uniqueItems,
            nextCursor = cursor?.let { LEGACY_FEED_CURSOR_PREFIX + it },
            snapshotAt = System.currentTimeMillis(),
            anchorIncluded = anchorIncluded,
        )
    }

    private companion object {
        const val LEGACY_FEED_CURSOR_PREFIX = "ranked-feed-v1:"
        const val LEGACY_PAGE_SIZE = 8
        const val LEGACY_MAX_PAGES = 4
    }
}

/** Temporary rollout adapter from the existing SDUI group to MediaFeed's typed item. */
internal fun WireGroup.toMediaFeedItemOrNull(): MediaFeedItem? {
    val metadata = cells.filterIsInstance<WireCell.Metadata>().firstOrNull() ?: return null
    val title = cells.filterIsInstance<WireCell.Title>().firstOrNull() ?: return null
    val body = cells.filterIsInstance<WireCell.Text>().firstOrNull()?.body
    val actions = cells.filterIsInstance<WireCell.ActionBar>().firstOrNull()
    val image = cells.filterIsInstance<WireCell.Image>().firstOrNull()
    val carousel = cells.filterIsInstance<WireCell.ImageCarousel>().firstOrNull()
    val video = cells.filterIsInstance<WireCell.Video>().firstOrNull()
    val gallery = carousel?.items.orEmpty().map { item ->
        MediaFeedMedia(
            mediaId = item.mediaId,
            placeholderColor = item.placeholderColor,
            aspectRatio = item.aspectRatio,
            isVideo = false,
            width = item.width,
            height = item.height,
            url = item.url,
            zoomUrl = item.zoomUrl ?: item.url,
            altText = item.altText,
            cacheKey = item.cacheKey ?: item.mediaId?.let { "image:$it" } ?: "post:$groupId",
        )
    }
    val media = when {
        gallery.isNotEmpty() -> gallery.first()
        image != null -> MediaFeedMedia(
            placeholderColor = image.placeholderColor,
            aspectRatio = image.aspectRatio,
            isVideo = false,
            url = image.url,
            zoomUrl = image.url,
            altText = image.altText,
            cacheKey = image.cacheKey ?: "post:$groupId",
        )
        video != null -> MediaFeedMedia(
            placeholderColor = video.placeholderColor,
            aspectRatio = video.aspectRatio,
            isVideo = true,
            durationSeconds = video.durationSeconds,
            url = video.url,
            altText = video.altText,
            hlsUrl = video.hlsUrl,
            dashUrl = video.dashUrl,
            posterUrl = video.posterUrl,
            fallbackUrl = video.fallbackUrl ?: video.url,
            deliveryStatus = video.deliveryStatus,
            processingProgress = video.processingProgress,
            cacheKey = video.cacheKey ?: "post:$groupId",
        )
        else -> return null
    }
    return MediaFeedItem(
        postId = groupId,
        author = metadata.author.removePrefix("u/"),
        subreddit = metadata.subreddit.removePrefix("r/"),
        title = title.text,
        body = body,
        score = actions?.score ?: 0,
        commentCount = actions?.commentCount ?: 0,
        viewerVote = actions?.vote ?: 0,
        kind = if (media.isVideo) "video" else "image",
        createdAt = metadata.createdAt,
        postedAgo = metadata.postedAgo,
        media = media,
        communityAvatarUrl = metadata.avatarUrl,
        mediaItems = gallery,
        flair = title.flair?.let {
            dev.readthat.shared.PostFlair(it.id, it.text, it.backgroundColor, it.textColor)
        },
    )
}

class HttpCommentsRemoteSource(private val client: BackendClient) : CommentsRemoteSource {
    override suspend fun fetchTree(
        postId: String,
        maxCount: Int,
        maxDepth: Int,
        rootCommentId: String?,
    ): CommentTree {
        val timer = performanceTimer()
        val path = buildString {
            append("/v1/posts/$postId/comments?count=$maxCount&depth=$maxDepth")
            if (rootCommentId != null) {
                append("&rootCommentId=")
                append(URLEncoder.encode(rootCommentId, StandardCharsets.UTF_8.name()))
            }
        }
        val metric = if (maxCount <= 8) {
            PerformanceMetric.COMMENTS_INITIAL_FETCH
        } else {
            PerformanceMetric.COMMENTS_FULL_FETCH
        }
        return try {
            val dto = client.json.decodeFromJsonElement<ApiCommentTree>(
                client.requestJson("GET", path),
            )
            PerformanceTelemetry.duration(
                metric,
                timer,
                surface = PerformanceSurface.DETAIL,
                attributes = mapOf("phase" to if (maxCount <= 8) "initial_8" else "full_200"),
            )
            dto.toDomain()
        } catch (error: Throwable) {
            PerformanceTelemetry.duration(
                metric,
                timer,
                surface = PerformanceSurface.DETAIL,
                outcome = PerformanceOutcome.FAILURE,
                attributes = mapOf("phase" to if (maxCount <= 8) "initial_8" else "full_200"),
            )
            throw error
        }
    }

    override suspend fun fetchFocusedTree(
        postId: String,
        focusCommentId: String,
        maxCount: Int,
        maxDepth: Int,
    ): CommentTree {
        val path = "/v1/posts/$postId/comments?count=$maxCount&depth=$maxDepth&focusCommentId=" +
            URLEncoder.encode(focusCommentId, StandardCharsets.UTF_8.name())
        return client.json.decodeFromJsonElement<ApiCommentTree>(client.requestJson("GET", path)).toDomain()
    }

    override suspend fun fetchPostHeader(postId: String): PostHeader {
        val response = client.requestJson("GET", "/v1/posts/$postId")
        return client.json.decodeFromJsonElement<ApiPostEnvelope>(response).post.toDomain()
    }

    override suspend fun loadMore(
        postId: String,
        cursor: CommentNode.LoadMore,
        limit: Int,
        maxDepth: Int,
    ): LoadMoreResponse {
        val response = client.requestJson(
            "POST",
            "/v1/posts/$postId/comments/more",
            buildJsonObject {
                put("childIds", buildJsonArray { cursor.childIds.forEach { add(JsonPrimitive(it)) } })
                put("limit", limit)
                put("maxDepth", maxDepth)
            },
        )
        val dto = client.json.decodeFromJsonElement<ApiLoadMoreEnvelope>(response)
        return LoadMoreResponse(
            comments = dto.comments.map(ApiRawComment::toDomain),
            cursors = dto.cursors.map(ApiLoadMore::toDomain),
        )
    }

    override suspend fun createComment(postId: String, parentId: String?, body: String): RawComment {
        val response = client.requestJson(
            "POST",
            "/v1/posts/$postId/comments",
            buildJsonObject {
                if (parentId == null) put("parentId", null as String?) else put("parentId", parentId)
                put("body", body.trim())
                put("clientMutationId", "comment:${java.util.UUID.randomUUID()}")
            },
            requireAuthentication = true,
        )
        return client.json.decodeFromJsonElement<ApiCommentEnvelope>(response).comment.toDomain()
    }

    override suspend fun voteComment(commentId: String, value: Int): CommentVoteResult =
        vote("comments", commentId, value)

    override suspend fun votePost(postId: String, value: Int): CommentVoteResult =
        vote("posts", postId, value)

    private suspend fun vote(segment: String, id: String, value: Int): CommentVoteResult {
        val response = client.requestJson(
            "PUT",
            "/v1/$segment/$id/vote",
            buildJsonObject {
                put("value", value)
                put("clientMutationId", "vote:${java.util.UUID.randomUUID()}")
            },
            requireAuthentication = true,
        )
        val vote = response.jsonObject.getValue("vote").jsonObject
        return CommentVoteResult(
            id = vote.getValue("targetId").jsonPrimitive.content,
            value = vote.getValue("value").jsonPrimitive.content.toInt(),
            score = vote.getValue("score").jsonPrimitive.content.toInt(),
        )
    }
}

/** One client means one encrypted token set and one monotonic D1 bookmark. */
object BackendGraph {
    @Volatile private var client: BackendClient? = null
    @Volatile private var repository: BackendRepository? = null

    fun feed(context: Context, subreddit: String? = null): FeedRemoteSource =
        sharedClient(context).takeIf { it.enabled }?.let { HttpFeedRemoteSource(it, subreddit) }
            ?: dev.readthat.data.FakeFeedRemoteSource()

    fun search(context: Context): dev.readthat.search.data.SearchRemoteSource =
        sharedClient(context).takeIf { it.enabled }?.let(::HttpSearchRemoteSource)
            ?: object : dev.readthat.search.data.SearchRemoteSource {
                override suspend fun discover() = dev.readthat.search.domain.SearchDiscover()
                override suspend fun typeahead(query: String, limit: Int) =
                    dev.readthat.search.domain.SearchTypeahead(query)
                override suspend fun search(
                    request: dev.readthat.search.domain.SearchRequest,
                    cursor: String?,
                    limit: Int,
                ) = dev.readthat.search.domain.SearchPage(request.query, request.type.wire)
            }

    fun comments(context: Context): CommentsRemoteSource =
        sharedClient(context).takeIf { it.enabled }?.let(::HttpCommentsRemoteSource)
            ?: dev.readthat.comments.data.FakeCommentsApi(
                latencyMs = 250,
                postCatalog = { postId -> dev.readthat.data.FakeFeedApi.headerFor(postId) },
            )

    fun client(context: Context): BackendClient = sharedClient(context)

    fun mediaFeed(context: Context): MediaFeedRemoteSource =
        HttpMediaFeedRemoteSource(sharedClient(context))

    fun repository(context: Context): BackendRepository = repository ?: synchronized(this) {
        repository ?: BackendRepository(
            sharedClient(context),
            dev.readthat.data.db.AppDatabase.get(context.applicationContext),
        ).also { repository = it }
    }

    private fun sharedClient(context: Context): BackendClient = client ?: synchronized(this) {
        client ?: BackendClient(context.applicationContext).also { client = it }
    }
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

@Serializable
private sealed interface ApiCommentNode {
    val id: String
    fun toDomain(): CommentNode
}

@Serializable
@SerialName("comment")
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
    val children: List<ApiCommentNode>,
) : ApiCommentNode {
    override fun toDomain(): CommentNode = CommentNode.Comment(
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

@Serializable
@SerialName("load_more")
private data class ApiLoadMore(
    override val id: String,
    val parentId: String?,
    val remainingCount: Int,
    val childIds: List<String>,
) : ApiCommentNode {
    override fun toDomain(): CommentNode.LoadMore =
        CommentNode.LoadMore(id, parentId, remainingCount, childIds)
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

@Serializable private data class ApiCommentEnvelope(val comment: ApiRawComment)

@Serializable
private data class ApiLoadMoreEnvelope(
    val comments: List<ApiRawComment>,
    val cursors: List<ApiLoadMore>,
)

@Serializable private data class ApiPostEnvelope(val post: ApiPost)

@Serializable
private data class ApiMediaFeedEnvelope(
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
    val body: String?,
    val score: Int,
    val commentCount: Int,
    val viewerVote: Int = 0,
    val kind: String = "text",
    val url: String? = null,
    val media: ApiMedia?,
    val mediaItems: List<ApiMedia> = emptyList(),
    val createdAt: Long = 0,
    val subredditAvatarUrl: String? = null,
    val flair: ApiPostFlair? = null,
) {
    private fun domainMediaItems(): List<PostMedia> = mediaItems
        .ifEmpty { listOfNotNull(media) }
        .mapIndexed { index, item -> item.toDomain("post:$id:media:$index") }

    fun toDomain(): PostHeader {
        val gallery = domainMediaItems()
        return PostHeader(
            postId = id,
            title = title,
            author = "u/$author",
            subreddit = "r/$subreddit",
            score = score,
            commentCount = commentCount,
            viewerVote = viewerVote,
            kind = kind,
            body = body,
            media = gallery.firstOrNull(),
            linkUrl = url,
            mediaItems = gallery,
            flair = flair?.toDomain(),
        )
    }

    fun toMediaFeedItem(): MediaFeedItem {
        val gallery = domainMediaItems()
        val domainMedia = gallery.firstOrNull() ?: error("Media feed returned a post without media")
        return MediaFeedItem(
            postId = id,
            author = author,
            subreddit = subreddit,
            title = title,
            body = body,
            score = score,
            commentCount = commentCount,
            viewerVote = viewerVote,
            kind = kind,
            createdAt = createdAt,
            media = MediaFeedMedia.from(domainMedia),
            communityAvatarUrl = subredditAvatarUrl,
            mediaItems = gallery.map(MediaFeedMedia::from),
            flair = flair?.toDomain(),
        )
    }
}

@Serializable
private data class ApiPostFlair(
    val id: String,
    val text: String,
    val backgroundColor: String,
    val textColor: String,
) {
    fun toDomain() = dev.readthat.shared.PostFlair(id, text, backgroundColor, textColor)
}

@Serializable
private data class ApiMedia(
    val id: String? = null,
    val width: Int?,
    val height: Int?,
    val durationSeconds: Int?,
    val contentType: String?,
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
        posterUrl = posterUrl,
        fallbackUrl = fallbackUrl ?: url,
        deliveryStatus = deliveryStatus,
        processingProgress = processingProgress,
        cacheKey = cacheKey ?: id?.let { mediaId ->
            if (contentType?.startsWith("video/") == true) "video:$mediaId" else "image:$mediaId"
        } ?: fallbackCacheKey,
        mediaId = id,
        width = width,
        height = height,
        zoomUrl = zoomUrl ?: url,
    )
}
