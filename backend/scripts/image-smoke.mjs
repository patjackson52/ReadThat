import { readFile, stat } from "node:fs/promises";
import { basename, extname } from "node:path";

const baseUrl = (process.env.API_BASE_URL || "http://127.0.0.1:8787").replace(/\/$/, "");
const filePath = process.argv[2];
if (!filePath) throw new Error("Usage: npm run smoke:image -- /path/to/image.jpg");

const contentTypes = new Map([
  [".jpg", "image/jpeg"],
  [".jpeg", "image/jpeg"],
  [".png", "image/png"],
  [".webp", "image/webp"],
]);
const contentType = contentTypes.get(extname(filePath).toLowerCase());
if (!contentType) throw new Error("Smoke image must be JPEG, PNG, or WebP");

const file = await stat(filePath);
const bytes = new Uint8Array(await readFile(filePath));
const suffix = Date.now().toString(36);
const username = `image_${suffix}`;
const subreddit = `image_${suffix}`.slice(0, 21);
const password = `Image-${crypto.randomUUID()}-pass`;
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
  body: { username, password, displayName: "Images pipeline smoke" },
});
token = registration.payload.session.accessToken;

await request("/v1/subreddits", {
  method: "POST",
  body: {
    name: subreddit,
    displayName: "Responsive Images",
    description: "End-to-end R2 staging, Images delivery, and SDUI validation",
    accessType: "public",
    clientMutationId: crypto.randomUUID(),
  },
});

const upload = (await request("/v1/media/uploads", {
  method: "POST",
  body: {
    kind: "image",
    contentType,
    byteSize: file.size,
    width: 854,
    height: 480,
    altText: "Big Buck Bunny stock-video poster used for delivery validation",
  },
})).payload.upload;

const completed = await request(upload.uploadPath, {
  method: "PUT",
  headers: {
    "content-type": contentType,
    "content-length": String(bytes.byteLength),
    "x-upload-token": upload.uploadToken,
  },
  body: bytes,
});
if (completed.payload.media.delivery.status !== "ready" || !completed.payload.media.delivery.imageId) {
  throw new Error(`Images ingest did not become ready: ${JSON.stringify(completed.payload.media.delivery)}`);
}

const post = (await request("/v1/posts", {
  method: "POST",
  body: {
    subreddit,
    kind: "image",
    title: "Responsive Cloudflare Images pipeline",
    mediaId: upload.id,
    clientMutationId: `image-post-${suffix}`,
  },
})).payload.post;

const feed = await request("/v1/feed?limit=20");
const cell = feed.payload.groups
  .find((group) => group.groupId === post.id)
  ?.cells.find((candidate) => candidate.type === "image");
if (!cell?.url?.includes("imagedelivery.net") || !cell.cacheKey?.endsWith(":feed")) {
  throw new Error(`SDUI image cell was missing Images delivery contract: ${JSON.stringify(cell)}`);
}
if (!post.media?.url?.includes("imagedelivery.net") || !post.media.cacheKey?.endsWith(":detail")) {
  throw new Error("Post detail was missing the signed detail variant");
}

const [feedImage, detailImage] = await Promise.all([
  fetch(cell.url, { headers: { accept: "image/avif,image/webp,image/*" } }),
  fetch(post.media.url, { headers: { accept: "image/avif,image/webp,image/*" } }),
]);
if (!feedImage.ok || !feedImage.headers.get("content-type")?.startsWith("image/")) {
  throw new Error(`Feed variant fetch failed: ${feedImage.status}`);
}
if (!detailImage.ok || !detailImage.headers.get("content-type")?.startsWith("image/")) {
  throw new Error(`Detail variant fetch failed: ${detailImage.status}`);
}

console.log(JSON.stringify({
  ok: true,
  api: baseUrl,
  sourceFile: basename(filePath),
  sourceBytes: file.size,
  mediaId: upload.id,
  imageId: completed.payload.media.delivery.imageId,
  subreddit,
  postId: post.id,
  feedUrl: cell.url,
  detailUrl: post.media.url,
  feedContentType: feedImage.headers.get("content-type"),
  detailContentType: detailImage.headers.get("content-type"),
  sourceEvicted: post.media.fallbackUrl === null,
}));
