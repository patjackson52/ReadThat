import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const catalog = JSON.parse(await readFile(
  join(scriptDirectory, "../fixtures/readthat-case-study.json"),
  "utf8",
));
const planOnly = process.argv.includes("--plan");
const verifyAds = process.argv.includes("--verify-ads");
const local = process.env.D1_LOCAL === "1";
const database = process.env.D1_DATABASE || catalog.collection.database;
const wranglerConfig = process.env.WRANGLER_CONFIG?.trim() || null;
const baseUrl = (process.env.API_BASE_URL
  || (local ? "http://127.0.0.1:8787" : catalog.collection.deployedApi)).replace(/\/$/u, "");
const version = catalog.collection.fixtureVersion;
const namespace = `readthat-case-study:v${version}`;
const editorialUserId = "editorial:patrickjackson";

function deterministicUuid(value) {
  const bytes = createHash("sha256").update(value).digest().subarray(0, 16);
  bytes[6] = (bytes[6] & 0x0f) | 0x50;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function sqlString(value) {
  return value === null ? "NULL" : `'${String(value).replaceAll("'", "''")}'`;
}

function communityId() {
  return deterministicUuid(`${namespace}:community:${catalog.community.name}`);
}

function flairId() {
  return deterministicUuid(`${namespace}:flair:case-study`);
}

function postId(slug) {
  return deterministicUuid(`${namespace}:post:${slug}`);
}

function postBody(post) {
  if (typeof post.body === "string") return post.body.trim();
  return [
    `**Problem:** ${post.problem}`,
    `**Tradeoff:** ${post.tradeoff}`,
    `**Solution:** ${post.solution}`,
    `**Deep dive:** [Read the full case study](${post.url})`,
  ].join("\n");
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

async function fetchJson(url, attempts = 5) {
  let lastError;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      const response = await fetch(url, { headers: { accept: "application/json" } });
      if (response.ok) return response.json();
      lastError = new Error(`${url}: ${response.status} ${await response.text()}`);
      if (response.status !== 429 && response.status < 500) break;
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, attempt * 500));
  }
  throw lastError;
}

function validateCatalog() {
  if (!Number.isInteger(version) || version < 1) throw new Error("fixtureVersion must be positive");
  if (!Number.isInteger(catalog.collection.feedBalanceRevision) || catalog.collection.feedBalanceRevision < 1) {
    throw new Error("feedBalanceRevision must be positive");
  }
  if (catalog.collection.author !== "patrickjackson") throw new Error("The editorial author must be patrickjackson");
  if (catalog.community.name !== catalog.collection.community) throw new Error("Community names do not match");
  if (!/^[a-z0-9_]{3,21}$/u.test(catalog.community.name)) throw new Error("Invalid community name");
  if (catalog.community.displayName.length < 1 || catalog.community.displayName.length > 50) {
    throw new Error("Community display name must be 1–50 characters");
  }
  if (catalog.community.description.length > 500) throw new Error("Community description exceeds 500 characters");
  if (catalog.posts.length !== 11) throw new Error("Expected the overview plus ten case-study posts");

  const slugs = new Set();
  const urls = new Set();
  for (const post of catalog.posts) {
    if (!/^[a-z0-9-]+$/u.test(post.slug) || slugs.has(post.slug)) throw new Error(`Invalid or duplicate slug: ${post.slug}`);
    if (urls.has(post.url)) throw new Error(`Duplicate case-study URL: ${post.url}`);
    const parsedUrl = new URL(post.url);
    if (parsedUrl.protocol !== "https:" || parsedUrl.hostname !== "patrickjackson.dev") {
      throw new Error(`${post.slug}: deep-dive URL must be on patrickjackson.dev over HTTPS`);
    }
    if (post.title.length < 1 || post.title.length > 300) throw new Error(`${post.slug}: invalid title length`);
    const hasCustomBody = typeof post.body === "string";
    if (!hasCustomBody && ![post.problem, post.tradeoff, post.solution].every((value) => value.length >= 40)) {
      throw new Error(`${post.slug}: TLDR sections must be substantive`);
    }
    if (!Number.isFinite(Date.parse(post.publishedAt))) throw new Error(`${post.slug}: invalid publishedAt`);
    const body = postBody(post);
    const supportedLabels = ["**Problem:**", "**Tradeoff:**", "**Solution:**", "**Deep dive:**"];
    if (
      body.length < 40
      || body.length > 40_000
      || /^#{1,6}\s/imu.test(body)
      || /\n\s*\n/u.test(body)
      || !body.includes(`](${post.url})`)
      || (!hasCustomBody && !supportedLabels.every((label) => body.includes(label)))
    ) {
      throw new Error(`${post.slug}: invalid post body`);
    }
    slugs.add(post.slug);
    urls.add(post.url);
  }
}

