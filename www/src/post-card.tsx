import { useState, type CSSProperties, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { rememberAd } from "./ad-page";
import { ApiError, api } from "./api";
import { useApp } from "./app-context";
import type {
  ActionBarCell,
  AdHeaderCell,
  AdMediaItem,
  AdMediaCell,
  AdRelatedPostsCell,
  AdSummaryCell,
  AdTitleCell,
  AdLaunchContext,
  FeedGroup,
  ImageCarouselCell,
  ImageCell,
  LinkCell,
  MetadataCell,
  TextCell,
  TitleCell,
  VideoCell,
  VoteValue,
} from "./types";
import { formatCount, Icon } from "./ui";
import { VideoPlayer } from "./video";

function cell<T extends FeedGroup["cells"][number]>(group: FeedGroup, type: T["type"]): T | undefined {
  return group.cells.find((candidate) => candidate.type === type) as T | undefined;
}

function transitionStyle(id: string): CSSProperties {
  return { viewTransitionName: `post-${id.replace(/[^A-Za-z0-9_-]/gu, "-")}` };
}

function InlineMarkdown({ text }: { text: string }) {
  const parts: ReactNode[] = [];
  const pattern = /\[(.+?)\]\((https:\/\/[^)\s]+)\)|\*\*([^*]+)\*\*/gu;
  let cursor = 0;
  for (const match of text.matchAll(pattern)) {
    const index = match.index ?? 0;
    if (index > cursor) parts.push(text.slice(cursor, index));
    if (match[1] && match[2]) {
      parts.push(<a key={`${index}:link`} href={match[2]} target="_blank" rel="noopener noreferrer">{match[1]}</a>);
    } else if (match[3]) {
      parts.push(<strong key={`${index}:strong`}>{match[3]}</strong>);
    }
    cursor = index + match[0].length;
  }
  if (cursor < text.length) parts.push(text.slice(cursor));
  return <>{parts}</>;
}

function markdownPreview(body: string): string {
  return body
    .replace(/^#{1,6}\s+/gmu, "")
    .replace(/\[(.+?)\]\(https:\/\/[^)\s]+\)/gu, "$1")
    .replace(/\*\*([^*]+)\*\*/gu, "$1");
}

function promotedVideo(item: AdMediaItem): VideoCell {
  return {
    type: "video",
    cellId: `promoted:${item.creativeId}`,
    url: item.fallbackUrl ?? null,
    hlsUrl: item.hlsUrl ?? null,
    dashUrl: item.dashUrl ?? null,
    posterUrl: item.posterUrl ?? null,
    previewUrl: null,
    fallbackUrl: item.fallbackUrl ?? null,
    deliveryStatus: "ready",
    processingProgress: 100,
    cachePolicy: "segments_only",
    placeholderColor: item.placeholderColor,
    aspectRatio: item.aspectRatio,
    durationSeconds: item.durationSeconds ?? 0,
    altText: item.altText,
  };
}

export function PostBody({ body, detail }: { body: string; detail: boolean }) {
  if (!detail) return <p className="post-excerpt">{markdownPreview(body)}</p>;
  return <div className="post-body">{body.split(/\n\s*\n/gu).map((block, index) => {
    const heading = /^(#{1,6})\s+(.+)$/u.exec(block);
    return heading
      ? <h3 key={index}><InlineMarkdown text={heading[2] ?? ""} /></h3>
      : <p key={index}><InlineMarkdown text={block} /></p>;
  })}</div>;
}

function PhotoCarousel({ postId, carousel, detail, subreddit }: {
  postId: string;
  carousel: ImageCarouselCell;
  detail: boolean;
  subreddit?: string;
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
        to={detail ? `/post/${postId}` : `/media?anchorPostId=${encodeURIComponent(postId)}${subreddit ? `&subreddit=${encodeURIComponent(subreddit)}` : ""}`}
        key={image.mediaId ?? image.cacheKey ?? index}
        aria-label={`Photo ${index + 1} of ${carousel.items.length}`}
      >
        {image.url && <img src={detail ? image.zoomUrl ?? image.url : image.url} alt={image.altText} loading={detail || index === 0 ? "eager" : "lazy"} decoding="async" />}
      </Link>)}
    </div>
    <span className="gallery-count">{Math.min(page + 1, carousel.items.length)}/{carousel.items.length}</span>
  </div>;
}

