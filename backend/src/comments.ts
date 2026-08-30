import { z } from "zod";
import { assertCanRead, subredditById } from "./access";
import { requireViewer } from "./auth";
import { AppError, isUniqueConstraint, jsonResponse, readJson } from "./http";
import { requireVisiblePost } from "./posts";
import type { RequestContext } from "./types";

const createCommentSchema = z.object({
  parentId: z.string().uuid().nullable().default(null),
  body: z.string().trim().min(1).max(10_000),
  clientMutationId: z.string().trim().min(8).max(100),
}).strict();

const MAX_CURSOR_CHILD_IDS = 100;
const MAX_VOTE_IDS_PER_QUERY = 90;

const loadMoreSchema = z.object({
  childIds: z.array(z.string().uuid()).min(1).max(MAX_CURSOR_CHILD_IDS),
  limit: z.number().int().min(1).max(100).default(100),
  maxDepth: z.number().int().min(1).max(10).default(10),
}).strict();

interface RawCommentRow {
  id: string;
  post_id: string;
  parent_id: string | null;
  author_id: string;
  author_username: string;
  author_display_name: string;
  author_avatar_url: string | null;
  author_avatar_media_id: string | null;
  author_profile_updated_at: number;
  body: string;
  depth: number;
  score: number;
  upvotes: number;
  downvotes: number;
  child_count: number;
  version: number;
  created_at: number;
  updated_at: number;
  edited_at: number | null;
  viewer_vote: number;
}

interface ParentRow { id: string; depth: number }
interface CachedTreeRow { payload_json: string }
interface VoteRow { target_id: string; value: number }

interface CommentNode {
  type: "comment";
  id: string;
  author: string;
  displayName: string;
  avatarUrl: string | null;
  body: string;
  score: number;
  viewerVote: number;
  createdAt: number;
  createdAgoMin: number;
  isEdited: boolean;
  children: TreeNode[];
}

interface LoadMoreNode {
  type: "load_more";
  id: string;
  parentId: string | null;
  remainingCount: number;
  childIds: string[];
}

type TreeNode = CommentNode | LoadMoreNode;

interface CommentTreePayload {
  postId: string;
  roots: TreeNode[];
  requestedCount: number;
  requestedDepth: number;
  sort: "best";
  corpusTruncated: boolean;
}

interface Candidate {
  row: RawCommentRow;
  depth: number;
}

class MaxHeap {
  private readonly values: Candidate[] = [];

  get size(): number { return this.values.length; }

  push(value: Candidate): void {
    this.values.push(value);
    let index = this.values.length - 1;
    while (index > 0) {
      const parent = Math.floor((index - 1) / 2);
      const parentValue = this.values[parent];
      if (!parentValue || !this.higher(value, parentValue)) break;
      this.values[index] = parentValue;
      index = parent;
    }
    this.values[index] = value;
  }

  pop(): Candidate | null {
    const first = this.values[0];
    const last = this.values.pop();
    if (!first) return null;
    if (!last || this.values.length === 0) return first;
    let index = 0;
    while (true) {
      const left = index * 2 + 1;
      const right = left + 1;
      let best = index;
      const leftValue = this.values[left];
      const rightValue = this.values[right];
      const bestValue = best === index ? last : this.values[best];
      if (leftValue && bestValue && this.higher(leftValue, bestValue)) best = left;
      const currentBest = best === index ? last : this.values[best];
      if (rightValue && currentBest && this.higher(rightValue, currentBest)) best = right;
      if (best === index) break;
      const child = this.values[best];
      if (!child) break;
      this.values[index] = child;
      index = best;
    }
    this.values[index] = last;
    return first;
  }

  private higher(left: Candidate, right: Candidate): boolean {
    return left.row.score > right.row.score ||
      (left.row.score === right.row.score && left.row.id < right.row.id);
  }
}

function parseBoundedInteger(value: string | null, fallback: number, min: number, max: number): number {
  if (value === null) return fallback;
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < min || parsed > max) {
    throw new AppError(422, "invalid_query", `Expected an integer between ${min} and ${max}`);
  }
  return parsed;
}

function authorAvatarUrl(context: RequestContext, row: RawCommentRow): string | null {
  return row.author_avatar_media_id
    ? `${context.url.origin}/v1/users/${encodeURIComponent(row.author_username)}/avatar?v=${row.author_profile_updated_at}`
    : row.author_avatar_url;
}

