import { z } from "zod";
import {
  assertCanModerate,
  assertCanRead,
  requireSubredditByName,
  type SubredditRole,
} from "./access";
import { requireViewer } from "./auth";
import { AppError, isUniqueConstraint, jsonResponse, readJson } from "./http";
import type { RequestContext } from "./types";

const subredditName = z.string().trim().regex(/^[A-Za-z0-9_]{3,21}$/u, "Use 3-21 letters, digits, or underscores");
const accessType = z.enum(["public", "restricted", "private"]);

const createSchema = z.object({
  name: subredditName,
  displayName: z.string().trim().min(1).max(100),
  description: z.string().trim().max(1_000).default(""),
  accessType: accessType.default("public"),
  clientMutationId: z.string().uuid(),
}).strict();

const updateSchema = z.object({
  displayName: z.string().trim().min(1).max(100).optional(),
  description: z.string().trim().max(1_000).optional(),
  accessType: accessType.optional(),
}).strict().refine((value) => Object.keys(value).length > 0, "At least one field is required");

const roleSchema = z.object({
  role: z.enum(["moderator", "member", "banned"]),
}).strict();

interface CountRow { subscriber_count: number }
interface MemberRow { role: SubredditRole }
interface UserIdRow { id: string }
interface RuleRow {
  id: string;
  title: string;
  description: string;
  sort_order: number;
}
interface FlairRow {
  id: string;
  text: string;
  background_color: string;
  text_color: string;
  sort_order: number;
}
interface CreatedSubredditRow {
  id: string;
  name: string;
  display_name: string;
  description: string;
  access_type: "public" | "restricted" | "private";
  client_mutation_id: string;
  created_at: number;
  updated_at: number;
}

const defaultRule = {
  id: "respect-community",
  title: "Be respectful",
  description: "Keep discussion relevant and address ideas rather than attacking people.",
  order: 0,
};

const defaultFlairs = [
  { key: "discussion", text: "Discussion", backgroundColor: "#E4E9EC", textColor: "#0B1416" },
  { key: "question", text: "Question", backgroundColor: "#46A508", textColor: "#FFFFFF" },
  { key: "project", text: "Project", backgroundColor: "#0A66C2", textColor: "#FFFFFF" },
  { key: "news", text: "News", backgroundColor: "#FF4500", textColor: "#FFFFFF" },
  { key: "tutorial", text: "Tutorial", backgroundColor: "#8E44AD", textColor: "#FFFFFF" },
] as const;

function subredditJson(
  access: Awaited<ReturnType<typeof requireSubredditByName>>,
  subscriberCount: number,
  rules: RuleRow[],
) {
  return {
    id: access.id,
    name: access.name,
    displayName: access.displayName,
    description: access.description,
    accessType: access.accessType,
    viewerRole: access.viewerRole,
    subscriberCount,
    avatarUrl: access.avatarUrl,
    rules: rules.map((rule) => ({
      id: rule.id,
      title: rule.title,
      description: rule.description,
      order: rule.sort_order,
    })),
    createdAt: access.createdAt,
    updatedAt: access.updatedAt,
  };
}

async function existingCreation(
  context: RequestContext,
  userId: string,
  mutationId: string,
): Promise<CreatedSubredditRow | null> {
  return context.db.prepare(
    `SELECT id, name, display_name, description, access_type, client_mutation_id,
            created_at, updated_at
     FROM subreddits WHERE created_by = ? AND client_mutation_id = ?`,
  ).bind(userId, mutationId).first<CreatedSubredditRow>();
}

function assertSameCreation(
  existing: CreatedSubredditRow,
  input: z.infer<typeof createSchema>,
): void {
  if (
    existing.name !== input.name.toLowerCase()
    || existing.display_name !== input.displayName
    || existing.description !== input.description
    || existing.access_type !== input.accessType
  ) {
    throw new AppError(
      409,
      "mutation_id_reused",
      "clientMutationId was already used for a different subreddit creation",
    );
  }
}

