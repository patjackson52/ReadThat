const baseUrl = (process.env.API_BASE_URL || "http://127.0.0.1:8787").replace(/\/$/, "");
const suffix = Date.now().toString(36);
const password = `Smoke-${crypto.randomUUID()}-pass`;

function apiClient() {
  return {
    token: null,
    bookmark: null,
    async request(path, { method = "GET", body, headers: suppliedHeaders = {} } = {}) {
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
      if (!response.ok) {
        throw new Error(`${method} ${path}: ${response.status} ${JSON.stringify(payload)}`);
      }
      return { response, payload };
    },
  };
}

async function register(client, username) {
  const { payload } = await client.request("/v1/auth/register", {
    method: "POST",
    body: { username, password, displayName: username },
  });
  client.token = payload.session.accessToken;
  return payload;
}

async function createMedia(client, kind, contentType, bytes, extra = {}) {
  const { payload: created } = await client.request("/v1/media/uploads", {
    method: "POST",
    body: {
      kind,
      contentType,
      byteSize: bytes.byteLength,
      width: 640,
      height: 360,
      altText: `${kind} smoke-test media`,
      ...extra,
    },
  });
  await client.request(`/v1/media/uploads/${created.upload.id}`, {
    method: "PUT",
    headers: {
      "content-type": contentType,
      "content-length": String(bytes.byteLength),
      "x-upload-token": created.upload.uploadToken,
    },
    body: bytes,
  });
  return created.upload.id;
}

const owner = apiClient();
const member = apiClient();
const ownerName = `owner_${suffix}`;
const memberName = `member_${suffix}`;
const subreddit = `platform_${suffix}`.slice(0, 21);
await register(owner, ownerName);
await register(member, memberName);

await owner.request("/v1/subreddits", {
  method: "POST",
  body: {
    name: subreddit,
    displayName: "Android Client Platform",
    description: "Remote deployment smoke-test community",
    accessType: "restricted",
    clientMutationId: crypto.randomUUID(),
  },
});
await member.request(`/v1/subreddits/${subreddit}/join`, { method: "POST" });
await owner.request(`/v1/subreddits/${subreddit}/members/${memberName}`, {
  method: "PUT",
  body: { role: "member" },
});

const textPost = (await owner.request("/v1/posts", {
  method: "POST",
  body: {
    subreddit,
    kind: "text",
    title: "Cloudflare-backed SDUI feed is live",
    body: "This post was created by the deployed-backend smoke flow.",
    clientMutationId: `post-text-${suffix}`,
  },
})).payload.post;

await owner.request("/v1/posts", {
  method: "POST",
  body: {
    subreddit,
    kind: "link",
    title: "Cloudflare Workers documentation",
    url: "https://developers.cloudflare.com/workers/",
    clientMutationId: `post-link-${suffix}`,
  },
});

const imageBytes = new TextEncoder().encode("remote-r2-smoke-image");
const imageId = await createMedia(owner, "image", "image/png", imageBytes);
const imagePost = (await owner.request("/v1/posts", {
  method: "POST",
  body: {
    subreddit,
    kind: "image",
    title: "R2-backed image post",
    mediaId: imageId,
    clientMutationId: `post-image-${suffix}`,
  },
})).payload.post;

const rangeResponse = await fetch(imagePost.media.url, { headers: { range: "bytes=0-5" } });
if (rangeResponse.status !== 206 || (await rangeResponse.text()) !== "remote") {
  throw new Error("R2 signed range request failed");
}

let parentId = null;
for (let depth = 0; depth < 12; depth += 1) {
  const { payload } = await member.request(`/v1/posts/${textPost.id}/comments`, {
    method: "POST",
    body: {
      parentId,
      body: `Remote nested comment ${depth}`,
      clientMutationId: `comment-${suffix}-${depth}`,
    },
  });
  parentId = payload.comment.id;
}

const voteMutation = `vote-${suffix}-stable`;
const firstVote = await member.request(`/v1/posts/${textPost.id}/vote`, {
  method: "PUT",
  body: { value: 1, clientMutationId: voteMutation },
});
const replayVote = await member.request(`/v1/posts/${textPost.id}/vote`, {
  method: "PUT",
  body: { value: 1, clientMutationId: voteMutation },
});
if (firstVote.payload.replayed || !replayVote.payload.replayed || firstVote.payload.vote.score !== 2) {
  throw new Error("Idempotent vote assertion failed");
}

await member.request(`/v1/posts/${textPost.id}/reshare`, {
  method: "POST",
  body: { subreddit, clientMutationId: `reshare-${suffix}` },
});

const small = await member.request(`/v1/posts/${textPost.id}/comments?count=8&depth=10`);
const full = await member.request(`/v1/posts/${textPost.id}/comments?count=200&depth=10`);
const feed = await member.request("/v1/feed?limit=20");
if (!feed.payload.groups.some((group) => group.groupId === textPost.id)) {
  throw new Error("Personalized feed did not contain the subscribed-community post");
}

console.log(JSON.stringify({
  ok: true,
  api: baseUrl,
  subreddit,
  textPostId: textPost.id,
  imagePostId: imagePost.id,
  nestedCommentDepth: 12,
  smallTreeCache: small.response.headers.get("x-comment-tree-cache"),
  fullTreeCount: full.payload.requestedCount,
  feedGroups: feed.payload.groups.length,
  voteScore: firstVote.payload.vote.score,
}));
