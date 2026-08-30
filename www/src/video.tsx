import type Hls from "hls.js";
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import type { MediaAsset, VideoCell } from "./types";
import { Icon, Spinner } from "./ui";

type VideoAsset = VideoCell | MediaAsset;

interface VideoCoordinatorValue {
  activeId: string | null;
  report: (id: string, ratio: number) => void;
  remove: (id: string) => void;
}

const VideoCoordinator = createContext<VideoCoordinatorValue | null>(null);

export function VideoCoordinatorProvider({ children }: { children: ReactNode }) {
  const ratios = useRef(new Map<string, number>());
  const [activeId, setActiveId] = useState<string | null>(null);
  const choose = useCallback(() => {
    let best: { id: string; ratio: number } | null = null;
    for (const [id, ratio] of ratios.current) {
      if (ratio >= 0.55 && (!best || ratio > best.ratio)) best = { id, ratio };
    }
    const next = best?.id ?? null;
    setActiveId((current) => current === next ? current : next);
  }, []);
  const report = useCallback((id: string, ratio: number) => {
    ratios.current.set(id, ratio);
    choose();
  }, [choose]);
  const remove = useCallback((id: string) => {
    ratios.current.delete(id);
    choose();
  }, [choose]);
  const value = useMemo<VideoCoordinatorValue>(() => ({
    activeId,
    report,
    remove,
  }), [activeId, remove, report]);
  return <VideoCoordinator.Provider value={value}>{children}</VideoCoordinator.Provider>;
}

function meteredConnection(): boolean {
  const connection = (navigator as Navigator & { connection?: { saveData?: boolean; effectiveType?: string } }).connection;
  return Boolean(connection?.saveData) || ["slow-2g", "2g", "3g"].includes(connection?.effectiveType ?? "");
}

function assetValue(asset: VideoAsset, key: "hlsUrl" | "fallbackUrl" | "posterUrl"): string | null {
  return asset[key] ?? null;
}

export function VideoPlayer({ id, asset, aspectRatio, label }: { id: string; asset: VideoAsset; aspectRatio: number; label: string }) {
  const coordinator = useContext(VideoCoordinator);
  if (!coordinator) throw new Error("VideoPlayer must be inside VideoCoordinatorProvider");
  const container = useRef<HTMLDivElement>(null);
  const video = useRef<HTMLVideoElement>(null);
  const hls = useRef<Hls | null>(null);
  const [near, setNear] = useState(false);
  const [muted, setMuted] = useState(true);
  const [started, setStarted] = useState(false);
  const { activeId, report, remove } = coordinator;
  const active = activeId === id;
  const status = asset.deliveryStatus;

  useEffect(() => {
    const element = container.current;
    if (!element) return;
    const nearObserver = new IntersectionObserver(([entry]) => setNear(Boolean(entry?.isIntersecting)), { rootMargin: "600px 0px" });
    const activeObserver = new IntersectionObserver(([entry]) => report(id, entry?.intersectionRatio ?? 0), { threshold: [0, .25, .55, .75, 1] });
    nearObserver.observe(element);
    activeObserver.observe(element);
    return () => {
      nearObserver.disconnect();
      activeObserver.disconnect();
      remove(id);
    };
  }, [id, remove, report]);

  useEffect(() => {
    const element = video.current;
    const hlsUrl = assetValue(asset, "hlsUrl");
    const fallbackUrl = assetValue(asset, "fallbackUrl") ?? ("url" in asset ? asset.url : null);
    const shouldAttach = near && (active || !meteredConnection());
    if (!element || !shouldAttach || (!hlsUrl && !fallbackUrl)) return;
    let disposed = false;
    void (async () => {
      if (hlsUrl && element.canPlayType("application/vnd.apple.mpegurl")) {
        element.src = hlsUrl;
      } else if (hlsUrl) {
        const { default: HlsPlayer } = await import("hls.js");
        if (disposed) return;
        if (HlsPlayer.isSupported()) {
          const instance = new HlsPlayer({
            capLevelToPlayerSize: true,
            maxBufferLength: active ? 35 : 4,
            maxMaxBufferLength: active ? 60 : 8,
            startLevel: meteredConnection() ? 0 : -1,
            enableWorker: true,
          });
          instance.loadSource(hlsUrl);
          instance.attachMedia(element);
          hls.current = instance;
        } else if (fallbackUrl) {
          element.src = fallbackUrl;
        }
      } else if (fallbackUrl) {
        element.src = fallbackUrl;
      }
    })();
    return () => {
      disposed = true;
      hls.current?.destroy();
      hls.current = null;
      element.pause();
      element.removeAttribute("src");
      element.load();
    };
  }, [active, asset, near]);

  useEffect(() => {
    const element = video.current;
    if (!element) return;
    element.muted = muted;
    if (active) void element.play().then(() => setStarted(true)).catch(() => undefined);
    else element.pause();
  }, [active, muted]);

  const poster = assetValue(asset, "posterUrl") ?? undefined;
  return <div ref={container} className="video-shell" style={{ aspectRatio }}>
    <video
      ref={video}
      aria-label={label}
      controls={started && active}
      muted={muted}
      playsInline
      poster={poster}
      preload={active ? "auto" : "metadata"}
      onVolumeChange={(event) => setMuted(event.currentTarget.muted)}
    />
    {(status === "waiting" || status === "processing") && <div className="media-processing"><Spinner label="Processing video" /><span>Preparing video · {asset.processingProgress}%</span></div>}
    {status === "error" && <div className="media-processing error">Video processing failed</div>}
    {!started && status === "ready" && <button className="video-play" type="button" aria-label={`Play ${label}`} onClick={() => {
      setStarted(true);
      void video.current?.play();
    }}><Icon name="play" /></button>}
    {started && <button className="video-mute" type="button" onClick={() => setMuted((value) => !value)}>{muted ? "Unmute" : "Mute"}</button>}
  </div>;
}
