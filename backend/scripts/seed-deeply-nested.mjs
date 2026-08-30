import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { gzipSync } from "node:zlib";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { community, posts } from "../fixtures/deeply-nested-discussions.mjs";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const backendDirectory = join(scriptDirectory, "..");
const database = process.env.D1_DATABASE || "readthat";
const baseUrl = (process.env.API_BASE_URL
  || "http://127.0.0.1:8787").replace(/\/$/u, "");
const local = process.env.D1_LOCAL === "1";
const INSERT_TARGET_BYTES = 80_000;
const VERSION_PREFIX = `deeply-nested-v${community.fixtureVersion}`;

function deterministicUuid(value) {
  const bytes = createHash("sha256").update(value).digest().subarray(0, 16);
  bytes[6] = (bytes[6] & 0x0f) | 0x50;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function sqlString(value) {
  return `'${String(value).replaceAll("'", "''")}'`;
}

function wait(milliseconds) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, milliseconds);
}

function runSql(sql) {
  const args = ["wrangler", "d1", "execute", database, "--json", "--command", sql, local ? "--local" : "--remote"];
  let lastFailure = "";
  for (let attempt = 1; attempt <= 3; attempt++) {
    const result = spawnSync(process.platform === "win32" ? "npx.cmd" : "npx", args, {
      cwd: backendDirectory,
      encoding: "utf8",
      maxBuffer: 30 * 1024 * 1024,
    });
    if (result.status === 0) return JSON.parse(result.stdout);
    lastFailure = result.stderr || result.stdout;
    if (attempt < 3) wait(attempt * 500);
  }
  throw new Error(`wrangler d1 execute failed after retries: ${lastFailure}`);
}

function words(value) {
  return value.trim().split(/\s+/u).length;
}

function oppositeAuthor(username) {
  return username === community.owner ? community.member : community.owner;
}

function referenceParagraph(fixture, index) {
  if (index % 17 !== 0) return "";
  const link = fixture.links[index % fixture.links.length];
  return `\n\nFor a concrete reference, revisit [the primary documentation](${link}) and state which part of the proposed contract it actually supports. A link is evidence, not a substitute for a workload-specific measurement.`;
}

function wideCommentBody(fixture, index) {
  const [heading, argument] = fixture.debatePoints[index % fixture.debatePoints.length];
  const ordinal = index + 1;
  const experiment = index % 4 === 0
    ? "I would capture raw and gzip bytes, decode time, normalized-node count, flattened-row count, and scroll-anchor movement before changing the budget."
    : index % 4 === 1
      ? "The failure mode I want in the trace is a correct-looking screen backed by an unbounded request or an unstable merge."
      : index % 4 === 2
        ? "My counterexample is a thread whose shape differs from its node count: one deep branch and hundreds of shallow siblings exercise different paths."
        : "The acceptance criterion should include both a correctness invariant and a p95 cost on a release build; an average latency alone is too forgiving.";
  return `**Thread sample ${ordinal}: ${heading}**\n\n${argument} ${experiment}\n\nFor this ${fixture.workload.rootCount}-root corpus, I would keep the server-authored cursor identity separate from client-derived visible and collapsed counts. That lets us debate policy without making the UI invent facts about unloaded comments.${referenceParagraph(fixture, index)}`;
}

function deepCommentBody(fixture, depth, role = "chain") {
  const [heading, argument] = fixture.debatePoints[depth % fixture.debatePoints.length];
  const positions = [
    "I agree with the constraint but not yet with the default.",
    "Counterpoint: the optimistic path is underspecified when the cache is warm.",
    "That works for breadth; depth changes the cost model.",
    "The server can be authoritative without dictating the entire presentation.",
    "I would make the invariant executable before tuning the threshold.",
  ];
  const roleSentence = role === "side"
    ? "This side reply intentionally shares a parent with the featured chain so sibling ordering and cursor replacement are exercised together."
    : `This is structural level ${depth + 1}; once presentation depth reaches ten, the next omission should become a rooted continuation instead of narrower indentation.`;
  return `**Level ${depth + 1}: ${heading}**\n\n${positions[depth % positions.length]} ${argument}\n\n${roleSentence} The test should preserve stable keys, parent identity, and the reader's anchor while loading or re-rooting the next slice.${referenceParagraph(fixture, depth)}`;
}

