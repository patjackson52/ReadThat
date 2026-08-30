import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

const baseUrl = (process.env.API_BASE_URL
  ?? "http://127.0.0.1:8787").replace(/\/$/u, "");
const suffix = randomUUID().replaceAll("-", "").slice(0, 12);
const username = `create_${suffix}`;
const password = `Creation-${randomUUID()}-pass`;
const subreddit = `offline_${suffix}`;
let token = "";
let bookmark = "";

async function request(path, body, expectedStatus) {
  const headers = new Headers();
  if (token) headers.set("authorization", `Bearer ${token}`);
  if (bookmark) headers.set("x-d1-bookmark", bookmark);
  if (body !== undefined) headers.set("content-type", "application/json");
  const response = await fetch(`${baseUrl}${path}`, {
    method: body === undefined ? "GET" : "POST",
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  bookmark = response.headers.get("x-d1-bookmark") ?? bookmark;
  const text = await response.text();
  const payload = text ? JSON.parse(text) : {};
  if (expectedStatus === undefined && !response.ok) {
    throw new Error(`${response.status} ${JSON.stringify(payload)}`);
  }
  if (expectedStatus !== undefined) assert.equal(response.status, expectedStatus, JSON.stringify(payload));
  return { response, payload };
}

const registered = await request("/v1/auth/register", {
  username,
  password,
  displayName: "Creation Smoke",
});
token = registered.payload.session.accessToken;

const communityMutationId = randomUUID();
const communityCommand = {
  name: subreddit,
  displayName: "Offline-first creation",
  description: "Production idempotency smoke",
  accessType: "restricted",
  clientMutationId: communityMutationId,
};
const createdCommunity = await request("/v1/subreddits", communityCommand, 201);
const replayedCommunity = await request("/v1/subreddits", communityCommand, 200);
assert.equal(createdCommunity.payload.replayed, false);
assert.equal(replayedCommunity.payload.replayed, true);
assert.equal(replayedCommunity.payload.subreddit.id, createdCommunity.payload.subreddit.id);
const mismatchedCommunity = await request("/v1/subreddits", {
  ...communityCommand,
  displayName: "Different command",
}, 409);
assert.equal(mismatchedCommunity.payload.error.code, "mutation_id_reused");

const postMutationId = randomUUID();
const postCommand = {
  subreddit,
  kind: "text",
  title: "Queued locally, published once",
  body: "A client-generated UUID makes retries safe.",
  clientMutationId: postMutationId,
};
const createdPost = await request("/v1/posts", postCommand, 201);
const replayedPost = await request("/v1/posts", postCommand, 200);
assert.equal(createdPost.payload.replayed, false);
assert.equal(replayedPost.payload.replayed, true);
assert.equal(replayedPost.payload.post.id, createdPost.payload.post.id);
const mismatchedPost = await request("/v1/posts", { ...postCommand, title: "Different command" }, 409);
assert.equal(mismatchedPost.payload.error.code, "mutation_id_reused");

const feed = await request("/v1/feed?limit=20");
assert.ok(feed.payload.groups.some((group) => group.groupId === createdPost.payload.post.id));

console.log(JSON.stringify({
  ok: true,
  baseUrl,
  username,
  subreddit,
  communityId: createdCommunity.payload.subreddit.id,
  postId: createdPost.payload.post.id,
  communityReplay: replayedCommunity.payload.replayed,
  postReplay: replayedPost.payload.replayed,
}));
