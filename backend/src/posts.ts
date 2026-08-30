import { z } from "zod";
import {
  assertCanPost,
  assertCanRead,
  requireSubredditByName,
  subredditById,
} from "./access";
import { requireViewer } from "./auth";
import { AppError, isUniqueConstraint, jsonResponse, readJson } from "./http";
import { optimizedStreamPosterUrl, requireReadyMedia, signedImageUrl, signedMediaUrl } from "./media";
import type { RequestContext } from "./types";

const clientMutationId = z.string().trim().min(8).max(100);
const postKind = z.enum(["text", "image", "video", "link"]);
const createPostSchema = z.object({
  subreddit: z.string().trim().min(3).max(21),
  kind: postKind,
  title: z.string().trim().min(1).max(300),
  body: z.string().trim().min(1).max(40_000).optional(),
  url: z.string().url().max(2_048).optional(),
  mediaId: z.string().uuid().optional(),
  mediaIds: z.array(z.string().uuid()).min(1).max(20)
    .refine((ids) => new Set(ids).size === ids.length, "Gallery media IDs must be unique")
    .optional(),
  flairId: z.string().trim().min(1).max(128).optional(),
  clientMutationId,
}).strict().superRefine((value, context) => {
  if (value.kind === "link") {
    if (!value.url) context.addIssue({ code: "custom", message: "Link posts require url" });
    else if (!/^https?:\/\//iu.test(value.url)) context.addIssue({ code: "custom", message: "Only HTTP(S) links are supported" });
  }
  if (value.kind === "image" && !value.mediaId && !value.mediaIds) {
    context.addIssue({ code: "custom", message: "Image posts require mediaId or mediaIds" });
  }
  if (value.kind === "image" && value.mediaId && value.mediaIds && value.mediaIds[0] !== value.mediaId) {
    context.addIssue({ code: "custom", message: "mediaId must match the first gallery item" });
  }
  if (value.kind === "video" && !value.mediaId) {
    context.addIssue({ code: "custom", message: "Video posts require mediaId" });
  }
  if (value.kind !== "image" && value.mediaIds) {
    context.addIssue({ code: "custom", message: "mediaIds is supported only for image posts" });
  }
});

const reshareSchema = z.object({
  subreddit: z.string().trim().min(3).max(21),
  title: z.string().trim().min(1).max(300).optional(),
  clientMutationId,
}).strict();

export interface PostRow {
  id: string;
  subreddit_id: string;
  subreddit_name: string;
  subreddit_avatar_url?: string | null;
  subreddit_access_type: "public" | "restricted" | "private";
  author_id: string;
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
  upvotes: number;
  downvotes: number;
  comment_count: number;
  version: number;
  viewer_vote: number | null;
  created_at: number;
  updated_at: number;
  deleted_at: number | null;
  media_content_type: string | null;
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
  media_image_status: "not_applicable" | "waiting" | "ready" | "error" | null;
  media_etag: string | null;
}

interface GalleryMediaRow {
  id: string;
  kind: "image" | "video";
  content_type: string;
  width: number | null;
  height: number | null;
  duration_seconds: number | null;
  alt_text: string;
  stream_status: "not_applicable" | "waiting" | "processing" | "ready" | "error";
  stream_progress: number;
  hls_url: string | null;
  dash_url: string | null;
  thumbnail_url: string | null;
  preview_url: string | null;
  source_deleted_at: number | null;
  image_uid: string | null;
  image_status: "not_applicable" | "waiting" | "ready" | "error";
  etag: string | null;
  position: number;
}

function inputMediaIds(input: z.infer<typeof createPostSchema>): string[] {
  if (input.kind === "image") return input.mediaIds ?? [input.mediaId ?? ""];
  if (input.kind === "video") return [input.mediaId ?? ""];
  return [];
}

async function postMediaIds(context: RequestContext, postId: string): Promise<string[]> {
  const result = await context.db.prepare(
    "SELECT media_id FROM post_media WHERE post_id = ? ORDER BY position",
  ).bind(postId).all<{ media_id: string }>();
  return result.results.map((item) => item.media_id);
}

/** Ordered typed media payload shared by post detail, MediaFeed, and SDUI feed. */
export async function postMediaJson(
  context: RequestContext,
  postId: string,
  variant: "feed" | "detail",
) {
  const result = await context.db.prepare(
    `SELECT m.id, m.kind, m.content_type, m.width, m.height, m.duration_seconds,
            m.alt_text, m.stream_status, m.stream_progress, m.hls_url, m.dash_url,
            m.thumbnail_url, m.preview_url, m.source_deleted_at, m.image_uid,
            m.image_status, m.etag, pm.position
     FROM post_media pm
     JOIN media m ON m.id = pm.media_id
     WHERE pm.post_id = ?
     ORDER BY pm.position`,
  ).bind(postId).all<GalleryMediaRow>();

  return Promise.all(result.results.map(async (media) => {
    const streamReady = media.kind === "video" && media.stream_status === "ready" && media.hls_url !== null;
    const imageUrl = media.kind === "image" && media.image_uid
      ? await signedImageUrl(context, media.image_uid, variant)
      : null;
    const fallbackUrl = media.source_deleted_at === null
      ? await signedMediaUrl(context, media.id)
      : null;
    const width = media.width;
    const height = media.height;
    const ratio = width && height ? Math.max(0.25, Math.min(4, width / height)) : 16 / 9;
    return {
      id: media.id,
      contentType: media.content_type,
      width,
      height,
      durationSeconds: media.duration_seconds,
      altText: media.alt_text,
      url: imageUrl ?? (streamReady ? media.hls_url : fallbackUrl),
      zoomUrl: media.kind === "image" ? (imageUrl ?? fallbackUrl) : null,
      hlsUrl: streamReady ? media.hls_url : null,
      dashUrl: streamReady ? media.dash_url : null,
      posterUrl: optimizedStreamPosterUrl(
        media.thumbnail_url,
        width,
        height,
        media.duration_seconds,
      ),
      previewUrl: media.preview_url,
      fallbackUrl,
      deliveryStatus: media.kind === "image" ? media.image_status : media.stream_status,
      processingProgress: media.stream_progress ?? 0,
      cachePolicy: media.kind === "video" ? "segments_only" : "private_immutable",
      cacheKey: media.kind === "image"
        ? `image:${media.id}:${media.etag ?? "pending"}:${variant}`
        : `video:${media.id}:${media.etag ?? "pending"}`,
      placeholderColor: 0xff23386b,
      aspectRatio: ratio,
      position: media.position,
    };
  }));
}

async function findPostRow(
  context: RequestContext,
  postId: string,
): Promise<PostRow | null> {
  return context.db.prepare(
    `SELECT p.id, p.subreddit_id, s.name AS subreddit_name,
            s.avatar_url AS subreddit_avatar_url,
            s.access_type AS subreddit_access_type,
            p.author_id, u.username AS author_username, p.kind, p.title, p.body,
            p.url, p.media_id, p.flair_id, pf.text AS flair_text,
            pf.background_color AS flair_background_color, pf.text_color AS flair_text_color,
            p.crosspost_parent_id, p.score, p.upvotes,
            p.downvotes, p.comment_count, p.version, p.created_at, p.updated_at,
            p.deleted_at, v.value AS viewer_vote,
            m.content_type AS media_content_type, m.width AS media_width,
            m.height AS media_height, m.duration_seconds AS media_duration_seconds,
            m.alt_text AS media_alt_text, m.stream_status AS media_stream_status,
            m.stream_progress AS media_stream_progress, m.hls_url AS media_hls_url,
            m.dash_url AS media_dash_url, m.thumbnail_url AS media_thumbnail_url,
            m.preview_url AS media_preview_url, m.source_deleted_at AS media_source_deleted_at,
            m.image_uid AS media_image_uid, m.image_status AS media_image_status,
            m.etag AS media_etag
     FROM posts p
     JOIN subreddits s ON s.id = p.subreddit_id
     JOIN users u ON u.id = p.author_id
     LEFT JOIN votes v ON v.target_type = 'post' AND v.target_id = p.id AND v.user_id = ?
     LEFT JOIN media m ON m.id = p.media_id
     LEFT JOIN post_flairs pf ON pf.id = p.flair_id
     WHERE p.id = ? AND p.deleted_at IS NULL`,
  ).bind(context.viewer?.id ?? "", postId).first<PostRow>();
}

export async function requireVisiblePost(
  context: RequestContext,
  postId: string,
): Promise<PostRow> {
  const row = await findPostRow(context, postId);
  if (!row) throw new AppError(404, "post_not_found", "Post not found");
  const access = await subredditById(context.db, row.subreddit_id, context.viewer?.id ?? null);
  if (!access) throw new AppError(404, "post_not_found", "Post not found");
  assertCanRead(access);
  return row;
}

export async function postJson(context: RequestContext, row: PostRow) {
  const mediaItems = row.media_id ? await postMediaJson(context, row.id, "detail") : [];
  return {
    id: row.id,
    subreddit: row.subreddit_name,
    subredditAvatarUrl: row.subreddit_avatar_url ?? null,
    author: row.author_username,
    authorId: row.author_id,
    kind: row.kind,
    title: row.title,
    body: row.body,
    url: row.url,
    flair: row.flair_id && row.flair_text && row.flair_background_color && row.flair_text_color ? {
      id: row.flair_id,
      text: row.flair_text,
      backgroundColor: row.flair_background_color,
      textColor: row.flair_text_color,
    } : null,
    media: mediaItems[0] ?? null,
    mediaItems,
    crosspostParentId: row.crosspost_parent_id,
    score: row.score,
    upvotes: row.upvotes,
    downvotes: row.downvotes,
    commentCount: row.comment_count,
    viewerVote: row.viewer_vote ?? 0,
    version: row.version,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

async function existingMutationPost(context: RequestContext, mutationId: string): Promise<PostRow | null> {
  const id = await context.db.prepare(
    "SELECT id FROM posts WHERE author_id = ? AND client_mutation_id = ?",
  ).bind(requireViewer(context).id, mutationId).first<string>("id");
  return id ? findPostRow(context, id) : null;
}

async function assertSameCreatePost(
  context: RequestContext,
  row: PostRow,
  input: z.infer<typeof createPostSchema>,
): Promise<void> {
  const expectedBody = input.kind === "text" ? input.body?.trim() ?? "" : input.body?.trim() || null;
  const expectedUrl = input.kind === "link" ? input.url ?? null : null;
  const expectedMediaIds = inputMediaIds(input);
  const actualMediaIds = row.media_id ? await postMediaIds(context, row.id) : [];
  if (
    row.crosspost_parent_id !== null
    || row.subreddit_name !== input.subreddit.toLowerCase()
    || row.kind !== input.kind
    || row.title !== input.title
    || row.body !== expectedBody
    || row.url !== expectedUrl
    || row.flair_id !== (input.flairId ?? null)
    || actualMediaIds.length !== expectedMediaIds.length
    || actualMediaIds.some((id, index) => id !== expectedMediaIds[index])
  ) {
    throw new AppError(409, "mutation_id_reused", "clientMutationId was already used for a different post");
  }
}

function assertSameReshare(
  row: PostRow,
  sourcePostId: string,
  input: z.infer<typeof reshareSchema>,
): void {
  if (
    row.crosspost_parent_id !== sourcePostId
    || row.subreddit_name !== input.subreddit.toLowerCase()
    || (input.title !== undefined && row.title !== input.title)
  ) {
    throw new AppError(409, "mutation_id_reused", "clientMutationId was already used for a different post");
  }
}

async function insertPost(
  context: RequestContext,
  values: {
    id: string;
    subredditId: string;
    authorId: string;
    kind: PostRow["kind"];
    title: string;
    body: string | null;
    url: string | null;
    mediaId: string | null;
    mediaIds: string[];
    flairId: string | null;
    crosspostParentId: string | null;
    clientMutationId: string;
    now: number;
  },
): Promise<void> {
  await context.db.batch([
    context.db.prepare(
      `INSERT INTO posts (
         id, subreddit_id, author_id, kind, title, body, url, media_id,
         flair_id, crosspost_parent_id, client_mutation_id, created_at, updated_at
       ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).bind(
      values.id,
      values.subredditId,
      values.authorId,
      values.kind,
      values.title,
      values.body,
      values.url,
      values.mediaId,
      values.flairId,
      values.crosspostParentId,
      values.clientMutationId,
      values.now,
      values.now,
    ),
    context.db.prepare(
      `INSERT INTO votes (
         user_id, target_type, target_id, value, version, last_mutation_id, updated_at
       ) VALUES (?, 'post', ?, 1, 1, ?, ?)`,
    ).bind(values.authorId, values.id, `author-seed:${values.id}`, values.now),
    ...values.mediaIds.map((mediaId, position) => context.db.prepare(
      "INSERT INTO post_media (post_id, media_id, position) VALUES (?, ?, ?)",
    ).bind(values.id, mediaId, position)),
  ]);
}

export async function createPost(context: RequestContext): Promise<Response> {
  const viewer = requireViewer(context);
  const input = await readJson(context.request, createPostSchema);
  const prior = await existingMutationPost(context, input.clientMutationId);
  if (prior) {
    await assertSameCreatePost(context, prior, input);
    return jsonResponse({ post: await postJson(context, prior), replayed: true });
  }

  const access = await requireSubredditByName(context.db, input.subreddit, viewer.id);
  assertCanPost(access);
  if (input.flairId) {
    const flair = await context.db.prepare(
      "SELECT id FROM post_flairs WHERE id = ? AND subreddit_id = ? AND enabled = 1",
    ).bind(input.flairId, access.id).first<{ id: string }>();
    if (!flair) throw new AppError(422, "invalid_post_flair", "Choose a flair from the selected community");
  }
  const mediaIds = inputMediaIds(input);
  if (input.kind === "image" || input.kind === "video") {
    for (const mediaId of mediaIds) {
      await requireReadyMedia(context.db, mediaId, viewer.id, input.kind);
    }
  }
  const id = crypto.randomUUID();
  const now = Date.now();
  try {
    await insertPost(context, {
      id,
      subredditId: access.id,
      authorId: viewer.id,
      kind: input.kind,
      title: input.title,
      // The original schema uses non-null body as the text-post discriminator.
      // Empty string preserves that invariant while allowing Reddit-style
      // title-only posts; clients render it exactly like an absent body.
      body: input.kind === "text" ? input.body?.trim() ?? "" : input.body?.trim() || null,
      url: input.kind === "link" ? input.url ?? null : null,
      mediaId: mediaIds[0] ?? null,
      mediaIds,
      flairId: input.flairId ?? null,
      crosspostParentId: null,
      clientMutationId: input.clientMutationId,
      now,
    });
  } catch (error) {
    if (isUniqueConstraint(error)) {
      const replay = await existingMutationPost(context, input.clientMutationId);
      if (replay) {
        await assertSameCreatePost(context, replay, input);
        return jsonResponse({ post: await postJson(context, replay), replayed: true });
      }
    }
    throw error;
  }
  const row = await findPostRow(context, id);
  if (!row) throw new AppError(500, "post_write_failed", "Post was not visible after creation");
  return jsonResponse({ post: await postJson(context, row), replayed: false }, { status: 201 });
}

export async function getPost(context: RequestContext, postId: string): Promise<Response> {
  const row = await requireVisiblePost(context, postId);
  return jsonResponse({ post: await postJson(context, row) });
}

export async function resharePost(context: RequestContext, postId: string): Promise<Response> {
  const viewer = requireViewer(context);
  const input = await readJson(context.request, reshareSchema);
  const prior = await existingMutationPost(context, input.clientMutationId);
  if (prior) {
    assertSameReshare(prior, postId, input);
    return jsonResponse({ post: await postJson(context, prior), replayed: true });
  }
  const source = await requireVisiblePost(context, postId);
  const sourceAccess = await subredditById(context.db, source.subreddit_id, viewer.id);
  if (!sourceAccess) throw new AppError(404, "post_not_found", "Post not found");
  const targetAccess = await requireSubredditByName(context.db, input.subreddit, viewer.id);
  assertCanPost(targetAccess);
  if (sourceAccess.accessType === "private" && sourceAccess.id !== targetAccess.id) {
    throw new AppError(403, "private_crosspost", "Private subreddit posts cannot be reshared elsewhere");
  }

  const id = crypto.randomUUID();
  const now = Date.now();
  const sourceMediaIds = source.media_id ? await postMediaIds(context, source.id) : [];
  try {
    await insertPost(context, {
      id,
      subredditId: targetAccess.id,
      authorId: viewer.id,
      kind: source.kind,
      title: input.title ?? source.title,
      body: source.body,
      url: source.url,
      mediaId: source.media_id,
      mediaIds: sourceMediaIds,
      flairId: null,
      crosspostParentId: source.id,
      clientMutationId: input.clientMutationId,
      now,
    });
  } catch (error) {
    if (isUniqueConstraint(error)) {
      const replay = await existingMutationPost(context, input.clientMutationId);
      if (replay) {
        assertSameReshare(replay, postId, input);
        return jsonResponse({ post: await postJson(context, replay), replayed: true });
      }
    }
    throw error;
  }
  const row = await findPostRow(context, id);
  if (!row) throw new AppError(500, "post_write_failed", "Reshare was not visible after creation");
  context.execution.waitUntil(context.env.POST_ROOMS.getByName(source.id).publish({
    type: "post.reshared",
    postId: source.id,
    actorId: viewer.id,
    entityId: id,
    occurredAt: now,
    payload: { targetSubreddit: targetAccess.name },
  }));
  return jsonResponse({ post: await postJson(context, row), replayed: false }, { status: 201 });
}