function makeComment(fixture, key, parentKey, depth, sequence, body, featured = false) {
  const author = sequence % 2 === 0 ? oppositeAuthor(fixture.author) : fixture.author;
  return {
    key,
    id: deterministicUuid(`${VERSION_PREFIX}:${fixture.fixtureId}:comment:${key}`),
    parentKey,
    depth,
    sequence,
    author,
    body,
    featured,
  };
}

function generateWideComments(fixture) {
  const comments = [];
  for (let index = 0; index < fixture.workload.rootCount; index++) {
    const key = `root-${String(index + 1).padStart(4, "0")}`;
    comments.push(makeComment(fixture, key, null, 0, index, wideCommentBody(fixture, index)));
  }
  return comments;
}

function generateDeepComments(fixture) {
  const comments = [];
  let parentKey = null;
  for (let depth = 0; depth < fixture.workload.levels; depth++) {
    const key = `chain-${String(depth + 1).padStart(2, "0")}`;
    comments.push(makeComment(fixture, key, parentKey, depth, depth, deepCommentBody(fixture, depth), true));
    parentKey = key;
  }
  return comments;
}

function generateHybridComments(fixture) {
  const comments = generateWideComments(fixture);
  let parentKey = comments[0]?.key ?? null;
  if (!parentKey) return comments;
  comments[0].featured = true;
  let sequence = comments.length;
  for (let depth = 1; depth < fixture.workload.chainLevels; depth++) {
    const chainKey = `featured-chain-${String(depth + 1).padStart(2, "0")}`;
    comments.push(makeComment(
      fixture,
      chainKey,
      parentKey,
      depth,
      sequence++,
      deepCommentBody(fixture, depth),
      true,
    ));
    for (let side = 0; side < fixture.workload.sideRepliesPerLevel; side++) {
      const sideKey = `side-${String(depth + 1).padStart(2, "0")}-${side + 1}`;
      comments.push(makeComment(
        fixture,
        sideKey,
        parentKey,
        depth,
        sequence++,
        deepCommentBody(fixture, depth, "side"),
      ));
    }
    parentKey = chainKey;
  }
  return comments;
}

function generateComments(fixture) {
  switch (fixture.workload.kind) {
    case "wide": return generateWideComments(fixture);
    case "deep": return generateDeepComments(fixture);
    case "hybrid": return generateHybridComments(fixture);
    default: throw new Error(`${fixture.fixtureId}: unsupported workload ${fixture.workload.kind}`);
  }
}

function validateFixture(fixture, comments) {
  if (words(fixture.body) < 650) throw new Error(`${fixture.fixtureId}: post is not long-form`);
  if (fixture.body.length > 40_000) throw new Error(`${fixture.fixtureId}: post exceeds API limit`);
  if (fixture.links.some((link) => !fixture.body.includes(link))) {
    throw new Error(`${fixture.fixtureId}: declared primary-source link is missing from the body`);
  }
  const byKey = new Map();
  for (const comment of comments) {
    if (byKey.has(comment.key)) throw new Error(`${fixture.fixtureId}: duplicate key ${comment.key}`);
    if (comment.body.length > 10_000) throw new Error(`${fixture.fixtureId}: comment exceeds API limit`);
    if (comment.parentKey !== null) {
      const parent = byKey.get(comment.parentKey);
      if (!parent) throw new Error(`${fixture.fixtureId}: parent must precede ${comment.key}`);
      if (comment.depth !== parent.depth + 1) throw new Error(`${fixture.fixtureId}: invalid depth at ${comment.key}`);
    } else if (comment.depth !== 0) {
      throw new Error(`${fixture.fixtureId}: root comment has nonzero depth`);
    }
    byKey.set(comment.key, comment);
  }
}

function commentValueSql(comment, postId, userIds, createdAt) {
  const resolvedParentId = comment.parentKey === null
    ? "NULL"
    : sqlString(deterministicUuid(`${VERSION_PREFIX}:${comment.postFixtureId}:comment:${comment.parentKey}`));
  const mutationId = `${VERSION_PREFIX}:${comment.postFixtureId}:${comment.key}`;
  return `(${sqlString(comment.id)}, ${sqlString(postId)}, ${resolvedParentId}, ${sqlString(userIds[comment.author])}, ${sqlString(comment.body)}, ${comment.depth}, ${sqlString(mutationId)}, ${createdAt}, ${createdAt})`;
}

