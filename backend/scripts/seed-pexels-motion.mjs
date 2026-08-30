import { createHash } from "node:crypto";
import { open, readFile, stat } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const baseUrl = (process.env.API_BASE_URL || "http://127.0.0.1:8787").replace(/\/$/u, "");
const sourceDirectory = process.argv[2];
if (!sourceDirectory) {
  throw new Error("Usage: npm run seed:pexels-motion -- /path/to/downloaded/videos");
}

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const catalog = JSON.parse(await readFile(join(scriptDirectory, "../fixtures/pexels-motion-portrait.json"), "utf8"));
const requestedSkip = Number(process.env.SEED_SKIP || 0);
const requestedLimit = Number(process.env.SEED_LIMIT || catalog.videos.length - requestedSkip);
if (!Number.isSafeInteger(requestedSkip) || requestedSkip < 0 || requestedSkip >= catalog.videos.length) {
  throw new Error(`SEED_SKIP must be an integer from 0 to ${catalog.videos.length - 1}`);
}
if (!Number.isSafeInteger(requestedLimit) || requestedLimit < 1 || requestedSkip + requestedLimit > catalog.videos.length) {
  throw new Error(`SEED_LIMIT must select at least one of the ${catalog.videos.length} videos`);
}
const videos = catalog.videos.slice(requestedSkip, requestedSkip + requestedLimit);
const suffix = Date.now().toString(36);
const username = `motion_curator_${suffix}`.slice(0, 24);
const password = `Motion-${crypto.randomUUID()}-pass`;
let token = null;
let bookmark = null;

async function request(path, {
  method = "GET",
  body,
  headers: suppliedHeaders = {},
  acceptedStatuses = [],
} = {}) {
  const headers = new Headers(suppliedHeaders);
  if (token) headers.set("authorization", `Bearer ${token}`);
  if (bookmark) headers.set("x-d1-bookmark", bookmark);
  let requestBody = body;
  if (body !== undefined && !(body instanceof Uint8Array)) {
    headers.set("content-type", "application/json");
    requestBody = JSON.stringify(body);
  }
  const response = await fetch(`${baseUrl}${path}`, { method, headers, body: requestBody });
  bookmark = response.headers.get("x-d1-bookmark") || bookmark;
  const text = await response.text();
  const payload = text ? JSON.parse(text) : {};
  if (!response.ok && !acceptedStatuses.includes(response.status)) {
    throw new Error(`${method} ${path}: ${response.status} ${JSON.stringify(payload)}`);
  }
  return { response, payload };
}

async function validateSource(video) {
  const path = join(sourceDirectory, video.fileName);
  const file = await stat(path);
  if (file.size !== video.byteSize) {
    throw new Error(`${video.fileName}: expected ${video.byteSize} bytes, found ${file.size}`);
  }
  const digest = createHash("sha256").update(await readFile(path)).digest("hex");
  if (digest !== video.sha256) {
    throw new Error(`${video.fileName}: SHA-256 mismatch`);
  }
  return { path, file };
}

async function uploadSource(video, source) {
  const altText = `${video.description} Video by ${video.creator} on Pexels. ${video.license}: ${video.sourceUrl}`;
  const created = await request("/v1/media/uploads", {
    method: "POST",
    body: {
      kind: "video",
      contentType: "video/mp4",
      byteSize: source.file.size,
      width: video.width,
      height: video.height,
      durationSeconds: video.durationSeconds,
      altText,
    },
  });
  const upload = created.payload.upload;
  let completed;
  const handle = await open(source.path, "r");
  try {
    if (upload.mode === "single") {
      const bytes = new Uint8Array(source.file.size);
      await handle.read(bytes, 0, bytes.length, 0);
      completed = await request(upload.uploadPath, {
        method: "PUT",
        headers: {
          "content-type": "video/mp4",
          "content-length": String(bytes.byteLength),
          "x-upload-token": upload.uploadToken,
        },
        body: bytes,
      });
    } else {
      let offset = 0;
      for (let partNumber = 1; offset < source.file.size; partNumber += 1) {
        const length = Math.min(upload.partSize, source.file.size - offset);
        const bytes = new Uint8Array(length);
        const result = await handle.read(bytes, 0, length, offset);
        if (result.bytesRead !== length) throw new Error(`Short read for ${video.fileName} part ${partNumber}`);
        await request(upload.uploadPath.replace("{partNumber}", String(partNumber)), {
          method: "PUT",
          headers: {
            "content-type": "video/mp4",
            "content-length": String(length),
            "x-upload-token": upload.uploadToken,
          },
          body: bytes,
        });
        offset += length;
      }
      completed = await request(upload.completePath, {
        method: "POST",
        headers: { "x-upload-token": upload.uploadToken },
        body: new Uint8Array(0),
      });
    }
  } finally {
    await handle.close();
  }
  return { upload, media: completed.payload.media };
}

function hlsRenditions(manifestText) {
  return [...manifestText.matchAll(/#EXT-X-STREAM-INF:([^\n]+)\n([^\n]+)/gu)].map((match) => {
    const attributes = Object.fromEntries(match[1].split(",").map((value) => value.split("=", 2)));
    return {
      bandwidth: Number(attributes.BANDWIDTH || 0),
      resolution: attributes.RESOLUTION || null,
      path: match[2].trim(),
    };
  });
}