function createdSubredditJson(row: CreatedSubredditRow) {
  return {
    id: row.id,
    name: row.name,
    displayName: row.display_name,
    description: row.description,
    accessType: row.access_type,
    viewerRole: "owner",
    subscriberCount: 1,
    avatarUrl: null,
    rules: [defaultRule],
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

export async function createSubreddit(context: RequestContext): Promise<Response> {
  const viewer = requireViewer(context);
  const input = await readJson(context.request, createSchema);
  const prior = await existingCreation(context, viewer.id, input.clientMutationId);
  if (prior) {
    assertSameCreation(prior, input);
    return jsonResponse({ subreddit: createdSubredditJson(prior), replayed: true });
  }
  const id = crypto.randomUUID();
  const now = Date.now();
  try {
    await context.db.batch([
      context.db.prepare(
        `INSERT INTO subreddits (
           id, name, display_name, description, access_type, created_by,
           client_mutation_id, created_at, updated_at
         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      ).bind(
        id,
        input.name.toLowerCase(),
        input.displayName,
        input.description,
        input.accessType,
        viewer.id,
        input.clientMutationId,
        now,
        now,
      ),
      context.db.prepare(
        `INSERT INTO subreddit_members (subreddit_id, user_id, role, created_at, updated_at)
         VALUES (?, ?, 'owner', ?, ?)`,
      ).bind(id, viewer.id, now, now),
      context.db.prepare(
        `INSERT INTO moderation_log (id, subreddit_id, actor_id, action, details_json, created_at)
         VALUES (?, ?, ?, 'subreddit.created', ?, ?)`,
      ).bind(crypto.randomUUID(), id, viewer.id, JSON.stringify({ accessType: input.accessType }), now),
      context.db.prepare(
        `INSERT INTO subreddit_rules (
           id, subreddit_id, title, description, sort_order, created_at, updated_at
         ) VALUES (?, ?, ?, ?, 0, ?, ?)`,
      ).bind(
        crypto.randomUUID(),
        id,
        defaultRule.title,
        defaultRule.description,
        now,
        now,
      ),
      ...defaultFlairs.map((flair, index) => context.db.prepare(
        `INSERT INTO post_flairs (
           id, subreddit_id, text, background_color, text_color,
           sort_order, enabled, created_at, updated_at
         ) VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)`,
      ).bind(
        `flair:${flair.key}:${id}`,
        id,
        flair.text,
        flair.backgroundColor,
        flair.textColor,
        index,
        now,
        now,
      )),
    ]);
  } catch (error) {
    if (isUniqueConstraint(error)) {
      const replay = await existingCreation(context, viewer.id, input.clientMutationId);
      if (replay) {
        assertSameCreation(replay, input);
        return jsonResponse({ subreddit: createdSubredditJson(replay), replayed: true });
      }
      throw new AppError(409, "subreddit_exists", "A subreddit with that name already exists");
    }
    throw error;
  }
  return jsonResponse({ subreddit: {
    id,
    name: input.name.toLowerCase(),
    displayName: input.displayName,
    description: input.description,
    accessType: input.accessType,
    viewerRole: "owner",
    subscriberCount: 1,
    avatarUrl: null,
    rules: [defaultRule],
    createdAt: now,
    updatedAt: now,
  }, replayed: false }, { status: 201 });
}

export async function getPostFlairs(context: RequestContext, requestedName: string): Promise<Response> {
  const access = await requireSubredditByName(context.db, requestedName, context.viewer?.id ?? null);
  assertCanRead(access);
  const result = await context.db.prepare(
    `SELECT id, text, background_color, text_color, sort_order
     FROM post_flairs
     WHERE subreddit_id = ? AND enabled = 1
     ORDER BY sort_order, id`,
  ).bind(access.id).all<FlairRow>();
  return jsonResponse({
    subreddit: access.name,
    flairs: result.results.map((flair) => ({
      id: flair.id,
      text: flair.text,
      backgroundColor: flair.background_color,
      textColor: flair.text_color,
    })),
  }, { headers: { "cache-control": "private, max-age=300" } });
}

export async function getSubreddit(context: RequestContext, name: string): Promise<Response> {
  const access = await requireSubredditByName(context.db, name, context.viewer?.id ?? null);
  assertCanRead(access);
  const [count, rules] = await Promise.all([
    context.db.prepare(
      "SELECT COUNT(*) AS subscriber_count FROM subreddit_members WHERE subreddit_id = ? AND role <> 'banned'",
    ).bind(access.id).first<CountRow>(),
    context.db.prepare(
      `SELECT id, title, description, sort_order FROM subreddit_rules
       WHERE subreddit_id = ? ORDER BY sort_order, id LIMIT 20`,
    ).bind(access.id).all<RuleRow>(),
  ]);
  return jsonResponse({ subreddit: subredditJson(access, count?.subscriber_count ?? 0, rules.results) });
}

export async function updateSubreddit(context: RequestContext, name: string): Promise<Response> {
  const viewer = requireViewer(context);
  const access = await requireSubredditByName(context.db, name, viewer.id);
  const actorRole = assertCanModerate(access);
  const input = await readJson(context.request, updateSchema);
  if (input.accessType !== undefined && actorRole !== "owner") {
    throw new AppError(403, "owner_required", "Only the owner can change community access");
  }
  const now = Date.now();
  await context.db.batch([
    context.db.prepare(
      `UPDATE subreddits SET display_name = ?, description = ?, access_type = ?, updated_at = ? WHERE id = ?`,
    ).bind(
      input.displayName ?? access.displayName,
      input.description ?? access.description,
      input.accessType ?? access.accessType,
      now,
      access.id,
    ),
    context.db.prepare(
      `INSERT INTO moderation_log (id, subreddit_id, actor_id, action, details_json, created_at)
       VALUES (?, ?, ?, 'subreddit.updated', ?, ?)`,
    ).bind(crypto.randomUUID(), access.id, viewer.id, JSON.stringify(input), now),
  ]);
  const updated = await requireSubredditByName(context.db, name, viewer.id);
  const [count, rules] = await Promise.all([
    context.db.prepare(
      "SELECT COUNT(*) AS subscriber_count FROM subreddit_members WHERE subreddit_id = ? AND role <> 'banned'",
    ).bind(access.id).first<CountRow>(),
    context.db.prepare(
      `SELECT id, title, description, sort_order FROM subreddit_rules
       WHERE subreddit_id = ? ORDER BY sort_order, id LIMIT 20`,
    ).bind(access.id).all<RuleRow>(),
  ]);
  return jsonResponse({ subreddit: subredditJson(updated, count?.subscriber_count ?? 0, rules.results) });
}

export async function joinSubreddit(context: RequestContext, name: string): Promise<Response> {
  const viewer = requireViewer(context);
  const access = await requireSubredditByName(context.db, name, viewer.id);
  assertCanRead(access);
  if (access.accessType === "private") {
    throw new AppError(403, "private_subreddit", "Private subreddits require moderator approval");
  }
  if (access.viewerRole === "banned") {
    throw new AppError(403, "subreddit_banned", "You are banned from this subreddit");
  }
  const now = Date.now();
  await context.db.prepare(
    `INSERT INTO subreddit_members (subreddit_id, user_id, role, created_at, updated_at)
     VALUES (?, ?, 'subscriber', ?, ?)
     ON CONFLICT(subreddit_id, user_id) DO UPDATE SET
       role = CASE WHEN subreddit_members.role = 'subscriber' THEN 'subscriber' ELSE subreddit_members.role END,
       updated_at = excluded.updated_at`,
  ).bind(access.id, viewer.id, now, now).run();
  return jsonResponse({ membership: { subredditId: access.id, userId: viewer.id, role: access.viewerRole ?? "subscriber" } });
}

export async function leaveSubreddit(context: RequestContext, name: string): Promise<Response> {
  const viewer = requireViewer(context);
  const access = await requireSubredditByName(context.db, name, viewer.id);
  if (access.viewerRole === "owner") throw new AppError(409, "owner_cannot_leave", "Transfer ownership before leaving");
  if (access.viewerRole === "banned") throw new AppError(403, "subreddit_banned", "A ban cannot be removed by leaving");
  await context.db.prepare(
    "DELETE FROM subreddit_members WHERE subreddit_id = ? AND user_id = ?",
  ).bind(access.id, viewer.id).run();
  return new Response(null, { status: 204 });
}

export async function setMemberRole(
  context: RequestContext,
  subredditNameValue: string,
  targetUsername: string,
): Promise<Response> {
  const viewer = requireViewer(context);
  const access = await requireSubredditByName(context.db, subredditNameValue, viewer.id);
  const actorRole = assertCanModerate(access);
  const input = await readJson(context.request, roleSchema);
  const target = await context.db.prepare("SELECT id FROM users WHERE username = ?")
    .bind(targetUsername.toLowerCase()).first<UserIdRow>();
  if (!target) throw new AppError(404, "user_not_found", "User not found");
  const existing = await context.db.prepare(
    "SELECT role FROM subreddit_members WHERE subreddit_id = ? AND user_id = ?",
  ).bind(access.id, target.id).first<MemberRow>();
  if (existing?.role === "owner") throw new AppError(409, "cannot_modify_owner", "The owner role cannot be changed here");
  if ((input.role === "moderator" || existing?.role === "moderator") && actorRole !== "owner") {
    throw new AppError(403, "owner_required", "Only the owner can add or remove moderators");
  }
  const now = Date.now();
  await context.db.batch([
    context.db.prepare(
      `INSERT INTO subreddit_members (subreddit_id, user_id, role, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?)
       ON CONFLICT(subreddit_id, user_id) DO UPDATE SET role = excluded.role, updated_at = excluded.updated_at`,
    ).bind(access.id, target.id, input.role, now, now),
    context.db.prepare(
      `INSERT INTO moderation_log (
         id, subreddit_id, actor_id, target_user_id, action, details_json, created_at
       ) VALUES (?, ?, ?, ?, 'member.role_changed', ?, ?)`,
    ).bind(crypto.randomUUID(), access.id, viewer.id, target.id, JSON.stringify({ role: input.role }), now),
  ]);
  return jsonResponse({ membership: { subredditId: access.id, userId: target.id, role: input.role } });
}

export async function removeMember(
  context: RequestContext,
  subredditNameValue: string,
  targetUsername: string,
): Promise<Response> {
  const viewer = requireViewer(context);
  const access = await requireSubredditByName(context.db, subredditNameValue, viewer.id);
  const actorRole = assertCanModerate(access);
  const target = await context.db.prepare("SELECT id FROM users WHERE username = ?")
    .bind(targetUsername.toLowerCase()).first<UserIdRow>();
  if (!target) throw new AppError(404, "user_not_found", "User not found");
  const existing = await context.db.prepare(
    "SELECT role FROM subreddit_members WHERE subreddit_id = ? AND user_id = ?",
  ).bind(access.id, target.id).first<MemberRow>();
  if (!existing) return new Response(null, { status: 204 });
  if (existing.role === "owner") throw new AppError(409, "cannot_modify_owner", "The owner cannot be removed");
  if (existing.role === "moderator" && actorRole !== "owner") {
    throw new AppError(403, "owner_required", "Only the owner can remove moderators");
  }
  const now = Date.now();
  await context.db.batch([
    context.db.prepare(
      "DELETE FROM subreddit_members WHERE subreddit_id = ? AND user_id = ?",
    ).bind(access.id, target.id),
    context.db.prepare(
      `INSERT INTO moderation_log (
         id, subreddit_id, actor_id, target_user_id, action, details_json, created_at
       ) VALUES (?, ?, ?, ?, 'member.removed', '{}', ?)`,
    ).bind(crypto.randomUUID(), access.id, viewer.id, target.id, now),
  ]);
  return new Response(null, { status: 204 });
}
