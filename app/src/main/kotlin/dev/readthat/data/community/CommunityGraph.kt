package dev.readthat.data.community

import android.content.Context
import dev.readthat.communities.data.CommunityRepository
import dev.readthat.data.backend.BackendGraph
import dev.readthat.data.backend.HttpCommunityDrawerRemoteSource
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.AndroidDatabaseProvider
import dev.readthat.data.sync.CommunityVisitSyncScheduler
import dev.readthat.data.sync.SubredditCreationScheduler
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object CommunityGraph {
    private val repositories = ConcurrentHashMap<String, CommunityRepository>()
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun repository(context: Context, accountId: String): CommunityRepository =
        repositories[accountId] ?: synchronized(this) {
            repositories[accountId] ?: CommunityRepository(
                db = AndroidDatabaseProvider.get(context.applicationContext),
                remote = HttpCommunityDrawerRemoteSource(BackendGraph.client(context)),
                accountId = accountId,
                scope = applicationScope,
                scheduleVisitSync = {
                    CommunityVisitSyncScheduler.enqueue(context.applicationContext, accountId)
                },
                scheduleCommunityCreation = { mutationId ->
                    SubredditCreationScheduler.enqueue(context.applicationContext, mutationId)
                },
            ).also { repositories[accountId] = it }
        }
}
