import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { open, readFile, stat } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const catalog = JSON.parse(await readFile(
  join(scriptDirectory, "../fixtures/pexels-parkour.json"),
  "utf8",
));
const planOnly = process.argv.includes("--plan");
const sourceDirectory = process.argv.slice(2).find((argument) => !argument.startsWith("--"));
const local = process.env.D1_LOCAL === "1";
const database = process.env.D1_DATABASE || catalog.collection.database;
const wranglerConfig = process.env.WRANGLER_CONFIG?.trim() || null;
const baseUrl = (process.env.API_BASE_URL
  || (local ? "http://127.0.0.1:8787" : catalog.collection.deployedApi)).replace(/\/$/u, "");
const suffix = Date.now().toString(36);
const username = `parkour_replay_${suffix}`.slice(0, 24);
const password = `Parkour-${crypto.randomUUID()}-pass`;
let token = null;
let bookmark = null;

function sqlString(value) {
  return `'${String(value).replaceAll("'", "''")}'`;
}

function runSql(sql) {
  const args = ["wrangler", "d1", "execute", database, "--json", "--command", sql];
  if (wranglerConfig) args.push("--config", wranglerConfig);
  args.push(local ? "--local" : "--remote");
  const result = spawnSync(process.platform === "win32" ? "npx.cmd" : "npx", args, {
    cwd: join(scriptDirectory, ".."),
    encoding: "utf8",
    maxBuffer: 20 * 1024 * 1024,
  });
  if (result.status !== 0) {
    throw new Error(`wrangler d1 execute failed (${result.status}): ${result.stderr || result.stdout}`);
  }
  return JSON.parse(result.stdout);
}

function validateCatalog() {
  if (catalog.collection.fixtureVersion !== 1) throw new Error("Unsupported fixture version");
  if (catalog.videos.length !== 5) throw new Error("Expected five parkour videos");
  const pexelsIds = new Set();
  const timestamps = new Set();
  for (const video of catalog.videos) {
    if (pexelsIds.has(video.pexelsId)) throw new Error(`Duplicate Pexels id: ${video.pexelsId}`);
    if (timestamps.has(video.publishedAt)) throw new Error(`Duplicate timestamp: ${video.publishedAt}`);
    if (!Number.isFinite(Date.parse(video.publishedAt))) throw new Error(`${video.pexelsId}: invalid publishedAt`);
    if (!video.sourceUrl.startsWith("https://www.pexels.com/video/")) {
      throw new Error(`${video.pexelsId}: invalid Pexels source page`);
    }
    if (!video.sourceFileUrl.startsWith("https://videos.pexels.com/video-files/")) {
      throw new Error(`${video.pexelsId}: invalid Pexels media URL`);
    }
    if (!/^[a-f0-9]{64}$/u.test(video.sha256) || video.byteSize < 1) {
      throw new Error(`${video.pexelsId}: invalid source integrity metadata`);
    }
    pexelsIds.add(video.pexelsId);
    timestamps.add(video.publishedAt);
  }
}

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
  if (digest !== video.sha256) throw new Error(`${video.fileName}: SHA-256 mismatch`);
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

validateCatalog();

if (planOnly) {
  console.log(JSON.stringify({
    ok: true,
    write: false,
    api: baseUrl,
    database,
    subreddit: catalog.collection.subreddit,
    videos: catalog.videos.map((video) => ({
      pexelsId: video.pexelsId,
      title: video.postTitle,
      sourceUrl: video.sourceUrl,
      creator: video.creator,
      publishedAt: video.publishedAt,
      expectedRankAtScoreOne: 1_000_000_000 + Date.parse(video.publishedAt),
    })),
  }, null, 2));
  process.exit(0);
}

if (!sourceDirectory) {
  throw new Error("Usage: npm run seed:pexels-parkour -- /path/to/downloaded/videos");
}

const validatedSources = new Map();
for (const video of catalog.videos) {
  validatedSources.set(video.pexelsId, await validateSource(video));
}

