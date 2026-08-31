import { assertCanRead, requireSubredditByName } from "./access";
import { keyedHash, signOpaquePayload, verifyOpaquePayload } from "./crypto";
import { MEDIA_FEED_PATTERN, mergeShowcaseLanes } from "./feed-showcase";
import { AppError } from "./http";
import { postJson, requireVisiblePost, type PostRow } from "./posts";
import type { RequestContext } from "./types";

interface MediaFeedCursor {
  version: 1;
  snapshotAt: number;
  lastRank: number;
  lastId: string;
  subreddit: string | null;
  anchorPostId: string | null;
  /** Media items already emitted, including a first-page anchor when present. */
  itemOffset?: number;
  /** Independent keysets keep video spacing stable across page boundaries. */
  showcaseLanes?: Partial<Record<MediaShowcaseLane, MediaLaneCursor>>;
  audience: string;
}

type MediaShowcaseLane = "image" | "video";

interface MediaLaneCursor {
  lastRank: number;
  lastId: string;
}

interface RankedMediaPostRow extends PostRow {
  rank_value: number;
}

const mediaShowcaseLanes = ["image", "video"] as const;

function validMediaLaneCursors(value: MediaFeedCursor["showcaseLanes"]): boolean {
  if (value === undefined || value === null || typeof value !== "object") return value === undefined;
  return Object.entries(value).every(([lane, state]) => (
    mediaShowcaseLanes.includes(lane as MediaShowcaseLane) &&
    state !== undefined && Number.isSafeInteger(state.lastRank) && typeof state.lastId === "string"
  ));
}

function boundedLimit(value: string | null): number {
  if (value === null) return 8;
  const parsed = Number(value);
  // Two is the minimum so an anchored first page can still advance its keyset.
  if (!Number.isInteger(parsed) || parsed < 2 || parsed > 20) {
    throw new AppError(422, "invalid_limit", "Media feed limit must be an integer between 2 and 20");
  }
  return parsed;
}

function mediaFeedSelect(): string {
  return `SELECT p.id, p.subreddit_id, s.name AS subreddit_name,
                 s.avatar_url AS subreddit_avatar_url,
                 s.access_type AS subreddit_access_type,
                 p.author_id, u.username AS author_username, p.kind, p.title, p.body,
                 p.url, p.media_id, p.crosspost_parent_id, p.score, p.upvotes,
                 p.downvotes, p.comment_count, p.version, p.created_at, p.updated_at,
                 p.deleted_at, v.value AS viewer_vote,
                 m.content_type AS media_content_type, m.width AS media_width,
                 m.height AS media_height, m.duration_seconds AS media_duration_seconds,
                 m.alt_text AS media_alt_text, m.stream_status AS media_stream_status,
                 m.stream_progress AS media_stream_progress, m.hls_url AS media_hls_url,
                 m.dash_url AS media_dash_url, m.thumbnail_url AS media_thumbnail_url,
                 m.preview_url AS media_preview_url, m.source_deleted_at AS media_source_deleted_at,
                 m.image_uid AS media_image_uid, m.image_status AS media_image_status,
                 m.etag AS media_etag, candidates.personalized_rank AS rank_value
          FROM candidates
          JOIN posts p ON p.id = candidates.id
          JOIN subreddits s ON s.id = p.subreddit_id
          JOIN users u ON u.id = p.author_id
          LEFT JOIN votes v ON v.target_type = 'post' AND v.target_id = p.id AND v.user_id = ?
          JOIN media m ON m.id = p.media_id`;
}

