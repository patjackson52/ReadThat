package dev.readthat.data

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.flatMap
import androidx.paging.map
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.CacheScope
import dev.readthat.data.db.GroupWithState
import dev.readthat.data.db.ItemStateEntity
import dev.readthat.data.db.PendingVoteEntity
import dev.readthat.data.paging.FeedRemoteMediator
import dev.readthat.domain.CellUi
import dev.readthat.domain.FeedFlattener
import dev.readthat.domain.WireCell
import dev.readthat.domain.WireFeedPage
import dev.readthat.domain.WireGroup
import dev.readthat.domain.NormalFeedMediaContext
import dev.readthat.domain.toPostTransitionPreview
import dev.readthat.shared.PostTransitionPreview
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceOutcome
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.performanceTimer
import dev.readthat.core.post.PostInteractionRepository

/** The network contract. Kept an interface so tests can drive it deterministically. */
interface FeedRemoteSource {
    suspend fun loadPage(cursor: String?): WireFeedPage

    /** Returns the authoritative aggregate, or null for an offline fixture. */
    suspend fun votePost(postId: String, value: Int, clientMutationId: String): PostVoteResult? = null
}

data class PostVoteResult(val score: Int, val liked: Boolean, val value: Int = if (liked) 1 else 0)

class FakeFeedRemoteSource(
    private val api: FakeFeedApi = FakeFeedApi(),
) : FeedRemoteSource {
    override suspend fun loadPage(cursor: String?): WireFeedPage = api.loadPage(cursor)
}

/**
 * The repository boundary.
 *
 * **The only thing in the app that knows a network exists.** Everything above
 * sees a `Flow<PagingData<CellUi>>`; everything below is plumbing.
 *
 * ### Reads — Paging 3 over Room, flattened late
 *
 * ```
 *   Pager(pagingSourceFactory = dao::pagingSource,
 *         remoteMediator     = FeedRemoteMediator)
 *          │  PagingData<GroupWithState>      ← paged at the GROUP level
 *          ▼
 *   .flatMap { group -> flatten(group) }
 *          │  PagingData<CellUi>              ← rendered as a flat list
 *          ▼
 *   LazyColumn
 * ```
 *
 * ⭐ **Why page groups and flatten afterwards, rather than paging cells.**
 * A `Group` is the atomic unit the server returns and the unit a cursor
 * addresses — a page boundary must never fall *inside* a post unit, or a post
 * renders with its header on one page and its action bar on the next.
 * `PagingData.flatMap` expands each group into its cells after paging has
 * already decided the boundaries, so flattening cannot corrupt them.
 *
 * ### Writes — optimistic, and possible *because* of the storage split
 *
 * [toggleLike] atomically writes `item_state` plus a tiny `vote_outbox` row.
 * The server's blob is never rewritten, Room invalidates the PagingSource,
 * and the affected page re-emits. Had the like count lived inside the payload JSON, an optimistic
 * write would mean parsing, patching and re-serializing a blob on the main
 * path — which is exactly why the two are separate tables.
 */
