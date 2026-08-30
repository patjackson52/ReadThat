import { requireSubredditByName, assertCanRead } from "./access";
import { keyedHash, signOpaquePayload, verifyOpaquePayload } from "./crypto";
import { AppError, jsonResponse } from "./http";
import { optimizedStreamPosterUrl, signedImageUrl } from "./media";
import type { RequestContext } from "./types";

type SearchType = "all" | "posts" | "communities" | "comments" | "media" | "profiles";
type SearchSort = "relevance" | "hot" | "top" | "new" | "comments";
type SearchTime = "all" | "year" | "month" | "week" | "day" | "hour";

interface SearchCursor {
  version: 1;
  audience: string;
  fingerprint: string;
  snapshotAt: number;
  primary: number;
  createdAt: number;
  id: string;
}

interface RankedRow {
  id: string;
  relevance: number;
  primary_sort: number;
  created_at: number;
}

interface CommunityRow extends RankedRow {
  name: string;
  display_name: string;
  description: string;
  access_type: string;
  subscriber_count: number;
}

interface ProfileRow extends RankedRow {
  username: string;
  display_name: string;
  bio: string;
  avatar_url: string | null;
  avatar_media_id: string | null;
  karma: number;
  updated_at: number;
}

interface PostSearchRow extends RankedRow {
  subreddit_name: string;
  author_username: string;
  kind: "text" | "image" | "video" | "link";
  title: string;
  body: string | null;
  url: string | null;
  score: number;
  comment_count: number;
  viewer_vote: number;
  media_id: string | null;
  media_image_uid: string | null;
  media_etag: string | null;
  media_thumbnail_url: string | null;
  media_width: number | null;
  media_height: number | null;
  media_duration_seconds: number | null;
}

interface CommentSearchRow extends RankedRow {
  post_id: string;
  parent_id: string | null;
  author_username: string;
  body: string;
  score: number;
  viewer_vote: number;
  post_title: string;
  subreddit_name: string;
  post_score: number;
  post_comment_count: number;
}

interface Page<T> { items: T[]; nextCursor: string | null }

const TYPES = new Set<SearchType>(["all", "posts", "communities", "comments", "media", "profiles"]);
const SORTS = new Set<SearchSort>(["relevance", "hot", "top", "new", "comments"]);
const TIMES = new Set<SearchTime>(["all", "year", "month", "week", "day", "hour"]);

function enumParam<T extends string>(value: string | null, fallback: T, values: Set<T>, name: string): T {
  const normalized = (value ?? fallback) as T;
  if (!values.has(normalized)) throw new AppError(422, `invalid_${name}`, `Unsupported search ${name}`);
  return normalized;
}

function boundedLimit(value: string | null, fallback = 20): number {
  const limit = value === null ? fallback : Number(value);
  if (!Number.isInteger(limit) || limit < 1 || limit > 50) {
    throw new AppError(422, "invalid_limit", "Search limit must be an integer between 1 and 50");
  }
  return limit;
}

function queryInput(context: RequestContext): { raw: string; match: string } {
  const raw = (context.url.searchParams.get("q") ?? "").trim().replace(/\s+/gu, " ");
  if (raw.length < 1 || raw.length > 100) {
    throw new AppError(422, "invalid_query", "Search query must contain 1-100 characters");
  }
  const terms = raw.normalize("NFKC").toLocaleLowerCase("en-US").match(/[\p{L}\p{N}_]+/gu)?.slice(0, 8) ?? [];
  if (terms.length === 0) throw new AppError(422, "invalid_query", "Search query needs a letter or number");
  return { raw, match: terms.map((term) => `"${term}"*`).join(" AND ") };
}

function timeFloor(time: SearchTime, snapshotAt: number): number {
  const duration = {
    all: Number.MAX_SAFE_INTEGER,
    year: 365 * 24 * 60 * 60 * 1_000,
    month: 30 * 24 * 60 * 60 * 1_000,
    week: 7 * 24 * 60 * 60 * 1_000,
    day: 24 * 60 * 60 * 1_000,
    hour: 60 * 60 * 1_000,
  }[time];
  return time === "all" ? 0 : snapshotAt - duration;
}