function mediaLaneStatement(
  context: RequestContext,
  lane: MediaShowcaseLane,
  laneCursor: MediaLaneCursor | undefined,
  viewerId: string,
  snapshotAt: number,
  subreddit: string | null,
  anchorPostId: string | null,
  pageSize: number,
): D1PreparedStatement {
  return context.db.prepare(
    `WITH subscribed AS (
       SELECT p.id, p.rank_value + 4000000000000000 AS personalized_rank
       FROM subreddit_members membership
       JOIN subreddits s ON s.id = membership.subreddit_id
       JOIN posts p ON p.subreddit_id = membership.subreddit_id
       WHERE membership.user_id = ?
         AND membership.role IN ('subscriber', 'member', 'moderator', 'owner')
         AND (s.access_type <> 'private' OR membership.role IN ('member', 'moderator', 'owner'))
         AND p.deleted_at IS NULL AND p.created_at <= ?
         AND p.kind = ? AND p.media_id IS NOT NULL
         AND (? = '' OR s.name = ?)
         AND (? = '' OR p.id <> ?)
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
         AND p.kind = ? AND p.media_id IS NOT NULL
         AND (s.access_type <> 'private' OR visibility.role IN ('member', 'moderator', 'owner'))
         AND (? = '' OR s.name = ?)
         AND (? = '' OR p.id <> ?)
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
     ${mediaFeedSelect()}
     ORDER BY candidates.personalized_rank DESC, p.id DESC
     LIMIT ?`,
  ).bind(
    viewerId,
    snapshotAt,
    lane,
    subreddit ?? "",
    subreddit ?? "",
    anchorPostId ?? "",
    anchorPostId ?? "",
    laneCursor ? 1 : 0,
    laneCursor?.lastRank ?? 0,
    laneCursor?.lastRank ?? 0,
    laneCursor?.lastId ?? "",
    pageSize,
    viewerId,
    snapshotAt,
    lane,
    subreddit ?? "",
    subreddit ?? "",
    anchorPostId ?? "",
    anchorPostId ?? "",
    viewerId,
    laneCursor ? 1 : 0,
    laneCursor?.lastRank ?? 0,
    laneCursor?.lastRank ?? 0,
    laneCursor?.lastId ?? "",
    pageSize,
    viewerId,
    pageSize,
  );
}

/**
 * Typed media-only projection over the same rank and ACL rules as /v1/feed.
 * Feed updates remain request/response cursor paging; only post/comment live
 * mutations use the existing per-post realtime channel.
 */
