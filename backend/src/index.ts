import { getMe, getUser, login, logout, refresh, register, resolveViewer, updateMe } from "./auth";
import { createComment, getCommentTree, loadMoreComments } from "./comments";
import { getFeed } from "./feed";
import { getMediaFeed } from "./media-feed";
import {
  abortUpload,
  completeMultipart,
  createUpload,
  handleStreamWebhook,
  refreshVideoStatus,
  serveUserAvatar,
  serveMedia,
  uploadPart,
  uploadSingle,
} from "./media";
import { getPost, createPost, requireVisiblePost, resharePost } from "./posts";
import { PostRoom } from "./post-room";
import { RateLimiter } from "./rate-limiter";
import { Router } from "./router";
import {
  createSubreddit,
  getSubreddit,
  getPostFlairs,
  joinSubreddit,
  leaveSubreddit,
  removeMember,
  setMemberRole,
  updateSubreddit,
} from "./subreddits";
import { vote } from "./votes";
import { ingestPerformance } from "./telemetry";
import { ingestProductAnalytics } from "./product-analytics";
import { discoverSearch, search, typeahead } from "./search";
import { getCommunityDrawer, syncCommunityVisits } from "./community-drawer";
import {
  AppError,
  applyCors,
  clientIp,
  errorResponse,
  jsonResponse,
  preflightResponse,
} from "./http";
import type { AppEnv, RequestContext, RouteHandler } from "./types";

export { PostRoom, RateLimiter };

const router = new Router();

function limited(
  bucket: string,
  limit: number,
  windowMs: number,
  handler: RouteHandler,
): RouteHandler {
  return async (context, params) => {
    const identity = context.viewer?.id ?? clientIp(context.request);
    const decision = await context.env.RATE_LIMITERS
      .getByName(`${bucket}:${identity}`)
      .consume(limit, windowMs);
    if (!decision.allowed) {
      throw new AppError(429, "rate_limited", "Too many requests", {
        retryAfterMs: Math.max(0, decision.resetAt - Date.now()),
      });
    }
    return handler(context, params);
  };
}

router
  .on("GET", "/health", async (context) => jsonResponse({
    ok: true,
    environment: context.env.ENVIRONMENT,
    now: Date.now(),
  }, { headers: { "cache-control": "no-store" } }))
  .on("POST", "/v1/auth/register", limited("register", 5, 60 * 60 * 1_000, register))
  .on("POST", "/v1/auth/login", limited("login", 10, 10 * 60 * 1_000, login))
  .on("POST", "/v1/auth/refresh", limited("refresh", 30, 10 * 60 * 1_000, refresh))
  .on("POST", "/v1/auth/logout", logout)
  .on("POST", "/v1/telemetry/performance", limited("performance", 300, 60 * 60 * 1_000,
    ingestPerformance))
  .on("POST", "/v1/telemetry/product", limited("product-analytics", 600, 60 * 60 * 1_000,
    ingestProductAnalytics))
  .on("GET", "/v1/me", getMe)
  .on("PATCH", "/v1/me", updateMe)
  .on("GET", "/v1/me/community-drawer", getCommunityDrawer)
  .on("PUT", "/v1/me/community-visits", limited("community-visits", 600, 60 * 60 * 1_000,
    syncCommunityVisits))
  .on("GET", "/v1/users/:username", async (context, params) => getUser(context, params.username ?? ""))
  .on("GET", "/v1/users/:username/avatar", async (context, params) =>
    serveUserAvatar(context, params.username ?? ""))
  .on("POST", "/v1/subreddits", limited("create-subreddit", 10, 60 * 60 * 1_000, createSubreddit))
  .on("GET", "/v1/subreddits/:name", async (context, params) => getSubreddit(context, params.name ?? ""))
  .on("GET", "/v1/subreddits/:name/flairs", async (context, params) =>
    getPostFlairs(context, params.name ?? ""))
  .on("PATCH", "/v1/subreddits/:name", async (context, params) => updateSubreddit(context, params.name ?? ""))
  .on("POST", "/v1/subreddits/:name/join", async (context, params) => joinSubreddit(context, params.name ?? ""))
  .on("DELETE", "/v1/subreddits/:name/join", async (context, params) => leaveSubreddit(context, params.name ?? ""))
  .on("PUT", "/v1/subreddits/:name/members/:username", async (context, params) =>
    setMemberRole(context, params.name ?? "", params.username ?? ""))
  .on("DELETE", "/v1/subreddits/:name/members/:username", async (context, params) =>
    removeMember(context, params.name ?? "", params.username ?? ""))
  .on("POST", "/v1/media/uploads", limited("media-create", 60, 60 * 60 * 1_000, createUpload))
  .on("PUT", "/v1/media/uploads/:id", async (context, params) => uploadSingle(context, params.id ?? ""))
  .on("PUT", "/v1/media/uploads/:id/parts/:partNumber", async (context, params) =>
    uploadPart(context, params.id ?? "", params.partNumber ?? ""))
  .on("POST", "/v1/media/uploads/:id/complete", async (context, params) =>
    completeMultipart(context, params.id ?? ""))
  .on("POST", "/v1/media/uploads/:id/refresh", async (context, params) =>
    refreshVideoStatus(context, params.id ?? ""))
  .on("DELETE", "/v1/media/uploads/:id", async (context, params) => abortUpload(context, params.id ?? ""))
  .on("POST", "/v1/media/stream/webhook", handleStreamWebhook)
  .on("GET", "/v1/media/:id", async (context, params) => serveMedia(context, params.id ?? ""))
  .on("HEAD", "/v1/media/:id", async (context, params) => serveMedia(context, params.id ?? ""))
  .on("POST", "/v1/posts", limited("post", 60, 60 * 60 * 1_000, createPost))
  .on("GET", "/v1/posts/:postId", async (context, params) => getPost(context, params.postId ?? ""))
  .on("POST", "/v1/posts/:postId/reshare", limited("reshare", 60, 60 * 60 * 1_000,
    async (context, params) => resharePost(context, params.postId ?? "")))
  .on("PUT", "/v1/posts/:postId/vote", limited("vote", 600, 60 * 60 * 1_000,
    async (context, params) => vote(context, "post", params.postId ?? "")))
  .on("POST", "/v1/posts/:postId/comments", limited("comment", 120, 60 * 60 * 1_000,
    async (context, params) => createComment(context, params.postId ?? "")))
  .on("GET", "/v1/posts/:postId/comments", async (context, params) =>
    getCommentTree(context, params.postId ?? ""))
  .on("POST", "/v1/posts/:postId/comments/more", async (context, params) =>
    loadMoreComments(context, params.postId ?? ""))
  .on("PUT", "/v1/comments/:commentId/vote", limited("vote", 600, 60 * 60 * 1_000,
    async (context, params) => vote(context, "comment", params.commentId ?? "")))
  .on("GET", "/v1/feed", getFeed)
  .on("GET", "/v1/feeds/media", getMediaFeed)
  .on("GET", "/v1/search/discover", limited("search", 600, 60 * 60 * 1_000, discoverSearch))
  .on("GET", "/v1/search/typeahead", limited("search", 600, 60 * 60 * 1_000, typeahead))
  .on("GET", "/v1/search", limited("search", 600, 60 * 60 * 1_000, search))
  .on("GET", "/v1/posts/:postId/live", async (context, params) => {
    if (!context.viewer) throw new AppError(401, "authentication_required", "Authentication is required");
    const postId = params.postId ?? "";
    await requireVisiblePost(context, postId);
    const headers = new Headers(context.request.headers);
    headers.set("x-authenticated-user", context.viewer.id);
    const internalUrl = new URL(context.request.url);
    internalUrl.pathname = "/internal/live";
    return context.env.POST_ROOMS.getByName(postId).fetch(new Request(internalUrl, {
      method: "GET",
      headers,
    }));
  });

