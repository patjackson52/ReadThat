import { z } from "zod";
import { requireViewer } from "./auth";
import { keyedHash, signOpaquePayload, verifyOpaquePayload } from "./crypto";
import { AppError, jsonResponse, readJson } from "./http";
import type { RequestContext } from "./types";

interface DrawerSummaryRow {
  version: number;
  member_count: number;
  max_community_updated_at: number;
}

interface CommunityRow {
  id: string;
  name: string;
  display_name: string;
  access_type: "public" | "restricted" | "private";
  role: "owner" | "moderator" | "member" | "subscriber";
}

interface RecentRow {
  id: string;
  name: string;
  display_name: string;
  visited_at: number;
}

interface DrawerCursor {
  version: 1;
  audience: string;
  validator: string;
  snapshotAt: number;
  lastName: string;
  lastId: string;
}

const visitCommand = z.discriminatedUnion("operation", [
  z.object({
    id: z.string().uuid(),
    operation: z.literal("visit"),
    name: z.string().trim().regex(/^[A-Za-z0-9_]{3,21}$/u),
    occurredAt: z.number().int(),
  }).strict(),
  z.object({
    id: z.string().uuid(),
    operation: z.literal("remove"),
    name: z.string().trim().regex(/^[A-Za-z0-9_]{3,21}$/u),
    occurredAt: z.number().int(),
  }).strict(),
  z.object({
    id: z.string().uuid(),
    operation: z.literal("clear"),
    occurredAt: z.number().int(),
  }).strict(),
]);

const visitBatch = z.object({
  commands: z.array(visitCommand).min(1).max(50),
}).strict().superRefine(({ commands }, context) => {
  const ids = new Set<string>();
  for (const [index, command] of commands.entries()) {
    if (ids.has(command.id)) {
      context.addIssue({
        code: "custom",
        message: "Mutation ids must be unique within a batch",
        path: ["commands", index, "id"],
      });
    }
    ids.add(command.id);
  }
});

function boundedLimit(value: string | null): number {
  if (value === null) return 50;
  const limit = Number(value);
  if (!Number.isInteger(limit) || limit < 1 || limit > 100) {
    throw new AppError(422, "invalid_limit", "Drawer limit must be between 1 and 100");
  }
  return limit;
}

async function audience(context: RequestContext, userId: string): Promise<string> {
  return (await keyedHash(context.env.CURSOR_SECRET, `community-drawer:${userId}`)).slice(0, 22);
}

async function summary(context: RequestContext, userId: string): Promise<DrawerSummaryRow> {
  return await context.db.prepare(
    `SELECT COALESCE(d.version, 0) AS version,
            COUNT(m.subreddit_id) AS member_count,
            COALESCE(MAX(s.updated_at), 0) AS max_community_updated_at
     FROM users u
     LEFT JOIN community_drawer_versions d ON d.user_id = u.id
     LEFT JOIN subreddit_members m ON m.user_id = u.id AND m.role <> 'banned'
     LEFT JOIN subreddits s ON s.id = m.subreddit_id
     WHERE u.id = ? GROUP BY u.id, d.version`,
  ).bind(userId).first<DrawerSummaryRow>() ?? {
    version: 0,
    member_count: 0,
    max_community_updated_at: 0,
  };
}

async function validator(context: RequestContext, userId: string): Promise<string> {
  const value = await summary(context, userId);
  const digest = await keyedHash(
    context.env.CURSOR_SECRET,
    `${userId}:${value.version}:${value.member_count}:${value.max_community_updated_at}`,
  );
  return `\"drawer-${digest.slice(0, 24)}\"`;
}

function responseHeaders(etag: string): Headers {
  return new Headers({
    etag,
    "cache-control": "private, no-cache",
    vary: "Authorization",
  });
}

