import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const catalog = JSON.parse(await readFile(
  join(scriptDirectory, "../fixtures/persona-conversations.json"),
  "utf8",
));
const database = process.env.D1_DATABASE || "readthat";
const baseUrl = (process.env.API_BASE_URL
  || "http://127.0.0.1:8787").replace(/\/$/u, "");

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

function renderedCommentBody(comment) {
  return [comment.body, ...(comment.additionalParagraphs || [])].join("\n\n");
}

function proseParagraphCount(body) {
  return body.split(/\n\s*\n/u).filter((block) => {
    const lines = block.trim().split("\n");
    return lines.some((line) => !/^\s*(?:#{1,6}\s|[-+*]\s|\d{1,3}[.)]\s|>)/u.test(line));
  }).length;
}

function markdownLinks(body) {
  return [...body.matchAll(/\[[^\]]+\]\((https:\/\/[^)]+)\)/gu)].map((match) => match[1]);
}

function runSql(sql) {
  const args = ["wrangler", "d1", "execute", database, "--json", "--command", sql];
  args.push(process.env.D1_LOCAL === "1" ? "--local" : "--remote");
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

const expectedUsers = [...new Set(catalog.posts.flatMap((post) => [post.author, post.commenter]))];
const expectedCommunities = [...new Set(catalog.posts.map((post) => post.subreddit))];
const preflight = runSql(
  `SELECT (SELECT COUNT(*) FROM users WHERE username IN (${expectedUsers.map(sqlString).join(", ")})) AS users, `
  + `(SELECT COUNT(*) FROM subreddits WHERE name IN (${expectedCommunities.map(sqlString).join(", ")})) AS communities`,
);
const counts = preflight[0]?.results?.[0];
if (Number(counts?.users) !== expectedUsers.length || Number(counts?.communities) !== expectedCommunities.length) {
  throw new Error(`Missing fixture users or communities: ${JSON.stringify(counts)}`);
}

const seeded = [];
const baseTimestamp = Date.now();
for (const [index, fixture] of catalog.posts.entries()) {
  if (proseParagraphCount(fixture.body) !== fixture.expectedProseParagraphs) {
    throw new Error(`${fixture.fixtureId}: post prose paragraph count changed`);
  }
  const commentBody = renderedCommentBody(fixture.comment);
  if (proseParagraphCount(commentBody) !== fixture.comment.expectedProseParagraphs) {
    throw new Error(`${fixture.fixtureId}: comment prose paragraph count changed`);
  }
  if (markdownLinks(fixture.body).length === 0 || markdownLinks(commentBody).length === 0) {
    throw new Error(`${fixture.fixtureId}: posts and comments must each contain an HTTPS Markdown link`);
  }

  const postId = deterministicUuid(`persona-conversations:v${catalog.collection.version}:${fixture.fixtureId}:post`);
  const commentId = deterministicUuid(`persona-conversations:v${catalog.collection.version}:${fixture.fixtureId}:comment`);
  const postMutationId = `persona-v${catalog.collection.version}:${fixture.fixtureId}:post`;
  const commentMutationId = `persona-v${catalog.collection.version}:${fixture.fixtureId}:comment`;
  const postCreatedAt = baseTimestamp + index * 2_000;
  const commentCreatedAt = postCreatedAt + 1_000;

  runSql(`
    INSERT OR IGNORE INTO posts (
      id, subreddit_id, author_id, kind, title, body, url, media_id,
      crosspost_parent_id, client_mutation_id, created_at, updated_at
    )
    SELECT ${sqlString(postId)}, s.id, u.id, 'text', ${sqlString(fixture.title)},
      ${sqlString(fixture.body)}, NULL, NULL, NULL, ${sqlString(postMutationId)},
      ${postCreatedAt}, ${postCreatedAt}
    FROM subreddits s CROSS JOIN users u
    WHERE s.name = ${sqlString(fixture.subreddit)} AND u.username = ${sqlString(fixture.author)};

    INSERT OR IGNORE INTO votes (
      user_id, target_type, target_id, value, version, last_mutation_id, updated_at
    )
    SELECT u.id, 'post', ${sqlString(postId)}, 1, 1, ${sqlString(`author-seed:${postId}`)}, ${postCreatedAt}
    FROM users u WHERE u.username = ${sqlString(fixture.author)};
  `);

  runSql(`
    INSERT OR IGNORE INTO comments (
      id, post_id, parent_id, author_id, body, depth,
      client_mutation_id, created_at, updated_at
    )
    SELECT ${sqlString(commentId)}, ${sqlString(postId)}, NULL, u.id,
      ${sqlString(commentBody)}, 0, ${sqlString(commentMutationId)},
      ${commentCreatedAt}, ${commentCreatedAt}
    FROM users u WHERE u.username = ${sqlString(fixture.commenter)};

    INSERT OR IGNORE INTO votes (
      user_id, target_type, target_id, value, version, last_mutation_id, updated_at
    )
    SELECT u.id, 'comment', ${sqlString(commentId)}, 1, 1,
      ${sqlString(`author-seed:${commentId}`)}, ${commentCreatedAt}
    FROM users u WHERE u.username = ${sqlString(fixture.commenter)};
  `);

  seeded.push({
    fixture,
    postId,
    commentId,
    commentBody,
  });
}

for (const item of seeded) {
  const [postResponse, commentsResponse] = await Promise.all([
    fetch(`${baseUrl}/v1/posts/${item.postId}`),
    fetch(`${baseUrl}/v1/posts/${item.postId}/comments?count=200&depth=10`),
  ]);
  const postPayload = await postResponse.json();
  const commentsPayload = await commentsResponse.json();
  const comment = commentsPayload.roots?.find((node) => node.id === item.commentId);
  if (!postResponse.ok || postPayload.post?.body !== item.fixture.body) {
    throw new Error(`${item.fixture.fixtureId}: live post verification failed (${postResponse.status})`);
  }
  if (!commentsResponse.ok || comment?.body !== item.commentBody) {
    throw new Error(`${item.fixture.fixtureId}: live comment verification failed (${commentsResponse.status})`);
  }
  if (postPayload.post.author !== item.fixture.author || comment.author !== `u/${item.fixture.commenter}`) {
    throw new Error(`${item.fixture.fixtureId}: author relationship mismatch`);
  }
}

console.log(JSON.stringify({
  ok: true,
  api: baseUrl,
  database,
  fictionalProfiles: catalog.collection.fictionalProfiles,
  posts: seeded.map((item) => ({
    fixtureId: item.fixture.fixtureId,
    subreddit: item.fixture.subreddit,
    author: item.fixture.author,
    commenter: item.fixture.commenter,
    length: item.fixture.length,
    postId: item.postId,
    postUrl: `${baseUrl}/post/${item.postId}`,
    postWords: item.fixture.body.trim().split(/\s+/u).length,
    postProseParagraphs: proseParagraphCount(item.fixture.body),
    postLinks: markdownLinks(item.fixture.body),
    commentId: item.commentId,
    commentWords: item.commentBody.trim().split(/\s+/u).length,
    commentProseParagraphs: proseParagraphCount(item.commentBody),
    commentLinks: markdownLinks(item.commentBody),
  })),
}, null, 2));