function validateSecrets(env: AppEnv): void {
  for (const [name, value] of [
    ["AUTH_PEPPER", env.AUTH_PEPPER],
    ["ANALYTICS_ID_PEPPER", env.ANALYTICS_ID_PEPPER],
    ["CURSOR_SECRET", env.CURSOR_SECRET],
    ["MEDIA_SIGNING_SECRET", env.MEDIA_SIGNING_SECRET],
    ["IMAGES_SIGNING_KEY", env.IMAGES_SIGNING_KEY],
  ] as const) {
    if (typeof value !== "string" || value.length < 32) {
      throw new AppError(500, "server_misconfigured", `${name} must contain at least 32 characters`);
    }
  }
  if (env.STREAM_WEBHOOK_SECRET !== undefined && env.STREAM_WEBHOOK_SECRET.length < 32) {
    throw new AppError(500, "server_misconfigured", "STREAM_WEBHOOK_SECRET must contain at least 32 characters");
  }
}

async function handleRequest(
  request: Request,
  env: AppEnv,
  execution: ExecutionContext,
  requestId: string,
): Promise<Response> {
  validateSecrets(env);
  if (request.method === "OPTIONS") return preflightResponse(request, env.ALLOWED_ORIGINS);
  const url = new URL(request.url);
  const bookmark = request.headers.get("x-d1-bookmark");
  if (bookmark && (bookmark.length > 512 || /[^\x21-\x7e]/u.test(bookmark))) {
    throw new AppError(400, "invalid_d1_bookmark", "X-D1-Bookmark is malformed");
  }
  const isRead = request.method === "GET" || request.method === "HEAD";
  const db = env.DB.withSession(bookmark || (isRead ? "first-unconstrained" : "first-primary"));
  const viewer = await resolveViewer(request, db, env.AUTH_PEPPER);
  const context: RequestContext = { request, url, env, execution, db, requestId, viewer };
  const { handler, params } = router.match(request.method, url.pathname);
  const response = await handler(context, params);
  if (response.status === 101) return response;

  const headers = new Headers(response.headers);
  const nextBookmark = db.getBookmark();
  if (nextBookmark) headers.set("x-d1-bookmark", nextBookmark);
  headers.set("x-request-id", requestId);
  headers.set("referrer-policy", "no-referrer");
  headers.set("permissions-policy", "camera=(), microphone=(), geolocation=()");
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
}

export default {
  async fetch(request: Request, env: AppEnv, execution: ExecutionContext): Promise<Response> {
    const requestId = request.headers.get("cf-ray") ?? crypto.randomUUID();
    const startedAt = Date.now();
    let response: Response;
    try {
      response = await handleRequest(request, env, execution, requestId);
    } catch (error) {
      response = errorResponse(error, requestId);
    }
    if (response.status !== 101) {
      const headers = new Headers(response.headers);
      headers.set("server-timing", `edge;dur=${Date.now() - startedAt}`);
      response = new Response(response.body, {
        status: response.status,
        statusText: response.statusText,
        headers,
      });
      response = applyCors(request, response, env.ALLOWED_ORIGINS);
    }
    console.log(JSON.stringify({
      level: "info",
      message: "request completed",
      requestId,
      method: request.method,
      path: new URL(request.url).pathname,
      status: response.status,
      durationMs: Date.now() - startedAt,
    }));
    return response;
  },
} satisfies ExportedHandler<AppEnv>;