let registration;
for (;;) {
  registration = await request("/v1/auth/register", {
    method: "POST",
    acceptedStatuses: [429],
    body: { username, password, displayName: "Parkour Replay" },
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
  body: {
    name: catalog.collection.subreddit,
    displayName: catalog.collection.displayName,
    description: catalog.collection.description,
    accessType: "public",
    clientMutationId: crypto.randomUUID(),
  },
});

const seeded = [];
for (const [index, video] of catalog.videos.entries()) {
  const source = validatedSources.get(video.pexelsId);
  console.error(`[${index + 1}/${catalog.videos.length}] Uploading ${video.fileName}`);
  const uploaded = await uploadSource(video, source);
  const post = (await request("/v1/posts", {
    method: "POST",
    body: {
      subreddit: catalog.collection.subreddit,
      kind: "video",
      title: video.postTitle,
      mediaId: uploaded.upload.id,
      clientMutationId: `pexels-parkour-${video.pexelsId}-${suffix}`,
    },
  })).payload.post;
  seeded.push({ source: video, post, media: uploaded.media, uploadMode: uploaded.upload.mode });
}

const rebalanceSql = seeded.map((item) => {
  const timestamp = Date.parse(item.source.publishedAt);
  return `UPDATE posts SET created_at = ${timestamp}, updated_at = ${timestamp}, rank_value = score * 1000000000 + ${timestamp} WHERE id = ${sqlString(item.post.id)};`;
}).join("\n");
runSql(rebalanceSql);
bookmark = null;

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
  const poster = await fetch(item.media.delivery.thumbnailUrl);
  if (!poster.ok) throw new Error(`${item.source.fileName}: poster returned ${poster.status}`);
  await poster.body?.cancel();
}

const communityFeed = await request(
  `/v1/feed?subreddit=${encodeURIComponent(catalog.collection.subreddit)}&limit=20`,
);
for (const item of seeded) {
  const group = communityFeed.payload.groups.find((candidate) => candidate.groupId === item.post.id);
  const cell = group?.cells.find((candidate) => candidate.type === "video");
  const metadata = group?.cells.find((candidate) => candidate.type === "metadata");
  const title = group?.cells.find((candidate) => candidate.type === "title");
  if (
    !cell
    || cell.hlsUrl !== item.media.delivery.hlsUrl
    || cell.cachePolicy !== "segments_only"
    || metadata?.createdAt !== Date.parse(item.source.publishedAt)
    || title?.text !== item.source.postTitle
  ) {
    throw new Error(`${item.source.fileName}: SDUI video cell or balanced timestamp was not ready`);
  }
}

const homeFeed = await request("/v1/feed?limit=50");
const homeFeedIds = homeFeed.payload.groups.map((group) => group.groupId);
for (const item of seeded) {
  item.homeFeedPosition = homeFeedIds.indexOf(item.post.id) + 1;
  if (item.homeFeedPosition < 1) throw new Error(`${item.source.fileName}: missing from anonymous home feed`);
}

console.log(JSON.stringify({
  ok: true,
  api: baseUrl,
  database,
  subreddit: catalog.collection.subreddit,
  communityUrl: `${baseUrl}/r/${catalog.collection.subreddit}`,
  author: username,
  videos: seeded.map((item) => ({
    pexelsId: item.source.pexelsId,
    title: item.source.postTitle,
    sourceUrl: item.source.sourceUrl,
    creator: item.source.creator,
    creatorUrl: item.source.creatorUrl,
    license: item.source.license,
    publishedAt: item.source.publishedAt,
    sha256: item.source.sha256,
    sourceBytes: item.source.byteSize,
    uploadMode: item.uploadMode,
    mediaId: item.media.id,
    postId: item.post.id,
    postUrl: `${baseUrl}/post/${item.post.id}`,
    homeFeedPosition: item.homeFeedPosition,
    hlsUrl: item.media.delivery.hlsUrl,
    dashUrl: item.media.delivery.dashUrl,
    posterUrl: item.media.delivery.thumbnailUrl,
    previewUrl: item.media.delivery.previewUrl,
    renditionCount: item.renditions.length,
    renditions: item.renditions,
  })),
}, null, 2));
