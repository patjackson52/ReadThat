import { z } from "zod";
import { requireViewer } from "./auth";
import { hmac, keyedHash, randomToken, secureTextEqual } from "./crypto";
import { AppError, jsonResponse, readBoundedBody, readJson } from "./http";
import type { Database, RequestContext } from "./types";

const PART_SIZE = 8 * 1024 * 1024;
const MULTIPART_THRESHOLD = 10 * 1024 * 1024;
const MAX_IMAGE_BYTES = 20 * 1024 * 1024;
const MAX_VIDEO_BYTES = 100 * 1024 * 1024;
const UPLOAD_TTL_MS = 60 * 60 * 1_000;
const STREAM_IMPORT_TTL_MS = 2 * 60 * 60 * 1_000;
const WEBHOOK_MAX_AGE_SECONDS = 5 * 60;
const STREAM_POSTER_MAX_EDGE_PX = 1_080;
const SIGNED_MEDIA_EXPIRY_BUCKET_MS = 60_000;

const createUploadSchema = z.object({
  kind: z.enum(["image", "video"]),
  contentType: z.string().trim().min(1).max(100),
  byteSize: z.number().int().positive(),
  width: z.number().int().positive().max(20_000).optional(),
  height: z.number().int().positive().max(20_000).optional(),
  durationSeconds: z.number().int().nonnegative().max(24 * 60 * 60).optional(),
  altText: z.string().trim().max(1_000).default(""),
}).strict();

interface MediaRow {
  id: string;
  uploader_id: string;
  kind: "image" | "video";
  content_type: string;
  byte_size: number;
  r2_key: string;
  status: "pending" | "ready" | "failed" | "aborted";
  upload_mode: "single" | "multipart";
  r2_upload_id: string | null;
  upload_token_hash: string;
  upload_expires_at: number;
  etag: string | null;
  width: number | null;
  height: number | null;
  duration_seconds: number | null;
  alt_text: string;
  created_at: number;
  completed_at: number | null;
  delivery_provider: "r2" | "stream" | "images";
  stream_uid: string | null;
  stream_status: "not_applicable" | "waiting" | "processing" | "ready" | "error";
  stream_progress: number;
  hls_url: string | null;
  dash_url: string | null;
  thumbnail_url: string | null;
  preview_url: string | null;
  stream_error_code: string | null;
  stream_error_message: string | null;
  source_deleted_at: number | null;
  image_uid: string | null;
  image_status: "not_applicable" | "waiting" | "ready" | "error";
  image_error_message: string | null;
}

interface MediaPartRow {
  part_number: number;
  etag: string;
  byte_size: number;
}

const contentTypes = {
  image: new Set(["image/jpeg", "image/png", "image/webp", "image/avif", "image/gif"]),
  video: new Set(["video/mp4", "video/webm", "video/quicktime"]),
} as const;

function extension(contentType: string): string {
  return ({
    "image/jpeg": "jpg",
    "image/png": "png",
    "image/webp": "webp",
    "image/avif": "avif",
    "image/gif": "gif",
    "video/mp4": "mp4",
    "video/webm": "webm",
    "video/quicktime": "mov",
  } satisfies Record<string, string>)[contentType] ?? "bin";
}

/**
 * The preview must be the same frame where playback begins. This makes the decoded preview a
 * continuity surface rather than a representative thumbnail that visibly jumps at autoplay.
 */
export function streamThumbnailTimestampPct(_durationSeconds: number | null): number {
  return 0;
}

function nearestEvenPixel(value: number): number {
  return Math.max(2, Math.round(value / 2) * 2);
}

/**
 * Normalize existing videos too: Stream can generate the first frame on demand even when the
 * stored default thumbnail points later into the clip. Exact-aspect dimensions avoid the default
 * square crop while bounding mobile decode and transfer cost.
 */
export function optimizedStreamPosterUrl(
  thumbnailUrl: string | null,
  width: number | null,
  height: number | null,
  _durationSeconds: number | null,
): string | null {
  if (!thumbnailUrl) return null;
  try {
    const poster = new URL(thumbnailUrl);
    if (!poster.pathname.includes("/thumbnails/thumbnail.")) return thumbnailUrl;

    poster.searchParams.set("time", "0s");

    if (width !== null && height !== null && width > 0 && height > 0) {
      const scale = Math.min(1, STREAM_POSTER_MAX_EDGE_PX / Math.max(width, height));
      // Stream rejects odd width/height query values. Round to the nearest even pixel while
      // retaining the source aspect ratio and the bounded mobile decode size.
      poster.searchParams.set("width", String(nearestEvenPixel(width * scale)));
      poster.searchParams.set("height", String(nearestEvenPixel(height * scale)));
      poster.searchParams.set("fit", "crop");
    }
    return poster.toString();
  } catch {
    return thumbnailUrl;
  }
}

