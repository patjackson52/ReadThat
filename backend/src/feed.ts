import { assertCanRead, requireSubredditByName } from "./access";
import { base64UrlEncode, keyedHash, sha256, signOpaquePayload, verifyOpaquePayload } from "./crypto";
import { AppError } from "./http";
import { optimizedStreamPosterUrl, signedMediaUrl } from "./media";
import { interleavePromotedGroups, promotedFeedGroups } from "./promoted";
import { postMediaJson } from "./posts";
import type { RequestContext } from "./types";

interface FeedCursor {
  version: 2;
  snapshotAt: number;
  lastRank: number;
  lastId: string;
  subreddit: string | null;
  /** Binds personalized ordering to the viewer without exposing their user id. */
  audience: string;
}

interface FeedRow {
  id: string;
  subreddit_name: string;
  subreddit_avatar_url: string | null;
  author_username: string;
  kind: "text" | "image" | "video" | "link";
  title: string;
  body: string | null;
  url: string | null;
  media_id: string | null;
  flair_id: string | null;
  flair_text: string | null;
  flair_background_color: string | null;
  flair_text_color: string | null;
  crosspost_parent_id: string | null;
  score: number;
  comment_count: number;
  viewer_vote: number;
  created_at: number;
  version: number;
  rank_value: number;
  media_width: number | null;
  media_height: number | null;
  media_duration_seconds: number | null;
  media_alt_text: string | null;
  media_stream_status: "not_applicable" | "waiting" | "processing" | "ready" | "error" | null;
  media_stream_progress: number | null;
  media_hls_url: string | null;
  media_dash_url: string | null;
  media_thumbnail_url: string | null;
  media_preview_url: string | null;
  media_source_deleted_at: number | null;
  media_image_uid: string | null;
  media_etag: string | null;
}

type WireCell = Record<string, unknown>;

function boundedLimit(value: string | null): number {
  if (value === null) return 12;
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > 50) {
    throw new AppError(422, "invalid_limit", "Feed limit must be an integer between 1 and 50");
  }
  return parsed;
}

