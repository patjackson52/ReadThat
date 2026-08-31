package dev.readthat.deeplink

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ReadThatDeepLink {
    val postId: String

    data class Post(override val postId: String) : ReadThatDeepLink

    data class Comment(
        override val postId: String,
        val commentId: String,
    ) : ReadThatDeepLink
}

/**
 * One strict, platform-neutral contract for links accepted by every ReadThat client.
 *
 * Canonical web links remain useful when the app is not installed. The custom scheme is retained
 * for development tools and platform-to-platform handoffs that cannot use a verified web origin.
 */
object ReadThatDeepLinks {
    const val PRODUCTION_HOST = "sdui-reddit-api.patjackson52.workers.dev"
    const val PRODUCTION_ORIGIN = "https://$PRODUCTION_HOST"
    const val CUSTOM_SCHEME = "readthat"

    private val defaultTrustedHosts = setOf(PRODUCTION_HOST)
    private val safeId = Regex("^[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$")

    fun postUrl(postId: String, origin: String = PRODUCTION_ORIGIN): String {
        requireValidId(postId, "postId")
        return "${origin.normalizedOrigin()}/post/$postId"
    }

    fun commentUrl(postId: String, commentId: String, origin: String = PRODUCTION_ORIGIN): String {
        requireValidId(postId, "postId")
        requireValidId(commentId, "commentId")
        return "${origin.normalizedOrigin()}/post/$postId/comment/$commentId"
    }

    fun parse(
        rawUrl: String?,
        trustedHosts: Set<String> = defaultTrustedHosts,
    ): ReadThatDeepLink? {
        val raw = rawUrl?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_URL_LENGTH }
            ?: return null
        if (raw.any { it.isWhitespace() || it.code < 0x20 }) return null

        val schemeEnd = raw.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = raw.substring(0, schemeEnd).lowercase()
        val remainder = raw.substring(schemeEnd + 3)
        val authorityEnd = remainder.indexOfFirst { it == '/' || it == '?' || it == '#' }
            .let { if (it < 0) remainder.length else it }
        val authority = remainder.substring(0, authorityEnd)
        val suffix = remainder.substring(authorityEnd)

        return when (scheme) {
            "https" -> parseWeb(authority, suffix, trustedHosts)
            CUSTOM_SCHEME -> parseCustom(authority, suffix)
            else -> null
        }
    }

    private fun parseWeb(
        authority: String,
        suffix: String,
        trustedHosts: Set<String>,
    ): ReadThatDeepLink? {
        if ('@' in authority) return null
        val normalizedAuthority = authority.lowercase()
        val host = normalizedAuthority.removeSuffix(":443")
        if (':' in host || host !in trustedHosts.mapTo(mutableSetOf()) { it.lowercase() }) return null

        val parts = splitSuffix(suffix)
        val segments = parts.path.pathSegments() ?: return null
        if (segments.size != 2 && segments.size != 4) return null
        if (segments[0] != "post" || !isValidId(segments[1])) return null
        val postId = segments[1]

        if (segments.size == 4) {
            if (segments[2] != "comment" || !isValidId(segments[3])) return null
            return ReadThatDeepLink.Comment(postId, segments[3])
        }

        val queryComment = parts.query
            .split('&')
            .mapNotNull { parameter ->
                val separator = parameter.indexOf('=')
                if (separator <= 0) null
                else parameter.substring(0, separator) to parameter.substring(separator + 1)
            }
            .firstOrNull { it.first == "commentId" }
            ?.second
        val fragmentComment = parts.fragment.removePrefix("comment-").takeIf {
            parts.fragment.startsWith("comment-")
        }
        val commentId = queryComment ?: fragmentComment
        return if (commentId == null) ReadThatDeepLink.Post(postId)
        else commentId.takeIf(::isValidId)?.let { ReadThatDeepLink.Comment(postId, it) }
    }

    private fun parseCustom(authority: String, suffix: String): ReadThatDeepLink? {
        val parts = splitSuffix(suffix)
        val path = parts.path.pathSegments() ?: return null
        val segments = if (authority.isBlank()) path else listOf(authority.lowercase()) + path
        return when {
            segments.size == 2 && segments[0] == "post" && isValidId(segments[1]) ->
                ReadThatDeepLink.Post(segments[1])
            segments.size == 4 && segments[0] == "post" && segments[2] == "comment" &&
                isValidId(segments[1]) && isValidId(segments[3]) ->
                ReadThatDeepLink.Comment(segments[1], segments[3])
            segments.size == 3 && segments[0] == "comment" &&
                isValidId(segments[1]) && isValidId(segments[2]) ->
                ReadThatDeepLink.Comment(segments[1], segments[2])
            else -> null
        }
    }

    private fun String.pathSegments(): List<String>? {
        if (isEmpty() || this == "/") return emptyList()
        if (!startsWith('/')) return null
        val segments = drop(1).split('/')
        return segments.takeIf { values -> values.none(String::isEmpty) }
    }

    private fun splitSuffix(suffix: String): UrlSuffix {
        val fragmentAt = suffix.indexOf('#')
        val withoutFragment = if (fragmentAt < 0) suffix else suffix.substring(0, fragmentAt)
        val fragment = if (fragmentAt < 0) "" else suffix.substring(fragmentAt + 1)
        val queryAt = withoutFragment.indexOf('?')
        return UrlSuffix(
            path = if (queryAt < 0) withoutFragment else withoutFragment.substring(0, queryAt),
            query = if (queryAt < 0) "" else withoutFragment.substring(queryAt + 1),
            fragment = fragment,
        )
    }

    private fun String.normalizedOrigin(): String {
        val value = trim().trimEnd('/')
        require(value.startsWith("https://") && value.length > "https://".length) {
            "origin must be an HTTPS origin"
        }
        require(value.drop("https://".length).none { it == '/' || it == '?' || it == '#' || it.isWhitespace() }) {
            "origin must not contain a path, query, or fragment"
        }
        return value
    }

    private fun requireValidId(value: String, label: String) {
        require(isValidId(value)) { "$label contains unsupported characters" }
    }

    private fun isValidId(value: String): Boolean = safeId.matches(value)

    private data class UrlSuffix(val path: String, val query: String, val fragment: String)
    private const val MAX_URL_LENGTH = 2_048
}

/**
 * A tiny lifecycle-independent handoff between platform URL callbacks and shared navigation.
 * The target survives cold-start/session restoration until the UI explicitly consumes it.
 */
class DeepLinkInbox {
    private val mutablePending = MutableStateFlow<ReadThatDeepLink?>(null)
    val pending: StateFlow<ReadThatDeepLink?> = mutablePending.asStateFlow()

    fun offerUrl(url: String): Boolean {
        val target = ReadThatDeepLinks.parse(url) ?: return false
        mutablePending.value = target
        return true
    }

    fun offer(target: ReadThatDeepLink) {
        mutablePending.value = target
    }

    fun consume(target: ReadThatDeepLink) {
        mutablePending.compareAndSet(target, null)
    }
}