function mediaJson(row: MediaRow) {
  return {
    id: row.id,
    kind: row.kind,
    contentType: row.content_type,
    byteSize: row.byte_size,
    status: row.status,
    width: row.width,
    height: row.height,
    durationSeconds: row.duration_seconds,
    altText: row.alt_text,
    etag: row.etag,
    createdAt: row.created_at,
    completedAt: row.completed_at,
    delivery: {
      provider: row.delivery_provider,
      status: row.kind === "image" ? row.image_status : row.stream_status,
      progress: row.kind === "image" ? (row.image_status === "ready" ? 100 : 0) : row.stream_progress,
      hlsUrl: row.hls_url,
      dashUrl: row.dash_url,
      thumbnailUrl: row.thumbnail_url,
      previewUrl: row.preview_url,
      errorCode: row.stream_error_code,
      errorMessage: row.stream_error_message,
      imageId: row.image_uid,
      imageErrorMessage: row.image_error_message,
    },
  };
}

async function getMedia(db: Database, id: string): Promise<MediaRow | null> {
  return db.prepare(
     `SELECT id, uploader_id, kind, content_type, byte_size, r2_key, status,
            upload_mode, r2_upload_id, upload_token_hash, upload_expires_at,
            etag, width, height, duration_seconds, alt_text, created_at, completed_at,
            delivery_provider, stream_uid, stream_status, stream_progress,
            hls_url, dash_url, thumbnail_url, preview_url, stream_error_code,
            stream_error_message, source_deleted_at
            , image_uid, image_status, image_error_message
     FROM media WHERE id = ?`,
  ).bind(id).first<MediaRow>();
}

async function authorizeUpload(context: RequestContext, id: string): Promise<MediaRow> {
  const viewer = requireViewer(context);
  const token = context.request.headers.get("x-upload-token");
  if (!token) throw new AppError(401, "upload_token_required", "X-Upload-Token is required");
  const row = await getMedia(context.db, id);
  if (!row || row.uploader_id !== viewer.id) {
    throw new AppError(404, "upload_not_found", "Upload not found");
  }
  if (row.status !== "pending") {
    throw new AppError(409, "upload_not_pending", `Upload is already ${row.status}`);
  }
  if (row.upload_expires_at <= Date.now()) {
    throw new AppError(410, "upload_expired", "Upload session expired");
  }
  const tokenHash = await keyedHash(context.env.MEDIA_SIGNING_SECRET, `upload:${token}`);
  if (!(await secureTextEqual(tokenHash, row.upload_token_hash))) {
    throw new AppError(401, "invalid_upload_token", "Upload token is invalid");
  }
  return row;
}

function validateBodyMetadata(request: Request, row: MediaRow, expectedLength: number): void {
  const contentLength = Number(request.headers.get("content-length") ?? "NaN");
  if (!Number.isSafeInteger(contentLength) || contentLength !== expectedLength) {
    throw new AppError(400, "content_length_mismatch", `Content-Length must be ${expectedLength}`);
  }
  if (request.headers.get("content-type")?.split(";", 1)[0]?.trim() !== row.content_type) {
    throw new AppError(415, "content_type_mismatch", `Content-Type must be ${row.content_type}`);
  }
  if (!request.body) throw new AppError(400, "missing_body", "Upload body is required");
}