function resolveAuthor() {
  const rows = runSql(
    `SELECT id, username FROM users WHERE username = ${sqlString(catalog.collection.author)}`
    + ` OR id = ${sqlString(editorialUserId)}`,
  )[0]?.results ?? [];
  const usernameRow = rows.find((row) => row.username.toLowerCase() === catalog.collection.author);
  const idCollision = rows.find((row) => row.id === editorialUserId && row.username.toLowerCase() !== catalog.collection.author);
  if (idCollision) throw new Error(`Editorial user id collision: ${JSON.stringify(idCollision)}`);
  if (usernameRow) return usernameRow.id;

  const timestamp = Date.parse(catalog.collection.createdAt);
  runSql(`
    INSERT INTO users (
      id, username, display_name, bio, avatar_url, password_hash, password_salt,
      password_iterations, created_at, updated_at
    ) VALUES (
      ${sqlString(editorialUserId)}, 'patrickjackson', 'Patrick Jackson',
      'Android client platform engineer building server-driven UI, resilient media, offline-first systems, and privacy-bounded observability.',
      NULL, 'bm90LWEtbG9naW4tYWNjb3VudA', 'cmVhZHRoYXQtZWRpdG9yaWFs', 100000,
      ${timestamp}, ${timestamp}
    );
  `);
  return editorialUserId;
}

function assertNoContentCollisions(authorId) {
  const expectedCommunityId = communityId();
  const communityRows = runSql(
    `SELECT id, name FROM subreddits WHERE name = ${sqlString(catalog.community.name)}`
    + ` OR id = ${sqlString(expectedCommunityId)}`,
  )[0]?.results ?? [];
  for (const row of communityRows) {
    if (row.id !== expectedCommunityId || row.name.toLowerCase() !== catalog.community.name) {
      throw new Error(`Community identity collision: ${JSON.stringify(row)}`);
    }
  }

  const expectedPostIds = new Map(catalog.posts.map((post) => [postId(post.slug), post.slug]));
  const postRows = runSql(
    `SELECT id, author_id, client_mutation_id FROM posts WHERE id IN (${[...expectedPostIds.keys()].map(sqlString).join(",")})`
    + ` OR (author_id = ${sqlString(authorId)} AND client_mutation_id LIKE ${sqlString(`${namespace}:post:%`)})`,
  )[0]?.results ?? [];
  for (const row of postRows) {
    const slug = expectedPostIds.get(row.id);
    if (!slug || row.author_id !== authorId || row.client_mutation_id !== `${namespace}:post:${slug}`) {
      throw new Error(`Post identity collision: ${JSON.stringify(row)}`);
    }
  }
}

function seedCommunity(authorId) {
  const timestamp = Date.parse(catalog.collection.createdAt);
  runSql(`
    INSERT INTO subreddits (
      id, name, display_name, description, access_type, created_by,
      avatar_url, client_mutation_id, created_at, updated_at
    ) VALUES (
      ${sqlString(communityId())}, ${sqlString(catalog.community.name)},
      ${sqlString(catalog.community.displayName)}, ${sqlString(catalog.community.description)},
      ${sqlString(catalog.community.accessType)}, ${sqlString(authorId)}, NULL,
      ${sqlString(`${namespace}:community:${catalog.community.name}`)}, ${timestamp}, ${timestamp}
    )
    ON CONFLICT(id) DO UPDATE SET
      display_name = excluded.display_name,
      description = excluded.description,
      access_type = excluded.access_type,
      updated_at = excluded.updated_at;

    INSERT INTO subreddit_members (subreddit_id, user_id, role, created_at, updated_at)
    VALUES (${sqlString(communityId())}, ${sqlString(authorId)}, 'owner', ${timestamp}, ${timestamp})
    ON CONFLICT(subreddit_id, user_id) DO UPDATE SET role = 'owner', updated_at = excluded.updated_at;

    INSERT INTO post_flairs (
      id, subreddit_id, text, background_color, text_color, sort_order, enabled, created_at, updated_at
    ) VALUES (
      ${sqlString(flairId())}, ${sqlString(communityId())}, 'Case Study',
      '#FF4500', '#FFFFFF', 0, 1, ${timestamp}, ${timestamp}
    )
    ON CONFLICT(id) DO UPDATE SET
      text = excluded.text,
      background_color = excluded.background_color,
      text_color = excluded.text_color,
      enabled = 1,
      updated_at = excluded.updated_at;
  `);
}

