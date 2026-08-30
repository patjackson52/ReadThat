import { env, exports } from "cloudflare:workers";
import { describe, expect, it } from "vitest";

interface SessionPayload {
  accessToken: string;
  refreshToken: string;
}

interface ApiResponse {
  [key: string]: unknown;
}

interface TestClient {
  token: string | null;
  bookmark: string | null;
  request(path: string, init?: RequestInit): Promise<{ response: Response; body: ApiResponse }>;
}

let clientSequence = 0;

function client(): TestClient {
  const ip = `192.0.2.${++clientSequence}`;
  const state: TestClient = {
    token: null,
    bookmark: null,
    async request(path, init = {}) {
      const headers = new Headers(init.headers);
      headers.set("cf-connecting-ip", ip);
      if (state.token) headers.set("authorization", `Bearer ${state.token}`);
      if (state.bookmark) headers.set("x-d1-bookmark", state.bookmark);
      const response = await exports.default.fetch(new Request(`http://example.test${path}`, { ...init, headers }));
      state.bookmark = response.headers.get("x-d1-bookmark") ?? state.bookmark;
      const body = response.status === 204 || response.status === 304
        ? {}
        : await response.json<ApiResponse>();
      return { response, body };
    },
  };
  return state;
}

function json(body: unknown, init: RequestInit = {}): RequestInit {
  const headers = new Headers(init.headers);
  headers.set("content-type", "application/json");
  return { ...init, method: init.method ?? "POST", headers, body: JSON.stringify(body) };
}

async function register(testClient: TestClient, username: string): Promise<SessionPayload> {
  const { response, body } = await testClient.request("/v1/auth/register", json({
    username,
    password: "correct horse battery staple",
    displayName: username.toUpperCase(),
  }));
  expect(response.status).toBe(201);
  const session = body.session as SessionPayload;
  expect(session.accessToken).toBeTypeOf("string");
  expect(session.refreshToken).toBeTypeOf("string");
  expect(body.session).not.toHaveProperty("accessHash");
  expect(body.session).not.toHaveProperty("refreshHash");
  testClient.token = session.accessToken;
  return session;
}

async function createSubreddit(testClient: TestClient, name: string, accessType = "public") {
  const { response, body } = await testClient.request("/v1/subreddits", json({
    name,
    displayName: name,
    description: "test community",
    accessType,
    clientMutationId: crypto.randomUUID(),
  }));
  expect(response.status).toBe(201);
  return body.subreddit as { id: string; name: string };
}

async function createTextPost(testClient: TestClient, subreddit: string, mutation: string) {
  const { response, body } = await testClient.request("/v1/posts", json({
    subreddit,
    kind: "text",
    title: `Post ${mutation}`,
    body: "A body optimized for the detail domain model.",
    clientMutationId: mutation,
  }));
  expect(response.status).toBe(201);
  return body.post as { id: string; score: number; viewerVote: number; commentCount: number };
}

async function createImagePost(testClient: TestClient, subreddit: string, mutation: string) {
  const uploaded = await testClient.request("/v1/media/uploads", json({
    kind: "image",
    contentType: "image/png",
    byteSize: 4,
    width: 1200,
    height: 1600,
    altText: `Image ${mutation}`,
  }));
  expect(uploaded.response.status).toBe(201);
  const upload = uploaded.body.upload as { id: string; uploadPath: string; uploadToken: string };
  const stored = await testClient.request(upload.uploadPath, {
    method: "PUT",
    headers: {
      "content-type": "image/png",
      "content-length": "4",
      "x-upload-token": upload.uploadToken,
    },
    body: new Uint8Array([137, 80, 78, 71]),
  });
  expect(stored.response.status).toBe(200);
  const created = await testClient.request("/v1/posts", json({
    subreddit,
    kind: "image",
    title: `Image post ${mutation}`,
    mediaId: upload.id,
    clientMutationId: mutation,
  }));
  expect(created.response.status).toBe(201);
  return created.body.post as { id: string; kind: string; media: { id: string; cacheKey: string } };
}

async function uploadGalleryImage(testClient: TestClient, label: string, width: number, height: number) {
  const uploaded = await testClient.request("/v1/media/uploads", json({
    kind: "image",
    contentType: "image/png",
    byteSize: 4,
    width,
    height,
    altText: label,
  }));
  expect(uploaded.response.status).toBe(201);
  const upload = uploaded.body.upload as { id: string; uploadPath: string; uploadToken: string };
  const stored = await testClient.request(upload.uploadPath, {
    method: "PUT",
    headers: {
      "content-type": "image/png",
      "content-length": "4",
      "x-upload-token": upload.uploadToken,
    },
    body: new Uint8Array([137, 80, 78, 71]),
  });
  expect(stored.response.status).toBe(200);
  return upload.id;
}