async function markComplete(context: RequestContext, row: MediaRow, object: R2Object): Promise<Response> {
  if (object.size !== row.byte_size) {
    await context.env.MEDIA.delete(row.r2_key);
    await context.db.prepare("UPDATE media SET status = 'failed' WHERE id = ?").bind(row.id).run();
    throw new AppError(422, "uploaded_size_mismatch", "Uploaded object size does not match the declared size");
  }
  const completedAt = Date.now();
  await context.db.prepare(
    `UPDATE media SET status = 'ready', etag = ?, completed_at = ?,
       stream_status = CASE WHEN kind = 'video' THEN 'waiting' ELSE stream_status END
     WHERE id = ? AND status = 'pending'`,
  ).bind(object.etag, completedAt, row.id).run();
  let completeRow: MediaRow = {
    ...row,
    status: "ready",
    etag: object.etag,
    completed_at: completedAt,
    stream_status: row.kind === "video" ? "waiting" : row.stream_status,
  };
  if (completeRow.kind === "video" && context.env.VIDEO_TRANSCODING === "stream") {
    completeRow = await beginStreamTranscode(context, completeRow);
  } else if (completeRow.kind === "image" && context.env.IMAGE_DELIVERY === "images") {
    completeRow = await beginImageDelivery(context, completeRow);
  }
  return jsonResponse({ media: mediaJson(completeRow) });
}

