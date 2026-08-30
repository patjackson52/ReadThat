import { open, stat } from "node:fs/promises";
import { basename } from "node:path";

const baseUrl = (process.env.API_BASE_URL || "http://127.0.0.1:8787").replace(/\/$/, "");
const filePath = process.argv[2];
if (!filePath) throw new Error("Usage: npm run smoke:video -- /path/to/video.mp4");
const file = await stat(filePath);
const suffix = Date.now().toString(36);
const password = `Video-${crypto.randomUUID()}-pass`;
const username = `video_${suffix}`;
const subreddit = `video_${suffix}`.slice(0, 21);
let token = null;
let bookmark = null;

async function request(path, { method = "GET", body, headers: suppliedHeaders = {} } = {}) {
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
  if (!response.ok) throw new Error(`${method} ${path}: ${response.status} ${JSON.stringify(payload)}`);
  return { response, payload };
}

const registration = await request("/v1/auth/register", {
  method: "POST",
  body: { username, password, displayName: "Video pipeline smoke" },
});
token = registration.payload.session.accessToken;

await request("/v1/subreddits", {
  method: "POST",
  body: {
    name: subreddit,
    displayName: "Adaptive Video",
    description: "End-to-end R2, Stream, HLS, and SDUI validation",
    accessType: "public",
    clientMutationId: crypto.randomUUID(),
  },
});

const created = await request("/v1/media/uploads", {
  method: "POST",
  body: {
    kind: "video",
    contentType: "video/mp4",
    byteSize: file.size,
    width: 1920,
    height: 1080,
    durationSeconds: 30,
    altText: "Big Buck Bunny, Blender Foundation, CC BY 3.0",
  },
});
const upload = created.payload.upload;
let completed;
const handle = await open(filePath, "r");
try {
  if (upload.mode === "single") {
    const bytes = new Uint8Array(file.size);
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
    for (let partNumber = 1; offset < file.size; partNumber += 1) {
      const length = Math.min(upload.partSize, file.size - offset);
      const bytes = new Uint8Array(length);
      const result = await handle.read(bytes, 0, length, offset);
      if (result.bytesRead !== length) throw new Error(`Short read for part ${partNumber}`);
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

const post = (await request("/v1/posts", {
  method: "POST",
  body: {
    subreddit,
    kind: "video",
    title: "Adaptive HLS stock-video pipeline",
    mediaId: upload.id,
    clientMutationId: `video-post-${suffix}`,
  },
})).payload.post;

let media = completed.payload.media;
for (let attempt = 0; !["ready", "error"].includes(media.delivery.status) && attempt < 90; attempt += 1) {
  await new Promise((resolve) => setTimeout(resolve, 2_000));
  media = (await request(`/v1/media/uploads/${upload.id}/refresh`, { method: "POST" })).payload.media;
}
if (media.delivery.status !== "ready" || !media.delivery.hlsUrl) {
  throw new Error(`Stream did not become ready: ${JSON.stringify(media.delivery)}`);
}

const manifest = await fetch(media.delivery.hlsUrl, { headers: { accept: "application/vnd.apple.mpegurl" } });
const manifestText = await manifest.text();
if (!manifest.ok || !manifestText.startsWith("#EXTM3U")) {
  throw new Error(`HLS manifest validation failed: ${manifest.status}`);
}

const feed = await request("/v1/feed?limit=20");
const cell = feed.payload.groups
  .find((group) => group.groupId === post.id)
  ?.cells.find((candidate) => candidate.type === "video");
if (!cell || cell.hlsUrl !== media.delivery.hlsUrl || cell.cachePolicy !== "segments_only") {
  throw new Error("SDUI video cell was missing the ready HLS contract");
}

console.log(JSON.stringify({
  ok: true,
  api: baseUrl,
  sourceFile: basename(filePath),
  sourceBytes: file.size,
  uploadMode: upload.mode,
  mediaId: upload.id,
  subreddit,
  postId: post.id,
  hlsUrl: media.delivery.hlsUrl,
  posterUrl: media.delivery.thumbnailUrl,
  feedVideoCell: {
    deliveryStatus: cell.deliveryStatus,
    cachePolicy: cell.cachePolicy,
    hasFallback: Boolean(cell.fallbackUrl),
  },
}));
