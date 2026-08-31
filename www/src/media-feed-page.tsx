import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { ApiError, api } from "./api";
import { useApp } from "./app-context";
import type { MediaAsset, Post, VoteValue } from "./types";
import { formatCount, Icon, Spinner } from "./ui";
import { VideoPlayer, VideoTransportControls, type VideoTransportState } from "./video";

function mediaItems(post: Post): MediaAsset[] {
  return post.mediaItems?.length ? post.mediaItems : post.media ? [post.media] : [];
}

function MediaPage({ post, active, chromeVisible, onToggleChrome }: {
  post: Post;
  active: boolean;
  chromeVisible: boolean;
  onToggleChrome: () => void;
}) {
  const { auth, notify, signInRequested } = useApp();
  const [selected, setSelected] = useState(0);
  const [vote, setVote] = useState<VoteValue>(post.viewerVote);
  const [score, setScore] = useState(post.score);
  const [transport, setTransport] = useState<VideoTransportState | null>(null);
  const media = mediaItems(post);
  const selectedMedia = media[Math.min(selected, Math.max(0, media.length - 1))] ?? post.media;

  async function applyVote(value: VoteValue) {
    if (!auth) { signInRequested(); return; }
    const previousVote = vote;
    const previousScore = score;
    const next = previousVote === value ? 0 : value;
    setVote(next);
    setScore(previousScore - previousVote + next);
    try {
      const result = await api.vote("post", post.id, next);
      if (result) { setVote(result.vote.value); setScore(result.vote.score); }
      else notify("Vote queued for sync", "success");
    } catch (caught) {
      setVote(previousVote); setScore(previousScore);
      notify(caught instanceof Error ? caught.message : "Vote failed", "error");
    }
  }

  async function share() {
    const url = new URL(`/post/${post.id}`, location.origin).toString();
    try {
      if (navigator.share) await navigator.share({ title: post.title, url });
      else { await navigator.clipboard.writeText(url); notify("Link copied", "success"); }
    } catch { /* The native share sheet was dismissed. */ }
  }

  return <article className="media-feed-item" data-active={active}>
    <button className="media-chrome-toggle" type="button" aria-label={chromeVisible ? "Hide post controls" : "Show post controls"} onClick={onToggleChrome} />
    <div className="immersive-media-stage">
      {post.kind === "video" && selectedMedia
        ? <VideoPlayer id={`immersive:${post.id}`} asset={selectedMedia} aspectRatio={(selectedMedia.width && selectedMedia.height) ? selectedMedia.width / selectedMedia.height : 9 / 16} label={selectedMedia.altText || post.title} onTransportChange={setTransport} />
        : media.length > 0 && <div className="immersive-gallery" onScroll={(event) => {
          const element = event.currentTarget;
          setSelected(Math.round(element.scrollLeft / Math.max(1, element.clientWidth)));
        }}>
          {media.map((item, index) => <div className="immersive-gallery-slide" key={item.id}>
            {item.zoomUrl || item.url
              ? <img src={item.zoomUrl ?? item.url ?? ""} alt={item.altText ?? post.title} loading={Math.abs(index - selected) <= 1 ? "eager" : "lazy"} decoding="async" />
              : <div className="media-processing"><Spinner /><span>Preparing image</span></div>}
          </div>)}
        </div>}
      {media.length > 1 && <span className="immersive-gallery-count">{selected + 1}/{media.length}</span>}
    </div>
    <div className={`media-feed-chrome${chromeVisible ? " visible" : ""}`}>
      <div className="media-copy">
        <Link to={`/u/${post.author}`} viewTransition>u/{post.author}</Link>
        <Link to={`/post/${post.id}`} viewTransition><h1>{post.title}</h1></Link>
        {post.body && <p>{post.body}</p>}
        {transport && <VideoTransportControls transport={transport} />}
        <Link className="media-community" to={`/r/${post.subreddit}`} viewTransition>r/{post.subreddit}</Link>
      </div>
      <div className="media-actions" aria-label="Post actions">
        <button type="button" aria-label="Upvote" aria-pressed={vote === 1} onClick={() => void applyVote(1)}><Icon name="arrow-up" /><span>{formatCount(score)}</span></button>
        <button type="button" aria-label="Downvote" aria-pressed={vote === -1} onClick={() => void applyVote(-1)}><Icon name="arrow-down" /></button>
        <Link to={`/post/${post.id}#comments`} viewTransition aria-label={`${post.commentCount} comments`}><Icon name="comment" /><span>{formatCount(post.commentCount)}</span></Link>
        <button type="button" aria-label="Share post" onClick={() => void share()}><Icon name="share" /><span>Share</span></button>
      </div>
    </div>
  </article>;
}