export async function createUpload(context: RequestContext): Promise<Response> {
  const viewer = requireViewer(context);
  const input = await readJson(context.request, createUploadSchema);
  if (!contentTypes[input.kind].has(input.contentType)) {
    throw new AppError(415, "unsupported_media_type", `Unsupported ${input.kind} content type`);
  }
  const maxBytes = input.kind === "image" ? MAX_IMAGE_BYTES : MAX_VIDEO_BYTES;
  if (input.byteSize > maxBytes) {
    throw new AppError(413, "media_too_large", `${input.kind} uploads are limited to ${maxBytes} bytes`);
  }
  if (input.kind === "video" && input.durationSeconds === undefined) {
    throw new AppError(422, "duration_required", "Video durationSeconds is required");
  }

  const id = crypto.randomUUID();
  const uploadToken = randomToken(32);
  const uploadTokenHash = await keyedHash(context.env.MEDIA_SIGNING_SECRET, `upload:${uploadToken}`);
  const key = `${viewer.id}/${id}.${extension(input.contentType)}`;
  // A video is multipart whenever it can contain at least two valid R2 parts.
  // This is the mobile resume boundary; an interrupted upload retries one 8 MiB
  // part instead of restarting a 100 MiB file.
  const uploadMode = input.byteSize >= (input.kind === "video" ? PART_SIZE : MULTIPART_THRESHOLD)
    ? "multipart"
    : "single";
  const multipart = uploadMode === "multipart"
    ? await context.env.MEDIA.createMultipartUpload(key, {
      httpMetadata: { contentType: input.contentType, cacheControl: "private, max-age=31536000, immutable" },
      customMetadata: { mediaId: id, uploaderId: viewer.id },
    })
    : null;
  const now = Date.now();
  const expiresAt = now + UPLOAD_TTL_MS;
  await context.db.prepare(
    `INSERT INTO media (
       id, uploader_id, kind, content_type, byte_size, r2_key, status,
       upload_mode, r2_upload_id, upload_token_hash, upload_expires_at,
       width, height, duration_seconds, alt_text, created_at, stream_status
       , image_status
     ) VALUES (?, ?, ?, ?, ?, ?, 'pending', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).bind(
    id,
    viewer.id,
    input.kind,
    input.contentType,
    input.byteSize,
    key,
    uploadMode,
    multipart?.uploadId ?? null,
    uploadTokenHash,
    expiresAt,
    input.width ?? null,
    input.height ?? null,
    input.durationSeconds ?? null,
    input.altText,
    now,
    input.kind === "video" ? "waiting" : "not_applicable",
    input.kind === "image" ? "waiting" : "not_applicable",
  ).run();

  return jsonResponse({
    upload: {
      id,
      mode: uploadMode,
      uploadToken,
      expiresAt,
      partSize: uploadMode === "multipart" ? PART_SIZE : null,
      partCount: uploadMode === "multipart" ? Math.ceil(input.byteSize / PART_SIZE) : 1,
      uploadPath: uploadMode === "single"
        ? `/v1/media/uploads/${id}`
        : `/v1/media/uploads/${id}/parts/{partNumber}`,
      completePath: uploadMode === "multipart" ? `/v1/media/uploads/${id}/complete` : null,
    },
  }, { status: 201 });
}

export async function uploadSingle(context: RequestContext, id: string): Promise<Response> {
  const row = await authorizeUpload(context, id);
  if (row.upload_mode !== "single") {
    throw new AppError(409, "multipart_required", "Use multipart part uploads for this media");
  }
  validateBodyMetadata(context.request, row, row.byte_size);
  const body = context.request.body;
  if (!body) throw new AppError(400, "missing_body", "Upload body is required");
  const object = await context.env.MEDIA.put(row.r2_key, body, {
    httpMetadata: { contentType: row.content_type, cacheControl: "private, max-age=31536000, immutable" },
    customMetadata: { mediaId: row.id, uploaderId: row.uploader_id },
  });
  return markComplete(context, row, object);
}

export async function uploadPart(
  context: RequestContext,
  id: string,
  partNumberValue: string,
): Promise<Response> {
  const row = await authorizeUpload(context, id);
  if (row.upload_mode !== "multipart" || !row.r2_upload_id) {
    throw new AppError(409, "single_upload_required", "This is not a multipart upload");
  }
  const partNumber = Number(partNumberValue);
  const partCount = Math.ceil(row.byte_size / PART_SIZE);
  if (!Number.isInteger(partNumber) || partNumber < 1 || partNumber > partCount) {
    throw new AppError(422, "invalid_part_number", `Part number must be between 1 and ${partCount}`);
  }
  const expectedLength = partNumber === partCount
    ? row.byte_size - PART_SIZE * (partCount - 1)
    : PART_SIZE;
  validateBodyMetadata(context.request, row, expectedLength);

  const multipart = context.env.MEDIA.resumeMultipartUpload(row.r2_key, row.r2_upload_id);
  const body = context.request.body;
  if (!body) throw new AppError(400, "missing_body", "Upload body is required");
  const uploaded = await multipart.uploadPart(partNumber, body);
  await context.db.prepare(
    `INSERT INTO media_parts (media_id, part_number, etag, byte_size, created_at)
     VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(media_id, part_number) DO UPDATE SET
       etag = excluded.etag, byte_size = excluded.byte_size, created_at = excluded.created_at`,
  ).bind(row.id, partNumber, uploaded.etag, expectedLength, Date.now()).run();
  return jsonResponse({ part: { partNumber, etag: uploaded.etag, byteSize: expectedLength } });
}

export async function completeMultipart(context: RequestContext, id: string): Promise<Response> {
  const row = await authorizeUpload(context, id);
  if (row.upload_mode !== "multipart" || !row.r2_upload_id) {
    throw new AppError(409, "single_upload_required", "This is not a multipart upload");
  }
  const expectedCount = Math.ceil(row.byte_size / PART_SIZE);
  const result = await context.db.prepare(
    `SELECT part_number, etag, byte_size FROM media_parts WHERE media_id = ? ORDER BY part_number`,
  ).bind(row.id).all<MediaPartRow>();
  const parts = result.results;
  if (parts.length !== expectedCount || parts.some((part, index) => part.part_number !== index + 1)) {
    throw new AppError(409, "parts_incomplete", `Expected ${expectedCount} contiguous uploaded parts`);
  }
  const totalSize = parts.reduce((sum, part) => sum + part.byte_size, 0);
  if (totalSize !== row.byte_size) {
    throw new AppError(409, "parts_size_mismatch", "Uploaded part sizes do not match the declared media size");
  }
  const multipart = context.env.MEDIA.resumeMultipartUpload(row.r2_key, row.r2_upload_id);
  const object = await multipart.complete(parts.map(({ part_number, etag }) => ({
    partNumber: part_number,
    etag,
  })));
  return markComplete(context, row, object);
}

export async function abortUpload(context: RequestContext, id: string): Promise<Response> {
  const row = await authorizeUpload(context, id);
  if (row.upload_mode === "multipart" && row.r2_upload_id) {
    await context.env.MEDIA.resumeMultipartUpload(row.r2_key, row.r2_upload_id).abort();
  } else {
    await context.env.MEDIA.delete(row.r2_key);
  }
  await context.db.prepare("UPDATE media SET status = 'aborted' WHERE id = ?").bind(row.id).run();
  return new Response(null, { status: 204 });
}

export async function signedMediaUrl(
  context: RequestContext,
  mediaId: string,
  ttlMs = 10 * 60 * 1_000,
): Promise<string> {
  const expiresAt = Math.ceil((Date.now() + ttlMs) / SIGNED_MEDIA_EXPIRY_BUCKET_MS)
    * SIGNED_MEDIA_EXPIRY_BUCKET_MS;
  const signature = await keyedHash(context.env.MEDIA_SIGNING_SECRET, `media:${mediaId}:${expiresAt}`);
  return `${context.url.origin}/v1/media/${encodeURIComponent(mediaId)}?expires=${expiresAt}&signature=${encodeURIComponent(signature)}`;
}

/**
 * Sign the canonical Images CDN URL with the account key kept in Worker Secrets.
 * Expiry is bucketed so feed refreshes reuse the same URL and edge/client cache key.
 */
export async function signedImageUrl(
  context: RequestContext,
  imageUid: string,
  variant: "feed" | "detail",
): Promise<string> {
  const expiresAt = Math.ceil((Math.floor(Date.now() / 1_000) + 60 * 60) / (60 * 60)) * (60 * 60);
  const url = new URL(
    `https://imagedelivery.net/${encodeURIComponent(context.env.IMAGES_ACCOUNT_HASH)}`
      + `/${encodeURIComponent(imageUid)}/${variant}`,
  );
  url.searchParams.set("exp", String(expiresAt));
  const signature = await hmac(
    context.env.IMAGES_SIGNING_KEY,
    `${url.pathname}?${url.searchParams.toString()}`,
  );
  url.searchParams.set("sig", hex(signature));
  return url.toString();
}