function seedPosts(authorId) {
  for (const post of catalog.posts) {
    const id = postId(post.slug);
    const timestamp = Date.parse(post.publishedAt);
    runSql(`
      INSERT INTO posts (
        id, subreddit_id, author_id, kind, title, body, url, media_id, flair_id,
        crosspost_parent_id, client_mutation_id, created_at, updated_at
      ) VALUES (
        ${sqlString(id)}, ${sqlString(communityId())}, ${sqlString(authorId)}, 'text',
        ${sqlString(post.title)}, ${sqlString(postBody(post))}, NULL, NULL, ${sqlString(flairId())},
        NULL, ${sqlString(`${namespace}:post:${post.slug}`)}, ${timestamp}, ${timestamp}
      )
      ON CONFLICT(id) DO UPDATE SET
        subreddit_id = excluded.subreddit_id,
        author_id = excluded.author_id,
        kind = 'text',
        title = excluded.title,
        body = excluded.body,
        url = NULL,
        media_id = NULL,
        flair_id = excluded.flair_id,
        created_at = excluded.created_at,
        updated_at = excluded.updated_at;

      INSERT INTO votes (user_id, target_type, target_id, value, version, last_mutation_id, updated_at)
      VALUES (
        ${sqlString(authorId)}, 'post', ${sqlString(id)}, 1, 1,
        ${sqlString(`${namespace}:vote:${post.slug}`)}, ${timestamp}
      )
      ON CONFLICT(user_id, target_type, target_id) DO UPDATE SET
        value = 1,
        version = CASE WHEN votes.value <> 1 THEN votes.version + 1 ELSE votes.version END,
        last_mutation_id = excluded.last_mutation_id,
        updated_at = excluded.updated_at;

      UPDATE posts
      SET created_at = ${timestamp}, updated_at = ${timestamp},
          rank_value = score * 1000000000 + ${timestamp}
      WHERE id = ${sqlString(id)};
    `);
  }
}

function verifyStoredRows(authorId) {
  const ids = catalog.posts.map((post) => postId(post.slug));
  const result = runSql(`
    SELECT
      (SELECT COUNT(*) FROM users WHERE id = ${sqlString(authorId)} AND username = 'patrickjackson') AS users,
      (SELECT COUNT(*) FROM subreddits WHERE id = ${sqlString(communityId())} AND name = 'readthateng') AS communities,
      (SELECT COUNT(*) FROM subreddit_members WHERE subreddit_id = ${sqlString(communityId())} AND user_id = ${sqlString(authorId)} AND role = 'owner') AS owners,
      (SELECT COUNT(*) FROM posts WHERE id IN (${ids.map(sqlString).join(",")}) AND author_id = ${sqlString(authorId)}) AS posts,
      (SELECT COUNT(*) FROM votes WHERE user_id = ${sqlString(authorId)} AND target_type = 'post' AND target_id IN (${ids.map(sqlString).join(",")}) AND value = 1) AS votes;
  `)[0]?.results?.[0];
  const expected = { users: 1, communities: 1, owners: 1, posts: catalog.posts.length, votes: catalog.posts.length };
  for (const [key, count] of Object.entries(expected)) {
    if (Number(result?.[key]) !== count) throw new Error(`Stored ${key}: expected ${count}, found ${result?.[key]}`);
  }
  return expected;
}

