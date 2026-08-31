# Adaptive video client contract

The runnable production web application now lives in `www`; the TypeScript
files in `clients/web` remain small platform-policy references used while
keeping Android and iOS media behavior aligned.

All clients consume the backend's flattened video cell: `hlsUrl`, `dashUrl`,
`posterUrl`, `fallbackUrl`, `deliveryStatus`, and `processingProgress`.

- Android uses one process-wide transport in `:networking`: API, Coil images,
  and Media3 all share `HttpEngine` (QUIC/HTTP/3, TLS-session reuse, and default
  network migration) on Android 14+, with one pooled modern-TLS OkHttp HTTP/2
  fallback on Android 8-13. `:playback` adds HLS, exactly one lazy process
  player shared by feed/detail, Media3 `DefaultPreloadManager` for the adjacent
  feed window, atomic `PlayerView.switchTargetView` handoff, adaptive track
  ceilings, idle decoder release, and segment-only LRU. The periodic feed worker
  uses `PreCacheHelper` on unmetered networks for only the first two seconds of
  the first startup video.
- iOS uses AVPlayer native HLS plus one long-lived URLSession owned by the KMP
  `IosSharedHttpTransport` for API/images/previews/uploads/telemetry. Known
  Cloudflare origins race HTTP/3 immediately; arbitrary link/image origins use
  normal Alt-Svc discovery. Images use a stable media/version L1 key and
  protocol-driven URLCache on disk. AVPlayer owns its optimized native HLS
  transport, while persistent offline HLS uses `AVAssetDownloadURLSession`.
  `clients/ios/StreamAssetDownloadManager.swift` is the only compiled native
  client shim in this directory. Networking, background maintenance, frame
  telemetry, and player ownership live in their active KMP modules plus the
  thin `iosApp` lifecycle host; there are no parallel Swift client stacks.
- Android and iOS schedule constrained periodic refresh, and both expose an
  app-active refresh hook that commits network results to the local database
  behind already-visible cached UI.
- Web uses `clients/web/stream-video-player.ts` (native Safari HLS or an injected
  hls.js constructor with ABR and metered buffer policy).

Do not proxy, persist, or service-worker-cache `.m3u8` or `.mpd` manifests. They
are dynamic Stream assets. Segment caching must be bounded by the shared policy;
the web helper exposes the same 64/192/384 MiB tiers capped at 2% of quota.

Android feed-to-detail navigation uses a Compose `sharedBounds` container and a
TextureView-backed PlayerView. A small in-memory transition preview makes the
detail hero available before its header fetch; the ExoPlayer, decoder, position,
buffer, mute state, and segment cache never change owners during the animation.

TLS uses platform trust and negotiates TLS 1.3 (TLS 1.2 minimum). The clients do
not pin Cloudflare leaf keys, enable replayable 0-RTT for mutations, or route
public Workers/Stream traffic through Cloudflare Tunnel. A Tunnel adds an
unneeded connector hop for this public edge API; direct edge TLS is the intended
path. iOS MPTCP multipath is also intentionally off: it requires an entitlement
and server support, while QUIC already migrates connections across default-path
changes without keeping a potentially metered path alive.

The on-device transport probe runs API, Coil, Images, and Media3 through the
same Android engine identity. The Worker and `imagedelivery.net` negotiate H3;
the current `cloudflarestream.com` manifest endpoint advertises HTTP/2 only, so
video cleanly falls back without changing the playback or pooling layer.
