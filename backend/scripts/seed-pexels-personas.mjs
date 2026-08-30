import { createHash, randomUUID } from "node:crypto";
import { readFile, stat } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const baseUrl = (process.env.API_BASE_URL
  || "http://127.0.0.1:8787").replace(/\/$/u, "");
const sourceDirectory = process.argv[2];
if (!sourceDirectory) {
  throw new Error("Usage: npm run seed:pexels-personas -- /path/to/downloaded/images");
}

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const catalog = JSON.parse(await readFile(
  join(scriptDirectory, "../fixtures/pexels-demo-personas.json"),
  "utf8",
));

class ApiClient {
  constructor() {
    this.token = "";
    this.bookmark = "";
  }

  async request(path, {
    method = "GET",
    body,
    headers: suppliedHeaders = {},
    acceptedStatuses = [],
  } = {}) {
    const headers = new Headers(suppliedHeaders);
    if (this.token) headers.set("authorization", `Bearer ${this.token}`);
    if (this.bookmark) headers.set("x-d1-bookmark", this.bookmark);
    let requestBody = body;
    if (body !== undefined && !(body instanceof Uint8Array)) {
      headers.set("content-type", "application/json");
      requestBody = JSON.stringify(body);
    }
    const response = await fetch(`${baseUrl}${path}`, { method, headers, body: requestBody });
    this.bookmark = response.headers.get("x-d1-bookmark") || this.bookmark;
    const text = await response.text();
    const payload = text ? JSON.parse(text) : {};
    if (!response.ok && !acceptedStatuses.includes(response.status)) {
      throw new Error(`${method} ${path}: ${response.status} ${JSON.stringify(payload)}`);
    }
    return { response, payload };
  }
}

async function validateSource(media) {
  const path = join(sourceDirectory, media.fileName);
  const file = await stat(path);
  if (file.size !== media.byteSize) {
    throw new Error(`${media.fileName}: expected ${media.byteSize} bytes, found ${file.size}`);
  }
  const bytes = new Uint8Array(await readFile(path));
  const digest = createHash("sha256").update(bytes).digest("hex");
  if (digest !== media.sha256) throw new Error(`${media.fileName}: SHA-256 mismatch`);
  return { bytes, file };
}

function attributionAltText(media) {
  return `${media.description} Photo by ${media.creator} on Pexels (${media.sourceUrl}); ${media.license} (${catalog.collection.licenseUrl}).`;
}

async function uploadImage(client, media, source) {
  const created = await client.request("/v1/media/uploads", {
    method: "POST",
    body: {
      kind: "image",
      contentType: media.contentType,
      byteSize: source.file.size,
      width: media.width,
      height: media.height,
      altText: attributionAltText(media),
    },
  });
  const upload = created.payload.upload;
  if (upload.mode !== "single") {
    throw new Error(`${media.fileName}: expected a single-part image upload, got ${upload.mode}`);
  }
  const completed = await client.request(upload.uploadPath, {
    method: "PUT",
    headers: {
      "content-type": media.contentType,
      "content-length": String(source.bytes.byteLength),
      "x-upload-token": upload.uploadToken,
    },
    body: source.bytes,
  });
  const delivery = completed.payload.media?.delivery;
  if (delivery?.provider !== "images" || delivery.status !== "ready" || !delivery.imageId) {
    throw new Error(`${media.fileName}: Cloudflare Images ingest failed ${JSON.stringify(delivery)}`);
  }
  return { upload, media: completed.payload.media };
}

async function register(client, profile) {
  const password = `Demo-${randomUUID()}-pass`;
  for (;;) {
    const registration = await client.request("/v1/auth/register", {
      method: "POST",
      acceptedStatuses: [429],
      body: {
        username: profile.username,
        password,
        displayName: profile.displayName,
      },
    });
    if (registration.response.status !== 429) {
      client.token = registration.payload.session.accessToken;
      return registration.payload.user;
    }
    const retryAfterMs = Number(registration.payload.error?.details?.retryAfterMs ?? 30_000);
    const waitMs = Math.max(1_000, Math.min(30_000, retryAfterMs + 250));
    console.error(`Registration is rate-limited; retrying in ${Math.ceil(waitMs / 1_000)}s`);
    await new Promise((resolve) => setTimeout(resolve, waitMs));
  }
}

const validatedSources = new Map();
for (const persona of catalog.personas) {
  for (const media of [persona.avatar, ...persona.posts.map((post) => post.media)]) {
    validatedSources.set(media.fileName, await validateSource(media));
  }
}