function chunkCommentValues(comments, postId, userIds, postCreatedAt) {
  const chunks = [];
  let values = [];
  let bytes = 0;
  for (const comment of comments) {
    const value = commentValueSql(comment, postId, userIds, postCreatedAt + comment.sequence + 1);
    const valueBytes = Buffer.byteLength(value, "utf8") + 2;
    if (values.length > 0 && bytes + valueBytes > INSERT_TARGET_BYTES) {
      chunks.push(values);
      values = [];
      bytes = 0;
    }
    values.push(value);
    bytes += valueBytes;
  }
  if (values.length > 0) chunks.push(values);
  return chunks;
}

function treeMetrics(payload) {
  const stack = [...payload.roots];
  let comments = 0;
  let cursors = 0;
  let cursorRepresented = 0;
  let maxCursorIds = 0;
  while (stack.length > 0) {
    const node = stack.pop();
    if (node.type === "comment") {
      comments++;
      stack.push(...node.children);
    } else {
      cursors++;
      cursorRepresented += node.remainingCount;
      maxCursorIds = Math.max(maxCursorIds, node.childIds.length);
    }
  }
  return { comments, cursors, cursorRepresented, maxCursorIds };
}

async function fetchMeasured(path) {
  const response = await fetch(`${baseUrl}${path}`, { headers: { accept: "application/json" } });
  const bytes = Buffer.from(await response.arrayBuffer());
  if (!response.ok) throw new Error(`${path}: HTTP ${response.status}: ${bytes.toString("utf8")}`);
  return {
    payload: JSON.parse(bytes.toString("utf8")),
    utf8Bytes: bytes.length,
    gzipBytes: gzipSync(bytes, { level: 6 }).length,
    cacheStatus: response.headers.get("x-comment-tree-cache"),
    serverTiming: response.headers.get("server-timing"),
  };
}

const planned = posts.map((fixture) => {
  const comments = generateComments(fixture)
    .map((comment) => ({ ...comment, postFixtureId: fixture.fixtureId }));
  validateFixture(fixture, comments);
  return { fixture, comments };
});

if (process.argv.includes("--plan")) {
  console.log(JSON.stringify({
    ok: true,
    mode: "plan",
    community,
    totals: {
      posts: planned.length,
      comments: planned.reduce((sum, item) => sum + item.comments.length, 0),
      postWords: planned.reduce((sum, item) => sum + words(item.fixture.body), 0),
      postUtf8Bytes: planned.reduce((sum, item) => sum + Buffer.byteLength(item.fixture.body, "utf8"), 0),
      commentBodyUtf8Bytes: planned.reduce(
        (sum, item) => sum + Buffer.byteLength(item.comments.map((comment) => comment.body).join(""), "utf8"),
        0,
      ),
    },
    posts: planned.map(({ fixture, comments }) => ({
      fixtureId: fixture.fixtureId,
      title: fixture.title,
      workload: fixture.workload,
      postWords: words(fixture.body),
      postUtf8Bytes: Buffer.byteLength(fixture.body, "utf8"),
      comments: comments.length,
      roots: comments.filter((comment) => comment.parentKey === null).length,
      maxDepth: Math.max(...comments.map((comment) => comment.depth)),
      commentBodyUtf8Bytes: Buffer.byteLength(comments.map((comment) => comment.body).join(""), "utf8"),
    })),
  }, null, 2));
  process.exit(0);
}

const userResult = runSql(
  `SELECT id, username FROM users WHERE username IN (${[community.owner, community.member].map(sqlString).join(", ")})`,
);
const userRows = userResult[0]?.results ?? [];
const userIds = Object.fromEntries(userRows.map((row) => [row.username, row.id]));
if (!userIds[community.owner] || !userIds[community.member]) {
  throw new Error(`Required demo personas are missing: ${JSON.stringify(userRows)}`);
}

const communityId = deterministicUuid(`${VERSION_PREFIX}:community:${community.name}`);
const communityMutationId = deterministicUuid(`${VERSION_PREFIX}:community-mutation:${community.name}`);
const moderationLogId = deterministicUuid(`${VERSION_PREFIX}:moderation:${community.name}`);
const baseTimestamp = Date.now();