function visibleSubreddit(alias: string, membershipAlias: string): string {
  return `(${membershipAlias}.role IS NULL OR ${membershipAlias}.role <> 'banned')
    AND (${alias}.access_type <> 'private' OR ${membershipAlias}.role IN ('member', 'moderator', 'owner'))`;
}

function avatarUrl(context: RequestContext, row: ProfileRow): string | null {
  return row.avatar_media_id
    ? `${context.url.origin}/v1/users/${encodeURIComponent(row.username)}/avatar?v=${row.updated_at}`
    : row.avatar_url;
}

async function cursorContext(
  context: RequestContext,
  fingerprint: string,
): Promise<{ cursor: SearchCursor | null; audience: string; snapshotAt: number }> {
  const audience = (await keyedHash(
    context.env.CURSOR_SECRET,
    `search:${context.viewer?.id ?? "anonymous"}`,
  )).slice(0, 22);
  const encoded = context.url.searchParams.get("cursor");
  if (!encoded) return { cursor: null, audience, snapshotAt: Date.now() };
  const cursor = await verifyOpaquePayload<SearchCursor>(context.env.CURSOR_SECRET, encoded);
  if (
    !cursor || cursor.version !== 1 || cursor.audience !== audience || cursor.fingerprint !== fingerprint ||
    !Number.isFinite(cursor.primary) || !Number.isSafeInteger(cursor.snapshotAt) ||
    !Number.isSafeInteger(cursor.createdAt) || typeof cursor.id !== "string"
  ) {
    throw new AppError(400, "invalid_cursor", "Search cursor is invalid or belongs to another query");
  }
  return { cursor, audience, snapshotAt: cursor.snapshotAt };
}

async function pageCursor<T extends RankedRow>(
  context: RequestContext,
  rows: T[],
  limit: number,
  audience: string,
  fingerprint: string,
  snapshotAt: number,
): Promise<{ rows: T[]; nextCursor: string | null }> {
  const hasMore = rows.length > limit;
  const pageRows = rows.slice(0, limit);
  const last = pageRows.at(-1);
  return {
    rows: pageRows,
    nextCursor: hasMore && last ? await signOpaquePayload<SearchCursor>(context.env.CURSOR_SECRET, {
      version: 1,
      audience,
      fingerprint,
      snapshotAt,
      primary: last.primary_sort,
      createdAt: last.created_at,
      id: last.id,
    }) : null,
  };
}

function afterCursor(sort: SearchSort, alias: string): string {
  if (sort === "relevance") {
    return `(? = 0 OR ${alias}.relevance > ? OR (${alias}.relevance = ? AND
      (${alias}.created_at < ? OR (${alias}.created_at = ? AND ${alias}.id < ?))))`;
  }
  return `(? = 0 OR ${alias}.primary_sort < ? OR (${alias}.primary_sort = ? AND
    (${alias}.created_at < ? OR (${alias}.created_at = ? AND ${alias}.id < ?))))`;
}

function cursorBindings(cursor: SearchCursor | null): Array<string | number> {
  return [cursor ? 1 : 0, cursor?.primary ?? 0, cursor?.primary ?? 0,
    cursor?.createdAt ?? 0, cursor?.createdAt ?? 0, cursor?.id ?? ""];
}

function postPrimary(sort: SearchSort): string {
  if (sort === "relevance") return "matches.relevance";
  if (sort === "hot") return "p.rank_value";
  if (sort === "top") return "p.score";
  if (sort === "comments") return "p.comment_count";
  return "p.created_at";
}

function orderBy(sort: SearchSort, alias = "ranked"): string {
  const direction = sort === "relevance" ? "ASC" : "DESC";
  return `${alias}.primary_sort ${direction}, ${alias}.created_at DESC, ${alias}.id DESC`;
}