const validatedSources = new Map();
for (const video of videos) {
  validatedSources.set(video.pexelsId, await validateSource(video));
}

let registration;
for (;;) {
  registration = await request("/v1/auth/register", {
    method: "POST",
    acceptedStatuses: [429],
    body: { username, password, displayName: "Pexels Motion Curator" },
  });
  if (registration.response.status !== 429) break;
  const retryAfterMs = Number(registration.payload.error?.details?.retryAfterMs ?? 30_000);
  const waitMs = Math.max(1_000, Math.min(30_000, retryAfterMs + 250));
  console.error(`Registration is rate-limited; retrying in ${Math.ceil(waitMs / 1_000)}s`);
  await new Promise((resolve) => setTimeout(resolve, waitMs));
}
token = registration.payload.session.accessToken;

await request("/v1/subreddits", {
  method: "POST",
  acceptedStatuses: [409],
  body: {
    name: catalog.collection.subreddit,
    displayName: catalog.collection.displayName,
    description: catalog.collection.description,
    accessType: "public",
    clientMutationId: crypto.randomUUID(),
  },
});

const seeded = [];
for (const [index, video] of videos.entries()) {
  const source = validatedSources.get(video.pexelsId);
  console.error(`[${index + 1}/${videos.length}] Uploading ${video.fileName}`);
  const uploaded = await uploadSource(video, source);
  const post = (await request("/v1/posts", {
    method: "POST",
    body: {
      subreddit: catalog.collection.subreddit,
      kind: "video",
      title: video.postTitle,
      mediaId: uploaded.upload.id,
      clientMutationId: `pexels-${video.pexelsId}-${suffix}`,
    },
  })).payload.post;
  seeded.push({ source: video, post, media: uploaded.media, uploadMode: uploaded.upload.mode });
}

for (let attempt = 0; seeded.some((item) => !["ready", "error"].includes(item.media.delivery.status)); attempt += 1) {
  if (attempt >= 120) throw new Error("Timed out waiting for Cloudflare Stream encoding");
  await new Promise((resolve) => setTimeout(resolve, 3_000));
  for (const item of seeded) {
    if (["ready", "error"].includes(item.media.delivery.status)) continue;
    item.media = (await request(`/v1/media/uploads/${item.media.id}/refresh`, { method: "POST" })).payload.media;
  }
  const states = seeded.reduce((result, item) => {
    const state = item.media.delivery.status;
    result[state] = (result[state] || 0) + 1;
    return result;
  }, {});
  console.error(`Stream processing: ${JSON.stringify(states)}`);
}

for (const item of seeded) {
  if (item.media.delivery.status !== "ready" || !item.media.delivery.hlsUrl) {
    throw new Error(`${item.source.fileName}: Stream failed ${JSON.stringify(item.media.delivery)}`);
  }
  const manifest = await fetch(item.media.delivery.hlsUrl, {
    headers: { accept: "application/vnd.apple.mpegurl" },
  });
  const manifestText = await manifest.text();
  if (!manifest.ok || !manifestText.startsWith("#EXTM3U")) {
    throw new Error(`${item.source.fileName}: invalid HLS manifest (${manifest.status})`);
  }
  item.renditions = hlsRenditions(manifestText);
  if (item.renditions.length < 2) {
    throw new Error(`${item.source.fileName}: expected an adaptive HLS master playlist`);
  }
  const poster = await fetch(item.media.delivery.thumbnailUrl, { method: "HEAD" });
  if (!poster.ok) throw new Error(`${item.source.fileName}: poster returned ${poster.status}`);
}

const feed = await request(`/v1/feed?subreddit=${encodeURIComponent(catalog.collection.subreddit)}&limit=20`);
for (const item of seeded) {
  const cell = feed.payload.groups
    .find((group) => group.groupId === item.post.id)
    ?.cells.find((candidate) => candidate.type === "video");
  if (!cell || cell.hlsUrl !== item.media.delivery.hlsUrl || cell.cachePolicy !== "segments_only") {
    throw new Error(`${item.source.fileName}: SDUI video cell was not ready`);
  }
}

console.log(JSON.stringify({
  ok: true,
  api: baseUrl,
  subreddit: catalog.collection.subreddit,
  author: username,
  videos: seeded.map((item) => ({
    pexelsId: item.source.pexelsId,
    sourceUrl: item.source.sourceUrl,
    creator: item.source.creator,
    license: item.source.license,
    sha256: item.source.sha256,
    sourceBytes: item.source.byteSize,
    uploadMode: item.uploadMode,
    mediaId: item.media.id,
    postId: item.post.id,
    hlsUrl: item.media.delivery.hlsUrl,
    dashUrl: item.media.delivery.dashUrl,
    posterUrl: item.media.delivery.thumbnailUrl,
    previewUrl: item.media.delivery.previewUrl,
    renditionCount: item.renditions.length,
    renditions: item.renditions,
  })),
}, null, 2));