function postedAgo(createdAt: number, now: number): string {
  const seconds = Math.max(0, Math.floor((now - createdAt) / 1_000));
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h`;
  return `${Math.floor(hours / 24)}d`;
}

function aspectRatio(row: FeedRow): number {
  if (!row.media_width || !row.media_height) return 16 / 9;
  return Math.max(0.25, Math.min(4, row.media_width / row.media_height));
}

async function cellsFor(context: RequestContext, row: FeedRow, now: number): Promise<WireCell[]> {
  const cells: WireCell[] = [{
    type: "metadata",
    cellId: "meta",
    subreddit: row.subreddit_name,
    author: row.author_username,
    avatarUrl: row.subreddit_avatar_url,
    postedAgo: postedAgo(row.created_at, now),
    createdAt: row.created_at,
    pinned: false,
    flair: row.flair_id && row.flair_text && row.flair_background_color && row.flair_text_color ? {
      id: row.flair_id,
      text: row.flair_text,
      backgroundColor: row.flair_background_color,
      textColor: row.flair_text_color,
    } : null,
  }, {
    type: "title",
    cellId: "title",
    text: row.title,
    flair: row.flair_id && row.flair_text && row.flair_background_color && row.flair_text_color ? {
      id: row.flair_id,
      text: row.flair_text,
      backgroundColor: row.flair_background_color,
      textColor: row.flair_text_color,
    } : null,
  }];

  if (row.crosspost_parent_id) {
    cells.push({
      type: "announcement",
      cellId: "crosspost",
      text: `Reshared from post ${row.crosspost_parent_id}`,
      sourcePostId: row.crosspost_parent_id,
    });
  }
  if (row.kind === "text" && row.body) {
    cells.push({ type: "text", cellId: "body", body: row.body, maxLines: 3 });
  } else if (row.kind === "link" && row.url) {
    let domain = row.url;
    try { domain = new URL(row.url).hostname; } catch { /* URL was validated on write. */ }
    cells.push({ type: "link", cellId: "link", url: row.url, domain });
  } else if (row.kind === "image" && row.media_id) {
    const mediaItems = await postMediaJson(context, row.id, "feed");
    const first = mediaItems[0];
    if (mediaItems.length > 1) {
      cells.push({
        type: "image_carousel",
        cellId: "media",
        items: mediaItems.map((media) => ({
          mediaId: media.id,
          url: media.url,
          zoomUrl: media.zoomUrl,
          cacheKey: media.cacheKey,
          placeholderColor: media.placeholderColor,
          aspectRatio: media.aspectRatio,
          altText: media.altText,
          width: media.width,
          height: media.height,
        })),
      });
    } else if (first) {
      cells.push({
        type: "image",
        cellId: "media",
        url: first.url,
        cacheKey: first.cacheKey,
        placeholderColor: first.placeholderColor,
        aspectRatio: first.aspectRatio,
        altText: first.altText,
      });
    }
  } else if (row.kind === "video" && row.media_id) {
    const hlsReady = row.media_stream_status === "ready" && row.media_hls_url !== null;
    const fallbackUrl = hlsReady || row.media_source_deleted_at !== null
      ? null
      : await signedMediaUrl(context, row.media_id);
    cells.push({
      type: "video",
      cellId: "media",
      // `url` keeps old clients working: Stream HLS when ready, otherwise the
      // byte-range R2 source while encoding is in progress.
      url: hlsReady ? row.media_hls_url : fallbackUrl,
      hlsUrl: hlsReady ? row.media_hls_url : null,
      dashUrl: hlsReady ? row.media_dash_url : null,
      posterUrl: optimizedStreamPosterUrl(
        row.media_thumbnail_url,
        row.media_width,
        row.media_height,
        row.media_duration_seconds,
      ),
      previewUrl: row.media_preview_url,
      fallbackUrl,
      deliveryStatus: row.media_stream_status ?? "not_applicable",
      processingProgress: row.media_stream_progress ?? 0,
      // Manifests are dynamic and must bypass client caches. HLS media segments
      // are immutable CDN assets and are eligible for device LRU caching.
      cachePolicy: "segments_only",
      cacheKey: `video:${row.media_id}:${row.media_etag ?? "pending"}`,
      placeholderColor: 0xff0045ac,
      aspectRatio: aspectRatio(row),
      durationSeconds: row.media_duration_seconds ?? 0,
      altText: row.media_alt_text ?? "",
    });
  }

  cells.push({
    type: "actionbar",
    cellId: "actions",
    score: row.score,
    commentCount: row.comment_count,
    liked: row.viewer_vote === 1,
    vote: row.viewer_vote,
    version: row.version,
  });
  return cells;
}

export async function getFeed(context: RequestContext): Promise<Response> {
  const limit = boundedLimit(context.url.searchParams.get("limit"));
  const subreddit = context.url.searchParams.get("subreddit")?.trim().toLowerCase() || null;
  if (subreddit) {
    const access = await requireSubredditByName(context.db, subreddit, context.viewer?.id ?? null);
    assertCanRead(access);
  }

  const viewerId = context.viewer?.id ?? "";
  const audience = (await keyedHash(
    context.env.CURSOR_SECRET,
    `feed:${viewerId || "anonymous"}`,
  )).slice(0, 22);
  const encodedCursor = context.url.searchParams.get("cursor");
  let cursor: FeedCursor | null = null;
  if (encodedCursor) {
    cursor = await verifyOpaquePayload<FeedCursor>(context.env.CURSOR_SECRET, encodedCursor);
    if (
      !cursor || cursor.version !== 2 || cursor.subreddit !== subreddit || cursor.audience !== audience ||
      !Number.isSafeInteger(cursor.snapshotAt) || !Number.isSafeInteger(cursor.lastRank) ||
      typeof cursor.lastId !== "string"
    ) {
      throw new AppError(400, "invalid_cursor", "Feed cursor is invalid or belongs to another feed");
    }
  }

  const snapshotAt = cursor?.snapshotAt ?? Date.now();
  const result = await context.db.prepare(
    `WITH subscribed AS (
       SELECT p.id, p.rank_value + 4000000000000000 AS personalized_rank
       FROM subreddit_members membership
       JOIN subreddits s ON s.id = membership.subreddit_id
       JOIN posts p ON p.subreddit_id = membership.subreddit_id
       WHERE membership.user_id = ?
         AND membership.role IN ('subscriber', 'member', 'moderator', 'owner')
         AND (s.access_type <> 'private' OR membership.role IN ('member', 'moderator', 'owner'))
         AND p.deleted_at IS NULL AND p.created_at <= ?
         AND (? = '' OR s.name = ?)
         AND (? = 0 OR p.rank_value + 4000000000000000 < ?
              OR (p.rank_value + 4000000000000000 = ? AND p.id < ?))
       ORDER BY p.rank_value DESC, p.id DESC
       LIMIT ?
     ), discovery AS (
       SELECT p.id, p.rank_value AS personalized_rank
       FROM posts p
       JOIN subreddits s ON s.id = p.subreddit_id
       LEFT JOIN subreddit_members visibility
         ON visibility.subreddit_id = s.id AND visibility.user_id = ?
       WHERE p.deleted_at IS NULL AND p.created_at <= ?
         AND (s.access_type <> 'private' OR visibility.role IN ('member', 'moderator', 'owner'))
         AND (? = '' OR s.name = ?)
         AND NOT EXISTS (
           SELECT 1 FROM subreddit_members subscribed_membership
           WHERE subscribed_membership.subreddit_id = s.id
             AND subscribed_membership.user_id = ?
             AND subscribed_membership.role IN ('subscriber', 'member', 'moderator', 'owner')
         )
         AND (? = 0 OR p.rank_value < ? OR (p.rank_value = ? AND p.id < ?))
       ORDER BY p.rank_value DESC, p.id DESC
       LIMIT ?
     ), candidates AS (
       SELECT id, personalized_rank FROM subscribed
       UNION ALL
       SELECT id, personalized_rank FROM discovery
     )
     SELECT p.id, s.name AS subreddit_name, s.avatar_url AS subreddit_avatar_url,
            u.username AS author_username,
            p.kind, p.title, p.body, p.url, p.media_id, p.flair_id,
            pf.text AS flair_text, pf.background_color AS flair_background_color,
            pf.text_color AS flair_text_color, p.crosspost_parent_id,
            p.score, p.comment_count, COALESCE(v.value, 0) AS viewer_vote,
            p.created_at, p.version, m.width AS media_width, m.height AS media_height,
            m.duration_seconds AS media_duration_seconds, m.alt_text AS media_alt_text,
            m.stream_status AS media_stream_status, m.stream_progress AS media_stream_progress,
            m.hls_url AS media_hls_url, m.dash_url AS media_dash_url,
            m.thumbnail_url AS media_thumbnail_url, m.preview_url AS media_preview_url,
            m.source_deleted_at AS media_source_deleted_at,
            m.image_uid AS media_image_uid, m.etag AS media_etag,
            candidates.personalized_rank AS rank_value
     FROM candidates
     JOIN posts p ON p.id = candidates.id
     JOIN subreddits s ON s.id = p.subreddit_id
     JOIN users u ON u.id = p.author_id
     LEFT JOIN votes v ON v.target_type = 'post' AND v.target_id = p.id AND v.user_id = ?
     LEFT JOIN media m ON m.id = p.media_id
     LEFT JOIN post_flairs pf ON pf.id = p.flair_id
     ORDER BY candidates.personalized_rank DESC, p.id DESC
     LIMIT ?`,
  ).bind(
    viewerId,
    snapshotAt,
    subreddit ?? "",
    subreddit ?? "",
    cursor ? 1 : 0,
    cursor?.lastRank ?? 0,
    cursor?.lastRank ?? 0,
    cursor?.lastId ?? "",
    limit + 1,
    viewerId,
    snapshotAt,
    subreddit ?? "",
    subreddit ?? "",
    viewerId,
    cursor ? 1 : 0,
    cursor?.lastRank ?? 0,
    cursor?.lastRank ?? 0,
    cursor?.lastId ?? "",
    limit + 1,
    viewerId,
    limit + 1,
  ).all<FeedRow>();

  const hasMore = result.results.length > limit;
  const rows = result.results.slice(0, limit);
  const now = Date.now();
  const organicGroups = await Promise.all(rows.map(async (row) => ({
    groupId: row.id,
    cells: await cellsFor(context, row, now),
  })));
  // Editorial demo units are page-1 home content and never participate in the
  // ranked cursor, so pagination remains stable and subreddit feeds stay clean.
  const groups = cursor === null && subreddit === null
    && context.url.searchParams.get("includePromoted") === "true"
    ? interleavePromotedGroups(organicGroups, promotedFeedGroups())
    : organicGroups;
  const last = rows.at(-1);
  const nextCursor = hasMore && last
    ? await signOpaquePayload<FeedCursor>(context.env.CURSOR_SECRET, {
      version: 2,
      snapshotAt,
      lastRank: last.rank_value,
      lastId: last.id,
      subreddit,
      audience,
    })
    : null;
  const payload = {
    schemaVersion: 1,
    feedId: subreddit ? `subreddit:${subreddit}` : "home",
    serverTime: now,
    groups,
    nextCursor,
  };
  const body = JSON.stringify(payload);
  const etag = `"${base64UrlEncode(await sha256(body))}"`;
  if (context.request.headers.get("if-none-match") === etag) {
    return new Response(null, { status: 304, headers: { etag, "cache-control": "private, max-age=15" } });
  }
  return new Response(body, {
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "private, max-age=15",
      etag,
      vary: "Authorization, X-D1-Bookmark",
      "x-content-type-options": "nosniff",
    },
  });
}