async function searchPosts(
  context: RequestContext,
  match: string,
  sort: SearchSort,
  time: SearchTime,
  safe: boolean,
  subreddit: string | null,
  limit: number,
  fingerprint: string,
  mediaOnly = false,
): Promise<Page<Record<string, unknown>>> {
  const { cursor, audience, snapshotAt } = await cursorContext(context, fingerprint);
  const primary = postPrimary(sort);
  const result = await context.db.prepare(
    `WITH matches AS (
       SELECT id, bm25(search_posts, 8.0, 1.0) AS relevance
       FROM search_posts WHERE search_posts MATCH ?
     ), ranked AS (
       SELECT p.id, matches.relevance, ${primary} AS primary_sort, p.created_at,
              s.name AS subreddit_name, u.username AS author_username,
              p.kind, p.title, substr(p.body, 1, 320) AS body, p.url,
              p.score, p.comment_count, COALESCE(v.value, 0) AS viewer_vote,
              p.media_id, m.image_uid AS media_image_uid, m.etag AS media_etag,
              m.thumbnail_url AS media_thumbnail_url, m.width AS media_width,
              m.height AS media_height, m.duration_seconds AS media_duration_seconds
       FROM matches
       JOIN posts p ON p.id = matches.id
       JOIN subreddits s ON s.id = p.subreddit_id
       JOIN users u ON u.id = p.author_id
       LEFT JOIN subreddit_members visibility
         ON visibility.subreddit_id = s.id AND visibility.user_id = ?
       LEFT JOIN votes v
         ON v.target_type = 'post' AND v.target_id = p.id AND v.user_id = ?
       LEFT JOIN media m ON m.id = p.media_id
       WHERE p.deleted_at IS NULL AND p.created_at BETWEEN ? AND ?
         AND ${visibleSubreddit("s", "visibility")}
         AND (? = '' OR s.name = ?)
         AND (? = 0 OR p.is_mature = 0)
         AND (? = 0 OR p.kind IN ('image', 'video'))
     )
     SELECT * FROM ranked
     WHERE ${afterCursor(sort, "ranked")}
     ORDER BY ${orderBy(sort)} LIMIT ?`,
  ).bind(
    match,
    context.viewer?.id ?? "",
    context.viewer?.id ?? "",
    timeFloor(time, snapshotAt),
    snapshotAt,
    subreddit ?? "",
    subreddit ?? "",
    safe ? 1 : 0,
    mediaOnly ? 1 : 0,
    ...cursorBindings(cursor),
    limit + 1,
  ).all<PostSearchRow>();
  const page = await pageCursor(context, result.results, limit, audience, fingerprint, snapshotAt);
  const items = await Promise.all(page.rows.map(async (row) => ({
    type: "post",
    id: row.id,
    subreddit: row.subreddit_name,
    author: row.author_username,
    kind: row.kind,
    title: row.title,
    body: row.body,
    url: row.url,
    score: row.score,
    commentCount: row.comment_count,
    viewerVote: row.viewer_vote,
    createdAt: row.created_at,
    media: row.media_id ? {
      id: row.media_id,
      thumbnailUrl: row.kind === "image" && row.media_image_uid
        ? await signedImageUrl(context, row.media_image_uid, "feed")
        : optimizedStreamPosterUrl(
          row.media_thumbnail_url,
          row.media_width,
          row.media_height,
          row.media_duration_seconds,
        ),
      width: row.media_width,
      height: row.media_height,
      durationSeconds: row.media_duration_seconds,
      cacheKey: row.kind === "image" ? `image:${row.media_id}:${row.media_etag ?? "pending"}:feed` : null,
    } : null,
  })));
  return { items, nextCursor: page.nextCursor };
}