function PromotedCard({ group }: { group: FeedGroup }) {
  const header = cell<AdHeaderCell>(group, "ad_header");
  const title = cell<AdTitleCell>(group, "ad_title");
  const media = cell<AdMediaCell>(group, "ad_media");
  const summary = cell<AdSummaryCell>(group, "ad_summary");
  const related = cell<AdRelatedPostsCell>(group, "ad_related_posts");
  const [page, setPage] = useState(0);
  if (!header || !title || !media || !summary) return null;
  const promotedHeader = header;
  const promotedMedia = media;

  function launch(index: number): AdLaunchContext {
    const item = promotedMedia.items[Math.min(index, promotedMedia.items.length - 1)]!;
    return {
      adId: promotedHeader.adId,
      creativeId: item.creativeId,
      kind: item.kind,
      aspectRatio: item.aspectRatio,
      altText: item.altText,
      imageUrl: item.imageUrl ?? null,
      hlsUrl: item.hlsUrl ?? null,
      posterUrl: item.posterUrl ?? null,
      fallbackUrl: item.fallbackUrl ?? null,
      cacheKey: item.cacheKey ?? item.creativeId,
      destinationUrl: promotedMedia.destinationUrl,
      displayDomain: promotedMedia.displayDomain,
      ctaLabel: promotedMedia.ctaLabel,
      selectedIndex: index,
    };
  }
  const selectedAd = media.items.length > 0 ? launch(page) : null;

  return <article className="post-card promoted-card" style={transitionStyle(group.groupId)}>
    <div className="promoted-byline">
      {header.avatarUrl && <img src={header.avatarUrl} alt="" loading="lazy" decoding="async" />}
      <div><strong>u/{header.author}</strong><span>{header.label}</span></div>
    </div>
    <h2>{selectedAd ? <Link to={`/ad/${encodeURIComponent(header.adId)}`} state={{ ad: selectedAd }} onClick={() => rememberAd(selectedAd)}>{title.text}</Link> : title.text}</h2>
    {media.items.length > 0 && <div
      className="promoted-media-frame"
      style={{ aspectRatio: media.items[page]?.aspectRatio ?? 1 }}
      aria-label={`${media.items.length} item promoted gallery`}
    >
      <div className="promoted-media-track" onScroll={(event) => {
        const track = event.currentTarget;
        setPage(Math.round(track.scrollLeft / Math.max(1, track.clientWidth)));
      }}>
        {media.items.map((item, index) => {
          const preview = item.posterUrl ?? item.imageUrl;
          const ad = launch(index);
          return item.kind === "video" ? <div className="promoted-media-slide promoted-video-slide" key={item.creativeId}>
            <VideoPlayer id={`promoted:${group.groupId}:${item.creativeId}`} asset={promotedVideo(item)} aspectRatio={item.aspectRatio} label={item.altText} />
            <Link className="promoted-media-open" to={`/ad/${encodeURIComponent(header.adId)}`} state={{ ad }} onClick={() => rememberAd(ad)}>Open promotion</Link>
          </div> : <Link
            className="promoted-media-slide"
            to={`/ad/${encodeURIComponent(header.adId)}`}
            state={{ ad }}
            key={item.creativeId}
            aria-label={`${item.altText}. Open promoted detail.`}
            onClick={() => rememberAd(ad)}
          >
            {preview ? <img src={preview} alt={item.altText} loading="lazy" decoding="async" /> : <span aria-hidden="true" />}
          </Link>;
        })}
      </div>
      {media.items.length > 1 && <span className="gallery-count">{Math.min(page + 1, media.items.length)}/{media.items.length}</span>}
    </div>}
    <p className="promoted-summary">{summary.text}</p>
    <p className="promoted-disclosure">{summary.disclosureLabel}</p>
    {related && <section className="promoted-evidence" aria-label={related.disclosureLabel}>
      <div><strong>Read That engineering evidence</strong><span>Open in Read That</span></div>
      <ul>{related.posts.map((post) => <li key={post.postId}>
        {post.postId.startsWith("placeholder:")
          ? <span><b>{post.title}</b><small>r/{post.subreddit}</small></span>
          : <Link to={`/post/${post.postId}`} viewTransition><b>{post.title}</b><small>r/{post.subreddit} · {formatCount(post.score)} upvotes</small></Link>}
      </li>)}</ul>
    </section>}
    <a className="promoted-cta" href={media.destinationUrl} target="_blank" rel="noopener noreferrer sponsored">
      <span>{media.ctaLabel}</span><small>{media.displayDomain}</small>
    </a>
  </article>;
}

export function PostCard({ group, detail = false }: { group: FeedGroup; detail?: boolean }) {
  return cell<AdHeaderCell>(group, "ad_header")
    ? <PromotedCard group={group} />
    : <OrganicPostCard group={group} detail={detail} />;
}

function OrganicPostCard({ group, detail = false }: { group: FeedGroup; detail?: boolean }) {
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
  const mediaPath = `/media?anchorPostId=${encodeURIComponent(group.groupId)}${metadata?.subreddit ? `&subreddit=${encodeURIComponent(metadata.subreddit)}` : ""}`;

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
    {text && <PostBody body={text.body} detail={detail} />}
    {link && <a className="link-preview" href={link.url} target="_blank" rel="noopener noreferrer"><span>{link.domain}</span><strong>{link.url}</strong></a>}
    {image && <Link className="media-frame" to={detail ? `/post/${group.groupId}` : mediaPath} viewTransition style={{ aspectRatio: image.aspectRatio }} aria-label={detail ? image.altText : `${image.altText || title?.text || "Post image"}. Open media viewer.`}>
      <img src={image.url} alt={image.altText} loading={detail ? "eager" : "lazy"} decoding="async" />
    </Link>}
    {gallery && <PhotoCarousel postId={group.groupId} carousel={gallery} detail={detail} subreddit={metadata?.subreddit} />}
    {video && <div className="video-card-wrap"><VideoPlayer id={`${group.groupId}:${detail ? "detail" : "feed"}`} asset={video} aspectRatio={video.aspectRatio} label={video.altText || title?.text || "Post video"} />{!detail && <Link className="media-open-button" to={mediaPath} viewTransition>Open media</Link>}</div>}
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
