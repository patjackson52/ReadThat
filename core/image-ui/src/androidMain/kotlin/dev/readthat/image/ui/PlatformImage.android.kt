package dev.readthat.image.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.size.Precision

@Composable
actual fun PlatformImage(
    request: PlatformImageRequest,
    byteLoader: PlatformImageByteLoader?,
    contentDescription: String?,
    contentScale: ContentScale,
    backgroundColor: Color?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val imageLoader = context.imageLoader.also(AndroidImageLoaderOwner::install)
    val secureUrl = remember(request.url) { request.secureUrlOrNull() }
    Box(
        modifier.background(backgroundColor ?: MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (secureUrl != null && request.cacheKey.isNotBlank()) {
            AsyncImage(
                model = remember(context, secureUrl, request.cacheKey) {
                    ImageRequest.Builder(context)
                        .data(secureUrl)
                        .memoryCacheKey(request.decodedCacheKey)
                        .diskCacheKey(request.cacheKey)
                        .build()
                },
                imageLoader = imageLoader,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
actual fun PlatformImagePreloadWindow(
    requests: List<PlatformImageRequest>,
    byteLoader: PlatformImageByteLoader?,
) {
    val context = LocalContext.current.applicationContext
    val imageLoader = remember(context) { context.imageLoader.also(AndroidImageLoaderOwner::install) }
    val width = remember(context) { context.resources.displayMetrics.widthPixels }
    val height = remember(context) { context.resources.displayMetrics.heightPixels }
    val active = remember { mutableMapOf<String, Disposable>() }
    val bounded = remember(requests) { boundedPlatformImageRequests(requests) }

    LaunchedEffect(bounded, imageLoader, width, height) {
        val desiredKeys = bounded.mapTo(mutableSetOf(), PlatformImageRequest::decodedCacheKey)
        (active.keys - desiredKeys).forEach { key -> active.remove(key)?.dispose() }
        bounded.forEach { request ->
            if (request.decodedCacheKey !in active) {
                active[request.decodedCacheKey] = imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(requireNotNull(request.secureUrlOrNull()))
                        .memoryCacheKey(request.decodedCacheKey)
                        .diskCacheKey(request.cacheKey)
                        .size(width, height)
                        .precision(Precision.INEXACT)
                        .build(),
                )
            }
        }
    }

    DisposableEffect(active) {
        onDispose {
            active.values.forEach(Disposable::dispose)
            active.clear()
        }
    }
}

actual fun clearPlatformImageMemoryCache() {
    AndroidImageLoaderOwner.loader?.memoryCache?.clear()
}

private object AndroidImageLoaderOwner {
    @Volatile var loader: ImageLoader? = null
        private set

    fun install(value: ImageLoader) {
        loader = value
    }
}