@OptIn(ExperimentalPagingApi::class)
class FeedRepository(
    private val db: AppDatabase,
    private val remote: FeedRemoteSource,
    private val accountId: String = CacheScope.DEFAULT_ACCOUNT_ID,
    private val feedId: String = CacheScope.HOME_FEED_ID,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val onVoteQueued: () -> Unit = {},
    private val postInteractions: PostInteractionRepository? = null,
) {
    private val dao = db.feedDao()

    private val _droppedCellTypes = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val _initialCacheTier = MutableStateFlow<String?>(null)
    private val pendingLoadType = AtomicReference<String?>(null)

    /**
     * Forward-compatibility telemetry: cell types this build could not render.
     *
     * Deliberately a **side channel**, not paged content. Drop counts are an
     * observation *about* the stream, so folding them into `PagingData` would
     * mean a fake list item whose identity changes as pages load. In production
     * this flow is a metric, not a banner — it is what tells the platform team
     * how much of the feed a given app version is failing to render.
     */
    val droppedCellTypes: StateFlow<Map<String, Int>> = _droppedCellTypes.asStateFlow()

    /** Source of the first rendered rows, captured before Paging begins. */
    val initialCacheTier: StateFlow<String?> = _initialCacheTier.asStateFlow()

    fun feed(): Flow<PagingData<CellUi>> = flow {
        _initialCacheTier.value = withContext(io) {
            if (dao.groupCount(accountId, feedId) > 0) "room" else "network"
        }
        emitAll(pagedFeed())
    }

    private fun pagedFeed(): Flow<PagingData<CellUi>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            // Fetch the next page while the user is still this many items from
            // the end. Prefetching at the last item means the spinner is always
            // visible; prefetching too early wastes bandwidth on a fast scroll.
            prefetchDistance = PAGE_SIZE / 2,
            // Placeholders need a total count the server never gives us with
            // cursor pagination, so they are off. The cost is that the scrollbar
            // grows as you scroll — the alternative is a count query per page.
            enablePlaceholders = false,
            initialLoadSize = PAGE_SIZE,
            // Ten server pages stay hot. Older pages are dropped from
            // PagingData, not Room, so a reverse scroll rehydrates from disk
            // without retaining an unbounded object graph in the ViewModel.
            maxSize = MAX_IN_MEMORY_GROUPS,
        ),
        remoteMediator = FeedRemoteMediator(
            accountId = accountId,
            feedId = feedId,
            db = db,
            remote = remote,
            json = json,
            pendingLoadType = { pendingLoadType.getAndSet(null) },
        ),
        pagingSourceFactory = { dao.pagingSource(accountId, feedId) },
    ).flow.map { paging ->
        paging
            // Decode + merge per row, lazily: Paging only transforms the pages
            // it currently holds, not the whole feed.
            .map { row -> decode(row) }
            // Group → cells. AFTER paging, so a page boundary can never fall
            // inside a post unit.
            .flatMap { decoded -> decoded.toCells(::recordDropped) }
    }

    fun markUserRefresh() {
        pendingLoadType.set("User Refresh")
    }

    fun markErrorRetry() {
        pendingLoadType.set("Error Retry")
    }

    /**
     * Captures media in exactly the order used by the normal feed and captures
     * its cursor in the same Room transaction. MediaFeed can therefore start at
     * the tapped item, page backward through already-seen media, and continue
     * after the last cached normal-feed group without restarting ranking.
     */
    suspend fun mediaLaunchContext(
        anchorPostId: String,
        visibleFallback: PostTransitionPreview,
    ): NormalFeedMediaContext = withContext(io) {
        val snapshot = db.withTransaction {
            dao.orderedGroups(accountId, feedId) to dao.remoteKey(feedId, accountId)?.nextCursor
        }
        val cachedMedia = snapshot.first.mapNotNull { row ->
            val group = decode(row)
            val postId = group.group?.groupId ?: return@mapNotNull null
            group.toCells().toPostTransitionPreview(postId).takeIf { it.media != null }
        }
        val items = if (cachedMedia.any { it.postId == anchorPostId }) {
            cachedMedia
        } else {
            cachedMedia + listOfNotNull(visibleFallback.takeIf { it.media != null })
        }.distinctBy(PostTransitionPreview::postId)
        val anchorIndex = items.indexOfFirst { it.postId == anchorPostId }
        check(anchorIndex >= 0) { "Tapped media $anchorPostId is absent from the feed snapshot" }
        NormalFeedMediaContext(
            snapshotId = UUID.randomUUID().toString(),
            sourceFeedId = feedId,
            anchorPostId = anchorPostId,
            items = items,
            anchorIndex = anchorIndex,
            nextFeedCursor = snapshot.second,
        )
    }

    /**
     * Decodes one row into a wire group with the user's state merged in.
     *
     * The blob is the server's; `likeCount`/`liked` come from the JOIN. If the
     * payload version is one this build cannot read, the row degrades to an
     * empty group rather than throwing — an unparseable cached blob must not
     * take down the feed.
     */
    private fun decode(row: GroupWithState): DecodedGroup {
        val group = runCatching {
            if (row.payloadVersion != dev.readthat.data.db.GroupEntity.PAYLOAD_VERSION) null
            else json.decodeFromString<WireGroup>(row.payloadJson)
        }.getOrNull()
        return DecodedGroup(group, row.likeCount, row.liked, row.downvoted)
    }

    /** Optimistic three-state vote. Touches `item_state` only — never the blob. */
    suspend fun vote(itemId: String, requestedValue: Int): Unit = withContext(io) {
        postInteractions?.let {
            it.vote(itemId, requestedValue, PerformanceSurface.FEED)
            return@withContext
        }
        val localTimer = performanceTimer()
        require(requestedValue in -1..1)
        val current = dao.stateFor(itemId, accountId)
        val currentVote = when {
            current?.liked == true -> 1
            current?.downvoted == true -> -1
            else -> 0
        }
        val nextVote = if (currentVote == requestedValue) 0 else requestedValue
        val base = current?.likeCount ?: 0
        val mutationId = UUID.randomUUID().toString()
        db.withTransaction {
            dao.putState(ItemStateEntity(
                itemId = itemId,
                likeCount = base - currentVote + nextVote,
                liked = nextVote == 1,
                downvoted = nextVote == -1,
                accountId = accountId,
            ))
            dao.enqueueVote(PendingVoteEntity(
                itemId,
                mutationId,
                nextVote,
                System.currentTimeMillis(),
                accountId,
            ))
        }
        val mutationType = when (nextVote) {
            1 -> "post_upvote"
            -1 -> "post_downvote"
            else -> "post_vote_clear"
        }
        PerformanceTelemetry.duration(
            PerformanceMetric.MUTATION_LOCAL_COMMIT,
            localTimer,
            surface = PerformanceSurface.FEED,
            attributes = mapOf("mutation_type" to mutationType, "cache_tier" to "room"),
        )
        onVoteQueued()

        // Best effort now; the same durable row is retried by RemoteMediator on
        // the next successful page request after spotty connectivity recovers.
        try {
            val confirmed = remote.votePost(itemId, nextVote, mutationId)
            if (confirmed == null) dao.deletePendingVote(itemId, mutationId, accountId)
            else dao.confirmVote(itemId, mutationId, confirmed.score, confirmed.value, accountId)
            PerformanceTelemetry.duration(
                PerformanceMetric.MUTATION_SERVER_ACK,
                localTimer,
                surface = PerformanceSurface.FEED,
                attributes = mapOf("mutation_type" to mutationType),
            )
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            PerformanceTelemetry.record(dev.readthat.observability.PerformanceEvent(
                name = PerformanceMetric.MUTATION_SERVER_ACK,
                value = localTimer.elapsedMilliseconds(),
                surface = PerformanceSurface.FEED,
                outcome = PerformanceOutcome.QUEUED,
                attributes = mapOf("mutation_type" to mutationType),
            ))
            // Keep the optimistic state and outbox row. The mutation id is
            // stable, so a retry cannot apply the vote twice server-side.
        }
    }

    suspend fun toggleLike(itemId: String) = vote(itemId, 1)

    private fun recordDropped(dropped: Map<String, Int>) {
        if (dropped.isEmpty()) return
        val total = dropped.values.sum().toDouble()
        PerformanceTelemetry.record(dev.readthat.observability.PerformanceEvent(
            name = PerformanceMetric.SDUI_DROPPED_CELL,
            value = total,
            unit = dev.readthat.observability.PerformanceUnit.COUNT,
            surface = PerformanceSurface.FEED,
            outcome = PerformanceOutcome.FAILURE,
            measurements = mapOf("dropped_count" to total),
        ))
        _droppedCellTypes.update { current ->
            val merged = current.toMutableMap()
            dropped.forEach { (type, count) -> merged[type] = (merged[type] ?: 0) + count }
            merged
        }
    }

    private companion object {
        const val PAGE_SIZE = 12
        const val MAX_IN_MEMORY_GROUPS = PAGE_SIZE * 10
    }
}

