package dev.readthat.client

import dev.readthat.data.db.AppDatabase
import dev.readthat.domain.WireFeedPage
import kotlinx.coroutines.CoroutineScope

/** Explicit work selection lets each OS retain its own scheduling and power constraints. */
data class SharedBackgroundMaintenanceRequest(
    val drainMutations: Boolean = true,
    val refreshHomeFeed: Boolean = true,
)

data class SharedBackgroundMaintenanceResult(
    val refreshedFeed: WireFeedPage?,
)

/**
 * Lifecycle-neutral background executor shared by WorkManager and BGTaskScheduler.
 *
 * The native host restores and validates the active account before constructing this executor.
 * All useful work then runs through the same client, Room transactions, idempotency keys and
 * synchronization lanes as foreground UI actions.
 */
class SharedBackgroundMaintenance(
    client: ReadThatClient,
    database: AppDatabase,
    scope: CoroutineScope,
    accountId: String,
) {
    private val repository = OfflineFirstRepository(
        client = client,
        database = database,
        scope = scope,
        accountIdOverride = accountId,
        maintainGlobalState = false,
    )

    suspend fun run(
        request: SharedBackgroundMaintenanceRequest = SharedBackgroundMaintenanceRequest(),
    ): SharedBackgroundMaintenanceResult {
        if (request.drainMutations) repository.syncPendingMutations()
        val page = if (request.refreshHomeFeed) repository.refreshHomeFeedForBackground() else null
        return SharedBackgroundMaintenanceResult(page)
    }
}