export async function getMediaFeed(context: RequestContext): Promise<Response> {
  const limit = boundedLimit(context.url.searchParams.get("limit"));
  const subreddit = context.url.searchParams.get("subreddit")?.trim().toLowerCase() || null;
  if (subreddit) {
    const access = await requireSubredditByName(context.db, subreddit, context.viewer?.id ?? null);
    assertCanRead(access);
  }

  const viewerId = context.viewer?.id ?? "";
  const audience = (await keyedHash(
    context.env.CURSOR_SECRET,
    `media-feed:${viewerId || "anonymous"}`,
  )).slice(0, 22);
  const requestedAnchor = context.url.searchParams.get("anchorPostId")?.trim() || null;
  const encodedCursor = context.url.searchParams.get("cursor");
  let cursor: MediaFeedCursor | null = null;
  if (encodedCursor) {
    cursor = await verifyOpaquePayload<MediaFeedCursor>(context.env.CURSOR_SECRET, encodedCursor);
    if (
      !cursor || cursor.version !== 1 || cursor.subreddit !== subreddit || cursor.audience !== audience ||
      (requestedAnchor !== null && requestedAnchor !== cursor.anchorPostId) ||
      !Number.isSafeInteger(cursor.snapshotAt) || !Number.isSafeInteger(cursor.lastRank) ||
      typeof cursor.lastId !== "string" ||
      (cursor.itemOffset !== undefined && (
        !Number.isSafeInteger(cursor.itemOffset) || cursor.itemOffset < 0
      )) || !validMediaLaneCursors(cursor.showcaseLanes)
    ) {
      throw new AppError(400, "invalid_cursor", "Media feed cursor is invalid or belongs to another feed");
    }
  }

  const anchorPostId = cursor?.anchorPostId ?? requestedAnchor;
  const anchor = !cursor && anchorPostId ? await requireVisiblePost(context, anchorPostId) : null;
  if (anchor && (anchor.media_id === null || (anchor.kind !== "image" && anchor.kind !== "video"))) {
    throw new AppError(422, "anchor_not_media", "Media feed anchor must be an image or video post");
  }

  const snapshotAt = cursor?.snapshotAt ?? Date.now();
  const pageCapacity = limit - (anchor ? 1 : 0);
  const legacyCursor = cursor !== null && cursor.showcaseLanes === undefined;
  const legacyResult = legacyCursor ? await context.db.prepare(
    `WITH subscribed AS (
       SELECT p.id, p.rank_value + 4000000000000000 AS personalized_rank
       FROM subreddit_members membership
       JOIN subreddits s ON s.id = membership.subreddit_id
       JOIN posts p ON p.subreddit_id = membership.subreddit_id
       WHERE membership.user_id = ?
         AND membership.role IN ('subscriber', 'member', 'moderator', 'owner')
         AND (s.access_type <> 'private' OR membership.role IN ('member', 'moderator', 'owner'))
         AND p.deleted_at IS NULL AND p.created_at <= ?
         AND p.kind IN ('image', 'video') AND p.media_id IS NOT NULL
         AND (? = '' OR s.name = ?)
         AND (? = '' OR p.id <> ?)
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
         AND p.kind IN ('image', 'video') AND p.media_id IS NOT NULL
         AND (s.access_type <> 'private' OR visibility.role IN ('member', 'moderator', 'owner'))
         AND (? = '' OR s.name = ?)
         AND (? = '' OR p.id <> ?)
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
     ${mediaFeedSelect()}
     ORDER BY candidates.personalized_rank DESC, p.id DESC
     LIMIT ?`,
  ).bind(
    viewerId,
    snapshotAt,
    subreddit ?? "",
    subreddit ?? "",
    anchorPostId ?? "",
    anchorPostId ?? "",
    cursor ? 1 : 0,
    cursor?.lastRank ?? 0,
    cursor?.lastRank ?? 0,
    cursor?.lastId ?? "",
    pageCapacity + 1,
    viewerId,
    snapshotAt,
    subreddit ?? "",
    subreddit ?? "",
    anchorPostId ?? "",
    anchorPostId ?? "",
    viewerId,
    cursor ? 1 : 0,
    cursor?.lastRank ?? 0,
    cursor?.lastRank ?? 0,
    cursor?.lastId ?? "",
    pageCapacity + 1,
    viewerId,
    pageCapacity + 1,
  ).all<RankedMediaPostRow>() : null;

  let rows: RankedMediaPostRow[];
  let hasMore: boolean;
  let nextShowcaseLanes: MediaFeedCursor["showcaseLanes"];
  let nextItemOffset: number | undefined;
  if (legacyResult) {
    rows = legacyResult.results.slice(0, pageCapacity);
    hasMore = legacyResult.results.length > pageCapacity;
  } else {
    const lanePageSize = pageCapacity + 1;
    const laneResults = await context.db.batch<RankedMediaPostRow>(mediaShowcaseLanes.map((lane) => (
      mediaLaneStatement(
        context,
        lane,
        cursor?.showcaseLanes?.[lane],
        viewerId,
        snapshotAt,
        subreddit,
        anchorPostId,
        lanePageSize,
      )
    )));
    const laneRows: Record<MediaShowcaseLane, RankedMediaPostRow[]> = {
      image: laneResults[0]?.results ?? [],
      video: laneResults[1]?.results ?? [],
    };
    const offset = cursor?.itemOffset ?? (anchor ? 1 : 0);
    const merged = mergeShowcaseLanes(laneRows, MEDIA_FEED_PATTERN, offset, pageCapacity);
    rows = merged.items;
    hasMore = merged.hasMore;
    nextItemOffset = offset + rows.length;
    nextShowcaseLanes = { ...cursor?.showcaseLanes };
    for (const lane of mediaShowcaseLanes) {
      const consumed = merged.consumedLast[lane];
      if (consumed) {
        nextShowcaseLanes[lane] = { lastRank: consumed.rank_value, lastId: consumed.id };
      }
    }
  }
  const posts = await Promise.all([
    ...(anchor ? [postJson(context, anchor)] : []),
    ...rows.map((row) => postJson(context, row)),
  ]);
  const last = rows.at(-1);
  const nextCursor = hasMore && last
    ? await signOpaquePayload<MediaFeedCursor>(context.env.CURSOR_SECRET, {
      version: 1,
      snapshotAt,
      lastRank: last.rank_value,
      lastId: last.id,
      subreddit,
      anchorPostId,
      audience,
      ...(nextItemOffset === undefined ? {} : { itemOffset: nextItemOffset }),
      ...(nextShowcaseLanes === undefined ? {} : { showcaseLanes: nextShowcaseLanes }),
    })
    : null;

  return Response.json({
    schemaVersion: 1,
    feedId: subreddit ? `media:subreddit:${subreddit}` : "media:home",
    snapshotAt,
    anchorIncluded: anchor !== null,
    items: posts,
    nextCursor,
  }, {
    headers: {
      "cache-control": "private, max-age=15",
      vary: "Authorization, X-D1-Bookmark",
      "x-content-type-options": "nosniff",
    },
  });
}
