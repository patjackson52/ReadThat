import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

const baseUrl = (process.env.API_BASE_URL
  ?? "http://127.0.0.1:8787").replace(/\/$/u, "");
const username = `avatar_${randomUUID().replaceAll("-", "").slice(0, 14)}`;
const password = `Avatar-${randomUUID()}-pass`;
const png = Uint8Array.from(Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64",
));

let accessToken = "";
let bookmark = "";

async function request(path, init = {}) {
  const headers = new Headers(init.headers);
  if (accessToken) headers.set("authorization", `Bearer ${accessToken}`);
  if (bookmark) headers.set("x-d1-bookmark", bookmark);
  const response = await fetch(`${baseUrl}${path}`, { ...init, headers });
  bookmark = response.headers.get("x-d1-bookmark") ?? bookmark;
  const text = await response.text();
  const body = text ? JSON.parse(text) : {};
  if (!response.ok) throw new Error(`${response.status} ${JSON.stringify(body)}`);
  return { response, body };
}

function json(body, method = "POST") {
  return {
    method,
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  };
}

const registered = await request("/v1/auth/register", json({
  username,
  password,
  displayName: "Avatar Smoke",
}));
accessToken = registered.body.session.accessToken;

const created = await request("/v1/media/uploads", json({
  kind: "image",
  contentType: "image/png",
  byteSize: png.byteLength,
  width: 1,
  height: 1,
  altText: "Avatar smoke test",
}));
const upload = created.body.upload;
const uploaded = await request(upload.uploadPath, {
  method: "PUT",
  headers: {
    "content-type": "image/png",
    "x-upload-token": upload.uploadToken,
  },
  body: png,
});
assert.equal(uploaded.body.media.delivery.provider, "images");
assert.equal(uploaded.body.media.delivery.status, "ready");

const updated = await request("/v1/me", json({
  bio: "Cloudflare Images live smoke",
  avatarMediaId: upload.id,
}, "PATCH"));
assert.match(updated.body.user.avatarUrl, new RegExp(`/v1/users/${username}/avatar\\?v=\\d+$`, "u"));

const redirect = await fetch(updated.body.user.avatarUrl, { redirect: "manual" });
assert.equal(redirect.status, 302);
const deliveryUrl = redirect.headers.get("location");
assert.ok(deliveryUrl?.startsWith("https://imagedelivery.net/"));
const delivered = await fetch(deliveryUrl);
assert.equal(delivered.status, 200);
assert.match(delivered.headers.get("content-type") ?? "", /^image\//u);

const removed = await request("/v1/me", json({ avatarMediaId: null }, "PATCH"));
assert.equal(removed.body.user.avatarUrl, null);
assert.equal((await fetch(updated.body.user.avatarUrl, { redirect: "manual" })).status, 404);

console.log(JSON.stringify({
  ok: true,
  baseUrl,
  username,
  uploadedMediaId: upload.id,
  deliveryProvider: uploaded.body.media.delivery.provider,
}));