function rowToNode(context: RequestContext, row: RawCommentRow, children: TreeNode[] = []): CommentNode {
  return {
    type: "comment",
    id: row.id,
    author: `u/${row.author_username}`,
    displayName: row.author_display_name,
    avatarUrl: authorAvatarUrl(context, row),
    body: row.body,
    score: row.score,
    viewerVote: row.viewer_vote,
    createdAt: row.created_at,
    createdAgoMin: Math.max(0, Math.floor((Date.now() - row.created_at) / 60_000)),
    isEdited: row.edited_at !== null,
    children,
  };
}

function rawCommentJson(context: RequestContext, row: RawCommentRow) {
  return {
    id: row.id,
    postId: row.post_id,
    parentId: row.parent_id,
    author: `u/${row.author_username}`,
    displayName: row.author_display_name,
    avatarUrl: authorAvatarUrl(context, row),
    body: row.body,
    score: row.score,
    viewerVote: row.viewer_vote,
    createdAt: row.created_at,
    createdAgoMin: Math.max(0, Math.floor((Date.now() - row.created_at) / 60_000)),
    isEdited: row.edited_at !== null,
  };
}

/**
 * Keep Reddit-style morechildren cursors bounded. A single root with thousands
 * of replies must not put thousands of UUIDs into one mobile request payload.
 * Multiple chunks may share a parent, so the first child is part of the stable
 * cursor id and therefore the Compose key.
 */
function loadMoreNodes(parentId: string | null, childIds: string[]): LoadMoreNode[] {
  const nodes: LoadMoreNode[] = [];
  for (let offset = 0; offset < childIds.length; offset += MAX_CURSOR_CHILD_IDS) {
    const chunk = childIds.slice(offset, offset + MAX_CURSOR_CHILD_IDS);
    const firstId = chunk[0];
    if (!firstId) continue;
    nodes.push({
      type: "load_more",
      id: `more_${parentId ?? "root"}_${firstId}`,
      parentId,
      remainingCount: chunk.length,
      childIds: chunk,
    });
  }
  return nodes;
}

function buildTree(
  context: RequestContext,
  postId: string,
  rows: RawCommentRow[],
  maxCount: number,
  maxDepth: number,
  rootCommentId: string | null,
  corpusTruncated: boolean,
): CommentTreePayload {
  const byParent = new Map<string | null, RawCommentRow[]>();
  for (const row of rows) {
    const siblings = byParent.get(row.parent_id) ?? [];
    siblings.push(row);
    byParent.set(row.parent_id, siblings);
  }

  const heap = new MaxHeap();
  for (const root of byParent.get(rootCommentId) ?? []) heap.push({ row: root, depth: 0 });
  const selected = new Map<string, { row: RawCommentRow; depth: number; truncatedIds: string[] }>();

  while (heap.size > 0 && selected.size < maxCount) {
    const candidate = heap.pop();
    if (!candidate) break;
    const children = byParent.get(candidate.row.id) ?? [];
    const truncatedIds: string[] = [];
    if (candidate.depth + 1 <= maxDepth) {
      for (const child of children) heap.push({ row: child, depth: candidate.depth + 1 });
    } else {
      truncatedIds.push(...children.map((child) => child.id));
    }
    selected.set(candidate.row.id, { ...candidate, truncatedIds });
  }

  const leftovers = new Map<string | null, string[]>();
  while (heap.size > 0) {
    const candidate = heap.pop();
    if (!candidate) break;
    const ids = leftovers.get(candidate.row.parent_id) ?? [];
    ids.push(candidate.row.id);
    leftovers.set(candidate.row.parent_id, ids);
  }

  const assemble = (parentId: string | null): TreeNode[] => {
    const output: TreeNode[] = [];
    const children = (byParent.get(parentId) ?? [])
      .filter((row) => selected.has(row.id))
      .sort((left, right) => right.score - left.score || left.id.localeCompare(right.id));
    for (const row of children) {
      const selection = selected.get(row.id);
      if (!selection) continue;
      const descendants = assemble(row.id);
      if (selection.truncatedIds.length > 0) {
        descendants.push(...loadMoreNodes(row.id, selection.truncatedIds));
      }
      output.push(rowToNode(context, row, descendants));
    }
    const remaining = leftovers.get(parentId);
    if (remaining && remaining.length > 0) output.push(...loadMoreNodes(parentId, remaining));
    return output;
  };

  return {
    postId,
    roots: assemble(rootCommentId),
    requestedCount: maxCount,
    requestedDepth: maxDepth,
    sort: "best",
    corpusTruncated,
  };
}

