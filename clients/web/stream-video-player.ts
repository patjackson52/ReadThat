export interface StreamVideoAsset {
  hlsUrl?: string;
  dashUrl?: string;
  posterUrl?: string;
  fallbackUrl?: string;
  deliveryStatus: "waiting" | "processing" | "ready" | "error" | "not_applicable";
  processingProgress: number;
}

export interface HlsInstance {
  loadSource(url: string): void;
  attachMedia(video: HTMLVideoElement): void;
  destroy(): void;
}

export interface HlsConstructor {
  new (config: Record<string, unknown>): HlsInstance;
  isSupported(): boolean;
}

type NetworkInformation = {
  saveData?: boolean;
  effectiveType?: "slow-2g" | "2g" | "3g" | "4g";
  type?: string;
};

export interface WebVideoPreferences {
  autoplay: boolean;
  autoplayOnMetered: boolean;
  reduceDataOnMetered: boolean;
}

/** Native Safari HLS first; injected hls.js everywhere else. */
export class StreamVideoController {
  private hls?: HlsInstance;

  constructor(
    private readonly video: HTMLVideoElement,
    private readonly Hls: HlsConstructor | undefined,
    private readonly preferences: WebVideoPreferences,
  ) {}

  async attach(asset: StreamVideoAsset): Promise<void> {
    this.destroy();
    this.video.poster = asset.posterUrl ?? "";
    this.video.playsInline = true;
    const connection = (navigator as Navigator & { connection?: NetworkInformation }).connection;
    const metered = Boolean(connection?.saveData) || ["slow-2g", "2g", "3g"].includes(connection?.effectiveType ?? "");
    const hlsUrl = asset.hlsUrl;

    if (hlsUrl && this.video.canPlayType("application/vnd.apple.mpegurl")) {
      this.video.src = hlsUrl;
    } else if (hlsUrl && this.Hls?.isSupported()) {
      this.hls = new this.Hls({
        capLevelToPlayerSize: true,
        maxBufferLength: metered && this.preferences.reduceDataOnMetered ? 15 : 45,
        maxMaxBufferLength: metered ? 30 : 90,
        startLevel: metered ? 0 : -1,
        enableWorker: true,
      });
      this.hls.loadSource(hlsUrl);
      this.hls.attachMedia(this.video);
    } else if (asset.fallbackUrl) {
      this.video.src = asset.fallbackUrl;
    } else {
      return;
    }

    const mayAutoplay = this.preferences.autoplay && (!metered || this.preferences.autoplayOnMetered);
    this.video.muted = mayAutoplay;
    if (mayAutoplay) await this.video.play().catch(() => undefined);
  }

  destroy(): void {
    this.hls?.destroy();
    this.hls = undefined;
    this.video.pause();
    this.video.removeAttribute("src");
    this.video.load();
  }
}

export function recommendedSegmentCacheBytes(deviceMemoryGiB = 4, quotaBytes = 0): number {
  const tier = deviceMemoryGiB <= 2 ? 64 : deviceMemoryGiB >= 8 ? 384 : 192;
  const deviceTarget = tier * 1024 * 1024;
  return quotaBytes > 0 ? Math.min(deviceTarget, Math.floor(quotaBytes * 0.02)) : deviceTarget;
}

export function isDynamicStreamManifest(url: string): boolean {
  const path = new URL(url, location.href).pathname.toLowerCase();
  return path.endsWith(".m3u8") || path.endsWith(".mpd");
}