async function verifyLiveApi() {
  const [profile, community, feed] = await Promise.all([
    fetchJson(`${baseUrl}/v1/users/patrickjackson`),
    fetchJson(`${baseUrl}/v1/subreddits/${catalog.community.name}`),
    fetchJson(`${baseUrl}/v1/feed?subreddit=${catalog.community.name}&limit=20`),
  ]);
  if (profile.user?.username !== "patrickjackson" || community.subreddit?.displayName !== catalog.community.displayName) {
    throw new Error("Live profile/community verification failed");
  }
  const feedIds = new Set(feed.groups?.map((group) => group.groupId));
  const verifiedPosts = [];
  for (const post of catalog.posts) {
    const id = postId(post.slug);
    if (!feedIds.has(id)) throw new Error(`Community feed is missing ${post.slug}`);
    const payload = await fetchJson(`${baseUrl}/v1/posts/${id}`);
    if (
      payload.post?.author !== "patrickjackson"
      || payload.post?.subreddit !== catalog.community.name
      || payload.post?.title !== post.title
      || payload.post?.body !== postBody(post)
      || payload.post?.createdAt !== Date.parse(post.publishedAt)
    ) {
      throw new Error(`${post.slug}: live post verification failed`);
    }
    verifiedPosts.push({ slug: post.slug, id, url: `${baseUrl}/post/${id}` });
  }
  return verifiedPosts;
}

async function verifyLiveAds() {
  const promoted = [];
  let cursor = null;
  for (let page = 0; page < 3 && promoted.length < 7; page += 1) {
    const path = `${baseUrl}/v1/feed?limit=12&includePromoted=true`
      + (cursor ? `&cursor=${encodeURIComponent(cursor)}` : "");
    const feed = await fetchJson(path);
    promoted.push(...(feed.groups ?? []).filter((group) => group.groupId?.startsWith("promoted:")));
    cursor = feed.nextCursor ?? null;
    if (!cursor) break;
  }
  if (promoted.length !== 7) throw new Error(`Expected 7 promoted groups, found ${promoted.length}`);
  const validIds = new Set(catalog.posts.map((post) => postId(post.slug)));
  const referencedIds = new Set();
  for (const group of promoted) {
    const related = group.cells?.find((cell) => cell.type === "ad_related_posts");
    if (related?.disclosureLabel !== "ReadThat engineering deep dives") {
      throw new Error(`${group.groupId}: stale evidence disclosure`);
    }
    if (!Array.isArray(related.posts) || related.posts.length !== 3) {
      throw new Error(`${group.groupId}: expected 3 related posts`);
    }
    for (const post of related.posts) {
      if (!validIds.has(post.postId) || post.subreddit !== "readthateng") {
        throw new Error(`${group.groupId}: invalid related post ${post.postId}`);
      }
      referencedIds.add(post.postId);
    }
  }
  if (referencedIds.size !== catalog.posts.length) {
    throw new Error(`Ads reference ${referencedIds.size} of ${catalog.posts.length} case-study posts`);
  }
  return { promotedGroups: promoted.length, referencedPosts: referencedIds.size };
}

validateCatalog();
const plan = {
  fixtureVersion: version,
  feedBalanceRevision: catalog.collection.feedBalanceRevision,
  author: catalog.collection.author,
  community: { ...catalog.community, id: communityId() },
  posts: catalog.posts.map((post) => ({
    slug: post.slug,
    id: postId(post.slug),
    title: post.title,
    publishedAt: post.publishedAt,
    deepDiveUrl: post.url,
  })),
};

if (planOnly) {
  console.log(JSON.stringify({ ok: true, planOnly: true, ...plan }, null, 2));
  process.exit(0);
}

console.error(`Resolving u/patrickjackson in ${local ? "local" : "remote"} D1 ${database}`);
const authorId = resolveAuthor();
assertNoContentCollisions(authorId);
console.error("Seeding r/readthateng and 11 case-study posts");
seedCommunity(authorId);
seedPosts(authorId);
const counts = verifyStoredRows(authorId);
console.error(`Verifying content through ${baseUrl}`);
const verifiedPosts = await verifyLiveApi();
const ads = verifyAds ? await verifyLiveAds() : null;
console.log(JSON.stringify({
  ok: true,
  planOnly: false,
  fixtureVersion: version,
  feedBalanceRevision: catalog.collection.feedBalanceRevision,
  api: baseUrl,
  database,
  authorId,
  community: { ...catalog.community, id: communityId(), url: `${baseUrl}/r/${catalog.community.name}` },
  counts,
  verifiedPosts,
  ads,
}, null, 2));