/** A search permalink includes the matched comment itself, then its replies. */
function buildFocusedTree(
  context: RequestContext,
  postId: string,
  rows: RawCommentRow[],
  maxCount: number,
  maxDepth: number,
  focusCommentId: string,
  corpusTruncated: boolean,
): CommentTreePayload {
  const focus = rows.find((row) => row.id === focusCommentId);
  if (!focus) {
    return { postId, roots: [], requestedCount: maxCount, requestedDepth: maxDepth, sort: "best", corpusTruncated };
  }
  const replies = buildTree(
    context,
    postId,
    rows,
    Math.max(1, maxCount - 1),
    maxDepth,
    focusCommentId,
    corpusTruncated,
  );
  return { ...replies, requestedCount: maxCount, roots: [rowToNode(context, focus, replies.roots)] };
}

function collectCommentIds(nodes: TreeNode[], output: string[] = []): string[] {
  for (const node of nodes) {
    if (node.type !== "comment") continue;
    output.push(node.id);
    collectCommentIds(node.children, output);
  }
  return output;
}

function applyViewerVotes(nodes: TreeNode[], votes: Map<string, number>): void {
  for (const node of nodes) {
    if (node.type !== "comment") continue;
    node.viewerVote = votes.get(node.id) ?? 0;
    applyViewerVotes(node.children, votes);
  }
}

async function hydrateViewerVotes(context: RequestContext, tree: CommentTreePayload): Promise<void> {
  if (!context.viewer) return;
  const ids = collectCommentIds(tree.roots);
  if (ids.length === 0) return;
  applyViewerVotes(tree.roots, await viewerVotes(context, ids));
}

/** Stay below D1/SQLite's deployment-dependent bound-variable ceiling. */
async function viewerVotes(context: RequestContext, commentIds: string[]): Promise<Map<string, number>> {
  if (!context.viewer || commentIds.length === 0) return new Map();
  const statements: D1PreparedStatement[] = [];
  for (let offset = 0; offset < commentIds.length; offset += MAX_VOTE_IDS_PER_QUERY) {
    const chunk = commentIds.slice(offset, offset + MAX_VOTE_IDS_PER_QUERY);
    const placeholders = chunk.map(() => "?").join(",");
    statements.push(context.db.prepare(
      `SELECT target_id, value FROM votes
       WHERE user_id = ? AND target_type = 'comment' AND target_id IN (${placeholders})`,
    ).bind(context.viewer.id, ...chunk));
  }
  const results = await context.db.batch<VoteRow>(statements);
  return new Map(results.flatMap((result) => result.results).map((row) => [row.target_id, row.value]));
}

async function commentCorpus(context: RequestContext, postId: string): Promise<{
  rows: RawCommentRow[];
  truncated: boolean;
}> {
  const result = await context.db.prepare(
    `SELECT c.id, c.post_id, c.parent_id, c.author_id, u.username AS author_username,
            u.display_name AS author_display_name, u.avatar_url AS author_avatar_url,
            u.avatar_media_id AS author_avatar_media_id, u.updated_at AS author_profile_updated_at,
            c.body, c.depth, c.score, c.upvotes, c.downvotes, c.child_count,
            c.version, c.created_at, c.updated_at, c.edited_at, 0 AS viewer_vote
     FROM comments c
     JOIN users u ON u.id = c.author_id
     WHERE c.post_id = ? AND c.deleted_at IS NULL
     ORDER BY c.created_at ASC
     LIMIT 5001`,
  ).bind(postId).all<RawCommentRow>();
  return { rows: result.results.slice(0, 5_000), truncated: result.results.length > 5_000 };
}

async function existingComment(
  context: RequestContext,
  mutationId: string,
): Promise<RawCommentRow | null> {
  const viewer = requireViewer(context);
  return context.db.prepare(
    `SELECT c.id, c.post_id, c.parent_id, c.author_id, u.username AS author_username,
            u.display_name AS author_display_name, u.avatar_url AS author_avatar_url,
            u.avatar_media_id AS author_avatar_media_id, u.updated_at AS author_profile_updated_at,
            c.body, c.depth, c.score, c.upvotes, c.downvotes, c.child_count,
            c.version, c.created_at, c.updated_at, c.edited_at, COALESCE(v.value, 0) AS viewer_vote
     FROM comments c
     JOIN users u ON u.id = c.author_id
     LEFT JOIN votes v ON v.target_type = 'comment' AND v.target_id = c.id AND v.user_id = ?
     WHERE c.author_id = ? AND c.client_mutation_id = ?`,
  ).bind(viewer.id, viewer.id, mutationId).first<RawCommentRow>();
}