interface AvatarImageRow {
  id: string;
  delivery_provider: "r2" | "images";
  image_uid: string | null;
}

/**
 * Keeps a stable, versioned profile URL in API payloads while rotating the
 * short-lived delivery signature at the edge. User-uploaded avatars continue
 * to use Cloudflare Images, while trusted fixtures may retain their R2 source.
 * Avatar ownership is established when users.avatar_media_id is updated,
 * never from client URLs.
 */
export async function serveUserAvatar(
  context: RequestContext,
  requestedUsername: string,
): Promise<Response> {
  const row = await context.db.prepare(
    `SELECT m.id, m.delivery_provider, m.image_uid
     FROM users u
     JOIN media m ON m.id = u.avatar_media_id
     WHERE u.username = ? AND m.kind = 'image' AND m.status = 'ready'
       AND (
         (m.delivery_provider = 'images' AND m.image_status = 'ready')
         OR m.delivery_provider = 'r2'
       )`,
  ).bind(requestedUsername.toLowerCase()).first<AvatarImageRow>();
  if (!row) throw new AppError(404, "avatar_not_found", "Profile image not found");

  const location = row.delivery_provider === "images" && row.image_uid
    ? await signedImageUrl(context, row.image_uid, "feed")
    : await signedMediaUrl(context, row.id);
  return new Response(null, {
    status: 302,
    headers: {
      location,
      "cache-control": "public, max-age=300, stale-while-revalidate=86400",
    },
  });
}

function percent(value: string | undefined, ready: boolean): number {
  if (ready) return 100;
  const parsed = Math.round(Number(value ?? "0"));
  return Number.isFinite(parsed) ? Math.max(0, Math.min(99, parsed)) : 0;
}

async function evictSource(context: RequestContext, row: MediaRow): Promise<void> {
  if (row.source_deleted_at !== null) return;
  await context.env.MEDIA.delete(row.r2_key);
  await context.env.DB.prepare(
    "UPDATE media SET source_deleted_at = ? WHERE id = ? AND source_deleted_at IS NULL",
  ).bind(Date.now(), row.id).run();
}

async function beginImageDelivery(context: RequestContext, row: MediaRow): Promise<MediaRow> {
  try {
    const source = await context.env.MEDIA.get(row.r2_key);
    if (!source) throw new Error("R2 staging object disappeared before Images ingest");
    const image = await context.env.IMAGES.hosted.upload(source.body, {
      filename: `${row.id}.${extension(row.content_type)}`,
      requireSignedURLs: true,
      creator: row.uploader_id,
      metadata: {
        mediaId: row.id,
        contentType: row.content_type,
        altText: row.alt_text,
      },
    });
    const updated: MediaRow = {
      ...row,
      delivery_provider: "images",
      image_uid: image.id,
      image_status: "ready",
      image_error_message: null,
    };
    await context.db.prepare(
      `UPDATE media SET delivery_provider = 'images', image_uid = ?, image_status = 'ready',
         image_error_message = NULL WHERE id = ?`,
    ).bind(image.id, row.id).run();
    context.execution.waitUntil(evictSource(context, updated));
    return updated;
  } catch (error) {
    const message = error instanceof Error ? error.message : "Cloudflare Images ingest failed";
    await context.db.prepare(
      "UPDATE media SET image_status = 'error', image_error_message = ? WHERE id = ?",
    ).bind(message.slice(0, 500), row.id).run();
    console.error(JSON.stringify({
      level: "error",
      message: "image delivery ingest failed",
      mediaId: row.id,
      error: message,
    }));
    return {
      ...row,
      image_status: "error",
      image_error_message: message.slice(0, 500),
    };
  }
}

