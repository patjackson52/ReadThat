package dev.readthat.data.db

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Index

/**
 * ⭐ **The SDUI storage split: cache the template, bind the data separately.**
 *
 * This is the part of server-driven UI that is genuinely hard, and the part
 * almost nobody has published about. The problem:
 *
 *  - An SDUI payload is an **opaque render description**. The client is not
 *    supposed to understand it — that is the whole point.
 *  - But optimistic writes need something **addressable**. You cannot
 *    "increment the like count" inside a blob without re-parsing and
 *    rewriting it, and you certainly cannot do it on a background thread while
 *    the user scrolls.
 *
 * Three options exist and only one works:
 *
 *  | Option | Shape | Verdict |
 *  |---|---|---|
 *  | blob per group | `groupId → JSON` | offline reads fine, **optimistic writes impossible** |
 *  | normalize every cell | a row per cell | queryable, but you have re-invented a client schema — SDUI defeated |
 *  | **blob + extracted state** | this file | ✅ |
 *
 * So: [GroupEntity] holds the server's render description verbatim, and
 * [ItemStateEntity] holds only the handful of fields the *user* can mutate.
 * The read path JOINs them. The write path touches state only.
 *
 * Four teams converge on this split independently — Delivery Hero caches
 * templates and binds data separately, DoorDash gives every Facet a stable
 * persistable id, Netflix normalizes Sections into their own cache entries,
 * and Apollo/Relay normalize on cache ids. None of them names it as a pattern.
 */

/**
 * One post unit, as the server described it.
 *
 * @property payloadJson the serialized [dev.readthat.domain.WireGroup].
 *   Stored verbatim so a newer server can add cell types this build cannot
 *   render without the row becoming unreadable.
 * @property payloadVersion schema version of the payload.
 *   ⚠️ Without this, cached blobs outlive the app version that could parse
 *   them, and a schema change silently bricks the cache. This is the field
 *   people forget.
 * @property sortIndex server-assigned rank. The feed is ordered by this, never
 *   by a client-side sort — a ranked feed is not chronological, and sorting
 *   locally would quietly disagree with the server.
 */
@Entity(
    tableName = "feed_groups",
    primaryKeys = ["accountId", "feedId", "groupId"],
    indices = [Index(value = ["accountId", "feedId", "sortIndex"], unique = true)],
)
data class GroupEntity(
    val groupId: String,
    val sortIndex: Int,
    val payloadJson: String,
    val payloadVersion: Int = PAYLOAD_VERSION,
    val accountId: String = CacheScope.DEFAULT_ACCOUNT_ID,
    val feedId: String = CacheScope.HOME_FEED_ID,
) {
    companion object { const val PAYLOAD_VERSION = 1 }
}

/**
 * The mutable half — everything the user can change.
 *
 * Deliberately tiny. Every field here is one the client is allowed to write
 * optimistically before the server has agreed.
 *
 * Keyed by the **group id**, which is the stable item identity. Never by a
 * cell id: render identity belongs to the server and may change between
 * payload versions, so keying local state to it would orphan the state on any
 * layout change.
 */
@Entity(
    tableName = "item_state",
    primaryKeys = ["accountId", "itemId"],
)
data class ItemStateEntity(
    val itemId: String,
    val likeCount: Int,
    val liked: Boolean,
    val downvoted: Boolean = false,
    val accountId: String = CacheScope.DEFAULT_ACCOUNT_ID,
)

/**
 * The opaque pagination cursor.
 *
 * Persisted rather than held in memory so paging survives process death: the
 * user returns to a killed app and the next scroll continues where they were
 * instead of refetching page one.
 */
@Entity(
    tableName = "remote_keys",
    primaryKeys = ["accountId", "feedId"],
)
data class RemoteKeyEntity(
    val feedId: String,
    val nextCursor: String?,
    val accountId: String = CacheScope.DEFAULT_ACCOUNT_ID,
)

/** Typed media post content is stored once even when several media-feed scopes reference it. */
@Entity(
    tableName = "media_post_content",
    primaryKeys = ["accountId", "postId"],
    indices = [Index(value = ["accountId", "updatedAt"])],
)
data class MediaPostContentEntity(
    val accountId: String,
    val postId: String,
    val payloadJson: String,
    val updatedAt: Long,
)

/** Ordered membership for one immutable media-feed snapshot/anchor scope. */
@Entity(
    tableName = "media_feed_entries",
    primaryKeys = ["accountId", "feedId", "postId"],
    indices = [
        Index(value = ["accountId", "feedId", "position"], unique = true),
        Index(value = ["accountId", "postId"]),
    ],
)
data class MediaFeedEntryEntity(
    val accountId: String,
    val feedId: String,
    val postId: String,
    val position: Long,
)