const seededPersonas = [];
for (const [personaIndex, persona] of catalog.personas.entries()) {
  const client = new ApiClient();
  console.error(`[${personaIndex + 1}/${catalog.personas.length}] Creating u/${persona.profile.username}`);
  const registeredUser = await register(client, persona.profile);

  const avatarUpload = await uploadImage(
    client,
    persona.avatar,
    validatedSources.get(persona.avatar.fileName),
  );
  const profile = (await client.request("/v1/me", {
    method: "PATCH",
    body: {
      displayName: persona.profile.displayName,
      bio: persona.profile.bio,
      avatarMediaId: avatarUpload.upload.id,
    },
  })).payload.user;

  const community = (await client.request("/v1/subreddits", {
    method: "POST",
    body: {
      ...persona.community,
      accessType: "public",
      clientMutationId: randomUUID(),
    },
  })).payload.subreddit;

  const posts = [];
  for (const [postIndex, fixture] of persona.posts.entries()) {
    console.error(`  [${postIndex + 1}/${persona.posts.length}] Uploading ${fixture.media.fileName}`);
    const uploaded = await uploadImage(
      client,
      fixture.media,
      validatedSources.get(fixture.media.fileName),
    );
    const post = (await client.request("/v1/posts", {
      method: "POST",
      body: {
        subreddit: persona.community.name,
        kind: "image",
        title: fixture.postTitle,
        mediaId: uploaded.upload.id,
        clientMutationId: `pexels-${fixture.media.pexelsId}-${randomUUID()}`,
      },
    })).payload.post;
    if (!post.media?.url?.includes("imagedelivery.net") || post.media.fallbackUrl !== null) {
      throw new Error(`${fixture.media.fileName}: post is not using Cloudflare Images delivery`);
    }
    posts.push({ fixture, uploaded, post });
  }

  const publicProfile = (await client.request(
    `/v1/users/${encodeURIComponent(persona.profile.username)}`,
  )).payload.user;
  if (publicProfile.bio !== persona.profile.bio || !publicProfile.avatarUrl) {
    throw new Error(`${persona.profile.username}: public profile metadata mismatch`);
  }

  const avatarRedirect = await fetch(publicProfile.avatarUrl, { redirect: "manual" });
  const avatarDeliveryUrl = avatarRedirect.headers.get("location");
  if (avatarRedirect.status !== 302 || !avatarDeliveryUrl?.startsWith("https://imagedelivery.net/")) {
    throw new Error(`${persona.profile.username}: avatar did not redirect to Cloudflare Images`);
  }
  const avatarImage = await fetch(avatarDeliveryUrl);
  if (!avatarImage.ok || !avatarImage.headers.get("content-type")?.startsWith("image/")) {
    throw new Error(`${persona.profile.username}: avatar delivery failed (${avatarImage.status})`);
  }
  await avatarImage.body?.cancel();

  const feed = await client.request(
    `/v1/feed?subreddit=${encodeURIComponent(persona.community.name)}&limit=20`,
  );
  for (const item of posts) {
    const cell = feed.payload.groups
      .find((group) => group.groupId === item.post.id)
      ?.cells.find((candidate) => candidate.type === "image");
    if (!cell?.url?.includes("imagedelivery.net") || !cell.cacheKey?.endsWith(":feed")) {
      throw new Error(`${item.fixture.media.fileName}: SDUI image cell was not CDN-ready`);
    }
    const [feedImage, detailImage] = await Promise.all([
      fetch(cell.url, { headers: { accept: "image/avif,image/webp,image/*" } }),
      fetch(item.post.media.url, { headers: { accept: "image/avif,image/webp,image/*" } }),
    ]);
    if (!feedImage.ok || !feedImage.headers.get("content-type")?.startsWith("image/")) {
      throw new Error(`${item.fixture.media.fileName}: feed rendition failed (${feedImage.status})`);
    }
    if (!detailImage.ok || !detailImage.headers.get("content-type")?.startsWith("image/")) {
      throw new Error(`${item.fixture.media.fileName}: detail rendition failed (${detailImage.status})`);
    }
    await Promise.all([feedImage.body?.cancel(), detailImage.body?.cancel()]);
    item.feedCell = cell;
    item.feedContentType = feedImage.headers.get("content-type");
    item.detailContentType = detailImage.headers.get("content-type");
  }

  seededPersonas.push({
    client,
    source: persona,
    registeredUser,
    profile,
    publicProfile,
    avatarUpload,
    avatarDeliveryUrl,
    avatarContentType: avatarImage.headers.get("content-type"),
    community,
    posts,
  });
}

console.log(JSON.stringify({
  ok: true,
  api: baseUrl,
  fictionalProfiles: catalog.collection.fictionalProfiles,
  profileNotice: catalog.collection.profileNotice,
  license: catalog.collection.license,
  licenseUrl: catalog.collection.licenseUrl,
  personas: seededPersonas.map((persona) => ({
    username: persona.profile.username,
    displayName: persona.profile.displayName,
    bio: persona.profile.bio,
    userId: persona.profile.id,
    profileUrl: `${baseUrl}/v1/users/${encodeURIComponent(persona.profile.username)}`,
    avatar: {
      pexelsId: persona.source.avatar.pexelsId,
      creator: persona.source.avatar.creator,
      sourceUrl: persona.source.avatar.sourceUrl,
      sha256: persona.source.avatar.sha256,
      mediaId: persona.avatarUpload.media.id,
      imageId: persona.avatarUpload.media.delivery.imageId,
      stableUrl: persona.publicProfile.avatarUrl,
      currentDeliveryUrl: persona.avatarDeliveryUrl,
      contentType: persona.avatarContentType,
    },
    community: {
      id: persona.community.id,
      name: persona.community.name,
      displayName: persona.community.displayName,
      feedUrl: `${baseUrl}/v1/feed?subreddit=${encodeURIComponent(persona.community.name)}&limit=20`,
    },
    posts: persona.posts.map((item) => ({
      pexelsId: item.fixture.media.pexelsId,
      creator: item.fixture.media.creator,
      sourceUrl: item.fixture.media.sourceUrl,
      sha256: item.fixture.media.sha256,
      mediaId: item.uploaded.media.id,
      imageId: item.uploaded.media.delivery.imageId,
      postId: item.post.id,
      postUrl: `${baseUrl}/v1/posts/${item.post.id}`,
      feedUrl: item.feedCell.url,
      detailUrl: item.post.media.url,
      feedContentType: item.feedContentType,
      detailContentType: item.detailContentType,
      sourceEvicted: item.post.media.fallbackUrl === null,
    })),
  })),
}, null, 2));