async function persistStreamVideo(
  context: RequestContext,
  row: MediaRow,
  video: StreamVideo,
): Promise<MediaRow> {
  const isReady = video.readyToStream && video.status.state === "ready";
  const isError = video.status.state === "error";
  const streamStatus: MediaRow["stream_status"] = isReady ? "ready" : isError ? "error" : "processing";
  const width = video.input.width > 0 ? video.input.width : row.width;
  const height = video.input.height > 0 ? video.input.height : row.height;
  const duration = video.duration >= 0 ? Math.round(video.duration) : row.duration_seconds;
  const thumbnailUrl = optimizedStreamPosterUrl(video.thumbnail || null, width, height, duration);
  const updated: MediaRow = {
    ...row,
    delivery_provider: "stream",
    stream_uid: video.id,
    stream_status: streamStatus,
    stream_progress: percent(video.status.pctComplete, isReady),
    hls_url: video.hlsPlaybackUrl || null,
    dash_url: video.dashPlaybackUrl || null,
    thumbnail_url: thumbnailUrl,
    preview_url: video.preview ?? null,
    stream_error_code: video.status.errorReasonCode || null,
    stream_error_message: video.status.errorReasonText || null,
    width,
    height,
    duration_seconds: duration,
  };
  await context.db.prepare(
    `UPDATE media SET delivery_provider = 'stream', stream_uid = ?, stream_status = ?,
       stream_progress = ?, hls_url = ?, dash_url = ?, thumbnail_url = ?, preview_url = ?,
       stream_error_code = ?, stream_error_message = ?, width = ?, height = ?, duration_seconds = ?
     WHERE id = ?`,
  ).bind(
    updated.stream_uid,
    updated.stream_status,
    updated.stream_progress,
    updated.hls_url,
    updated.dash_url,
    updated.thumbnail_url,
    updated.preview_url,
    updated.stream_error_code,
    updated.stream_error_message,
    updated.width,
    updated.height,
    updated.duration_seconds,
    updated.id,
  ).run();
  if (isReady) context.execution.waitUntil(evictSource(context, updated));
  return updated;
}

async function beginStreamTranscode(context: RequestContext, row: MediaRow): Promise<MediaRow> {
  try {
    const sourceUrl = await signedMediaUrl(context, row.id, STREAM_IMPORT_TTL_MS);
    const video = await context.env.STREAM.upload(sourceUrl, {
      creator: row.uploader_id,
      meta: {
        mediaId: row.id,
        name: `${row.id}.${extension(row.content_type)}`,
        contentType: row.content_type,
      },
      thumbnailTimestampPct: streamThumbnailTimestampPct(row.duration_seconds),
    });
    return persistStreamVideo(context, row, video);
  } catch (error) {
    const message = error instanceof Error ? error.message : "Cloudflare Stream import failed";
    await context.db.prepare(
      `UPDATE media SET stream_status = 'error', stream_progress = 0,
         stream_error_code = 'STREAM_IMPORT_FAILED', stream_error_message = ? WHERE id = ?`,
    ).bind(message.slice(0, 500), row.id).run();
    console.error(JSON.stringify({
      level: "error",
      message: "video stream import failed",
      mediaId: row.id,
      error: message,
    }));
    return {
      ...row,
      stream_status: "error",
      stream_progress: 0,
      stream_error_code: "STREAM_IMPORT_FAILED",
      stream_error_message: message.slice(0, 500),
    };
  }
}

