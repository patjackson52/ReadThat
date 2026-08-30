package dev.readthat.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Process-safe entry point for all deferred feed work. */
object FeedSyncScheduler {
    private const val PERIODIC_REFRESH = "feed-periodic-refresh"
    private const val VOTE_OUTBOX = "feed-vote-outbox"
    private const val ON_DEMAND_REFRESH = "feed-on-demand-refresh"

    private val connected = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Idempotent; safe to call on every process start. */
    fun initialize(context: Context) {
        // The AndroidX Startup provider initializes WorkManager before the
        // production Application. Local JVM tests deliberately do not install
        // that provider, so application construction must remain side-effect
        // free there.
        if (!WorkManager.isInitialized()) return
        val request = PeriodicWorkRequestBuilder<FeedRefreshWorker>(
            1, TimeUnit.HOURS,
            15, TimeUnit.MINUTES,
        )
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_REFRESH,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** Coalesces rapid taps into one durable outbox drain. */
    fun enqueueVoteOutbox(context: Context) {
        val request = OneTimeWorkRequestBuilder<VoteOutboxWorker>()
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            VOTE_OUTBOX,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun enqueueRefresh(context: Context) {
        val request = OneTimeWorkRequestBuilder<FeedRefreshWorker>()
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            ON_DEMAND_REFRESH,
            // Rotation/rapid foreground transitions must not cancel a useful
            // refresh already on the wire.
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