async function searchCommunities(
  context: RequestContext,
  match: string,
  limit: number,
  fingerprint: string,
): Promise<Page<Record<string, unknown>>> {
  const sort: SearchSort = "relevance";
  const { cursor, audience, snapshotAt } = await cursorContext(context, fingerprint);
  const result = await context.db.prepare(
    `WITH matches AS (
       SELECT id, bm25(search_subreddits, 8.0, 4.0, 1.0) AS relevance
       FROM search_subreddits WHERE search_subreddits MATCH ?
     ), ranked AS (
       SELECT s.id, matches.relevance, matches.relevance AS primary_sort, s.created_at,
              s.name, s.display_name, substr(s.description, 1, 240) AS description,
              s.access_type,
              (SELECT COUNT(*) FROM subreddit_members members
               WHERE members.subreddit_id = s.id
                 AND members.role IN ('subscriber', 'member', 'moderator', 'owner')) AS subscriber_count
       FROM matches
       JOIN subreddits s ON s.id = matches.id
       LEFT JOIN subreddit_members visibility
         ON visibility.subreddit_id = s.id AND visibility.user_id = ?
       WHERE s.created_at <= ? AND ${visibleSubreddit("s", "visibility")}
     )
     SELECT * FROM ranked WHERE ${afterCursor(sort, "ranked")}
     ORDER BY ${orderBy(sort)} LIMIT ?`,
  ).bind(match, context.viewer?.id ?? "", snapshotAt, ...cursorBindings(cursor), limit + 1)
    .all<CommunityRow>();
  const page = await pageCursor(context, result.results, limit, audience, fingerprint, snapshotAt);
  return {
    items: page.rows.map((row) => ({
      type: "community",
      id: row.id,
      name: row.name,
      displayName: row.display_name,
      description: row.description,
      accessType: row.access_type,
      subscriberCount: row.subscriber_count,
    })),
    nextCursor: page.nextCursor,
  };
}

async function searchProfiles(
  context: RequestContext,
  match: string,
  limit: number,
  fingerprint: string,
): Promise<Page<Record<string, unknown>>> {
  const sort: SearchSort = "relevance";
  const { cursor, audience, snapshotAt } = await cursorContext(context, fingerprint);
  const result = await context.db.prepare(
    `WITH matches AS (
       SELECT id, bm25(search_users, 8.0, 4.0, 1.0) AS relevance
       FROM search_users WHERE search_users MATCH ?
     ), ranked AS (
       SELECT u.id, matches.relevance, matches.relevance AS primary_sort, u.created_at,
              u.username, u.display_name, substr(u.bio, 1, 240) AS bio,
              u.avatar_url, u.avatar_media_id, u.karma, u.updated_at
       FROM matches JOIN users u ON u.id = matches.id WHERE u.created_at <= ?
     )
     SELECT * FROM ranked WHERE ${afterCursor(sort, "ranked")}
     ORDER BY ${orderBy(sort)} LIMIT ?`,
  ).bind(match, snapshotAt, ...cursorBindings(cursor), limit + 1).all<ProfileRow>();
  const page = await pageCursor(context, result.results, limit, audience, fingerprint, snapshotAt);
  return {
    items: page.rows.map((row) => ({
      type: "profile",
      id: row.id,
      username: row.username,
      displayName: row.display_name,
      bio: row.bio,
      avatarUrl: avatarUrl(context, row),
      karma: row.karma,
    })),
    nextCursor: page.nextCursor,
  };
}

