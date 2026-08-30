import { z } from "zod";
import { keyedHash } from "./crypto";
import { jsonResponse, readJson } from "./http";
import type { RequestContext } from "./types";

const eventName = z.enum([
  "session_start",
  "session_foreground",
  "session_checkpoint",
  "session_summary",
  "post_impression",
  "post_detail_view",
  "comments_view",
  "comment_create",
  "media_playback",
  "media_feed_time_spent",
  "community_view",
  "community_time_spent",
  "community_join",
  "community_leave",
  "community_post_view",
  "ad_impression",
  "ad_view_time",
  "ad_click",
  "ad_cta_click",
  "ad_carousel_swipe",
  "ad_related_click",
  "ad_video_play",
  "ad_video_watch",
  "ad_video_complete",
  "ad_detail_view",
  "ad_landing_load",
]);

const eventSchema = z.object({
  name: eventName,
  surface: z.enum(["APP", "FEED", "DETAIL", "COMMENTS", "COMMUNITY", "MEDIA", "AD_DETAIL", "BACKGROUND"]),
  recordedAtEpochMs: z.number().int().positive(),
  // Deliberately excludes slashes, spaces, and URL punctuation. The value is
  // HMACed below and is never written or logged in raw form.
  contentId: z.string().regex(/^[A-Za-z0-9._:-]{1,160}$/u).optional(),
  contentType: z.enum(["POST", "COMMENT", "COMMUNITY", "VIDEO", "AD"]).optional(),
  reason: z.enum([
    "COLD_START",
    "FOREGROUND",
    "BACKGROUND",
    "TIMEOUT",
    "IDENTITY_CHANGE",
    "PAUSE",
    "ENDED",
    "MEDIA_CHANGE",
    "SURFACE_CHANGE",
    "ERROR",
  ]).optional(),
  durationMs: z.number().int().min(0).max(7 * 24 * 60 * 60 * 1_000).optional(),
  position: z.number().int().min(0).max(24 * 60 * 60 * 1_000).optional(),
  completionPercent: z.number().finite().min(0).max(100).optional(),
}).strict();

const batchSchema = z.object({
  schemaVersion: z.literal(1),
  platform: z.enum(["android", "ios", "web"]),
  appVersion: z.string().regex(/^[A-Za-z0-9._+-]{1,32}$/u),
  buildType: z.enum(["debug", "release", "staging"]),
  installationId: z.string().uuid(),
  sessionId: z.string().uuid(),
  events: z.array(eventSchema).min(1).max(50),
}).strict();

async function pseudonym(secret: string, namespace: string, value: string): Promise<string> {
  return (await keyedHash(secret, `product-analytics:v1:${namespace}:${value}`)).slice(0, 32);
}

/**
 * Ingests bounded behavior events into a dataset separate from performance
 * distributions. index1 is a stable, server-only pseudonym for either the
 * authenticated user or installation; raw account/install/content ids are not stored.
 */
export async function ingestProductAnalytics(context: RequestContext): Promise<Response> {
  const batch = await readJson(context.request, batchSchema, 64 * 1024);
  const principalType = context.viewer ? "user" : "installation";
  const principalSource = context.viewer?.id ?? batch.installationId;
  const principalId = await pseudonym(
    context.env.ANALYTICS_ID_PEPPER,
    principalType,
    principalSource,
  );
  const colo = String(context.request.cf?.colo ?? "unknown");
  const country = String(context.request.cf?.country ?? "unknown");
  const contentKeys = new Map<string, string>();
  await Promise.all([...new Set(batch.events.map((event) => event.contentId).filter(
    (value): value is string => value !== undefined,
  ))].map(async (contentId) => {
    contentKeys.set(
      contentId,
      await pseudonym(context.env.ANALYTICS_ID_PEPPER, "content", contentId),
    );
  }));

  for (const event of batch.events) {
    const contentKey = event.contentId ? contentKeys.get(event.contentId) ?? "" : "";
    context.env.PRODUCT_ANALYTICS.writeDataPoint({
      indexes: [principalId],
      blobs: [
        event.name,
        batch.platform,
        batch.appVersion,
        batch.buildType,
        batch.sessionId,
        event.surface,
        contentKey,
        event.contentType ?? "",
        event.reason ?? "",
        principalType,
        colo,
        country,
      ],
      doubles: [
        event.recordedAtEpochMs,
        event.durationMs ?? 0,
        event.position ?? 0,
        event.completionPercent ?? 0,
      ],
    });
  }

  console.log(JSON.stringify({
    level: "info",
    message: "client product analytics batch accepted",
    requestId: context.requestId,
    platform: batch.platform,
    principalType,
    eventCount: batch.events.length,
  }));
  return jsonResponse(
    { accepted: batch.events.length, requestId: context.requestId },
    { status: 202 },
  );
}