/** A decoded row: the server's group, plus the viewer state the JOIN supplied. */
data class DecodedGroup(
    val group: WireGroup?,
    val likeCount: Int?,
    val liked: Boolean?,
    val downvoted: Boolean? = null,
)

/**
 * Expands one decoded group into render-ready cells.
 *
 * ⭐ **The merge point.** The server's payload says *"there is an action bar
 * here"*; the count and the liked flag are overwritten from `item_state`,
 * which the user can mutate. This is the line that makes optimistic writes
 * work under SDUI.
 */
internal fun DecodedGroup.toCells(
    onDropped: (Map<String, Int>) -> Unit = {},
): List<CellUi> {
    val g = group ?: return emptyList()
    val merged = WireGroup(
        groupId = g.groupId,
        cells = g.cells.map { cell ->
            if (cell is WireCell.ActionBar) {
                cell.copy(
                    score = likeCount ?: cell.score,
                    liked = liked ?: cell.liked,
                    vote = when {
                        liked == true -> 1
                        downvoted == true -> -1
                        liked == false || downvoted == false -> 0
                        else -> cell.vote
                    },
                )
            } else {
                cell
            }
        },
    )
    return FeedFlattener.flatten(listOf(merged)).also { onDropped(it.droppedCellTypes) }.items
}