export async function refreshVideoStatus(context: RequestContext, id: string): Promise<Response> {
  const viewer = requireViewer(context);
  const row = await getMedia(context.db, id);
  if (!row || row.uploader_id !== viewer.id || row.kind !== "video") {
    throw new AppError(404, "upload_not_found", "Video upload not found");
  }
  let updated = row;
  if (context.env.VIDEO_TRANSCODING === "stream") {
    if (row.stream_uid && row.stream_status !== "ready") {
      updated = await persistStreamVideo(context, row, await context.env.STREAM.video(row.stream_uid).details());
    } else if (
      !row.stream_uid
      && row.stream_status === "error"
      && row.stream_error_code === "STREAM_IMPORT_FAILED"
      && row.source_deleted_at === null
    ) {
      updated = await beginStreamTranscode(context, row);
    }
  }
  return jsonResponse({ media: mediaJson(updated) }, {
    headers: { "cache-control": "no-store" },
  });
}

const streamWebhookSchema = z.object({
  uid: z.string().min(1),
  readyToStream: z.boolean(),
  thumbnail: z.string().url().nullable().optional(),
  preview: z.string().url().nullable().optional(),
  duration: z.number().nonnegative().optional(),
  input: z.object({ width: z.number().int().nonnegative(), height: z.number().int().nonnegative() }).optional(),
  playback: z.object({ hls: z.string().url(), dash: z.string().url() }).optional(),
  status: z.object({
    state: z.string(),
    pctComplete: z.string().optional(),
    errorReasonCode: z.string().optional(),
    errorReasonText: z.string().optional(),
    errReasonCode: z.string().optional(),
    errReasonText: z.string().optional(),
  }),
});

