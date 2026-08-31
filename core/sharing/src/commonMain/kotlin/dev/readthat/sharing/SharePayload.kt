package dev.readthat.sharing

import dev.readthat.networking.TransportSecurityPolicy

/** Content contract owned by shared product code and presented by the platform share sheet. */
data class SharePayload(
    val text: String,
    val subject: String? = null,
    val mimeType: String = TEXT_MIME_TYPE,
) {
    init {
        require(text.isNotBlank() && text.length <= MAX_TEXT_CHARS) { "Share text is invalid" }
        require(subject == null || subject.isNotBlank() && subject.length <= MAX_SUBJECT_CHARS) {
            "Share subject is invalid"
        }
        require(MIME_TYPE.matches(mimeType)) { "Share MIME type is invalid" }
    }

    companion object {
        const val TEXT_MIME_TYPE = "text/plain"
        private const val MAX_TEXT_CHARS = 32 * 1024
        private const val MAX_SUBJECT_CHARS = 512
        private val MIME_TYPE = Regex("^[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*$")
    }
}

object SharePayloads {
    fun post(title: String, publicUrl: String): SharePayload {
        val safeTitle = (title.trim().takeIf(String::isNotEmpty) ?: "ReadThat post")
            .take(MAX_SUBJECT_CHARS)
        // Cached content can outlive a removed/misconfigured endpoint. Never crash its Share
        // action and never publish a remote cleartext link; omit an unsafe URL instead.
        val safeUrl = publicUrl.trim()
            .takeIf { it.length <= MAX_URL_CHARS }
            ?.takeIf(localDevelopmentSecurityPolicy::permits)
        return SharePayload(
            text = if (safeUrl == null) safeTitle else "$safeTitle\n$safeUrl",
            subject = safeTitle,
        )
    }

    fun link(url: String, subject: String? = null): SharePayload = SharePayload(
        text = url.trim().also {
            productionSecurityPolicy.requirePermitted(it)
        },
        subject = subject?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_SUBJECT_CHARS),
    )

    fun linkOrNull(url: String, subject: String? = null): SharePayload? =
        runCatching { link(url, subject) }.getOrNull()

    private const val MAX_SUBJECT_CHARS = 512
    private const val MAX_URL_CHARS = 8 * 1024
    private val productionSecurityPolicy = TransportSecurityPolicy()
    private val localDevelopmentSecurityPolicy = TransportSecurityPolicy(allowLocalDevelopmentHttp = true)
}