runSql(`
  INSERT OR IGNORE INTO subreddits (
    id, name, display_name, description, access_type, created_by,
    client_mutation_id, created_at, updated_at
  ) VALUES (
    ${sqlString(communityId)}, ${sqlString(community.name)}, ${sqlString(community.displayName)},
    ${sqlString(community.description)}, ${sqlString(community.accessType)},
    ${sqlString(userIds[community.owner])}, ${sqlString(communityMutationId)},
    ${baseTimestamp}, ${baseTimestamp}
  );
  INSERT OR IGNORE INTO subreddit_members (subreddit_id, user_id, role, created_at, updated_at)
    VALUES (${sqlString(communityId)}, ${sqlString(userIds[community.owner])}, 'owner', ${baseTimestamp}, ${baseTimestamp});
  INSERT OR IGNORE INTO subreddit_members (subreddit_id, user_id, role, created_at, updated_at)
    VALUES (${sqlString(communityId)}, ${sqlString(userIds[community.member])}, 'subscriber', ${baseTimestamp}, ${baseTimestamp});
  INSERT OR IGNORE INTO moderation_log (id, subreddit_id, actor_id, action, details_json, created_at)
    VALUES (${sqlString(moderationLogId)}, ${sqlString(communityId)}, ${sqlString(userIds[community.owner])},
      'subreddit.created', ${sqlString(JSON.stringify({ accessType: community.accessType, fixture: VERSION_PREFIX }))}, ${baseTimestamp});
`);

const seeded = [];
for (const [postIndex, { fixture, comments }] of planned.entries()) {
  const postId = deterministicUuid(`${VERSION_PREFIX}:${fixture.fixtureId}:post`);
  const postCreatedAt = baseTimestamp + postIndex * 10_000;
  const postMutationId = `${VERSION_PREFIX}:${fixture.fixtureId}:post`;
  runSql(`
    INSERT OR IGNORE INTO posts (
      id, subreddit_id, author_id, kind, title, body, url, media_id,
      crosspost_parent_id, client_mutation_id, created_at, updated_at
    ) VALUES (
      ${sqlString(postId)}, ${sqlString(communityId)}, ${sqlString(userIds[fixture.author])},
      'text', ${sqlString(fixture.title)}, ${sqlString(fixture.body)}, NULL, NULL, NULL,
      ${sqlString(postMutationId)}, ${postCreatedAt}, ${postCreatedAt}
    );
    INSERT OR IGNORE INTO votes (user_id, target_type, target_id, value, version, last_mutation_id, updated_at)
      VALUES (${sqlString(userIds[fixture.author])}, 'post', ${sqlString(postId)}, 1, 1,
        ${sqlString(`author-seed:${postId}`)}, ${postCreatedAt});
  `);

  const chunks = chunkCommentValues(comments, postId, userIds, postCreatedAt);
  for (const values of chunks) {
    runSql(`
      INSERT OR IGNORE INTO comments (
        id, post_id, parent_id, author_id, body, depth,
        client_mutation_id, created_at, updated_at
      ) VALUES ${values.join(",\n")};
    `);
  }
  runSql(`
    INSERT OR IGNORE INTO votes (
      user_id, target_type, target_id, value, version, last_mutation_id, updated_at
    )
    SELECT author_id, 'comment', id, 1, 1, 'author-seed:' || id, updated_at
    FROM comments
    WHERE post_id = ${sqlString(postId)}
      AND client_mutation_id LIKE ${sqlString(`${VERSION_PREFIX}:${fixture.fixtureId}:%`)};
  `);

  const featuredIds = comments.filter((comment) => comment.featured).map((comment) => comment.id);
  if (featuredIds.length > 0) {
    runSql(`
      INSERT OR IGNORE INTO votes (
        user_id, target_type, target_id, value, version, last_mutation_id, updated_at
      )
      SELECT CASE WHEN author_id = ${sqlString(userIds[community.owner])}
          THEN ${sqlString(userIds[community.member])} ELSE ${sqlString(userIds[community.owner])} END,
        'comment', id, 1, 1, 'fixture-feature:' || id, updated_at
      FROM comments WHERE id IN (${featuredIds.map(sqlString).join(", ")});
    `);
  }
  seeded.push({ fixture, postId, comments, chunkCount: chunks.length });
}