async function searchComments(
  context: RequestContext,
  match: string,
  sort: SearchSort,
  time: SearchTime,
  safe: boolean,
  subreddit: string | null,
  limit: number,
  fingerprint: string,
): Promise<Page<Record<string, unknown>>> {
  const effectiveSort: SearchSort = sort === "hot" || sort === "comments" ? "relevance" : sort;
  const { cursor, audience, snapshotAt } = await cursorContext(context, fingerprint);
  const primary = effectiveSort === "relevance" ? "matches.relevance"
    : effectiveSort === "top" ? "c.score" : "c.created_at";
  const result = await context.db.prepare(
    `WITH matches AS (
       SELECT id, bm25(search_comments, 1.0) AS relevance
       FROM search_comments WHERE search_comments MATCH ?
     ), ranked AS (
       SELECT c.id, matches.relevance, ${primary} AS primary_sort, c.created_at,
              c.post_id, c.parent_id, u.username AS author_username,
              substr(c.body, 1, 500) AS body, c.score, COALESCE(v.value, 0) AS viewer_vote,
              p.title AS post_title, s.name AS subreddit_name,
              p.score AS post_score, p.comment_count AS post_comment_count
       FROM matches
       JOIN comments c ON c.id = matches.id
       JOIN posts p ON p.id = c.post_id
       JOIN subreddits s ON s.id = p.subreddit_id
       JOIN users u ON u.id = c.author_id
       LEFT JOIN subreddit_members visibility
         ON visibility.subreddit_id = s.id AND visibility.user_id = ?
       LEFT JOIN votes v
         ON v.target_type = 'comment' AND v.target_id = c.id AND v.user_id = ?
       WHERE c.deleted_at IS NULL AND p.deleted_at IS NULL
         AND c.created_at BETWEEN ? AND ?
         AND ${visibleSubreddit("s", "visibility")}
         AND (? = '' OR s.name = ?)
         AND (? = 0 OR p.is_mature = 0)
     )
     SELECT * FROM ranked WHERE ${afterCursor(effectiveSort, "ranked")}
     ORDER BY ${orderBy(effectiveSort)} LIMIT ?`,
  ).bind(
    match,
    context.viewer?.id ?? "",
    context.viewer?.id ?? "",
    timeFloor(time, snapshotAt),
    snapshotAt,
    subreddit ?? "",
    subreddit ?? "",
    safe ? 1 : 0,
    ...cursorBindings(cursor),
    limit + 1,
  ).all<CommentSearchRow>();
  const page = await pageCursor(context, result.results, limit, audience, fingerprint, snapshotAt);
  return {
    items: page.rows.map((row) => ({
      type: "comment",
      id: row.id,
      postId: row.post_id,
      parentId: row.parent_id,
      author: row.author_username,
      body: row.body,
      score: row.score,
      viewerVote: row.viewer_vote,
      createdAt: row.created_at,
      post: {
        title: row.post_title,
        subreddit: row.subreddit_name,
        score: row.post_score,
        commentCount: row.post_comment_count,
      },
    })),
    nextCursor: page.nextCursor,
  };
}

