package dev.readthat.search.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SearchType(val wire: String, val label: String) {
    All("all", "All"),
    Posts("posts", "Posts"),
    Communities("communities", "Communities"),
    Comments("comments", "Comments"),
    Media("media", "Media"),
    Profiles("profiles", "Profiles"),
}

@Serializable
enum class SearchSort(val wire: String, val label: String) {
    Relevance("relevance", "Relevance"),
    Hot("hot", "Hot"),
    Top("top", "Top"),
    New("new", "New"),
    Comments("comments", "Comment count"),
}

@Serializable
enum class SearchTime(val wire: String, val label: String) {
    All("all", "All time"),
    Year("year", "Past year"),
    Month("month", "Past month"),
    Week("week", "Past week"),
    Day("day", "Past 24 hours"),
    Hour("hour", "Past hour"),
}

@Serializable
data class SearchRequest(
    val query: String,
    val type: SearchType = SearchType.All,
    val sort: SearchSort = SearchSort.Relevance,
    val time: SearchTime = SearchTime.All,
    val safe: Boolean = true,
    val subreddit: String? = null,
) {
    val cacheKey: String get() = listOf(
        query.trim().lowercase(), type.wire, sort.wire, time.wire,
        if (safe) "safe" else "unfiltered", subreddit.orEmpty().lowercase(),
    ).joinToString("|")
}

@Serializable
sealed interface SearchItem { val id: String }

@Serializable
@SerialName("community")
data class SearchCommunity(
    override val id: String,
    val name: String,
    val displayName: String,
    val description: String = "",
    val accessType: String = "public",
    val subscriberCount: Int = 0,
) : SearchItem

@Serializable
@SerialName("profile")
data class SearchProfile(
    override val id: String,
    val username: String,
    val displayName: String,
    val bio: String = "",
    val avatarUrl: String? = null,
    val karma: Int = 0,
) : SearchItem

@Serializable
data class SearchMedia(
    val id: String,
    val thumbnailUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationSeconds: Int? = null,
    val cacheKey: String? = null,
)

@Serializable
@SerialName("post")
data class SearchPost(
    override val id: String,
    val subreddit: String,
    val author: String,
    val kind: String,
    val title: String,
    val body: String? = null,
    val url: String? = null,
    val score: Int = 0,
    val commentCount: Int = 0,
    val viewerVote: Int = 0,
    val createdAt: Long = 0,
    val media: SearchMedia? = null,
) : SearchItem

@Serializable
data class SearchParentPost(
    val title: String,
    val subreddit: String,
    val score: Int = 0,
    val commentCount: Int = 0,
)

@Serializable
@SerialName("comment")
data class SearchComment(
    override val id: String,
    val postId: String,
    val parentId: String? = null,
    val author: String,
    val body: String,
    val score: Int = 0,
    val viewerVote: Int = 0,
    val createdAt: Long = 0,
    val post: SearchParentPost,
) : SearchItem

@Serializable
data class SearchSections(
    val communities: List<SearchItem> = emptyList(),
    val posts: List<SearchItem> = emptyList(),
    val comments: List<SearchItem> = emptyList(),
    val media: List<SearchItem> = emptyList(),
    val profiles: List<SearchItem> = emptyList(),
) {
    val isEmpty: Boolean get() = communities.isEmpty() && posts.isEmpty() && comments.isEmpty() &&
        media.isEmpty() && profiles.isEmpty()
}

@Serializable
data class SearchPage(
    val query: String,
    val type: String,
    val items: List<SearchItem> = emptyList(),
    val sections: SearchSections? = null,
    val nextCursor: String? = null,
)

@Serializable
data class SearchTypeahead(
    val query: String,
    val completions: List<String> = emptyList(),
    val communities: List<SearchItem> = emptyList(),
    val profiles: List<SearchItem> = emptyList(),
)

@Serializable
data class TrendingSearch(
    val id: String,
    val query: String,
    val subreddit: String,
    val kind: String,
    val score: Int = 0,
    val commentCount: Int = 0,
    val createdAt: Long = 0,
)

@Serializable
data class DiscoverCommunity(
    val id: String,
    val name: String,
    val displayName: String,
    val subscriberCount: Int = 0,
)

@Serializable
data class SearchDiscover(
    val trending: List<TrendingSearch> = emptyList(),
    val communities: List<DiscoverCommunity> = emptyList(),
)
