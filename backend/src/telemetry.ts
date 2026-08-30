import { z } from "zod";
import { jsonResponse, readJson } from "./http";
import type { RequestContext } from "./types";

const metricName = z.enum([
  "home_tti",
  "feed_initial_fetch",
  "feed-load-success",
  "feed-load-fail",
  "feed_query_response_size",
  "comments_tti",
  "comments_initial_fetch",
  "comments_full_fetch",
  "community_tti",
  "community_initial_fetch",
  "media_feed_tti",
  "mutation_local_commit",
  "mutation_server_ack",
  "interaction_to_next_frame",
  "screen_frame_summary",
  "network_request",
  "video_time_to_first_frame",
  "video_rebuffer",
  "sdui_dropped_cell",
  "largest_contentful_paint",
  "interaction_to_next_paint",
  "cumulative_layout_shift",
]);

const attributeName = z.enum([
  "start_type",
  "load_type",
  "mutation_type",
  "cache_tier",
  "protocol",
  "route",
  "network_type",
  "content_kind",
  "phase",
  "interaction_type",
  "from_prefetch",
  "status_class",
]);

const measurementName = z.enum([
  "frame_count",
  "jank_count",
  "slow_frame_count",
  "frozen_frame_count",
  "fps",
  "bytes_in",
  "bytes_out",
  "edge_ms",
  "cache_hit",
  "dropped_count",
]);

const finiteNumber = z.number().finite().min(0).max(Number.MAX_SAFE_INTEGER);
const eventSchema = z.object({
  name: metricName,
  value: finiteNumber,
  unit: z.enum(["MILLISECOND", "BYTE", "COUNT", "PERCENT"]),
  surface: z.enum(["APP", "FEED", "DETAIL", "COMMUNITY", "CREATE_POST", "MEDIA", "BACKGROUND", "UNKNOWN"]),
  outcome: z.enum(["SUCCESS", "FAILURE", "QUEUED", "CANCELLED"]),
  recordedAtEpochMs: z.number().int().positive(),
  attributes: z.partialRecord(attributeName, z.string().min(1).max(80)).default({}),
  measurements: z.partialRecord(measurementName, finiteNumber).default({}),
}).strict();

const batchSchema = z.object({
  schemaVersion: z.literal(1),
  platform: z.enum(["android", "ios", "web"]),
  appVersion: z.string().regex(/^[A-Za-z0-9._+-]{1,32}$/u),
  buildType: z.enum(["debug", "release", "staging"]),
  // Random per process, never a persistent installation/device/account id.
  sessionId: z.string().uuid(),
  events: z.array(eventSchema).min(1).max(50),
}).strict();

const attributeOrder = [
  "start_type",
  "load_type",
  "mutation_type",
  "cache_tier",
  "protocol",
  "route",
  "network_type",
  "content_kind",
  "phase",
  "interaction_type",
  "from_prefetch",
  "status_class",
] as const;

const measurementOrder = [
  "frame_count",
  "jank_count",
  "slow_frame_count",
  "frozen_frame_count",
  "fps",
  "bytes_in",
  "bytes_out",
  "edge_ms",
  "cache_hit",
  "dropped_count",
] as const;

export async function ingestPerformance(context: RequestContext): Promise<Response> {
  const batch = await readJson(context.request, batchSchema, 64 * 1024);
  const colo = String(context.request.cf?.colo ?? "unknown");
  const country = String(context.request.cf?.country ?? "unknown");

  for (const event of batch.events) {
    context.env.PERFORMANCE.writeDataPoint({
      indexes: [batch.sessionId],
      blobs: [
        event.name,
        batch.platform,
        batch.appVersion,
        event.surface,
        event.outcome,
        ...attributeOrder.map((name) => String(event.attributes[name] ?? "")),
        String(batch.buildType),
        colo,
        country,
      ],
      doubles: [
        event.value,
        event.recordedAtEpochMs,
        ...measurementOrder.map((name) => event.measurements[name] ?? 0),
      ],
    });
  }

  console.log(JSON.stringify({
    level: "info",
    message: "client performance batch accepted",
    requestId: context.requestId,
    platform: batch.platform,
    appVersion: batch.appVersion,
    eventCount: batch.events.length,
  }));
  return jsonResponse(
    { accepted: batch.events.length, requestId: context.requestId },
    { status: 202 },
  );
}
