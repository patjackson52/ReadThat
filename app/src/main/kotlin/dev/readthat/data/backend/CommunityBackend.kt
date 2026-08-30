package dev.readthat.data.backend

import dev.readthat.communities.domain.CommunityDrawerPage
import dev.readthat.communities.domain.CommunityDrawerRemoteResult
import dev.readthat.communities.domain.CommunityDrawerRemoteSource
import dev.readthat.communities.domain.CommunityVisitCommand
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HttpCommunityDrawerRemoteSource(
    private val client: BackendClient,
) : CommunityDrawerRemoteSource {
    override suspend fun fetchDrawer(
        validator: String?,
        cursor: String?,
        limit: Int,
    ): CommunityDrawerRemoteResult {
        val path = buildString {
            append("/v1/me/community-drawer?limit=")
            append(limit)
            cursor?.let { append("&cursor=").append(it.encoded()) }
        }
        return when (val response = client.requestConditionalJson(path, validator.takeIf { cursor == null })) {
            BackendConditionalResponse.NotModified -> CommunityDrawerRemoteResult.NotModified
            is BackendConditionalResponse.Body -> {
                val page = client.json.decodeFromJsonElement(
                    CommunityDrawerPage.serializer(),
                    response.body,
                )
                CommunityDrawerRemoteResult.Page(
                    page.copy(validator = response.validator ?: page.validator),
                )
            }
        }
    }

    override suspend fun syncVisits(commands: List<CommunityVisitCommand>): Set<String> {
        val body = client.json.encodeToJsonElement(VisitCommandEnvelope.serializer(), VisitCommandEnvelope(commands))
        val response = client.requestJson(
            method = "PUT",
            path = "/v1/me/community-visits",
            body = body,
            requireAuthentication = true,
        )
        return response.jsonObject.getValue("applied").jsonArray
            .mapTo(linkedSetOf()) { it.jsonPrimitive.content }
    }
}

@Serializable
private data class VisitCommandEnvelope(val commands: List<CommunityVisitCommand>)

private fun String.encoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())