const postIds = seeded.map((item) => item.postId);
const verification = runSql(`
  SELECT p.id, p.title, p.comment_count,
    COUNT(c.id) AS stored_comments,
    SUM(CASE WHEN c.parent_id IS NULL THEN 1 ELSE 0 END) AS root_comments,
    MAX(c.depth) AS max_depth,
    SUM(length(c.body)) AS comment_body_bytes
  FROM posts p
  LEFT JOIN comments c ON c.post_id = p.id AND c.deleted_at IS NULL
  WHERE p.id IN (${postIds.map(sqlString).join(", ")})
  GROUP BY p.id, p.title, p.comment_count ORDER BY p.created_at ASC;
`)[0]?.results ?? [];
const byPostId = new Map(verification.map((row) => [row.id, row]));
for (const item of seeded) {
  const row = byPostId.get(item.postId);
  if (!row || Number(row.stored_comments) !== item.comments.length || Number(row.comment_count) !== item.comments.length) {
    throw new Error(`${item.fixture.fixtureId}: D1 comment count mismatch: ${JSON.stringify(row)}`);
  }
  const expectedRoots = item.comments.filter((comment) => comment.parentKey === null).length;
  const expectedMaxDepth = Math.max(...item.comments.map((comment) => comment.depth));
  if (Number(row.root_comments) !== expectedRoots || Number(row.max_depth) !== expectedMaxDepth) {
    throw new Error(`${item.fixture.fixtureId}: D1 shape mismatch: ${JSON.stringify(row)}`);
  }
}

const measured = [];
for (const item of seeded) {
  const post = await fetchMeasured(`/v1/posts/${item.postId}`);
  if (post.payload.post?.body !== item.fixture.body || post.payload.post?.commentCount !== item.comments.length) {
    throw new Error(`${item.fixture.fixtureId}: live post verification failed`);
  }
  const initial = await fetchMeasured(`/v1/posts/${item.postId}/comments?count=8&depth=10`);
  const full = await fetchMeasured(`/v1/posts/${item.postId}/comments?count=200&depth=10`);
  const initialMetrics = treeMetrics(initial.payload);
  const fullMetrics = treeMetrics(full.payload);
  if (initialMetrics.maxCursorIds > 100 || fullMetrics.maxCursorIds > 100) {
    throw new Error(`${item.fixture.fixtureId}: live API returned an oversized cursor`);
  }
  measured.push({
    fixtureId: item.fixture.fixtureId,
    title: item.fixture.title,
    workload: item.fixture.workload,
    author: item.fixture.author,
    postId: item.postId,
    postUrl: `${baseUrl}/post/${item.postId}`,
    postWords: words(item.fixture.body),
    storedComments: item.comments.length,
    rootComments: item.comments.filter((comment) => comment.parentKey === null).length,
    maxStoredDepth: Math.max(...item.comments.map((comment) => comment.depth)),
    commentBodyUtf8Bytes: Buffer.byteLength(item.comments.map((comment) => comment.body).join(""), "utf8"),
    insertChunks: item.chunkCount,
    initial: {
      ...initialMetrics,
      utf8Bytes: initial.utf8Bytes,
      gzipBytes: initial.gzipBytes,
      cacheStatus: initial.cacheStatus,
      corpusTruncated: initial.payload.corpusTruncated,
    },
    full: {
      ...fullMetrics,
      utf8Bytes: full.utf8Bytes,
      gzipBytes: full.gzipBytes,
      cacheStatus: full.cacheStatus,
      corpusTruncated: full.payload.corpusTruncated,
    },
  });
}

console.log(JSON.stringify({
  ok: true,
  api: baseUrl,
  database,
  community: {
    id: communityId,
    name: community.name,
    displayName: community.displayName,
    owner: community.owner,
    member: community.member,
    pageUrl: `${baseUrl}/r/${community.name}`,
    apiUrl: `${baseUrl}/v1/subreddits/${community.name}`,
  },
  totals: {
    posts: measured.length,
    comments: measured.reduce((sum, item) => sum + item.storedComments, 0),
    commentBodyUtf8Bytes: measured.reduce((sum, item) => sum + item.commentBodyUtf8Bytes, 0),
  },
  posts: measured,
}, null, 2));
