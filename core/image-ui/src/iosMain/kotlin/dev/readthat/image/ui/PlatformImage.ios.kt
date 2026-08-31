package dev.readthat.image.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

@Composable
@OptIn(ExperimentalResourceApi::class)
actual fun PlatformImage(
    request: PlatformImageRequest,
    byteLoader: PlatformImageByteLoader?,
    contentDescription: String?,
    contentScale: ContentScale,
    backgroundColor: Color?,
    modifier: Modifier,
) {
    val decodedKey = request.decodedCacheKey
    val secureUrl = remember(request.url) { request.secureUrlOrNull() }
    var bitmap by remember(decodedKey) { mutableStateOf(IosDecodedImageCache.get(decodedKey)) }
    var failed by remember(decodedKey) { mutableStateOf(false) }
    var retryRequest by remember(decodedKey) { mutableStateOf(0) }

    LaunchedEffect(secureUrl, decodedKey, retryRequest, byteLoader) {
        if (bitmap == null) {
            failed = false
            bitmap = if (secureUrl == null || request.cacheKey.isBlank() || byteLoader == null) {
                null
            } else {
                decode(request, byteLoader)
            }?.also { IosDecodedImageCache.put(decodedKey, it) }
            failed = bitmap == null
        }
    }

    Box(
        modifier.background(backgroundColor ?: MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(it, contentDescription, Modifier.fillMaxSize(), contentScale = contentScale)
        } ?: if (failed) {
            if (secureUrl != null && byteLoader != null) {
                TextButton(onClick = { retryRequest += 1 }) {
                    Text(if (request.videoPreview) "Retry preview" else "Retry image")
                }
            } else Unit
        } else {
            CircularProgressIndicator(Modifier.size(28.dp))
        }
    }
}

@Composable
@OptIn(ExperimentalResourceApi::class)
actual fun PlatformImagePreloadWindow(
    requests: List<PlatformImageRequest>,
    byteLoader: PlatformImageByteLoader?,
) {
    val bounded = remember(requests) { boundedPlatformImageRequests(requests) }
    LaunchedEffect(bounded, byteLoader) {
        if (byteLoader == null) return@LaunchedEffect
        bounded.chunked(PREFETCH_CONCURRENCY).forEach { batch ->
            coroutineScope {
                batch.forEach { request ->
                    launch {
                        if (IosDecodedImageCache.get(request.decodedCacheKey) != null) return@launch
                        decode(request, byteLoader)?.let {
                            IosDecodedImageCache.put(request.decodedCacheKey, it)
                        }
                    }
                }
            }
        }
    }
}

actual fun clearPlatformImageMemoryCache() {
    IosDecodedImageCache.clear()
}

@OptIn(ExperimentalResourceApi::class)
private suspend fun decode(
    request: PlatformImageRequest,
    byteLoader: PlatformImageByteLoader,
): ImageBitmap? = try {
    val bytes = byteLoader.load(request)
    withContext(Dispatchers.Default) { bytes.decodeToImageBitmap() }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    null
}

/** Decoded L1; compressed bytes remain in the shared client's bounded memory/disk tiers. */
private object IosDecodedImageCache {
    private const val MAX_BYTES = 64L * 1_048_576L
    private val entries = LinkedHashMap<String, ImageBitmap>()
    private var sizeBytes = 0L

    fun get(key: String): ImageBitmap? = entries.remove(key)?.also { entries[key] = it }

    fun put(key: String, bitmap: ImageBitmap) {
        entries.remove(key)?.let { sizeBytes -= it.bytes }
        entries[key] = bitmap
        sizeBytes += bitmap.bytes
        while (sizeBytes > MAX_BYTES && entries.isNotEmpty()) {
            val eldest = entries.entries.first()
            entries.remove(eldest.key)
            sizeBytes -= eldest.value.bytes
        }
    }

    fun clear() {
        entries.clear()
        sizeBytes = 0L
    }

    private val ImageBitmap.bytes: Long get() = width.toLong() * height.toLong() * 4L
}

private const val PREFETCH_CONCURRENCY = 2
