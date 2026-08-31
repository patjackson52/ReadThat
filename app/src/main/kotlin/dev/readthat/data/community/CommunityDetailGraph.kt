package dev.readthat.data.community

import android.content.Context
import dev.readthat.communitydetail.data.CommunityDetailRepository
import dev.readthat.communitydetail.data.FakeCommunityDetailRemoteSource
import dev.readthat.data.backend.BackendGraph
import dev.readthat.data.backend.HttpCommunityDetailRemoteSource
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.AndroidDatabaseProvider
import dev.readthat.data.sync.CommunityMembershipSyncScheduler
import java.util.concurrent.ConcurrentHashMap

object CommunityDetailGraph {
    private val repositories = ConcurrentHashMap<String, CommunityDetailRepository>()

    fun repository(context: Context, accountId: String, name: String): CommunityDetailRepository {
        val normalized = name.trim().removePrefix("r/").lowercase()
        val key = "$accountId:$normalized"
        return repositories[key] ?: synchronized(this) {
            repositories[key] ?: CommunityDetailRepository(
                db = AndroidDatabaseProvider.get(context.applicationContext),
                remote = BackendGraph.client(context).let { client ->
                    if (client.enabled) HttpCommunityDetailRemoteSource(client)
                    else FakeCommunityDetailRemoteSource()
                },
                accountId = accountId,
                name = normalized,
                scheduleMembershipSync = {
                    CommunityMembershipSyncScheduler.enqueue(context.applicationContext, accountId)
                },
            ).also { repositories[key] = it }
        }
    }
}
