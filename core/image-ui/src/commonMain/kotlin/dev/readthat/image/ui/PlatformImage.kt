package dev.readthat.image.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import dev.readthat.networking.TransportSecurityPolicy

enum class PlatformImageKind {
    Still,
    VideoPreview,
    Avatar,
}

data class PlatformImageRequest(
    val url: String,
    val cacheKey: String,
    val kind: PlatformImageKind = PlatformImageKind.Still,
) {
    val videoPreview: Boolean get() = kind == PlatformImageKind.VideoPreview

    /** Keeps decoded variants distinct while compressed bytes retain their server cache identity. */
    val decodedCacheKey: String
        get() = when (kind) {
            PlatformImageKind.Still -> "image:"
            PlatformImageKind.VideoPreview -> "preview:"
            PlatformImageKind.Avatar -> "avatar:"
        } + cacheKey

    internal fun secureUrlOrNull(
        security: TransportSecurityPolicy = TransportSecurityPolicy(),
    ): String? = url.takeIf(security::permits)
}

/**
 * Supplies compressed bytes on platforms that do not have a process image loader. Implementations
 * must use the shared client so API, image and preview requests retain one connection/cache owner.
 */
fun interface PlatformImageByteLoader {
    suspend fun load(request: PlatformImageRequest): ByteArray
}

/**
 * Returns a deterministic, bounded window for native prefetchers. Equal decoded identities are
 * collapsed without conflating a video poster with a full-resolution still.
 */
fun boundedPlatformImageRequests(
    requests: List<PlatformImageRequest>,
    limit: Int = MAX_PLATFORM_IMAGE_PREFETCH_REQUESTS,
): List<PlatformImageRequest> {
    if (limit <= 0) return emptyList()
    return requests
        .asSequence()
        .filter { it.cacheKey.isNotBlank() && it.secureUrlOrNull() != null }
        .distinctBy(PlatformImageRequest::decodedCacheKey)
        .take(limit)
        .toList()
}

@Composable
expect fun PlatformImage(
    request: PlatformImageRequest,
    byteLoader: PlatformImageByteLoader? = null,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    backgroundColor: Color? = null,
    modifier: Modifier = Modifier,
)

/** Cancels work that leaves the window and retains at most the common bounded request count. */
@Composable
expect fun PlatformImagePreloadWindow(
    requests: List<PlatformImageRequest>,
    byteLoader: PlatformImageByteLoader? = null,
)

/** Called by the root memory-pressure bridge; compressed disk tiers remain available offline. */
expect fun clearPlatformImageMemoryCache()

const val MAX_PLATFORM_IMAGE_PREFETCH_REQUESTS = 24
