import { createHash, randomUUID } from "node:crypto";
import { readFile, stat } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const baseUrl = (process.env.API_BASE_URL
  || "http://127.0.0.1:8787").replace(/\/$/u, "");
const sourceDirectory = process.argv[2];
if (!sourceDirectory) {
  throw new Error("Usage: npm run seed:pnw-carousels -- /path/to/downloaded/images");
}

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const catalog = JSON.parse(await readFile(
  join(scriptDirectory, "../fixtures/pnw-carousel-personas.json"),
  "utf8",
));
const mediaById = new Map(catalog.media.map((media) => [media.pexelsId, media]));

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

function requireMedia(pexelsId) {
  const media = mediaById.get(pexelsId);
  if (!media) throw new Error(`Unknown Pexels media id ${pexelsId}`);
  return media;
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

function validateCatalog() {
  if (catalog.collection.fictionalProfiles !== true) {
    throw new Error("Carousel personas must be explicitly marked fictional");
  }
  if (catalog.collection.searchUrl !== "https://www.pexels.com/search/pnw/") {
    throw new Error("Carousel media must retain the requested Pexels PNW search provenance");
  }
  if (mediaById.size !== catalog.media.length) throw new Error("Duplicate Pexels media id");
  for (const persona of catalog.personas) {
    const allowed = new Set(persona.mediaPexelsIds);
    if (!allowed.has(persona.avatarPexelsId)) {
      throw new Error(`${persona.profile.username}: avatar is not in owned media list`);
    }
    for (const pexelsId of allowed) requireMedia(pexelsId);
    for (const post of persona.posts) {
      if (post.mediaPexelsIds.length < 2 || post.mediaPexelsIds.length > 20) {
        throw new Error(`${post.postTitle}: carousel must contain 2–20 images`);
      }
      if (new Set(post.mediaPexelsIds).size !== post.mediaPexelsIds.length) {
        throw new Error(`${post.postTitle}: duplicate carousel image`);
      }
      for (const pexelsId of post.mediaPexelsIds) {
        if (!allowed.has(pexelsId)) {
          throw new Error(`${post.postTitle}: persona does not own Pexels media ${pexelsId}`);
        }
      }
    }
  }
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
    throw new Error(`${media.fileName}: expected a single-part upload, got ${upload.mode}`);
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

validateCatalog();
const validatedSources = new Map();
for (const media of catalog.media) {
  validatedSources.set(media.pexelsId, await validateSource(media));
}

const seededPersonas = [];
for (const [personaIndex, persona] of catalog.personas.entries()) {
  const client = new ApiClient();
  console.error(`[${personaIndex + 1}/${catalog.personas.length}] Creating u/${persona.profile.username}`);
  const registeredUser = await register(client, persona.profile);

  const uploadsById = new Map();
  for (const [mediaIndex, pexelsId] of persona.mediaPexelsIds.entries()) {
    const media = requireMedia(pexelsId);
    console.error(`  [media ${mediaIndex + 1}/${persona.mediaPexelsIds.length}] ${media.fileName}`);
    uploadsById.set(pexelsId, await uploadImage(
      client,
      media,
      validatedSources.get(pexelsId),
    ));
  }

  const avatarUpload = uploadsById.get(persona.avatarPexelsId);
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
  const flairs = (await client.request(
    `/v1/subreddits/${encodeURIComponent(persona.community.name)}/flairs`,
  )).payload.flairs;

  const posts = [];
  for (const [postIndex, fixture] of persona.posts.entries()) {
    const mediaUploads = fixture.mediaPexelsIds.map((pexelsId) => uploadsById.get(pexelsId));
    const flair = flairs.find((candidate) => candidate.text === fixture.flair);
    if (!flair) throw new Error(`${fixture.postTitle}: missing flair ${fixture.flair}`);
    console.error(`  [post ${postIndex + 1}/${persona.posts.length}] ${fixture.postTitle}`);
    const created = await client.request("/v1/posts", {
      method: "POST",
      body: {
        subreddit: persona.community.name,
        kind: "image",
        title: fixture.postTitle,
        body: fixture.body,
        mediaId: mediaUploads[0].upload.id,
        mediaIds: mediaUploads.map((uploaded) => uploaded.upload.id),
        flairId: flair.id,
        clientMutationId: `pnw-carousel-v${catalog.collection.fixtureVersion}-${persona.profile.username}-${postIndex}`,
      },
    });
    const post = created.payload.post;
    const expectedIds = mediaUploads.map((uploaded) => uploaded.upload.id);
    const actualIds = post.mediaItems.map((media) => media.id);
    if (JSON.stringify(actualIds) !== JSON.stringify(expectedIds)) {
      throw new Error(`${fixture.postTitle}: detail gallery order mismatch`);
    }
    if (post.flair?.id !== flair.id) throw new Error(`${fixture.postTitle}: flair mismatch`);
    posts.push({ fixture, post, mediaUploads });
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

  const feed = await client.request(
    `/v1/feed?subreddit=${encodeURIComponent(persona.community.name)}&limit=20`,
  );
  for (const item of posts) {
    const group = feed.payload.groups.find((candidate) => candidate.groupId === item.post.id);
    const carousel = group?.cells.find((candidate) => candidate.type === "image_carousel");
    if (!carousel || carousel.items.length !== item.fixture.mediaPexelsIds.length) {
      throw new Error(`${item.fixture.postTitle}: SDUI carousel was missing or incomplete`);
    }
    const expectedIds = item.mediaUploads.map((uploaded) => uploaded.upload.id);
    if (JSON.stringify(carousel.items.map((media) => media.mediaId)) !== JSON.stringify(expectedIds)) {
      throw new Error(`${item.fixture.postTitle}: SDUI carousel order mismatch`);
    }
    const detail = (await client.request(`/v1/posts/${encodeURIComponent(item.post.id)}`)).payload.post;
    if (detail.mediaItems.length !== carousel.items.length) {
      throw new Error(`${item.fixture.postTitle}: feed/detail gallery counts differ`);
    }
    const responses = await Promise.all([
      ...carousel.items.map((media) => fetch(media.url, {
        headers: { accept: "image/avif,image/webp,image/*" },
      })),
      ...detail.mediaItems.map((media) => fetch(media.url, {
        headers: { accept: "image/avif,image/webp,image/*" },
      })),
    ]);
    if (responses.some((response) => !response.ok
      || !response.headers.get("content-type")?.startsWith("image/"))) {
      throw new Error(`${item.fixture.postTitle}: one or more CDN renditions failed`);
    }
    await Promise.all(responses.map((response) => response.body?.cancel()));
    item.carousel = carousel;
    item.detail = detail;
  }

  seededPersonas.push({
    source: persona,
    registeredUser,
    profile,
    publicProfile,
    avatarUpload,
    avatarDeliveryUrl,
    community,
    uploadsById,
    posts,
  });
}

console.log(JSON.stringify({
  ok: true,
  api: baseUrl,
  fixtureVersion: catalog.collection.fixtureVersion,
  fictionalProfiles: catalog.collection.fictionalProfiles,
  profileNotice: catalog.collection.profileNotice,
  pexelsSearchUrl: catalog.collection.searchUrl,
  license: catalog.collection.license,
  licenseUrl: catalog.collection.licenseUrl,
  personas: seededPersonas.map((persona) => ({
    userId: persona.profile.id,
    username: persona.profile.username,
    displayName: persona.profile.displayName,
    profileUrl: `${baseUrl}/v1/users/${encodeURIComponent(persona.profile.username)}`,
    avatar: {
      pexelsId: persona.source.avatarPexelsId,
      mediaId: persona.avatarUpload.media.id,
      imageId: persona.avatarUpload.media.delivery.imageId,
      stableUrl: persona.publicProfile.avatarUrl,
    },
    community: {
      id: persona.community.id,
      name: persona.community.name,
      displayName: persona.community.displayName,
      feedUrl: `${baseUrl}/v1/feed?subreddit=${encodeURIComponent(persona.community.name)}&limit=20`,
    },
    media: persona.source.mediaPexelsIds.map((pexelsId) => {
      const source = requireMedia(pexelsId);
      const uploaded = persona.uploadsById.get(pexelsId);
      return {
        pexelsId,
        creator: source.creator,
        sourceUrl: source.sourceUrl,
        sha256: source.sha256,
        mediaId: uploaded.media.id,
        imageId: uploaded.media.delivery.imageId,
      };
    }),
    posts: persona.posts.map((item) => ({
      title: item.post.title,
      postId: item.post.id,
      postUrl: `${baseUrl}/v1/posts/${item.post.id}`,
      pexelsIds: item.fixture.mediaPexelsIds,
      mediaIds: item.detail.mediaItems.map((media) => media.id),
      carouselCount: item.carousel.items.length,
      flair: item.post.flair,
    })),
  })),
}, null, 2));
