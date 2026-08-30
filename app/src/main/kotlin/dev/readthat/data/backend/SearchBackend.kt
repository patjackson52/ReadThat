package dev.readthat.data.backend

import dev.readthat.search.data.SearchRemoteSource
import dev.readthat.search.domain.SearchDiscover
import dev.readthat.search.domain.SearchPage
import dev.readthat.search.domain.SearchRequest
import dev.readthat.search.domain.SearchTypeahead
import kotlinx.serialization.json.decodeFromJsonElement
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class HttpSearchRemoteSource(private val client: BackendClient) : SearchRemoteSource {
    override suspend fun discover(): SearchDiscover = client.json.decodeFromJsonElement(
        client.requestJson("GET", "/v1/search/discover"),
    )

    override suspend fun typeahead(query: String, limit: Int): SearchTypeahead =
        client.json.decodeFromJsonElement(
            client.requestJson("GET", "/v1/search/typeahead?q=${query.encoded()}&limit=$limit"),
        )

    override suspend fun search(request: SearchRequest, cursor: String?, limit: Int): SearchPage {
        val path = buildString {
            append("/v1/search?q=${request.query.encoded()}")
            append("&type=${request.type.wire}")
            append("&sort=${request.sort.wire}")
            append("&time=${request.time.wire}")
            append("&safe=${request.safe}")
            append("&limit=$limit")
            request.subreddit?.takeIf(String::isNotBlank)?.let { append("&subreddit=${it.encoded()}") }
            cursor?.let { append("&cursor=${it.encoded()}") }
        }
        return client.json.decodeFromJsonElement(client.requestJson("GET", path))
    }

    private fun String.encoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())
}