function hex(bytes: Uint8Array): string {
  return [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

export async function handleStreamWebhook(context: RequestContext): Promise<Response> {
  const secret = context.env.STREAM_WEBHOOK_SECRET;
  if (!secret) throw new AppError(503, "stream_webhook_unconfigured", "Stream webhook is not configured");
  const signatureHeader = context.request.headers.get("webhook-signature") ?? "";
  const fields = Object.fromEntries(signatureHeader.split(",").map((part) => {
    const index = part.indexOf("=");
    return index > 0 ? [part.slice(0, index), part.slice(index + 1)] : [part, ""];
  }));
  const sentAt = Number(fields.time ?? "0");
  if (!Number.isSafeInteger(sentAt) || Math.abs(Math.floor(Date.now() / 1_000) - sentAt) > WEBHOOK_MAX_AGE_SECONDS) {
    throw new AppError(401, "stale_webhook", "Webhook timestamp is invalid");
  }
  const rawBody = await readBoundedBody(context.request, 64 * 1024, "Stream webhook body");
  const expected = hex(await hmac(secret, `${sentAt}.${rawBody}`));
  if (!(await secureTextEqual(fields.sig1 ?? "", expected))) {
    throw new AppError(401, "invalid_webhook_signature", "Webhook signature is invalid");
  }
  let decoded: unknown;
  try {
    decoded = JSON.parse(rawBody);
  } catch {
    throw new AppError(400, "invalid_webhook_json", "Stream webhook body is not valid JSON");
  }
  const parsed = streamWebhookSchema.safeParse(decoded);
  if (!parsed.success) {
    throw new AppError(
      422,
      "invalid_webhook_payload",
      "Stream webhook payload failed validation",
      parsed.error.issues,
    );
  }
  const payload = parsed.data;
  const row = await context.db.prepare(
    `SELECT id, uploader_id, kind, content_type, byte_size, r2_key, status,
            upload_mode, r2_upload_id, upload_token_hash, upload_expires_at,
            etag, width, height, duration_seconds, alt_text, created_at, completed_at,
            delivery_provider, stream_uid, stream_status, stream_progress,
            hls_url, dash_url, thumbnail_url, preview_url, stream_error_code,
            stream_error_message, source_deleted_at
            , image_uid, image_status, image_error_message
     FROM media WHERE stream_uid = ?`,
  ).bind(payload.uid).first<MediaRow>();
  if (!row) return new Response(null, { status: 204 });
  const ready = payload.readyToStream && payload.status.state === "ready";
  const error = payload.status.state === "error";
  const updated: MediaRow = {
    ...row,
    stream_status: ready ? "ready" : error ? "error" : "processing",
    stream_progress: percent(payload.status.pctComplete, ready),
    hls_url: payload.playback?.hls ?? row.hls_url,
    dash_url: payload.playback?.dash ?? row.dash_url,
    thumbnail_url: optimizedStreamPosterUrl(
      payload.thumbnail ?? row.thumbnail_url,
      payload.input?.width || row.width,
      payload.input?.height || row.height,
      payload.duration === undefined ? row.duration_seconds : Math.round(payload.duration),
    ),
    preview_url: payload.preview ?? row.preview_url,
    stream_error_code: payload.status.errorReasonCode ?? payload.status.errReasonCode ?? null,
    stream_error_message: payload.status.errorReasonText ?? payload.status.errReasonText ?? null,
    width: payload.input?.width || row.width,
    height: payload.input?.height || row.height,
    duration_seconds: payload.duration === undefined ? row.duration_seconds : Math.round(payload.duration),
  };
  await context.db.prepare(
    `UPDATE media SET stream_status = ?, stream_progress = ?, hls_url = ?, dash_url = ?,
       thumbnail_url = ?, preview_url = ?, stream_error_code = ?, stream_error_message = ?,
       width = ?, height = ?, duration_seconds = ? WHERE id = ?`,
  ).bind(
    updated.stream_status, updated.stream_progress, updated.hls_url, updated.dash_url,
    updated.thumbnail_url, updated.preview_url, updated.stream_error_code,
    updated.stream_error_message, updated.width, updated.height, updated.duration_seconds, updated.id,
  ).run();
  if (ready) context.execution.waitUntil(evictSource(context, updated));
  return new Response(null, { status: 204 });
}

export async function serveMedia(context: RequestContext, id: string): Promise<Response> {
  const expires = Number(context.url.searchParams.get("expires") ?? "0");
  const signature = context.url.searchParams.get("signature") ?? "";
  if (!Number.isSafeInteger(expires) || expires < Date.now() || expires > Date.now() + STREAM_IMPORT_TTL_MS + 60_000) {
    throw new AppError(403, "media_url_expired", "Media URL is expired or invalid");
  }
  const expected = await keyedHash(context.env.MEDIA_SIGNING_SECRET, `media:${id}:${expires}`);
  if (!(await secureTextEqual(signature, expected))) {
    throw new AppError(403, "invalid_media_signature", "Media URL signature is invalid");
  }
  const row = await getMedia(context.db, id);
  if (!row || row.status !== "ready") throw new AppError(404, "media_not_found", "Media not found");

  const isHead = context.request.method === "HEAD";
  const object = isHead
    ? await context.env.MEDIA.head(row.r2_key)
    : await context.env.MEDIA.get(row.r2_key, {
        onlyIf: context.request.headers,
        range: context.request.headers,
      });
  if (!object) throw new AppError(404, "media_not_found", "Media object not found");

  const headers = new Headers();
  object.writeHttpMetadata(headers);
  headers.set("etag", object.httpEtag);
  headers.set("accept-ranges", "bytes");
  headers.set("cache-control", "private, max-age=600");
  headers.set("x-content-type-options", "nosniff");
  if (!isHead && !("body" in object)) {
    return new Response(null, {
      status: context.request.headers.has("if-none-match") ? 304 : 412,
      headers,
    });
  }

  let status = 200;
  if (!isHead && context.request.headers.has("range") && object.range) {
    const suffix = "suffix" in object.range && typeof object.range.suffix === "number"
      ? object.range.suffix
      : null;
    const offset = suffix === null
      ? ("offset" in object.range ? object.range.offset ?? 0 : 0)
      : Math.max(0, object.size - suffix);
    const length = suffix === null
      ? ("length" in object.range ? object.range.length ?? object.size - offset : object.size - offset)
      : Math.min(object.size, suffix);
    if (!Number.isSafeInteger(offset) || !Number.isSafeInteger(length) || offset < 0 || length <= 0) {
      throw new AppError(500, "invalid_media_range", "Media storage returned an invalid byte range");
    }
    headers.set("content-range", `bytes ${offset}-${offset + length - 1}/${object.size}`);
    headers.set("content-length", String(length));
    status = 206;
  } else {
    headers.set("content-length", String(object.size));
  }
  const body: BodyInit | null = !isHead && "body" in object ? (object as R2ObjectBody).body : null;
  return new Response(body, { status, headers });
}

export async function requireReadyMedia(
  db: Database,
  mediaId: string,
  uploaderId: string,
  kind: "image" | "video",
): Promise<MediaRow> {
  const row = await getMedia(db, mediaId);
  if (!row || row.uploader_id !== uploaderId || row.kind !== kind || row.status !== "ready") {
    throw new AppError(422, "invalid_media", "Media must be ready, owned by the author, and match the post kind");
  }
  return row;
}
