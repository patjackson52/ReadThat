import { useState, type CSSProperties } from "react";
import { Link } from "react-router-dom";
import { ApiError, api } from "./api";
import { useApp } from "./app-context";
import type { ActionBarCell, FeedGroup, ImageCarouselCell, ImageCell, LinkCell, MetadataCell, TextCell, TitleCell, VideoCell, VoteValue } from "./types";
import { formatCount, Icon } from "./ui";
import { VideoPlayer } from "./video";

function cell<T extends FeedGroup["cells"][number]>(group: FeedGroup, type: T["type"]): T | undefined {
  return group.cells.find((candidate) => candidate.type === type) as T | undefined;
}

function transitionStyle(id: string): CSSProperties {
  return { viewTransitionName: `post-${id.replace(/[^A-Za-z0-9_-]/gu, "-")}` };
}

function PhotoCarousel({ postId, carousel, detail }: {
  postId: string;
  carousel: ImageCarouselCell;
  detail: boolean;
}) {
  const [page, setPage] = useState(0);
  if (carousel.items.length === 0) return null;
  return <div
    className="media-frame gallery-frame"
    style={{ aspectRatio: carousel.items[0]?.aspectRatio ?? 16 / 9 }}
    aria-label={`${carousel.items.length} photo gallery`}
  >
    <div className="gallery-track" onScroll={(event) => {
      const track = event.currentTarget;
      setPage(Math.round(track.scrollLeft / Math.max(1, track.clientWidth)));
    }}>
      {carousel.items.map((image, index) => <Link
        className="gallery-slide"
        to={`/post/${postId}`}
        key={image.mediaId ?? image.cacheKey ?? index}
        aria-label={`Photo ${index + 1} of ${carousel.items.length}`}
      >
        {image.url && <img src={detail ? image.zoomUrl ?? image.url : image.url} alt={image.altText} loading={detail || index === 0 ? "eager" : "lazy"} decoding="async" />}
      </Link>)}
    </div>
    <span className="gallery-count">{Math.min(page + 1, carousel.items.length)}/{carousel.items.length}</span>
  </div>;
}

export function PostCard({ group, detail = false }: { group: FeedGroup; detail?: boolean }) {
  const metadata = cell<MetadataCell>(group, "metadata");
  const title = cell<TitleCell>(group, "title");
  const text = cell<TextCell>(group, "text");
  const link = cell<LinkCell>(group, "link");
  const image = cell<ImageCell>(group, "image");
  const gallery = cell<ImageCarouselCell>(group, "image_carousel");
  const video = cell<VideoCell>(group, "video");
  const actions = cell<ActionBarCell>(group, "actionbar");
  const announcement = group.cells.find((candidate) => candidate.type === "announcement");
  const { auth, notify, signInRequested } = useApp();
  const [vote, setVote] = useState<VoteValue>(actions?.vote ?? 0);
  const [score, setScore] = useState(actions?.score ?? 0);

  async function applyVote(value: VoteValue) {
    if (!auth) { signInRequested(); return; }
    const previousVote = vote;
    const previousScore = score;
    const nextVote = previousVote === value ? 0 : value;
    setVote(nextVote);
    setScore(previousScore - previousVote + nextVote);
    try {
      const result = await api.vote("post", group.groupId, nextVote);
      if (result) { setVote(result.vote.value); setScore(result.vote.score); }
      else notify("Vote queued for sync", "success");
    } catch (error) {
      setVote(previousVote); setScore(previousScore);
      notify(error instanceof ApiError ? error.message : "Could not save vote", "error");
    }
  }

  async function share() {
    const url = new URL(`/post/${group.groupId}`, location.origin).toString();
    try {
      if (navigator.share) await navigator.share({ title: title?.text, url });
      else { await navigator.clipboard.writeText(url); notify("Link copied", "success"); }
    } catch { /* User cancelled the share sheet. */ }
  }

  return <article className={`post-card${detail ? " post-card-detail" : ""}`} style={transitionStyle(group.groupId)}>
    {metadata && <div className="post-meta">
      <Link to={`/r/${metadata.subreddit}`} viewTransition>r/{metadata.subreddit}</Link>
      <span aria-hidden="true">·</span>
      <Link to={`/u/${metadata.author}`} viewTransition>u/{metadata.author}</Link>
      <span aria-hidden="true">·</span>
      <time dateTime={new Date(metadata.createdAt).toISOString()}>{metadata.postedAgo}</time>
    </div>}
    {announcement?.type === "announcement" && <p className="announcement">{announcement.text}</p>}
    {title && (detail ? <h1>{title.text}</h1> : <h2><Link to={`/post/${group.groupId}`} viewTransition>{title.text}</Link></h2>)}
    {text && <p className={!detail ? "post-excerpt" : undefined}>{text.body}</p>}
    {link && <a className="link-preview" href={link.url} target="_blank" rel="noopener noreferrer"><span>{link.domain}</span><strong>{link.url}</strong></a>}
    {image && <Link className="media-frame" to={`/post/${group.groupId}`} viewTransition style={{ aspectRatio: image.aspectRatio }}>
      <img src={image.url} alt={image.altText} loading={detail ? "eager" : "lazy"} decoding="async" />
    </Link>}
    {gallery && <PhotoCarousel postId={group.groupId} carousel={gallery} detail={detail} />}
    {video && <VideoPlayer id={`${group.groupId}:${detail ? "detail" : "feed"}`} asset={video} aspectRatio={video.aspectRatio} label={video.altText || title?.text || "Post video"} />}
    {actions && <div className="post-actions">
      <div className={`vote-control${vote !== 0 ? " voted" : ""}`} aria-label={`Score ${score}`}>
        <button type="button" aria-label="Upvote" aria-pressed={vote === 1} onClick={() => void applyVote(1)}><Icon name="arrow-up" /></button>
        <span>{formatCount(score)}</span>
        <button type="button" aria-label="Downvote" aria-pressed={vote === -1} onClick={() => void applyVote(-1)}><Icon name="arrow-down" /></button>
      </div>
      <Link className="action-link" to={`/post/${group.groupId}#comments`} viewTransition><Icon name="comment" /> {formatCount(actions.commentCount)} <span>comments</span></Link>
      <button type="button" className="action-link" onClick={() => void share()}><Icon name="share" /> Share</button>
    </div>}
  </article>;
}
