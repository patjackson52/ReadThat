import type Hls from "hls.js";
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import type { MediaAsset, VideoCell } from "./types";
import { Icon, Spinner } from "./ui";

type VideoAsset = VideoCell | MediaAsset;

export interface VideoTransportState {
  muted: boolean;
  playing: boolean;
  buffering: boolean;
  currentTime: number;
  duration: number;
  togglePlayback: () => void;
  seekTo: (seconds: number) => void;
  toggleMuted: () => void;
}

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

export function VideoPlayer({ id, asset, aspectRatio, label, onTransportChange }: {
  id: string;
  asset: VideoAsset;
  aspectRatio: number;
  label: string;
  onTransportChange?: (transport: VideoTransportState | null) => void;
}) {
  const coordinator = useContext(VideoCoordinator);
  if (!coordinator) throw new Error("VideoPlayer must be inside VideoCoordinatorProvider");
  const container = useRef<HTMLDivElement>(null);
  const video = useRef<HTMLVideoElement>(null);
  const hls = useRef<Hls | null>(null);
  const [near, setNear] = useState(false);
  const [muted, setMuted] = useState(true);
  const [started, setStarted] = useState(false);
  const [playing, setPlaying] = useState(false);
  const [buffering, setBuffering] = useState(false);
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(0);
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
  }, [muted]);

  useEffect(() => {
    const element = video.current;
    if (!element) return;
    if (active) void element.play().then(() => setStarted(true)).catch(() => undefined);
    else element.pause();
  }, [active]);

  const togglePlayback = useCallback(() => {
    const element = video.current;
    if (!element) return;
    if (element.paused || element.ended) {
      if (element.ended) element.currentTime = 0;
      setStarted(true);
      void element.play().catch(() => undefined);
    } else {
      element.pause();
    }
  }, []);
  const seekTo = useCallback((seconds: number) => {
    const element = video.current;
    if (!element || !Number.isFinite(seconds)) return;
    const upperBound = Number.isFinite(element.duration) && element.duration > 0 ? element.duration : seconds;
    element.currentTime = Math.max(0, Math.min(seconds, upperBound));
    setCurrentTime(element.currentTime);
  }, []);
  const toggleMuted = useCallback(() => setMuted((value) => !value), []);
  const transport = useMemo<VideoTransportState>(() => ({
    muted,
    playing,
    buffering,
    currentTime,
    duration,
    togglePlayback,
    seekTo,
    toggleMuted,
  }), [buffering, currentTime, duration, muted, playing, seekTo, toggleMuted, togglePlayback]);
  useEffect(() => {
    onTransportChange?.(transport);
  }, [onTransportChange, transport]);
  useEffect(() => () => onTransportChange?.(null), [id, onTransportChange]);

  const poster = assetValue(asset, "posterUrl") ?? undefined;
  return <div ref={container} className="video-shell" style={{ aspectRatio }}>
    <video
      ref={video}
      aria-label={label}
      controls={started && active && !onTransportChange}
      muted={muted}
      playsInline
      poster={poster}
      preload={active ? "auto" : "metadata"}
      onDurationChange={(event) => setDuration(Number.isFinite(event.currentTarget.duration) ? event.currentTarget.duration : 0)}
      onEnded={() => { setPlaying(false); setBuffering(false); }}
      onLoadedMetadata={(event) => setDuration(Number.isFinite(event.currentTarget.duration) ? event.currentTarget.duration : 0)}
      onPause={() => { setPlaying(false); setBuffering(false); }}
      onPlay={() => { setStarted(true); setPlaying(true); }}
      onPlaying={() => { setPlaying(true); setBuffering(false); }}
      onTimeUpdate={(event) => setCurrentTime(event.currentTarget.currentTime)}
      onVolumeChange={(event) => setMuted(event.currentTarget.muted)}
      onWaiting={() => setBuffering(true)}
    />
    {(status === "waiting" || status === "processing") && <div className="media-processing"><Spinner label="Processing video" /><span>Preparing video · {asset.processingProgress}%</span></div>}
    {status === "error" && <div className="media-processing error">Video processing failed</div>}
    {!started && status === "ready" && <button className="video-play" type="button" aria-label={`Play ${label}`} onClick={() => {
      setStarted(true);
      void video.current?.play();
    }}><Icon name="play" /></button>}
    {started && !onTransportChange && <button className="video-mute" type="button" onClick={toggleMuted}>{muted ? "Unmute" : "Mute"}</button>}
  </div>;
}

export function formatVideoElapsed(seconds: number): string {
  const total = Math.max(0, Math.floor(Number.isFinite(seconds) ? seconds : 0));
  const hours = Math.floor(total / 3_600);
  const minutes = Math.floor(total / 60) % 60;
  const remainder = total % 60;
  return hours > 0
    ? `${hours}:${String(minutes).padStart(2, "0")}:${String(remainder).padStart(2, "0")}`
    : `${minutes}:${String(remainder).padStart(2, "0")}`;
}

export function VideoTransportControls({ transport }: { transport: VideoTransportState }) {
  const duration = Number.isFinite(transport.duration) && transport.duration > 0 ? transport.duration : 0;
  const position = Math.max(0, Math.min(transport.currentTime, duration || transport.currentTime));
  return <div className="video-transport" aria-label="Video controls">
    <button type="button" aria-label={transport.playing ? "Pause video" : "Play video"} onClick={transport.togglePlayback}>
      {transport.buffering && transport.playing ? <Spinner label="Buffering video" /> : <Icon name={transport.playing ? "pause" : "play"} />}
    </button>
    <input
      type="range"
      aria-label="Video position"
      min={0}
      max={duration || 1}
      step="any"
      value={duration ? position : 0}
      disabled={!duration}
      onChange={(event) => transport.seekTo(Number(event.currentTarget.value))}
    />
    <time dateTime={`PT${Math.floor(position)}S`}>{formatVideoElapsed(position)}</time>
    <button type="button" aria-label={transport.muted ? "Unmute video" : "Mute video"} onClick={transport.toggleMuted}>
      <Icon name={transport.muted ? "volume-off" : "volume"} />
    </button>
  </div>;
}
