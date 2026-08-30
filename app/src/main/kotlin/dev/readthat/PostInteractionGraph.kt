package dev.readthat

import android.content.Context
import dev.readthat.core.post.ConfirmedPostVote
import dev.readthat.core.post.PostInteractionRepository
import dev.readthat.core.post.PostVoteRemoteSource
import dev.readthat.data.backend.BackendGraph
import dev.readthat.data.db.AppDatabase
import dev.readthat.data.db.CacheScope
import dev.readthat.data.sync.FeedSyncScheduler
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** App composition root for the post mutation path shared by every surface. */
object PostInteractionGraph {
    @Volatile private var instance: PostInteractionRepository? = null

    fun repository(context: Context): PostInteractionRepository = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    private fun create(context: Context): PostInteractionRepository {
        val backend = BackendGraph.repository(context)
        val client = BackendGraph.client(context)
        return PostInteractionRepository(
            db = AppDatabase.get(context),
            remote = PostVoteRemoteSource { postId, value, mutationId ->
                val response = client.requestJson(
                    method = "PUT",
                    path = "/v1/posts/$postId/vote",
                    body = buildJsonObject {
                        put("value", value)
                        put("clientMutationId", mutationId)
                    },
                    requireAuthentication = true,
                )
                val vote = response.jsonObject.getValue("vote").jsonObject
                ConfirmedPostVote(
                    score = vote.getValue("score").jsonPrimitive.content.toInt(),
                    value = vote.getValue("value").jsonPrimitive.content.toInt(),
                )
            },
            accountId = { backend.activeAccountId ?: CacheScope.DEFAULT_ACCOUNT_ID },
            onVoteQueued = { FeedSyncScheduler.enqueueVoteOutbox(context) },
        )
    }
}