/** Opaque server cursor for a media-feed scope. */
@Entity(tableName = "media_feed_remote_keys", primaryKeys = ["accountId", "feedId"])
data class MediaFeedRemoteKeyEntity(
    val accountId: String,
    val feedId: String,
    val nextCursor: String?,
    val updatedAt: Long,
)

data class MediaFeedRow(
    val accountId: String,
    val feedId: String,
    val postId: String,
    val position: Long,
    val payloadJson: String,
    val likeCount: Int?,
    val liked: Boolean?,
    val downvoted: Boolean?,
)

/**
 * Latest desired vote per post. The client-minted mutation id makes retries
 * safe, and using the post id as the primary key coalesces rapid offline taps
 * to the final desired state instead of replaying stale intermediate votes.
 */
@Entity(
    tableName = "vote_outbox",
    primaryKeys = ["accountId", "itemId"],
)
data class PendingVoteEntity(
    val itemId: String,
    val mutationId: String,
    val value: Int,
    val createdAt: Long,
    val accountId: String = CacheScope.DEFAULT_ACCOUNT_ID,
)

/** Freshness belongs to a query scope, never to a process-wide boolean. */
@Entity(
    tableName = "sync_metadata",
    primaryKeys = ["accountId", "scopeKey"],
)
data class SyncMetadataEntity(
    val accountId: String,
    val scopeKey: String,
    val lastSuccessfulSyncAt: Long,
    val validator: String? = null,
)

/** Non-secret account/profile snapshot used to render before network validation. */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val username: String,
    val displayName: String,
    val bio: String,
    val avatarUrl: String?,
    val karma: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAuthenticatedAt: Long,
    val isActive: Boolean,
)

/** Durable create-post/upload outbox; localPath points inside no-backup storage. */
@Entity(tableName = "post_outbox", indices = [Index("accountId")])
data class PendingPostEntity(
    @PrimaryKey val mutationId: String,
    val accountId: String,
    val subreddit: String,
    val kind: String,
    val title: String,
    val body: String,
    val linkUrl: String,
    val localPath: String?,
    val contentType: String?,
    val byteSize: Long?,
    val width: Int?,
    val height: Int?,
    val durationSeconds: Int?,
    val mediaId: String?,
    val state: String,
    val remotePostId: String?,
    val lastError: String?,
    val createdAt: Long,
    /** Ordered resumable upload descriptors; scalar media columns are the legacy cover item. */
    val mediaItemsJson: String = "[]",
    val flairId: String? = null,
    val flairText: String? = null,
    val flairBackgroundColor: String? = null,
    val flairTextColor: String? = null,
)

/** Durable, account-scoped community creation command and optimistic snapshot. */
@Entity(
    tableName = "subreddit_outbox",
    indices = [Index(value = ["accountId", "name"], unique = true)],
)
data class PendingSubredditEntity(
    @PrimaryKey val mutationId: String,
    val accountId: String,
    val name: String,
    val displayName: String,
    val description: String,
    val accessType: String,
    val state: String,
    val remoteSubredditId: String?,
    val lastError: String?,
    val createdAt: Long,
)

/**
 * L2 telemetry spool. The payload contains only the bounded performance event
 * contract: no account id, content id, body text, URL, or persistent device id.
 */
@Entity(tableName = "performance_outbox", indices = [Index("createdAt")])
data class PendingPerformanceEventEntity(
    @PrimaryKey val id: String,
    val payloadJson: String,
    val createdAt: Long,
)

/**
 * Recoverable product-analytics spool. Raw content ids live only in app-private
 * storage until upload; the Worker replaces them with keyed hashes.
 */
@Entity(
    tableName = "product_analytics_outbox",
    indices = [Index("createdAt"), Index(value = ["dedupeKey"], unique = true)],
)
data class PendingProductAnalyticsEventEntity(
    @PrimaryKey val id: String,
    val installationId: String,
    val sessionId: String,
    val accountId: String?,
    val payloadJson: String,
    val dedupeKey: String?,
    val createdAt: Long,
)

/** Community metadata; viewerRole makes the row account scoped. */
@Entity(tableName = "subreddits", primaryKeys = ["accountId", "name"])
data class SubredditEntity(
    val accountId: String,
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    val accessType: String,
    val viewerRole: String?,
    val subscriberCount: Int,
    val updatedAt: Long,
    val avatarUrl: String? = null,
    val rulesJson: String = "[]",
)

/** Coalesced desired join state. POST join and DELETE join are both idempotent. */
@Entity(
    tableName = "community_membership_outbox",
    primaryKeys = ["accountId", "name"],
    indices = [Index(value = ["accountId", "createdAt"])],
)
data class PendingCommunityMembershipEntity(
    val accountId: String,
    val name: String,
    val mutationId: String,
    val desiredJoined: Boolean,
    val createdAt: Long,
)