async function validateScope(context: RequestContext): Promise<string | null> {
  const raw = context.url.searchParams.get("subreddit")?.trim().replace(/^r\//iu, "").toLowerCase() ?? "";
  if (!raw) return null;
  const access = await requireSubredditByName(context.db, raw, context.viewer?.id ?? null);
  assertCanRead(access);
  return access.name;
}

export async function search(context: RequestContext): Promise<Response> {
  const query = queryInput(context);
  const type = enumParam(context.url.searchParams.get("type"), "all", TYPES, "type");
  const sort = enumParam(context.url.searchParams.get("sort"), "relevance", SORTS, "sort");
  const time = enumParam(context.url.searchParams.get("time"), "all", TIMES, "time");
  const safe = context.url.searchParams.get("safe") !== "false";
  const limit = boundedLimit(context.url.searchParams.get("limit"));
  const subreddit = await validateScope(context);
  const fingerprint = (await keyedHash(context.env.CURSOR_SECRET,
    [query.match, type, sort, time, safe ? "1" : "0", subreddit ?? ""].join("\u001f"))).slice(0, 24);

  if (type === "all") {
    if (context.url.searchParams.has("cursor")) {
      throw new AppError(422, "cursor_not_supported", "All results use section previews; paginate a result tab");
    }
    const [communities, posts, comments, media, profiles] = await Promise.all([
      searchCommunities(context, query.match, Math.min(3, limit), `${fingerprint}:communities`),
      searchPosts(context, query.match, sort, time, safe, subreddit, Math.min(10, limit), `${fingerprint}:posts`),
      searchComments(context, query.match, sort, time, safe, subreddit, Math.min(3, limit), `${fingerprint}:comments`),
      searchPosts(context, query.match, sort, time, safe, subreddit, Math.min(3, limit), `${fingerprint}:media`, true),
      searchProfiles(context, query.match, Math.min(3, limit), `${fingerprint}:profiles`),
    ]);
    return jsonResponse({
      query: query.raw,
      type,
      sections: {
        communities: communities.items,
        posts: posts.items,
        comments: comments.items,
        media: media.items,
        profiles: profiles.items,
      },
      nextCursor: null,
    });
  }

  let page: Page<Record<string, unknown>>;
  if (type === "communities") page = await searchCommunities(context, query.match, limit, fingerprint);
  else if (type === "profiles") page = await searchProfiles(context, query.match, limit, fingerprint);
  else if (type === "comments") {
    page = await searchComments(context, query.match, sort, time, safe, subreddit, limit, fingerprint);
  } else {
    page = await searchPosts(context, query.match, sort, time, safe, subreddit, limit, fingerprint, type === "media");
  }
  return jsonResponse({ query: query.raw, type, items: page.items, nextCursor: page.nextCursor });
}

export async function typeahead(context: RequestContext): Promise<Response> {
  const query = queryInput(context);
  const limit = boundedLimit(context.url.searchParams.get("limit"), 8);
  const fingerprintBase = (await keyedHash(context.env.CURSOR_SECRET, `typeahead:${query.match}`)).slice(0, 24);
  const [communities, profiles, titleRows] = await Promise.all([
    searchCommunities(context, query.match, Math.min(5, limit), `${fingerprintBase}:communities`),
    searchProfiles(context, query.match, Math.min(3, limit), `${fingerprintBase}:profiles`),
    context.db.prepare(
      `SELECT p.title FROM search_posts
       JOIN posts p ON p.id = search_posts.id
       JOIN subreddits s ON s.id = p.subreddit_id
       LEFT JOIN subreddit_members visibility
         ON visibility.subreddit_id = s.id AND visibility.user_id = ?
       WHERE search_posts MATCH ? AND p.deleted_at IS NULL AND p.is_mature = 0
         AND ${visibleSubreddit("s", "visibility")}
       ORDER BY bm25(search_posts, 8.0, 1.0), p.rank_value DESC LIMIT ?`,
    ).bind(context.viewer?.id ?? "", query.match, Math.min(6, limit)).all<{ title: string }>(),
  ]);
  const seen = new Set<string>();
  const completions = [query.raw, ...titleRows.results.map((row) => row.title.trim())]
    .filter((value) => {
      const key = value.toLocaleLowerCase("en-US");
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    })
    .slice(0, limit);
  return jsonResponse({ query: query.raw, completions, communities: communities.items, profiles: profiles.items });
}

export async function discoverSearch(context: RequestContext): Promise<Response> {
  const viewerId = context.viewer?.id ?? "";
  const [posts, communities] = await Promise.all([
    context.db.prepare(
      `SELECT p.id, p.title, s.name AS subreddit, p.kind, p.score, p.comment_count, p.created_at
       FROM posts p JOIN subreddits s ON s.id = p.subreddit_id
       LEFT JOIN subreddit_members visibility
         ON visibility.subreddit_id = s.id AND visibility.user_id = ?
       WHERE p.deleted_at IS NULL AND p.is_mature = 0
         AND ${visibleSubreddit("s", "visibility")}
       ORDER BY p.rank_value DESC, p.id DESC LIMIT 8`,
    ).bind(viewerId).all<Record<string, string | number>>(),
    context.db.prepare(
      `SELECT s.id, s.name, s.display_name,
              (SELECT COUNT(*) FROM subreddit_members members
               WHERE members.subreddit_id = s.id
                 AND members.role IN ('subscriber', 'member', 'moderator', 'owner')) AS subscriber_count
       FROM subreddits s
       LEFT JOIN subreddit_members visibility
         ON visibility.subreddit_id = s.id AND visibility.user_id = ?
       WHERE ${visibleSubreddit("s", "visibility")}
       ORDER BY subscriber_count DESC, s.created_at DESC, s.id DESC LIMIT 8`,
    ).bind(viewerId).all<{ id: string; name: string; display_name: string; subscriber_count: number }>(),
  ]);
  return jsonResponse({
    trending: posts.results.map((row) => ({
      id: row.id,
      query: row.title,
      subreddit: row.subreddit,
      kind: row.kind,
      score: row.score,
      commentCount: row.comment_count,
      createdAt: row.created_at,
    })),
    communities: communities.results.map((row) => ({
      id: row.id,
      name: row.name,
      displayName: row.display_name,
      subscriberCount: row.subscriber_count,
    })),
  });
}
