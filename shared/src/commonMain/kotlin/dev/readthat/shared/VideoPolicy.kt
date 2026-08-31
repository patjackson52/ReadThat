package dev.readthat.shared

import kotlinx.serialization.Serializable

@Serializable
data class AdaptiveVideoAsset(
    val hlsUrl: String? = null,
    val dashUrl: String? = null,
    val posterUrl: String? = null,
    val fallbackUrl: String? = null,
    val deliveryStatus: String = "not_applicable",
    val processingProgress: Int = 0,
)

/** Platform-neutral inputs used by Android, iOS, and web playback adapters. */
enum class ConnectionKind { Offline, Metered, Unmetered }
enum class DeviceTier { LowMemory, Standard, HighEnd }

/**
 * Converts a native physical-memory fact into the shared media tier. Unknown values retain the
 * standard policy; supported iPhones below 4 GiB get conservative decode/ABR limits, while 6 GiB
 * and newer devices can use the high-end profile.
 */
fun deviceTierForPhysicalMemory(physicalMemoryBytes: Long): DeviceTier {
    val gib = 1_024L * 1_024L * 1_024L
    return when {
        physicalMemoryBytes <= 0L -> DeviceTier.Standard
        physicalMemoryBytes < 4L * gib -> DeviceTier.LowMemory
        physicalMemoryBytes >= 6L * gib -> DeviceTier.HighEnd
        else -> DeviceTier.Standard
    }
}

data class VideoPlaybackPolicy(
    val autoplay: Boolean,
    val allowPrefetch: Boolean,
    val readCache: Boolean,
    val writeCache: Boolean,
    val maxVideoHeight: Int,
    val preferredPeakBitrate: Long,
    val forwardBufferSeconds: Int,
    val cacheBytes: Long,
)

/**
 * A poster transformation is immutable, but Room may briefly emit an older URL before refresh.
 * Include that URL's stable string hash so stale and current transformations cannot alias in L1/L2.
 */
fun videoPosterCacheKey(mediaKey: String, posterUrl: String?): String =
    "$mediaKey:poster:v3:${posterUrl?.hashCode() ?: "missing"}"

/**
 * Cloudflare Stream thumbnails are addressable by timestamp. Normalize any Stream thumbnail to
 * the playback start so cached/offline responses from an older backend cannot reintroduce a
 * representative-frame-to-first-frame jump.
 */
fun firstFrameVideoPreviewUrl(url: String?): String? {
    if (url.isNullOrBlank() || !url.contains("/thumbnails/thumbnail.", ignoreCase = true)) return url
    val fragmentIndex = url.indexOf('#')
    val withoutFragment = if (fragmentIndex >= 0) url.substring(0, fragmentIndex) else url
    val fragment = if (fragmentIndex >= 0) url.substring(fragmentIndex) else ""
    val queryIndex = withoutFragment.indexOf('?')
    val path = if (queryIndex >= 0) withoutFragment.substring(0, queryIndex) else withoutFragment
    val existing = if (queryIndex >= 0) withoutFragment.substring(queryIndex + 1) else ""
    val parameters = existing.split('&')
        .filter(String::isNotBlank)
        .filterNot { it.substringBefore('=').equals("time", ignoreCase = true) }
    return buildString {
        append(path)
        append("?time=0s")
        parameters.forEach { parameter -> append('&').append(parameter) }
        append(fragment)
    }
}

/**
 * One deterministic policy for all clients. Platform code only supplies network,
 * memory, and available-storage facts; business behavior remains testable here.
 */
object VideoPolicyResolver {
    private const val MIB = 1024L * 1024L

    fun resolve(
        settings: AppSettings,
        connection: ConnectionKind,
        dataSaverEnabled: Boolean,
        deviceTier: DeviceTier,
        availableCacheBytes: Long,
    ): VideoPlaybackPolicy {
        val online = connection != ConnectionKind.Offline
        val metered = connection == ConnectionKind.Metered
        val autoplay = settings.autoplayVideo && online && !dataSaverEnabled &&
            (!metered || settings.autoplayOnMetered)
        val targetCache = when (deviceTier) {
            DeviceTier.LowMemory -> 64L * MIB
            DeviceTier.Standard -> 192L * MIB
            DeviceTier.HighEnd -> 384L * MIB
        }
        // At most 2% of currently available cache storage. The 16 MiB floor is
        // only used when the device actually has that much room.
        val storageShare = (availableCacheBytes.coerceAtLeast(0L) / 50L)
        val cacheBytes = minOf(targetCache, storageShare).coerceAtLeast(
            minOf(16L * MIB, availableCacheBytes.coerceAtLeast(0L)),
        )
        val maxHeight = when {
            metered || dataSaverEnabled -> 480
            deviceTier == DeviceTier.LowMemory -> 720
            deviceTier == DeviceTier.HighEnd -> 1440
            else -> 1080
        }
        val peakBitrate = when {
            !online -> 0L
            metered || dataSaverEnabled -> 1_500_000L
            deviceTier == DeviceTier.LowMemory -> 5_000_000L
            deviceTier == DeviceTier.HighEnd -> 15_000_000L
            else -> 8_000_000L
        }
        return VideoPlaybackPolicy(
            autoplay = autoplay,
            allowPrefetch = online && !metered && !dataSaverEnabled,
            readCache = true,
            writeCache = online && !metered && !dataSaverEnabled,
            maxVideoHeight = maxHeight,
            preferredPeakBitrate = peakBitrate,
            forwardBufferSeconds = if (metered || dataSaverEnabled) 15 else 45,
            cacheBytes = cacheBytes,
        )
    }
}