function assertSameComment(
  row: RawCommentRow,
  postId: string,
  input: z.infer<typeof createCommentSchema>,
): void {
  if (row.post_id !== postId || row.parent_id !== input.parentId || row.body !== input.body) {
    throw new AppError(
      409,
      "mutation_id_reused",
      "clientMutationId was already used for a different comment",
    );
  }
}

export async function createComment(context: RequestContext, postId: string): Promise<Response> {
  const viewer = requireViewer(context);
  const input = await readJson(context.request, createCommentSchema);
  const prior = await existingComment(context, input.clientMutationId);
  if (prior) {
    assertSameComment(prior, postId, input);
    return jsonResponse({ comment: rawCommentJson(context, prior), replayed: true });
  }

  const post = await requireVisiblePost(context, postId);
  const access = await subredditById(context.db, post.subreddit_id, viewer.id);
  if (!access) throw new AppError(404, "post_not_found", "Post not found");
  assertCanRead(access);
  if (access.viewerRole === "banned") {
    throw new AppError(403, "subreddit_banned", "Banned users cannot comment");
  }

  let depth = 0;
  if (input.parentId) {
    const parent = await context.db.prepare(
      "SELECT id, depth FROM comments WHERE id = ? AND post_id = ? AND deleted_at IS NULL",
    ).bind(input.parentId, postId).first<ParentRow>();
    if (!parent) throw new AppError(422, "invalid_parent", "Parent comment does not belong to this post");
    depth = parent.depth + 1;
    if (depth > 1_000) throw new AppError(422, "comment_too_deep", "Comment nesting exceeds the write limit");
  }

  const id = crypto.randomUUID();
  const now = Date.now();
  try {
    await context.db.batch([
      context.db.prepare(
        `INSERT INTO comments (
           id, post_id, parent_id, author_id, body, depth,
           client_mutation_id, created_at, updated_at
         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      ).bind(id, postId, input.parentId, viewer.id, input.body, depth, input.clientMutationId, now, now),
      context.db.prepare(
        `INSERT INTO votes (
           user_id, target_type, target_id, value, version, last_mutation_id, updated_at
         ) VALUES (?, 'comment', ?, 1, 1, ?, ?)`,
      ).bind(viewer.id, id, `author-seed:${id}`, now),
    ]);
  } catch (error) {
    if (isUniqueConstraint(error)) {
      const replay = await existingComment(context, input.clientMutationId);
      if (replay) {
        assertSameComment(replay, postId, input);
        return jsonResponse({ comment: rawCommentJson(context, replay), replayed: true });
      }
    }
    throw error;
  }
  const created = await existingComment(context, input.clientMutationId);
  if (!created) throw new AppError(500, "comment_write_failed", "Comment was not visible after creation");
  context.execution.waitUntil(context.env.POST_ROOMS.getByName(postId).publish({
    type: "comment.created",
    postId,
    actorId: viewer.id,
    entityId: id,
    occurredAt: now,
    payload: { parentId: input.parentId, depth },
  }));
  return jsonResponse({ comment: rawCommentJson(context, created), replayed: false }, { status: 201 });
}

export async function getCommentTree(context: RequestContext, postId: string): Promise<Response> {
  const post = await requireVisiblePost(context, postId);
  const maxCount = parseBoundedInteger(context.url.searchParams.get("count"), 200, 1, 200);
  const maxDepth = parseBoundedInteger(context.url.searchParams.get("depth"), 10, 1, 10);
  const rootCommentId = context.url.searchParams.get("rootCommentId");
  const focusCommentId = context.url.searchParams.get("focusCommentId");
  if (rootCommentId && !z.string().uuid().safeParse(rootCommentId).success) {
    throw new AppError(422, "invalid_root_comment", "rootCommentId must be a UUID");
  }
  if (focusCommentId && !z.string().uuid().safeParse(focusCommentId).success) {
    throw new AppError(422, "invalid_focus_comment", "focusCommentId must be a UUID");
  }
  if (rootCommentId && focusCommentId) {
    throw new AppError(422, "ambiguous_comment_permalink", "Choose either rootCommentId or focusCommentId");
  }
  const rootKey = focusCommentId ? `focus:${focusCommentId}` : rootCommentId ?? "";
  const cache = await context.db.prepare(
    `SELECT payload_json FROM comment_tree_cache
     WHERE post_id = ? AND sort = 'best' AND requested_count = ?
       AND requested_depth = ? AND root_key = ? AND post_version = ? AND cached_at > ?`,
  ).bind(postId, maxCount, maxDepth, rootKey, post.version, Date.now() - 5 * 60 * 1_000)
    .first<CachedTreeRow>();

  let tree: CommentTreePayload;
  let cacheStatus: "hit" | "miss";
  if (cache) {
    try {
      tree = JSON.parse(cache.payload_json) as CommentTreePayload;
      cacheStatus = "hit";
    } catch {
      const corpus = await commentCorpus(context, postId);
      tree = focusCommentId
        ? buildFocusedTree(context, postId, corpus.rows, maxCount, maxDepth, focusCommentId, corpus.truncated)
        : buildTree(context, postId, corpus.rows, maxCount, maxDepth, rootCommentId, corpus.truncated);
      cacheStatus = "miss";
    }
  } else {
    const corpus = await commentCorpus(context, postId);
    tree = focusCommentId
      ? buildFocusedTree(context, postId, corpus.rows, maxCount, maxDepth, focusCommentId, corpus.truncated)
      : buildTree(context, postId, corpus.rows, maxCount, maxDepth, rootCommentId, corpus.truncated);
    cacheStatus = "miss";
  }

  if (cacheStatus === "miss") {
    const payload = JSON.stringify(tree);
    context.execution.waitUntil(context.env.DB.prepare(
      `INSERT INTO comment_tree_cache (
         post_id, sort, requested_count, requested_depth, root_key,
         post_version, payload_json, cached_at
       ) VALUES (?, 'best', ?, ?, ?, ?, ?, ?)
       ON CONFLICT(post_id, sort, requested_count, requested_depth, root_key) DO UPDATE SET
         post_version = excluded.post_version,
         payload_json = excluded.payload_json,
         cached_at = excluded.cached_at`,
    ).bind(postId, maxCount, maxDepth, rootKey, post.version, payload, Date.now()).run());
  }
  await hydrateViewerVotes(context, tree);
  return jsonResponse({ ...tree, cacheStatus }, { headers: {
    "cache-control": "private, max-age=15",
    "x-comment-tree-cache": cacheStatus,
  } });
}

export async function loadMoreComments(context: RequestContext, postId: string): Promise<Response> {
  await requireVisiblePost(context, postId);
  const input = await readJson(context.request, loadMoreSchema);
  const corpus = await commentCorpus(context, postId);
  const byId = new Map(corpus.rows.map((row) => [row.id, row]));
  const byParent = new Map<string, RawCommentRow[]>();
  for (const row of corpus.rows) {
    if (!row.parent_id) continue;
    const children = byParent.get(row.parent_id) ?? [];
    children.push(row);
    byParent.set(row.parent_id, children);
  }

  const heap = new MaxHeap();
  for (const id of input.childIds) {
    const row = byId.get(id);
    if (row) heap.push({ row, depth: 0 });
  }
  const comments: RawCommentRow[] = [];
  const cursors: LoadMoreNode[] = [];
  while (heap.size > 0 && comments.length < input.limit) {
    const candidate = heap.pop();
    if (!candidate) break;
    comments.push(candidate.row);
    const children = byParent.get(candidate.row.id) ?? [];
    if (candidate.depth + 1 <= input.maxDepth) {
      for (const child of children) heap.push({ row: child, depth: candidate.depth + 1 });
    } else if (children.length > 0) {
      cursors.push(...loadMoreNodes(candidate.row.id, children.map((child) => child.id)));
    }
  }
  const leftovers = new Map<string | null, string[]>();
  while (heap.size > 0) {
    const candidate = heap.pop();
    if (!candidate) break;
    const ids = leftovers.get(candidate.row.parent_id) ?? [];
    ids.push(candidate.row.id);
    leftovers.set(candidate.row.parent_id, ids);
  }
  for (const [parentId, ids] of leftovers) cursors.push(...loadMoreNodes(parentId, ids));

  if (context.viewer && comments.length > 0) {
    const byComment = await viewerVotes(context, comments.map((comment) => comment.id));
    comments.forEach((comment) => { comment.viewer_vote = byComment.get(comment.id) ?? 0; });
  }
  return jsonResponse({
    comments: comments.map((comment) => rawCommentJson(context, comment)),
    cursors,
    corpusTruncated: corpus.truncated,
  });
}