/** Compact drawer membership; full community detail stays in [SubredditEntity]. */
@Entity(
    tableName = "community_memberships",
    primaryKeys = ["accountId", "name"],
    indices = [Index(value = ["accountId", "source", "name"])],
)
data class CommunityMembershipEntity(
    val accountId: String,
    val id: String,
    val name: String,
    val displayName: String,
    val accessType: String,
    val viewerRole: String,
    val source: String,
    val syncedAt: Long,
)

/** Account-scoped recent-community L2 cache, ordered by the local visit time. */
@Entity(
    tableName = "community_visits",
    primaryKeys = ["accountId", "name"],
    indices = [Index(value = ["accountId", "visitedAt"])],
)
data class CommunityVisitEntity(
    val accountId: String,
    val id: String,
    val name: String,
    val displayName: String,
    val visitedAt: Long,
)

/** Durable ordered visit/remove/clear commands; the Worker API applies them idempotently. */
@Entity(
    tableName = "community_visit_outbox",
    indices = [Index(value = ["accountId", "createdAt"])],
)
data class CommunityVisitMutationEntity(
    @PrimaryKey val mutationId: String,
    val accountId: String,
    val operation: String,
    val name: String?,
    val occurredAt: Long,
    val createdAt: Long,
)

/** Validator/freshness for a fully committed drawer snapshot. */
@Entity(tableName = "community_drawer_sync")
data class CommunityDrawerSyncEntity(
    @PrimaryKey val accountId: String,
    val validator: String?,
    val lastSuccessfulSyncAt: Long,
)

/** Global device preferences. Kept in Room so settings are reactive and durable. */
@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: String = "global",
    val darkTheme: Boolean,
    val compactPosts: Boolean,
    val autoplayVideo: Boolean,
    val autoplayOnMetered: Boolean,
    val reduceDataOnMetered: Boolean,
    val reduceAnimations: Boolean,
    val blurMatureMedia: Boolean,
    val updatedAt: Long,
)

/** Opaque result rows for a single account-scoped search fingerprint. */
@Entity(
    tableName = "search_results",
    primaryKeys = ["accountId", "queryKey", "itemId"],
    indices = [Index(value = ["accountId", "queryKey", "sortIndex"], unique = true)],
)
data class SearchResultEntity(
    val accountId: String,
    val queryKey: String,
    val itemId: String,
    val sortIndex: Int,
    val payloadJson: String,
    val cachedAt: Long,
)

/** Cursor and freshness metadata survive process death and network loss. */
@Entity(tableName = "search_remote_keys", primaryKeys = ["accountId", "queryKey"])
data class SearchRemoteKeyEntity(
    val accountId: String,
    val queryKey: String,
    val nextCursor: String?,
    val updatedAt: Long,
)

/** Composite All/typeahead/discovery envelopes that do not map to paging rows. */
@Entity(tableName = "search_snapshots", primaryKeys = ["accountId", "queryKey"])
data class SearchSnapshotEntity(
    val accountId: String,
    val queryKey: String,
    val payloadJson: String,
    val cachedAt: Long,
)

/** Recent search terms stay device-local and can be cleared independently. */
@Entity(tableName = "search_recent", primaryKeys = ["accountId", "normalizedQuery"])
data class SearchRecentEntity(
    val accountId: String,
    val normalizedQuery: String,
    val query: String,
    val searchedAt: Long,
)

/**
 * Account-scoped JSON document cache for API resources that are not naturally
 * tabular (post details, comments, discovery and profile envelopes). This is
 * the durable L2 behind shared repositories; their StateFlows are the L1.
 */
@Entity(
    tableName = "cached_documents",
    primaryKeys = ["accountId", "cacheKey"],
    indices = [Index(value = ["accountId", "updatedAt"])],
)
data class CachedDocumentEntity(
    val accountId: String,
    val cacheKey: String,
    val payloadJson: String,
    val updatedAt: Long,
    val validator: String? = null,
)

/**
 * The row the UI actually pages over — blob JOINed with mutable state.
 *
 * Room projects the join into this directly, so the merge happens **in SQL**
 * rather than as an N+1 lookup per item during flattening.
 */
data class GroupWithState(
    val accountId: String,
    val feedId: String,
    val groupId: String,
    val sortIndex: Int,
    val payloadJson: String,
    val payloadVersion: Int,
    val likeCount: Int?,
    val liked: Boolean?,
    val downvoted: Boolean? = null,
)

object CacheScope {
    const val DEFAULT_ACCOUNT_ID = "local-default"
    const val LEGACY_ACCOUNT_ID = "legacy-unscoped"
    const val HOME_FEED_ID = "feed:home"

    /** One durable Paging/Room namespace per community, matching the mature Android feed ids. */
    fun communityFeedId(name: String): String =
        "feed:subreddit:${name.trim().removePrefix("r/").lowercase()}"
}
