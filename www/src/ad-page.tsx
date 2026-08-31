import { useEffect, useMemo } from "react";
import { useLocation, useNavigate, useParams } from "react-router-dom";
import type { AdLaunchContext, VideoCell } from "./types";
import { Icon } from "./ui";
import { VideoPlayer } from "./video";

const AD_STORAGE_PREFIX = "read-that-ad:";

export function rememberAd(ad: AdLaunchContext): void {
  try { sessionStorage.setItem(`${AD_STORAGE_PREFIX}${ad.adId}`, JSON.stringify(ad)); } catch { /* Navigation state is the primary handoff. */ }
}

function storedAd(adId: string): AdLaunchContext | null {
  try {
    const value = sessionStorage.getItem(`${AD_STORAGE_PREFIX}${adId}`);
    return value ? JSON.parse(value) as AdLaunchContext : null;
  } catch { return null; }
}

export function AdPage() {
  const { adId = "" } = useParams<{ adId: string }>();
  const location = useLocation();
  const navigate = useNavigate();
  const ad = useMemo(() => (location.state as { ad?: AdLaunchContext } | null)?.ad ?? storedAd(adId), [adId, location.state]);
  const secureDestination = Boolean(ad?.destinationUrl.startsWith("https://"));

  useEffect(() => { document.title = ad ? `Promoted by ${ad.displayDomain} · Read That` : "Promoted content · Read That"; }, [ad]);

  if (!ad) return <div className="immersive-state"><h1>This promotion is no longer available</h1><p>Open it again from the home feed.</p><button className="primary-button" type="button" onClick={() => navigate("/")}>Return home</button></div>;

  const video: VideoCell = {
    type: "video",
    cellId: `ad:${ad.creativeId}`,
    url: ad.fallbackUrl,
    hlsUrl: ad.hlsUrl,
    dashUrl: null,
    posterUrl: ad.posterUrl,
    previewUrl: null,
    fallbackUrl: ad.fallbackUrl,
    deliveryStatus: "ready",
    processingProgress: 100,
    cachePolicy: "segments_only",
    placeholderColor: 0,
    aspectRatio: ad.aspectRatio,
    durationSeconds: 0,
    altText: ad.altText,
  };

  return <div className="ad-detail-route">
    <section className="ad-detail-media">
      {ad.kind === "video"
        ? <VideoPlayer id={`ad-detail:${ad.creativeId}`} asset={video} aspectRatio={ad.aspectRatio} label={ad.altText} />
        : (ad.imageUrl ?? ad.posterUrl) && <img src={ad.imageUrl ?? ad.posterUrl ?? ""} alt={ad.altText} />}
      <button className="immersive-close" type="button" aria-label="Close promoted content" onClick={() => navigate(-1)}><Icon name="x" /></button>
      <span className="ad-badge">Promoted</span>
    </section>
    <section className="ad-destination">
      <header><span aria-hidden="true">🔒</span><strong>{ad.displayDomain}</strong><a href={ad.destinationUrl} target="_blank" rel="noopener noreferrer sponsored">Open in new tab</a></header>
      {secureDestination
        ? <iframe title={`${ad.displayDomain} promoted destination`} src={ad.destinationUrl} sandbox="allow-forms allow-popups allow-same-origin allow-scripts" referrerPolicy="no-referrer" />
        : <div className="ad-destination-error"><h1>This destination cannot be opened securely.</h1><p>Read That only opens HTTPS promoted destinations.</p></div>}
      <a className="promoted-cta ad-detail-cta" href={ad.destinationUrl} target="_blank" rel="noopener noreferrer sponsored"><span>{ad.ctaLabel}</span><small>{ad.displayDomain}</small></a>
    </section>
  </div>;
}
