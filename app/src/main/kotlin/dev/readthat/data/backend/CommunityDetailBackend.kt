package dev.readthat.data.backend

import dev.readthat.communitydetail.domain.CommunityDetail
import dev.readthat.communitydetail.domain.CommunityDetailRemoteSource
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement

class HttpCommunityDetailRemoteSource(
    private val client: BackendClient,
) : CommunityDetailRemoteSource {
    override suspend fun fetch(name: String): CommunityDetail {
        val response = client.requestJson("GET", "/v1/subreddits/${name.encoded()}")
        return client.json.decodeFromJsonElement<CommunityDetailEnvelope>(response).subreddit
    }

    override suspend fun setJoined(name: String, joined: Boolean): CommunityDetail {
        client.requestJson(
            method = if (joined) "POST" else "DELETE",
            path = "/v1/subreddits/${name.encoded()}/join",
            requireAuthentication = true,
        )
        // Membership endpoints are commands. Re-read the aggregate so the local member count,
        // role, rules, and server timestamps advance together under the D1 bookmark.
        return fetch(name)
    }
}

@Serializable
private data class CommunityDetailEnvelope(val subreddit: CommunityDetail)

private fun String.encoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())