export function MediaFeedPage() {
  const [parameters] = useSearchParams();
  const anchorPostId = parameters.get("anchorPostId")?.trim() || undefined;
  const subreddit = parameters.get("subreddit")?.trim().replace(/^r\//u, "") || undefined;
  const navigate = useNavigate();
  const [items, setItems] = useState<Post[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [appending, setAppending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [activeIndex, setActiveIndex] = useState(0);
  const [chromeVisible, setChromeVisible] = useState(true);
  const scroller = useRef<HTMLDivElement>(null);
  const busy = useRef(false);

  const load = useCallback(async (append: boolean) => {
    if (busy.current) return;
    const nextCursor = append ? cursor : null;
    if (append && !nextCursor) return;
    busy.current = true;
    append ? setAppending(true) : setLoading(true);
    setError(null);
    try {
      const page = await api.mediaFeed(nextCursor, { anchorPostId, subreddit });
      setItems((current) => {
        const combined = append ? [...current, ...page.items] : page.items;
        return [...new Map(combined.map((item) => [item.id, item])).values()];
      });
      setCursor(page.nextCursor);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "The media feed could not be loaded");
    } finally {
      busy.current = false;
      setLoading(false);
      setAppending(false);
    }
  }, [anchorPostId, cursor, subreddit]);

  useEffect(() => { void load(false); }, [anchorPostId, subreddit]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    document.title = `${subreddit ? `r/${subreddit} media` : "Media"} · Read That`;
  }, [subreddit]);
  useEffect(() => {
    const root = scroller.current;
    if (!root) return;
    const observer = new IntersectionObserver((entries) => {
      const visible = entries.filter((entry) => entry.isIntersecting).sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0];
      if (!visible) return;
      const index = Number((visible.target as HTMLElement).dataset.index ?? 0);
      setActiveIndex(index);
      if (index >= items.length - 3 && cursor && !busy.current) void load(true);
    }, { root, threshold: [.55, .75, .95] });
    root.querySelectorAll<HTMLElement>("[data-media-page]").forEach((element) => observer.observe(element));
    return () => observer.disconnect();
  }, [cursor, items.length, load]);

  if (loading && items.length === 0) return <div className="immersive-state"><Spinner /> Loading media…</div>;
  if (error && items.length === 0) return <div className="immersive-state"><Icon name="video" /><h1>Media unavailable</h1><p>{error}</p><button className="primary-button" type="button" onClick={() => void load(false)}>Try again</button><button className="text-button" type="button" onClick={() => navigate(-1)}>Go back</button></div>;
  if (items.length === 0) return <div className="immersive-state"><Icon name="video" /><h1>No media yet</h1><p>Image and video posts will appear here as communities publish them.</p><button className="primary-button" type="button" onClick={() => navigate(-1)}>Go back</button></div>;

  return <div className="media-feed-route">
    <button className="immersive-close" type="button" aria-label="Close media feed" onClick={() => navigate(-1)}><Icon name="x" /></button>
    <div ref={scroller} className="media-feed-scroller">
      {items.map((post, index) => <div data-media-page data-index={index} key={post.id}>
        <MediaPage post={post} active={index === activeIndex} chromeVisible={chromeVisible} onToggleChrome={() => setChromeVisible((value) => !value)} />
      </div>)}
      {appending && <div className="media-feed-append"><Spinner /> Loading more</div>}
      {error && <div className="media-feed-append">{error}</div>}
    </div>
  </div>;
}