export async function getCommunityDrawer(context: RequestContext): Promise<Response> {
  const viewer = requireViewer(context);
  const limit = boundedLimit(context.url.searchParams.get("limit"));
  const expectedAudience = await audience(context, viewer.id);
  const encodedCursor = context.url.searchParams.get("cursor");
  let cursor: DrawerCursor | null = null;
  let etag: string;
  let snapshotAt: number;

  if (encodedCursor) {
    cursor = await verifyOpaquePayload<DrawerCursor>(context.env.CURSOR_SECRET, encodedCursor);
    if (
      !cursor || cursor.version !== 1 || cursor.audience !== expectedAudience ||
      !Number.isSafeInteger(cursor.snapshotAt) || typeof cursor.validator !== "string" ||
      typeof cursor.lastName !== "string" || typeof cursor.lastId !== "string"
    ) {
      throw new AppError(400, "invalid_cursor", "Community drawer cursor is invalid");
    }
    etag = cursor.validator;
    snapshotAt = cursor.snapshotAt;
  } else {
    etag = await validator(context, viewer.id);
    snapshotAt = Date.now();
    if (context.request.headers.get("if-none-match") === etag) {
      return new Response(null, { status: 304, headers: responseHeaders(etag) });
    }
  }

  const communityStatement = context.db.prepare(
    `SELECT s.id, s.name, s.display_name, s.access_type, m.role
     FROM subreddit_members m
     JOIN subreddits s ON s.id = m.subreddit_id
     WHERE m.user_id = ? AND m.role <> 'banned' AND m.updated_at <= ?
       AND (? = '' OR s.name > ? OR (s.name = ? AND s.id > ?))
     ORDER BY s.name, s.id LIMIT ?`,
  ).bind(
    viewer.id,
    snapshotAt,
    cursor?.lastName ?? "",
    cursor?.lastName ?? "",
    cursor?.lastName ?? "",
    cursor?.lastId ?? "",
    limit + 1,
  );
  const recentStatement = context.db.prepare(
    `SELECT s.id, s.name, s.display_name, visits.visited_at
     FROM community_visits visits
     JOIN subreddits s ON s.id = visits.subreddit_id
     LEFT JOIN subreddit_members membership
       ON membership.subreddit_id = s.id AND membership.user_id = visits.user_id
     WHERE visits.user_id = ?
       AND (membership.role IS NULL OR membership.role <> 'banned')
       AND (s.access_type <> 'private' OR membership.role IN ('member', 'moderator', 'owner'))
     ORDER BY visits.visited_at DESC, s.id DESC LIMIT 50`,
  ).bind(viewer.id);

  let communityRows: CommunityRow[];
  let recentRows: RecentRow[];
  if (cursor) {
    communityRows = (await communityStatement.all<CommunityRow>()).results;
    recentRows = [];
  } else {
    const results = await context.db.batch<CommunityRow | RecentRow>([
      communityStatement,
      recentStatement,
    ]);
    const communitiesResult = results.at(0);
    const recentResult = results.at(1);
    if (!communitiesResult || !recentResult) {
      throw new AppError(500, "drawer_query_failed", "Community drawer query did not complete");
    }
    communityRows = communitiesResult.results.filter(
      (row): row is CommunityRow => "role" in row,
    );
    recentRows = recentResult.results.filter(
      (row): row is RecentRow => "visited_at" in row,
    );
  }
  const page = communityRows.slice(0, limit);
  const last = page.at(-1);
  const nextCursor = communityRows.length > limit && last
    ? await signOpaquePayload<DrawerCursor>(context.env.CURSOR_SECRET, {
      version: 1,
      audience: expectedAudience,
      validator: etag,
      snapshotAt,
      lastName: last.name,
      lastId: last.id,
    })
    : null;

  return jsonResponse({
    communities: page.map((row) => ({
      id: row.id,
      name: row.name,
      displayName: row.display_name,
      accessType: row.access_type,
      role: row.role,
    })),
    recentlyVisited: recentRows.map((row) => ({
      id: row.id,
      name: row.name,
      displayName: row.display_name,
      visitedAt: row.visited_at,
    })),
    nextCursor,
    validator: etag,
  }, { headers: responseHeaders(etag) });
}

export async function syncCommunityVisits(context: RequestContext): Promise<Response> {
  const viewer = requireViewer(context);
  const input = await readJson(context.request, visitBatch);
  const now = Date.now();
  const earliest = now - 90 * 24 * 60 * 60 * 1_000;
  const latest = now + 5 * 60 * 1_000;
  const batchToken = crypto.randomUUID();
  const commands = input.commands.map((command) => ({
    ...command,
    occurredAt: Math.min(latest, Math.max(earliest, command.occurredAt)),
    name: "name" in command ? command.name.toLowerCase() : undefined,
  }));
  // Cleanup is outside the command transaction and cannot change drawer state.
  // Retention exceeds the oldest accepted offline timestamp by thirty days.
  await context.db.prepare(
    "DELETE FROM community_visit_commands WHERE applied_at < ?",
  ).bind(now - 120 * 24 * 60 * 60 * 1_000).run();

  const statements = commands.flatMap((command) => {
    const claim = context.db.prepare(
      `INSERT OR IGNORE INTO community_visit_commands (
         user_id, mutation_id, batch_token, applied_at
       ) VALUES (?, ?, ?, ?)`,
    ).bind(viewer.id, command.id, batchToken, now);
    let operation: D1PreparedStatement;
    if (command.operation === "visit") {
      operation = context.db.prepare(
        `INSERT INTO community_visits (
           user_id, subreddit_id, visited_at, created_at, updated_at
         ) SELECT ?, s.id, ?, ?, ?
           FROM subreddits s
           LEFT JOIN subreddit_members membership
             ON membership.subreddit_id = s.id AND membership.user_id = ?
          WHERE s.name = ?
            AND (membership.role IS NULL OR membership.role <> 'banned')
            AND (s.access_type <> 'private' OR membership.role IN ('member', 'moderator', 'owner'))
            AND EXISTS (
              SELECT 1 FROM community_visit_commands claimed
              WHERE claimed.user_id = ? AND claimed.mutation_id = ? AND claimed.batch_token = ?
            )
         ON CONFLICT(user_id, subreddit_id) DO UPDATE SET
           visited_at = MAX(community_visits.visited_at, excluded.visited_at),
           updated_at = excluded.updated_at
         WHERE excluded.visited_at > community_visits.visited_at`,
      ).bind(
        viewer.id, command.occurredAt, now, now, viewer.id, command.name,
        viewer.id, command.id, batchToken,
      );
    } else if (command.operation === "remove") {
      operation = context.db.prepare(
        `DELETE FROM community_visits
         WHERE user_id = ? AND subreddit_id = (SELECT id FROM subreddits WHERE name = ?)
           AND visited_at <= ?
           AND EXISTS (
             SELECT 1 FROM community_visit_commands claimed
             WHERE claimed.user_id = ? AND claimed.mutation_id = ? AND claimed.batch_token = ?
           )`,
      ).bind(
        viewer.id, command.name, command.occurredAt,
        viewer.id, command.id, batchToken,
      );
    } else {
      operation = context.db.prepare(
        `DELETE FROM community_visits WHERE user_id = ? AND visited_at <= ?
           AND EXISTS (
             SELECT 1 FROM community_visit_commands claimed
             WHERE claimed.user_id = ? AND claimed.mutation_id = ? AND claimed.batch_token = ?
           )`,
      ).bind(viewer.id, command.occurredAt, viewer.id, command.id, batchToken);
    }
    return [claim, operation];
  });
  await context.db.batch(statements);
  return jsonResponse({ applied: commands.map((command) => command.id), serverTime: now });
}
