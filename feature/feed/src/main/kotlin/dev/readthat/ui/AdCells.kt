package dev.readthat.ui

import dev.readthat.domain.AdMediaKind
import dev.readthat.domain.CellUi
import dev.readthat.playback.AdaptiveVideoSource

/** Native preload identities for the common promoted-media renderer. */
internal fun CellUi.AdMedia.videoSources(): List<AdaptiveVideoSource> = items.mapNotNull { media ->
    media.takeIf { it.kind == AdMediaKind.Video && (it.hlsUrl != null || it.fallbackUrl != null) }
        ?.let { AdaptiveVideoSource(it.hlsUrl, it.fallbackUrl, it.cacheKey) }
}
