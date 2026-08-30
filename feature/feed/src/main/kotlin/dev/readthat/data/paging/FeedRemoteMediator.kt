package dev.readthat.data.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import dev.readthat.data.FeedSyncEngine
import dev.readthat.data.FeedRemoteSource
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.CacheScope
import dev.readthat.data.db.GroupWithState
import kotlinx.serialization.json.Json
import dev.readthat.observability.PerformanceMetric
import dev.readthat.observability.PerformanceOutcome
import dev.readthat.observability.PerformanceSurface
import dev.readthat.observability.PerformanceTelemetry
import dev.readthat.observability.performanceTimer

/**
 * ⭐ **The network↔database seam.**
 *
 * A [RemoteMediator] does not serve pages to the UI — Room's `PagingSource`
 * does. This only runs when the DB **runs out of cached pages**, fetches more,
 * and writes them down. The UI never sees the network.
 *
 * That inversion is the reason to use Paging 3 here at all rather than the
 * hand-rolled cursor loop this sample used to have:
 *
 *  - **Offline works by construction.** With no network, `APPEND` fails and
 *    Paging keeps serving whatever the DB already holds.
 *  - **Paging survives process death**, because the cursor is a row.
 *  - **One writer.** Both the mediator and an optimistic like write to Room;
 *    Room invalidates the PagingSource and the UI re-emits. There is no second
 *    code path that mutates a list in memory.
 *
 * @param feedId scopes the cursor. A second feed (Popular, a subreddit) is a
 *   different key, not a different table.
 */
@OptIn(ExperimentalPagingApi::class)
class FeedRemoteMediator(
    private val accountId: String = CacheScope.DEFAULT_ACCOUNT_ID,
    private val feedId: String = CacheScope.HOME_FEED_ID,
    private val db: AppDatabase,
    private val remote: FeedRemoteSource,
    private val json: Json,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val freshnessMillis: Long = DEFAULT_FRESHNESS_MILLIS,
    /** One-shot UI intent; null falls back to the normal Paging classification. */
    private val pendingLoadType: () -> String? = { null },
) : RemoteMediator<Int, GroupWithState>() {

    private val dao = db.feedDao()
    private val sync = FeedSyncEngine(db, remote, json, nowMillis)

    /**
     * Whether cached data is fresh enough to show before hitting the network.
     *
     * Fresh rows use [InitializeAction.SKIP_INITIAL_REFRESH], so cached content
     * is available immediately, offline included. A stale or empty cache uses
     * [InitializeAction.LAUNCH_INITIAL_REFRESH]; Room remains the UI source and
     * existing rows stay visible while that refresh runs.
     */
    override suspend fun initialize(): InitializeAction {
        if (dao.groupCount(accountId, feedId) == 0) return InitializeAction.LAUNCH_INITIAL_REFRESH
        val lastSync = dao.syncMetadata(accountId, feedId)?.lastSuccessfulSyncAt ?: 0L
        return if (nowMillis() - lastSync >= freshnessMillis) {
            InitializeAction.LAUNCH_INITIAL_REFRESH
        } else {
            InitializeAction.SKIP_INITIAL_REFRESH
        }
    }

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, GroupWithState>,
    ): MediatorResult {
        val cursor: String? = when (loadType) {
            // Start over from the head of the feed.
            LoadType.REFRESH -> null

            // The feed only grows downward. Returning endOfPagination here —
            // rather than trying to fetch — is what stops Paging asking for
            // items above the top on every scroll to the start.
            LoadType.PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)

            LoadType.APPEND -> dao.remoteKey(feedId, accountId)?.nextCursor
                // A null cursor means the server already told us this is the
                // end. Not an error — the natural terminus.
                ?: return MediatorResult.Success(endOfPaginationReached = true)
        }

        val timer = performanceTimer()
        val loadTypeName = pendingLoadType() ?: if (loadType == LoadType.REFRESH) {
            "Organic First Page"
        } else {
            "Next Page"
        }
        return try {
            val page = if (loadType == LoadType.REFRESH) {
                sync.refresh(accountId, feedId)
            } else {
                sync.append(accountId, feedId, requireNotNull(cursor))
            }

            PerformanceTelemetry.duration(
                PerformanceMetric.FEED_LOAD_SUCCESS,
                timer,
                surface = PerformanceSurface.FEED,
                attributes = mapOf("load_type" to loadTypeName),
                measurements = mapOf("cache_hit" to 0.0),
            )
            MediatorResult.Success(endOfPaginationReached = page.nextCursor == null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            PerformanceTelemetry.duration(
                PerformanceMetric.FEED_LOAD_FAIL,
                timer,
                surface = PerformanceSurface.FEED,
                outcome = PerformanceOutcome.FAILURE,
                attributes = mapOf("load_type" to loadTypeName),
            )
            // Paging surfaces this as LoadState.Error. The cached pages stay on
            // screen — an offline user keeps reading rather than seeing a
            // blank list.
            MediatorResult.Error(e)
        }
    }

    companion object {
        const val DEFAULT_FEED_ID = CacheScope.HOME_FEED_ID
        const val DEFAULT_FRESHNESS_MILLIS = 15 * 60 * 1_000L
    }
}