describe("ReadThat backend API", () => {
  it("registers, authenticates, rotates refresh tokens, and revokes logout", async () => {
    const api = client();
    const session = await register(api, "auth_user");

    const me = await api.request("/v1/me");
    expect(me.response.status).toBe(200);
    expect((me.body.user as { username: string }).username).toBe("auth_user");
    const iterations = await env.DB.prepare(
      "SELECT password_iterations FROM users WHERE username = 'auth_user'",
    ).first<number>("password_iterations");
    expect(iterations).toBe(100_000);

    const refreshed = await api.request("/v1/auth/refresh", json({ refreshToken: session.refreshToken }));
    expect(refreshed.response.status).toBe(200);
    const next = refreshed.body.session as SessionPayload;
    expect(next.accessToken).not.toBe(session.accessToken);
    api.token = next.accessToken;

    const staleRefresh = await api.request("/v1/auth/refresh", json({ refreshToken: session.refreshToken }));
    expect(staleRefresh.response.status).toBe(401);

    expect((await api.request("/v1/auth/logout", { method: "POST" })).response.status).toBe(204);
    expect((await api.request("/v1/me")).response.status).toBe(401);
  });

  it("serves the promoted editorial profile until its real account is registered", async () => {
    const api = client();
    const preview = await api.request("/v1/users/patrickjackson");
    expect(preview.response.status).toBe(200);
    expect(preview.body.user).toMatchObject({
      id: "editorial:patrickjackson",
      username: "patrickjackson",
      displayName: "Patrick Jackson",
      karma: 0,
    });
    expect(await env.DB.prepare(
      "SELECT COUNT(*) FROM users WHERE username = 'patrickjackson'",
    ).first<number>("COUNT(*)")).toBe(0);

    await register(api, "patrickjackson");
    const registered = await api.request("/v1/users/patrickjackson");
    expect(registered.response.status).toBe(200);
    expect(registered.body.user).toMatchObject({
      username: "patrickjackson",
      displayName: "PATRICKJACKSON",
    });
    expect((registered.body.user as { id: string }).id).not.toBe("editorial:patrickjackson");
  });

  it("deduplicates concurrent subreddit creation UUIDs and rejects payload reuse", async () => {
    const api = client();
    await register(api, "community_idem");
    const mutationId = crypto.randomUUID();
    const payload = {
      name: "idempotent_community",
      displayName: "Idempotent Community",
      description: "Created once",
      accessType: "restricted",
      clientMutationId: mutationId,
    };
    const [first, concurrentReplay] = await Promise.all([
      api.request("/v1/subreddits", json(payload)),
      api.request("/v1/subreddits", json(payload)),
    ]);
    expect([first.response.status, concurrentReplay.response.status].sort()).toEqual([200, 201]);
    expect((first.body.subreddit as { id: string }).id)
      .toBe((concurrentReplay.body.subreddit as { id: string }).id);
    expect([first.body.replayed, concurrentReplay.body.replayed].sort()).toEqual([false, true]);

    const mismatched = await api.request("/v1/subreddits", json({
      ...payload,
      displayName: "Different operation",
    }));
    expect(mismatched.response.status).toBe(409);
    expect(mismatched.body.error).toMatchObject({ code: "mutation_id_reused" });
  });

  it("replays identical post UUIDs and rejects a reused UUID with different content", async () => {
    const api = client();
    await register(api, "post_idem");
    await createSubreddit(api, "post_idem_community");
    const clientMutationId = crypto.randomUUID();
    const payload = {
      subreddit: "post_idem_community",
      kind: "text",
      title: "One logical post",
      body: "Created with retry-safe identity",
      clientMutationId,
    };
    const first = await api.request("/v1/posts", json(payload));
    const replay = await api.request("/v1/posts", json(payload));
    expect(first.response.status).toBe(201);
    expect(replay.response.status).toBe(200);
    expect((first.body.post as { id: string }).id).toBe((replay.body.post as { id: string }).id);
    expect(first.body.replayed).toBe(false);
    expect(replay.body.replayed).toBe(true);

    const mismatched = await api.request("/v1/posts", json({ ...payload, title: "Different post" }));
    expect(mismatched.response.status).toBe(409);
    expect(mismatched.body.error).toMatchObject({ code: "mutation_id_reused" });
  });

  it("lists community flair and persists it on a title-only post and feed cells", async () => {
    const api = client();
    await register(api, "flair_author");
    await createSubreddit(api, "flaircommunity");
    await createSubreddit(api, "otherflaircommunity");

    const listed = await api.request("/v1/subreddits/flaircommunity/flairs");
    expect(listed.response.status).toBe(200);
    const flairs = listed.body.flairs as Array<{
      id: string;
      text: string;
      backgroundColor: string;
      textColor: string;
    }>;
    expect(flairs.map((flair) => flair.text)).toEqual([
      "Discussion", "Question", "Project", "News", "Tutorial",
    ]);
    const question = flairs.find((flair) => flair.text === "Question")!;

    const created = await api.request("/v1/posts", json({
      subreddit: "flaircommunity",
      kind: "text",
      title: "Body text is optional",
      flairId: question.id,
      clientMutationId: crypto.randomUUID(),
    }));
    expect(created.response.status).toBe(201);
    expect(created.body.post).toMatchObject({
      body: "",
      flair: question,
    });

    const feed = await api.request("/v1/feed?limit=5&subreddit=flaircommunity");
    const group = (feed.body.groups as Array<{
      groupId: string;
      cells: Array<{ type: string; flair?: unknown }>;
    }>).find((item) => item.groupId === (created.body.post as { id: string }).id);
    expect(group?.cells.find((cell) => cell.type === "metadata")?.flair).toEqual(question);
    expect(group?.cells.find((cell) => cell.type === "title")?.flair).toEqual(question);

    const otherFlairs = await api.request("/v1/subreddits/otherflaircommunity/flairs");
    const foreignFlair = (otherFlairs.body.flairs as Array<{ id: string }>)[0]!.id;
    const rejected = await api.request("/v1/posts", json({
      subreddit: "flaircommunity",
      kind: "text",
      title: "Wrong community flair",
      flairId: foreignFlair,
      clientMutationId: crypto.randomUUID(),
    }));
    expect(rejected.response.status).toBe(422);
    expect(rejected.body.error).toMatchObject({ code: "invalid_post_flair" });
  });

  it("publishes only owned Cloudflare Images media as a removable profile avatar", async () => {
    const owner = client();
    await register(owner, "avatar_owner");
    const created = await owner.request("/v1/media/uploads", json({
      kind: "image",
      contentType: "image/png",
      byteSize: 4,
      width: 1,
      height: 1,
      altText: "avatar",
    }));
    expect(created.response.status).toBe(201);
    const upload = created.body.upload as {
      id: string;
      uploadToken: string;
      uploadPath: string;
    };
    const uploaded = await owner.request(upload.uploadPath, {
      method: "PUT",
      headers: {
        "content-type": "image/png",
        "content-length": "4",
        "x-upload-token": upload.uploadToken,
      },
      body: new Uint8Array([137, 80, 78, 71]),
    });
    expect(uploaded.response.status).toBe(200);
    await env.DB.prepare(
      `UPDATE media SET delivery_provider = 'images', image_uid = ?, image_status = 'ready'
       WHERE id = ?`,
    ).bind("avatar-image-1", upload.id).run();

    const other = client();
    await register(other, "avatar_other");
    const foreign = await other.request("/v1/me", json({ avatarMediaId: upload.id }, { method: "PATCH" }));
    expect(foreign.response.status).toBe(422);

    const externalUrl = await owner.request("/v1/me", json(
      { avatarUrl: "https://example.test/tracker.png" },
      { method: "PATCH" },
    ));
    expect(externalUrl.response.status).toBe(422);

    const updated = await owner.request("/v1/me", json({
      displayName: "Avatar Owner",
      bio: "Cloudflare-backed profile",
      avatarMediaId: upload.id,
    }, { method: "PATCH" }));
    expect(updated.response.status).toBe(200);
    const user = updated.body.user as { avatarUrl: string; bio: string };
    expect(user.bio).toBe("Cloudflare-backed profile");
    expect(user.avatarUrl).toMatch(/\/v1\/users\/avatar_owner\/avatar\?v=\d+$/u);

    const avatar = await exports.default.fetch(new Request(user.avatarUrl, { redirect: "manual" }));
    expect(avatar.status).toBe(302);
    expect(avatar.headers.get("location")).toMatch(
      /^https:\/\/imagedelivery\.net\/test-account-hash\/avatar-image-1\/feed\?exp=\d+&sig=[a-f0-9]+$/u,
    );
    expect(avatar.headers.get("cache-control")).toContain("stale-while-revalidate");

    const removed = await owner.request("/v1/me", json({ avatarMediaId: null }, { method: "PATCH" }));
    expect(removed.response.status).toBe(200);
    expect((removed.body.user as { avatarUrl: string | null }).avatarUrl).toBeNull();
    expect((await exports.default.fetch(new Request(user.avatarUrl))).status).toBe(404);
  });

  it("rejects oversized consistency metadata with traceable error headers", async () => {
    const response = await exports.default.fetch(new Request("http://example.test/v1/feed", {
      headers: { "x-d1-bookmark": "x".repeat(513) },
    }));
    expect(response.status).toBe(400);
    expect(response.headers.get("x-request-id")).toBeTruthy();
    expect(response.headers.get("referrer-policy")).toBe("no-referrer");
    expect((await response.json<ApiResponse>()).error).toMatchObject({ code: "invalid_d1_bookmark" });
  });

  it("accepts bounded anonymous client performance batches and rejects cardinality leaks", async () => {
    const api = client();
    const valid = {
      schemaVersion: 1,
      platform: "android",
      appVersion: "1.0",
      buildType: "debug",
      sessionId: "123e4567-e89b-42d3-a456-426614174000",
      events: [{
        name: "home_tti",
        value: 87.5,
        unit: "MILLISECOND",
        surface: "FEED",
        outcome: "SUCCESS",
        recordedAtEpochMs: Date.now(),
        attributes: { start_type: "warm", cache_tier: "room" },
        measurements: {},
      }],
    };
    const accepted = await api.request("/v1/telemetry/performance", json(valid));
    expect(accepted.response.status).toBe(202);
    expect(accepted.body).toMatchObject({ accepted: 1 });
    expect(accepted.response.headers.get("server-timing")).toMatch(/^edge;dur=\d+$/u);

    const leaking = structuredClone(valid);
    leaking.events[0]!.attributes = { post_id: "private-content-id" } as never;
    const rejected = await api.request("/v1/telemetry/performance", json(leaking));
    expect(rejected.response.status).toBe(422);
  });

  it("accepts anonymous and authenticated product sessions but rejects raw URLs", async () => {
    const anonymous = client();
    const valid = {
      schemaVersion: 1,
      platform: "android",
      appVersion: "1.0",
      buildType: "debug",
      installationId: "123e4567-e89b-42d3-a456-426614174001",
      sessionId: "123e4567-e89b-42d3-a456-426614174002",
      events: [{
        name: "post_impression",
        surface: "FEED",
        recordedAtEpochMs: Date.now(),
        contentId: "post:123e4567-e89b-42d3-a456-426614174003",
        contentType: "POST",
      }, {
        name: "media_playback",
        surface: "DETAIL",
        recordedAtEpochMs: Date.now(),
        contentId: "media-123",
        contentType: "VIDEO",
        reason: "PAUSE",
        durationMs: 4_250,
        position: 2_000,
        completionPercent: 31.5,
      }, {
        name: "ad_video_watch",
        surface: "AD_DETAIL",
        recordedAtEpochMs: Date.now(),
        contentId: "patrick-platform-01",
        contentType: "AD",
        reason: "SURFACE_CHANGE",
        durationMs: 12_500,
        position: 18_000,
        completionPercent: 72,
      }],
    };
    const acceptedAnonymous = await anonymous.request("/v1/telemetry/product", json(valid));
    expect(acceptedAnonymous.response.status).toBe(202);
    expect(acceptedAnonymous.body).toMatchObject({ accepted: 3 });

    const signedIn = client();
    await register(signedIn, "analytics_user");
    const acceptedUser = await signedIn.request("/v1/telemetry/product", json(valid));
    expect(acceptedUser.response.status).toBe(202);

    const leaking = structuredClone(valid);
    leaking.events[0]!.contentId = "https://example.com/private?token=secret";
    const rejected = await anonymous.request("/v1/telemetry/product", json(leaking));
    expect(rejected.response.status).toBe(422);
  });

  it("enforces restricted posting ACLs and applies an idempotent vote exactly once", async () => {
    const owner = client();
    const member = client();
    await register(owner, "acl_owner");
    await register(member, "acl_member");
    await createSubreddit(owner, "restricteddev", "restricted");

    expect((await member.request("/v1/subreddits/restricteddev/join", { method: "POST" })).response.status).toBe(200);
    const rejected = await member.request("/v1/posts", json({
      subreddit: "restricteddev",
      kind: "text",
      title: "not approved yet",
      body: "body",
      clientMutationId: "post-before-approval",
    }));
    expect(rejected.response.status).toBe(403);

    const promoted = await owner.request(
      "/v1/subreddits/restricteddev/members/acl_member",
      json({ role: "member" }, { method: "PUT" }),
    );
    expect(promoted.response.status).toBe(200);
    const post = await createTextPost(member, "restricteddev", "post-after-approval");
    expect(post.score).toBe(1);
    expect(post.viewerVote).toBe(1);

    owner.bookmark = member.bookmark;
    const firstVote = await owner.request(`/v1/posts/${post.id}/vote`, json({
      value: 1,
      clientMutationId: "owner-vote-0001",
    }, { method: "PUT" }));
    expect(firstVote.response.status).toBe(200);
    expect((firstVote.body.vote as { score: number }).score).toBe(2);
    expect(firstVote.body.replayed).toBe(false);

    const retry = await owner.request(`/v1/posts/${post.id}/vote`, json({
      value: 1,
      clientMutationId: "owner-vote-0001",
    }, { method: "PUT" }));
    expect(retry.response.status).toBe(200);
    expect((retry.body.vote as { score: number }).score).toBe(2);
    expect(retry.body.replayed).toBe(true);

    const misuse = await owner.request(`/v1/posts/${post.id}/vote`, json({
      value: -1,
      clientMutationId: "owner-vote-0001",
    }, { method: "PUT" }));
    expect(misuse.response.status).toBe(409);
  });

  it("creates deeply nested comments and returns count/depth cursors with a cacheable 8/200 split", async () => {
    const api = client();
    await register(api, "commenter");
    await env.DB.prepare(
      "UPDATE users SET display_name = ?, avatar_url = ?, updated_at = ? WHERE username = ?",
    ).bind("Comment Display Name", "https://cdn.example/commenter.jpg", Date.now(), "commenter").run();
    await createSubreddit(api, "deepthreads");
    const post = await createTextPost(api, "deepthreads", "deep-post-0001");
    let parentId: string | null = null;
    let rootCommentId: string | null = null;
    for (let index = 0; index < 14; index += 1) {
      const created = await api.request(`/v1/posts/${post.id}/comments`, json({
        parentId,
        body: `nested comment ${index}`,
        clientMutationId: `deep-comment-${index.toString().padStart(4, "0")}`,
      }));
      expect(created.response.status).toBe(201);
      parentId = (created.body.comment as { id: string }).id;
      if (index === 0) {
        rootCommentId = parentId;
        expect(created.body.comment).toMatchObject({
          author: "u/commenter",
          displayName: "Comment Display Name",
          avatarUrl: "https://cdn.example/commenter.jpg",
          isEdited: false,
        });
      }
    }
    expect(rootCommentId).toBeTruthy();
    await env.DB.prepare("UPDATE comments SET edited_at = ? WHERE id = ?")
      .bind(Date.now(), rootCommentId).run();

    const small = await api.request(`/v1/posts/${post.id}/comments?count=8&depth=10`);
    expect(small.response.status).toBe(200);
    expect(small.body.requestedCount).toBe(8);
    expect(small.body.cacheStatus).toBe("miss");
    expect(JSON.stringify(small.body)).toContain("load_more");
    expect((small.body.roots as Array<Record<string, unknown>>)[0]).toMatchObject({
      author: "u/commenter",
      displayName: "Comment Display Name",
      avatarUrl: "https://cdn.example/commenter.jpg",
      isEdited: true,
    });

    const smallAgain = await api.request(`/v1/posts/${post.id}/comments?count=8&depth=10`);
    expect(smallAgain.response.status).toBe(200);
    expect(smallAgain.body.cacheStatus).toBe("hit");

    const full = await api.request(`/v1/posts/${post.id}/comments?count=200&depth=10`);
    expect(full.response.status).toBe(200);
    expect(full.body.requestedCount).toBe(200);
    expect(JSON.stringify(full.body)).toContain("load_more");
  });

  it("serializes identical comment retries and rejects mutation UUID reuse", async () => {
    const api = client();
    await register(api, "comment_idem");
    await createSubreddit(api, "commentidem");
    const post = await createTextPost(api, "commentidem", "comment-idem-post");
    const payload = {
      parentId: null,
      body: "One logical comment",
      clientMutationId: "same-comment-mutation",
    };

    const attempts = await Promise.all(Array.from({ length: 12 }, () => api.request(
      `/v1/posts/${post.id}/comments`,
      json(payload),
    )));
    expect(attempts.every(({ response }) => response.status === 200 || response.status === 201)).toBe(true);
    const ids = attempts.map(({ body }) => (body.comment as { id: string }).id);
    expect(new Set(ids).size).toBe(1);
    expect(attempts.filter(({ body }) => body.replayed === false)).toHaveLength(1);

    const mismatched = await api.request(`/v1/posts/${post.id}/comments`, json({
      ...payload,
      body: "A different command using the same identity",
    }));
    expect(mismatched.response.status).toBe(409);
    expect(mismatched.body.error).toMatchObject({ code: "mutation_id_reused" });

    const stored = await env.DB.prepare(
      "SELECT COUNT(*) AS count FROM comments WHERE post_id = ? AND author_id = (SELECT id FROM users WHERE username = ?)",
    ).bind(post.id, "comment_idem").first<number>("count");
    expect(stored).toBe(1);
  });

  it("chunks long comment continuations into bounded progressive payloads", async () => {
    const api = client();
    await register(api, "long_threader");
    await createSubreddit(api, "longthreads");
    const post = await createTextPost(api, "longthreads", "long-thread-post");
    const authorId = await env.DB.prepare(
      "SELECT id FROM users WHERE username = 'long_threader'",
    ).first<string>("id");
    expect(authorId).toBeTruthy();
    if (!authorId) throw new Error("Missing test author");

    const now = Date.now();
    const inserts = Array.from({ length: 120 }, (_, index) => env.DB.prepare(
      `INSERT INTO comments (
         id, post_id, parent_id, author_id, body, depth,
         client_mutation_id, created_at, updated_at
       ) VALUES (?, ?, NULL, ?, ?, 0, ?, ?, ?)`,
    ).bind(
      crypto.randomUUID(),
      post.id,
      authorId,
      `root comment ${index}`,
      `long-root-${index.toString().padStart(4, "0")}`,
      now + index,
      now + index,
    ));
    await env.DB.batch(inserts.slice(0, 60));
    await env.DB.batch(inserts.slice(60));

    const page = await api.request(`/v1/posts/${post.id}/comments?count=8&depth=10`);
    expect(page.response.status).toBe(200);
    const cursors = (page.body.roots as Array<{
      type: string;
      id: string;
      childIds?: string[];
    }>).filter((node) => node.type === "load_more");
    expect(cursors.map((cursor) => cursor.childIds?.length)).toEqual([100, 12]);
    expect(new Set(cursors.map((cursor) => cursor.id)).size).toBe(2);

    const more = await api.request(`/v1/posts/${post.id}/comments/more`, json({
      childIds: cursors[0]?.childIds,
      limit: 100,
      maxDepth: 10,
    }));
    expect(more.response.status).toBe(200);
    expect((more.body.comments as unknown[])).toHaveLength(100);
  });

  it("keyset-pages a personalized feed without duplicates and binds its cursor to the viewer", async () => {
    const owner = client();
    await register(owner, "feed_owner");
    await createSubreddit(owner, "infinitefeed");
    for (let index = 0; index < 9; index += 1) {
      await createTextPost(owner, "infinitefeed", `feed-post-${index.toString().padStart(4, "0")}`);
    }

    const ids: string[] = [];
    let cursor: string | null = null;
    do {
      const path = `/v1/feed?limit=3&subreddit=infinitefeed${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`;
      const page = await owner.request(path);
      expect(page.response.status).toBe(200);
      const groups = page.body.groups as Array<{ groupId: string }>;
      ids.push(...groups.map((group) => group.groupId));
      cursor = page.body.nextCursor as string | null;
    } while (cursor);
    expect(ids).toHaveLength(9);
    expect(new Set(ids).size).toBe(9);

    const first = await owner.request("/v1/feed?limit=3&subreddit=infinitefeed");
    const ownerCursor = first.body.nextCursor as string;
    const other = client();
    await register(other, "feed_other");
    const replay = await other.request(
      `/v1/feed?limit=3&subreddit=infinitefeed&cursor=${encodeURIComponent(ownerCursor)}`,
    );
    expect(replay.response.status).toBe(400);
    expect(replay.body.error).toMatchObject({ code: "invalid_cursor" });
  });

  it("interleaves stable promoted ids only into the first home page", async () => {
    const api = client();
    await register(api, "promoted_feed_user");
    await createSubreddit(api, "promotedfeed");
    for (let index = 0; index < 12; index += 1) {
      await createTextPost(api, "promotedfeed", `promoted-feed-${index.toString().padStart(2, "0")}`);
    }

    const defaultHome = await api.request("/v1/feed?limit=9");
    expect((defaultHome.body.groups as Array<{ groupId: string }>).some(
      (group) => group.groupId.startsWith("promoted:"),
    )).toBe(false);

    const home = await api.request("/v1/feed?limit=9&includePromoted=true");
    expect(home.response.status).toBe(200);
    const groups = home.body.groups as Array<{
      groupId: string;
      cells: Array<{ type: string; adId?: string; items?: unknown[] }>;
    }>;
    const promoted = groups.filter((group) => group.groupId.startsWith("promoted:"));
    expect(promoted.map((group) => group.groupId)).toEqual([
      "promoted:patrick-platform-01",
      "promoted:patrick-systems-02",
    ]);
    expect(groups[3]?.groupId).toBe("promoted:patrick-platform-01");
    expect(groups[9]?.groupId).toBe("promoted:patrick-systems-02");
    expect(promoted[0]?.cells.map((cell) => cell.type)).toEqual([
      "ad_header",
      "ad_title",
      "ad_media",
      "ad_summary",
      "ad_related_posts",
      "ad_actionbar",
    ]);
    expect(promoted[0]?.cells.find((cell) => cell.type === "ad_header")).toMatchObject({
      author: "patrickjackson",
      label: "Ad",
    });
    expect(promoted[1]?.cells.find((cell) => cell.type === "ad_media")?.items).toHaveLength(3);

    const subreddit = await api.request("/v1/feed?limit=9&subreddit=promotedfeed");
    expect((subreddit.body.groups as Array<{ groupId: string }>).some(
      (group) => group.groupId.startsWith("promoted:"),
    )).toBe(false);

    const nextCursor = home.body.nextCursor as string;
    const nextPage = await api.request(
      `/v1/feed?limit=9&includePromoted=true&cursor=${encodeURIComponent(nextCursor)}`,
    );
    expect((nextPage.body.groups as Array<{ groupId: string }>).some(
      (group) => group.groupId.startsWith("promoted:"),
    )).toBe(false);
  });

  it("pages a typed media-only feed, puts the tapped anchor first, and binds its cursor", async () => {
    const owner = client();
    await register(owner, "media_feed_owner");
    await createSubreddit(owner, "mediafeeddev");
    await createTextPost(owner, "mediafeeddev", "media-feed-text");
    const mediaPosts = [];
    for (let index = 0; index < 5; index += 1) {
      mediaPosts.push(await createImagePost(
        owner,
        "mediafeeddev",
        `media-feed-image-${index.toString().padStart(2, "0")}`,
      ));
    }

    const anchor = mediaPosts[2]!;
    const first = await owner.request(
      `/v1/feeds/media?limit=3&subreddit=mediafeeddev&anchorPostId=${anchor.id}`,
    );
    expect(first.response.status).toBe(200);
    expect(first.body).toMatchObject({
      schemaVersion: 1,
      feedId: "media:subreddit:mediafeeddev",
      anchorIncluded: true,
    });
    const firstItems = first.body.items as Array<{
      id: string;
      kind: string;
      media: { id: string; cacheKey: string };
    }>;
    expect(firstItems).toHaveLength(3);
    expect(firstItems[0]?.id).toBe(anchor.id);
    expect(firstItems.every((item) => item.kind === "image" || item.kind === "video")).toBe(true);
    expect(firstItems.every((item) => item.media.cacheKey.startsWith("image:"))).toBe(true);

    const cursor = first.body.nextCursor as string;
    expect(cursor).toBeTypeOf("string");
    const second = await owner.request(
      `/v1/feeds/media?limit=3&subreddit=mediafeeddev&cursor=${encodeURIComponent(cursor)}`,
    );
    expect(second.response.status).toBe(200);
    expect(second.body.anchorIncluded).toBe(false);
    const allIds = [
      ...firstItems.map((item) => item.id),
      ...(second.body.items as Array<{ id: string }>).map((item) => item.id),
    ];
    expect(new Set(allIds).size).toBe(mediaPosts.length);

    const other = client();
    await register(other, "media_feed_other");
    const replay = await other.request(
      `/v1/feeds/media?limit=3&subreddit=mediafeeddev&cursor=${encodeURIComponent(cursor)}`,
    );
    expect(replay.response.status).toBe(400);
    expect(replay.body.error).toMatchObject({ code: "invalid_cursor" });
  });

  it("preserves ordered photo galleries across post, SDUI feed, MediaFeed, replay, and reshare", async () => {
    const api = client();
    await register(api, "gallery_owner");
    await createSubreddit(api, "gallerydev");
    await createSubreddit(api, "galleryshare");
    const mediaIds = [
      await uploadGalleryImage(api, "First photo", 1200, 900),
      await uploadGalleryImage(api, "Second photo", 900, 1200),
      await uploadGalleryImage(api, "Third photo", 1600, 900),
    ];
    const mutation = "gallery-post-0001";
    const payload = {
      subreddit: "gallerydev",
      kind: "image",
      title: "An ordered gallery",
      mediaIds,
      clientMutationId: mutation,
    };

    const created = await api.request("/v1/posts", json(payload));
    expect(created.response.status).toBe(201);
    const post = created.body.post as {
      id: string;
      media: { id: string };
      mediaItems: Array<{ id: string; altText: string }>;
    };
    expect(post.media.id).toBe(mediaIds[0]);
    expect(post.mediaItems.map((item) => item.id)).toEqual(mediaIds);
    expect(post.mediaItems.map((item) => item.altText)).toEqual([
      "First photo", "Second photo", "Third photo",
    ]);

    const replay = await api.request("/v1/posts", json(payload));
    expect(replay.response.status).toBe(200);
    expect(replay.body.replayed).toBe(true);
    const reorderedReplay = await api.request("/v1/posts", json({
      ...payload,
      mediaIds: [...mediaIds].reverse(),
    }));
    expect(reorderedReplay.response.status).toBe(409);

    const feed = await api.request("/v1/feed?limit=10&subreddit=gallerydev");
    const group = (feed.body.groups as Array<{
      groupId: string;
      cells: Array<{ type: string; items?: Array<{ mediaId: string }> }>;
    }>).find((item) => item.groupId === post.id);
    const carousel = group?.cells.find((cell) => cell.type === "image_carousel");
    expect(carousel?.items?.map((item) => item.mediaId)).toEqual(mediaIds);

    const mediaFeed = await api.request(
      `/v1/feeds/media?limit=2&subreddit=gallerydev&anchorPostId=${post.id}`,
    );
    const anchor = (mediaFeed.body.items as Array<{
      id: string;
      mediaItems: Array<{ id: string }>;
    }>)[0];
    expect(anchor?.id).toBe(post.id);
    expect(anchor?.mediaItems.map((item) => item.id)).toEqual(mediaIds);

    const reshared = await api.request(`/v1/posts/${post.id}/reshare`, json({
      subreddit: "galleryshare",
      clientMutationId: "gallery-reshare-0001",
    }));
    expect(reshared.response.status).toBe(201);
    expect((reshared.body.post as { mediaItems: Array<{ id: string }> }).mediaItems.map(
      (item) => item.id,
    )).toEqual(mediaIds);
  });

  it("streams media through R2, emits media SDUI, and serves byte ranges", async () => {
    const api = client();
    await register(api, "media_user");
    await createSubreddit(api, "mediadev");
    const bytes = new TextEncoder().encode("fake-image-body");
    const created = await api.request("/v1/media/uploads", json({
      kind: "image",
      contentType: "image/png",
      byteSize: bytes.byteLength,
      width: 800,
      height: 600,
      altText: "A fake test image",
    }));
    expect(created.response.status).toBe(201);
    const upload = created.body.upload as { id: string; uploadToken: string };
    const uploaded = await api.request(`/v1/media/uploads/${upload.id}`, {
      method: "PUT",
      headers: {
        "content-type": "image/png",
        "content-length": String(bytes.byteLength),
        "x-upload-token": upload.uploadToken,
      },
      body: bytes,
    });
    expect(uploaded.response.status).toBe(200);

    const postCreated = await api.request("/v1/posts", json({
      subreddit: "mediadev",
      kind: "image",
      title: "R2-backed image",
      mediaId: upload.id,
      clientMutationId: "media-post-0001",
    }));
    expect(postCreated.response.status).toBe(201);
    const post = postCreated.body.post as { id: string; media: { url: string } };
    expect(post.media.url).toContain(`/v1/media/${upload.id}`);

    const mediaUrl = new URL(post.media.url);
    const mediaExpiry = Number(mediaUrl.searchParams.get("expires"));
    expect(mediaExpiry - Date.now()).toBeGreaterThan(9 * 60 * 1_000);
    expect(mediaExpiry - Date.now()).toBeLessThanOrEqual(11 * 60 * 1_000);
    const ranged = await exports.default.fetch(new Request(mediaUrl, { headers: { range: "bytes=0-3" } }));
    expect(ranged.status).toBe(206);
    expect(ranged.headers.get("content-length")).toBe("4");
    expect(ranged.headers.get("content-range")).toBe(`bytes 0-3/${bytes.byteLength}`);
    expect(new TextDecoder().decode(await ranged.arrayBuffer())).toBe("fake");

    const head = await exports.default.fetch(new Request(mediaUrl, { method: "HEAD" }));
    expect(head.status).toBe(200);
    expect(head.headers.get("content-length")).toBe(String(bytes.byteLength));
    expect(head.headers.get("content-range")).toBeNull();
    expect((await head.arrayBuffer()).byteLength).toBe(0);

    const feed = await api.request("/v1/feed?limit=10");
    expect(feed.response.status).toBe(200);
    expect(JSON.stringify(feed.body)).toContain('"type":"image"');
  });

  it("bounds and validates signed Stream webhook bodies", async () => {
    const sentAt = Math.floor(Date.now() / 1_000);
    const oversized = await exports.default.fetch(new Request(
      "http://example.test/v1/media/stream/webhook",
      {
        method: "POST",
        headers: {
          "content-length": String(64 * 1024 + 1),
          "content-type": "application/json",
          "webhook-signature": `time=${sentAt},sig1=invalid`,
        },
        body: "{}",
      },
    ));
    expect(oversized.status).toBe(413);
    expect((await oversized.json<ApiResponse>()).error).toMatchObject({ code: "payload_too_large" });

    const malformedBody = "not-json";
    const signature = await streamWebhookSignature(sentAt, malformedBody);
    const malformed = await exports.default.fetch(new Request(
      "http://example.test/v1/media/stream/webhook",
      {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "webhook-signature": `time=${sentAt},sig1=${signature}`,
        },
        body: malformedBody,
      },
    ));
    expect(malformed.status).toBe(400);
    expect((await malformed.json<ApiResponse>()).error).toMatchObject({ code: "invalid_webhook_json" });
  });

  it("serializes concurrent retries of one vote mutation", async () => {
    const author = client();
    const voter = client();
    await register(author, "concurrent_author");
    await register(voter, "concurrent_voter");
    await createSubreddit(author, "concurrency");
    const post = await createTextPost(author, "concurrency", "concurrent-post-0001");
    voter.bookmark = author.bookmark;

    const attempts = await Promise.all(Array.from({ length: 12 }, () => voter.request(
      `/v1/posts/${post.id}/vote`,
      json({ value: 1, clientMutationId: "same-concurrent-vote" }, { method: "PUT" }),
    )));
    expect(attempts.every(({ response }) => response.status === 200)).toBe(true);
    const scores = attempts.map(({ body }) => (body.vote as { score: number }).score);
    expect(new Set(scores)).toEqual(new Set([2]));
    expect(attempts.filter(({ body }) => body.replayed === false)).toHaveLength(1);

    const stored = await env.DB.prepare(
      "SELECT score FROM posts WHERE id = ?",
    ).bind(post.id).first<number>("score");
    expect(stored).toBe(2);
  });

  it("searches trigger-indexed content with ACL-safe sections and stable cursors", async () => {
    const owner = client();
    const outsider = client();
    await register(owner, "search_owner");
    await register(outsider, "search_outsider");
    await createSubreddit(owner, "quantumandroid");
    const first = await createTextPost(owner, "quantumandroid", "quantum-one");
    await createTextPost(owner, "quantumandroid", "quantum-two");
    await createTextPost(owner, "quantumandroid", "quantum-three");
    const mature = await createTextPost(owner, "quantumandroid", "maturetypeaheadneedle");
    await env.DB.prepare("UPDATE posts SET is_mature = 1 WHERE id = ?").bind(mature.id).run();
    const comment = await owner.request(`/v1/posts/${first.id}/comments`, json({
      parentId: null,
      body: "Quantum Android renderer discussion",
      clientMutationId: "search-comment-0001",
    }));
    expect(comment.response.status).toBe(201);
    const commentId = (comment.body.comment as { id: string }).id;

    const typeahead = await owner.request("/v1/search/typeahead?q=quant&limit=8");
    expect(typeahead.response.status).toBe(200);
    expect(JSON.stringify(typeahead.body)).toContain("quantumandroid");

    const safeTypeahead = await owner.request("/v1/search/typeahead?q=maturetype&limit=8");
    expect(safeTypeahead.response.status).toBe(200);
    expect(JSON.stringify(safeTypeahead.body)).not.toContain("Post maturetypeaheadneedle");

    const safePosts = await owner.request("/v1/search?q=maturetype&type=posts");
    expect(JSON.stringify(safePosts.body)).not.toContain("maturetypeaheadneedle");
    const unsafePosts = await owner.request("/v1/search?q=maturetype&type=posts&safe=false");
    expect(JSON.stringify(unsafePosts.body)).toContain("maturetypeaheadneedle");

    const all = await owner.request("/v1/search?q=quantum&type=all");
    expect(all.response.status).toBe(200);
    expect(JSON.stringify(all.body)).toContain("quantumandroid");
    expect(JSON.stringify(all.body)).toContain("Quantum Android renderer discussion");

    const focused = await owner.request(
      `/v1/posts/${first.id}/comments?count=20&depth=10&focusCommentId=${commentId}`,
    );
    expect(focused.response.status).toBe(200);
    expect((focused.body.roots as Array<{ id: string }>)[0]?.id).toBe(commentId);

    const pageOne = await owner.request("/v1/search?q=quantum&type=posts&sort=new&limit=2");
    expect(pageOne.response.status).toBe(200);
    const firstItems = pageOne.body.items as Array<{ id: string }>;
    expect(firstItems).toHaveLength(2);
    const cursor = pageOne.body.nextCursor as string;
    expect(cursor).toBeTypeOf("string");
    const pageTwo = await owner.request(
      `/v1/search?q=quantum&type=posts&sort=new&limit=2&cursor=${encodeURIComponent(cursor)}`,
    );
    expect(pageTwo.response.status).toBe(200);
    const secondItems = pageTwo.body.items as Array<{ id: string }>;
    expect(secondItems.length).toBeGreaterThan(0);
    expect(new Set([...firstItems, ...secondItems].map((item) => item.id)).size)
      .toBe(firstItems.length + secondItems.length);

    await createSubreddit(owner, "privatequantum", "private");
    await createTextPost(owner, "privatequantum", "private-quantum-post");
    outsider.bookmark = owner.bookmark;
    const hidden = await outsider.request("/v1/search?q=private&type=all");
    expect(hidden.response.status).toBe(200);
    expect(JSON.stringify(hidden.body)).not.toContain("privatequantum");
    expect(JSON.stringify(hidden.body)).not.toContain("private-quantum-post");

    const replay = await outsider.request(
      `/v1/search?q=quantum&type=posts&sort=new&limit=2&cursor=${encodeURIComponent(cursor)}`,
    );
    expect(replay.response.status).toBe(400);
    expect(replay.body.error).toMatchObject({ code: "invalid_cursor" });
  });

  it("serves an ETag-paged community drawer and applies offline visit commands in order", async () => {
    const api = client();
    const outsider = client();
    await register(api, "drawer_owner");
    await register(outsider, "drawer_outsider");
    await createSubreddit(api, "drawer_alpha");
    await createSubreddit(api, "drawer_beta");
    await createSubreddit(api, "drawer_gamma");

    const first = await api.request("/v1/me/community-drawer?limit=2");
    expect(first.response.status).toBe(200);
    expect(first.response.headers.get("etag")).toMatch(/^"drawer-/u);
    const firstCommunities = first.body.communities as Array<{ name: string }>;
    expect(firstCommunities).toHaveLength(2);
    const cursor = first.body.nextCursor as string;
    expect(cursor).toBeTypeOf("string");

    const second = await api.request(
      `/v1/me/community-drawer?limit=2&cursor=${encodeURIComponent(cursor)}`,
    );
    expect(second.response.status).toBe(200);
    const allNames = [
      ...firstCommunities,
      ...(second.body.communities as Array<{ name: string }>),
    ].map((community) => community.name);
    expect(allNames).toEqual(["drawer_alpha", "drawer_beta", "drawer_gamma"]);

    const etag = first.response.headers.get("etag") ?? "";
    const unchanged = await api.request("/v1/me/community-drawer", {
      headers: { "if-none-match": etag },
    });
    expect(unchanged.response.status).toBe(304);

    const occurredAt = Date.now();
    const commands = [
      { id: crypto.randomUUID(), operation: "visit", name: "drawer_alpha", occurredAt },
      { id: crypto.randomUUID(), operation: "clear", occurredAt },
      { id: crypto.randomUUID(), operation: "visit", name: "drawer_beta", occurredAt: occurredAt + 1 },
    ];
    const synced = await api.request("/v1/me/community-visits", json({
      commands,
    }, { method: "PUT" }));
    expect(synced.response.status).toBe(200);
    expect(synced.body.applied).toHaveLength(3);

    const changed = await api.request("/v1/me/community-drawer", {
      headers: { "if-none-match": etag },
    });
    expect(changed.response.status).toBe(200);
    expect(changed.response.headers.get("etag")).not.toBe(etag);
    expect(changed.body.recentlyVisited).toMatchObject([{ name: "drawer_beta" }]);

    const changedEtag = changed.response.headers.get("etag") ?? "";
    const replayed = await api.request("/v1/me/community-visits", json({ commands }, { method: "PUT" }));
    expect(replayed.response.status).toBe(200);
    const stableAfterReplay = await api.request("/v1/me/community-drawer", {
      headers: { "if-none-match": changedEtag },
    });
    expect(stableAfterReplay.response.status).toBe(304);

    const removed = await api.request("/v1/me/community-visits", json({
      commands: [{
        id: crypto.randomUUID(),
        operation: "remove",
        name: "drawer_beta",
        occurredAt: occurredAt + 2,
      }],
    }, { method: "PUT" }));
    expect(removed.response.status).toBe(200);
    const empty = await api.request("/v1/me/community-drawer");
    expect(empty.body.recentlyVisited).toEqual([]);

    await createSubreddit(api, "drawer_private", "private");
    const hiddenVisit = await outsider.request("/v1/me/community-visits", json({
      commands: [{
        id: crypto.randomUUID(),
        operation: "visit",
        name: "drawer_private",
        occurredAt: Date.now(),
      }],
    }, { method: "PUT" }));
    expect(hiddenVisit.response.status).toBe(200);
    const outsiderDrawer = await outsider.request("/v1/me/community-drawer");
    expect(JSON.stringify(outsiderDrawer.body)).not.toContain("drawer_private");
  });

  it("orders per-post WebSocket events behind a replay barrier", async () => {
    const api = client();
    await register(api, "live_user");
    await createSubreddit(api, "liveevents");
    const post = await createTextPost(api, "liveevents", "live-post-0001");
    const response = await exports.default.fetch(new Request(
      `http://example.test/v1/posts/${post.id}/live?after=0`,
      { headers: { upgrade: "websocket", authorization: `Bearer ${api.token}` } },
    ));
    expect(response.status).toBe(101);
    const socket = response.webSocket;
    expect(socket).toBeTruthy();
    if (!socket) throw new Error("Missing WebSocket on upgrade response");
    const readyPromise = nextWebSocketJson(socket);
    socket.accept();
    expect(await readyPromise).toMatchObject({ type: "ready", sequence: 0 });

    const eventPromise = nextWebSocketJson(socket);
    const created = await api.request(`/v1/posts/${post.id}/comments`, json({
      parentId: null,
      body: "live comment",
      clientMutationId: "live-comment-0001",
    }));
    expect(created.response.status).toBe(201);
    expect(await eventPromise).toMatchObject({
      type: "comment.created",
      postId: post.id,
      sequence: 1,
    });
    socket.close(1000, "test complete");
  });
});

function nextWebSocketJson(socket: WebSocket): Promise<Record<string, unknown>> {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error("Timed out waiting for WebSocket event")), 2_000);
    socket.addEventListener("message", (event) => {
      clearTimeout(timeout);
      try {
        resolve(JSON.parse(String(event.data)) as Record<string, unknown>);
      } catch (error) {
        reject(error);
      }
    }, { once: true });
  });
}

async function streamWebhookSignature(sentAt: number, body: string): Promise<string> {
  const encoder = new TextEncoder();
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode("test-only-stream".padEnd(32, "x")),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = new Uint8Array(await crypto.subtle.sign(
    "HMAC",
    key,
    encoder.encode(`${sentAt}.${body}`),
  ));
  return [...signature].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}
