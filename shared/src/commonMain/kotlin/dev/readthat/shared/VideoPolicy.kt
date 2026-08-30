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
