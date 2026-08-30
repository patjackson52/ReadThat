import { z } from "zod";
import { subredditById, assertCanRead } from "./access";
import { requireViewer } from "./auth";
import { AppError, jsonResponse, readJson } from "./http";
import type { RequestContext } from "./types";

const voteSchema = z.object({
  value: z.union([z.literal(-1), z.literal(0), z.literal(1)]),
  clientMutationId: z.string().trim().min(8).max(100),
}).strict();

type VoteTargetType = "post" | "comment";

interface TargetRow {
  id: string;
  post_id: string;
  subreddit_id: string;
}

interface MutationRow {
  target_type: VoteTargetType;
  target_id: string;
  value: number;
}

interface AggregateRow {
  score: number;
  upvotes: number;
  downvotes: number;
  version: number;
  viewer_vote: number;
}

async function visibleTarget(
  context: RequestContext,
  targetType: VoteTargetType,
  id: string,
): Promise<TargetRow> {
  const row = targetType === "post"
    ? await context.db.prepare(
      `SELECT p.id, p.id AS post_id, p.subreddit_id
       FROM posts p WHERE p.id = ? AND p.deleted_at IS NULL`,
    ).bind(id).first<TargetRow>()
    : await context.db.prepare(
      `SELECT c.id, c.post_id, p.subreddit_id
       FROM comments c JOIN posts p ON p.id = c.post_id
       WHERE c.id = ? AND c.deleted_at IS NULL AND p.deleted_at IS NULL`,
    ).bind(id).first<TargetRow>();
  if (!row) throw new AppError(404, `${targetType}_not_found`, `${targetType} not found`);
  const access = await subredditById(context.db, row.subreddit_id, context.viewer?.id ?? null);
  if (!access) throw new AppError(404, `${targetType}_not_found`, `${targetType} not found`);
  assertCanRead(access);
  if (access.viewerRole === "banned") {
    throw new AppError(403, "subreddit_banned", "Banned users cannot vote in this subreddit");
  }
  return row;
}

async function currentAggregate(
  context: RequestContext,
  targetType: VoteTargetType,
  targetId: string,
  userId: string,
): Promise<AggregateRow | null> {
  const table = targetType === "post" ? "posts" : "comments";
  // `table` is selected from a closed server-side enum; no request data reaches SQL syntax.
  return context.db.prepare(
    `SELECT t.score, t.upvotes, t.downvotes, t.version, COALESCE(v.value, 0) AS viewer_vote
     FROM ${table} t
     LEFT JOIN votes v ON v.target_type = ? AND v.target_id = t.id AND v.user_id = ?
     WHERE t.id = ? AND t.deleted_at IS NULL`,
  ).bind(targetType, userId, targetId).first<AggregateRow>();
}

export async function vote(
  context: RequestContext,
  targetType: VoteTargetType,
  targetId: string,
): Promise<Response> {
  const viewer = requireViewer(context);
  const input = await readJson(context.request, voteSchema);
  const target = await visibleTarget(context, targetType, targetId);
  const now = Date.now();

  const results = await context.db.batch([
    context.db.prepare(
      `INSERT OR IGNORE INTO vote_mutations (
         user_id, mutation_id, target_type, target_id, value, created_at
       ) VALUES (?, ?, ?, ?, ?, ?)`,
    ).bind(viewer.id, input.clientMutationId, targetType, targetId, input.value, now),
    context.db.prepare(
      `INSERT INTO votes (
         user_id, target_type, target_id, value, version, last_mutation_id, updated_at
       )
       SELECT ?, ?, ?, ?, 1, ?, ? WHERE changes() = 1
       ON CONFLICT(user_id, target_type, target_id) DO UPDATE SET
         value = excluded.value,
         version = votes.version + CASE WHEN votes.value <> excluded.value THEN 1 ELSE 0 END,
         last_mutation_id = excluded.last_mutation_id,
         updated_at = excluded.updated_at`,
    ).bind(viewer.id, targetType, targetId, input.value, input.clientMutationId, now),
  ]);

  const recorded = await context.db.prepare(
    `SELECT target_type, target_id, value FROM vote_mutations WHERE user_id = ? AND mutation_id = ?`,
  ).bind(viewer.id, input.clientMutationId).first<MutationRow>();
  if (
    !recorded ||
    recorded.target_type !== targetType ||
    recorded.target_id !== targetId ||
    recorded.value !== input.value
  ) {
    throw new AppError(409, "mutation_id_reused", "clientMutationId was already used for a different vote");
  }

  const aggregate = await currentAggregate(context, targetType, targetId, viewer.id);
  if (!aggregate) throw new AppError(404, `${targetType}_not_found`, `${targetType} not found`);
  const applied = (results[1]?.meta.changes ?? 0) > 0;
  if (applied) {
    context.execution.waitUntil(context.env.POST_ROOMS.getByName(target.post_id).publish({
      type: "vote.changed",
      postId: target.post_id,
      actorId: viewer.id,
      entityId: targetId,
      occurredAt: now,
      payload: { targetType, value: input.value, score: aggregate.score },
    }));
  }
  return jsonResponse({
    vote: {
      targetType,
      targetId,
      value: aggregate.viewer_vote,
      score: aggregate.score,
      upvotes: aggregate.upvotes,
      downvotes: aggregate.downvotes,
      version: aggregate.version,
    },
    replayed: !applied,
  });
}
