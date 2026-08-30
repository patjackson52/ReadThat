import { AppError } from "./http";
import type { Database } from "./types";

export type SubredditRole = "owner" | "moderator" | "member" | "subscriber" | "banned";
export type SubredditAccessType = "public" | "restricted" | "private";

export interface SubredditAccess {
  id: string;
  name: string;
  displayName: string;
  description: string;
  accessType: SubredditAccessType;
  createdBy: string;
  createdAt: number;
  updatedAt: number;
  viewerRole: SubredditRole | null;
  avatarUrl: string | null;
}

function isApprovedMember(role: SubredditRole | null): boolean {
  return role === "owner" || role === "moderator" || role === "member";
}

interface AccessRow {
  id: string;
  name: string;
  display_name: string;
  description: string;
  access_type: SubredditAccessType;
  created_by: string;
  created_at: number;
  updated_at: number;
  viewer_role: SubredditRole | null;
  avatar_url: string | null;
}

function mapAccess(row: AccessRow): SubredditAccess {
  return {
    id: row.id,
    name: row.name,
    displayName: row.display_name,
    description: row.description,
    accessType: row.access_type,
    createdBy: row.created_by,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    viewerRole: row.viewer_role,
    avatarUrl: row.avatar_url,
  };
}

export async function subredditByName(
  db: Database,
  name: string,
  viewerId: string | null,
): Promise<SubredditAccess | null> {
  const row = await db.prepare(
    `SELECT s.id, s.name, s.display_name, s.description, s.access_type, s.avatar_url,
            s.created_by, s.created_at, s.updated_at, m.role AS viewer_role
     FROM subreddits s
     LEFT JOIN subreddit_members m ON m.subreddit_id = s.id AND m.user_id = ?
     WHERE s.name = ?`,
  ).bind(viewerId ?? "", name.toLowerCase()).first<AccessRow>();
  return row ? mapAccess(row) : null;
}

export async function subredditById(
  db: Database,
  id: string,
  viewerId: string | null,
): Promise<SubredditAccess | null> {
  const row = await db.prepare(
    `SELECT s.id, s.name, s.display_name, s.description, s.access_type, s.avatar_url,
            s.created_by, s.created_at, s.updated_at, m.role AS viewer_role
     FROM subreddits s
     LEFT JOIN subreddit_members m ON m.subreddit_id = s.id AND m.user_id = ?
     WHERE s.id = ?`,
  ).bind(viewerId ?? "", id).first<AccessRow>();
  return row ? mapAccess(row) : null;
}

export function assertCanRead(access: SubredditAccess): void {
  if (
    access.accessType === "private" &&
    !isApprovedMember(access.viewerRole)
  ) {
    // Deliberately indistinguishable from a missing private community.
    throw new AppError(404, "subreddit_not_found", "Subreddit not found");
  }
}

export function assertCanPost(access: SubredditAccess): void {
  if (access.viewerRole === "banned") {
    throw new AppError(403, "subreddit_banned", "You are banned from this subreddit");
  }
  if (access.accessType === "public") return;
  if (!isApprovedMember(access.viewerRole)) {
    throw new AppError(403, "posting_restricted", "Posting is restricted to approved members");
  }
}

export function assertCanModerate(access: SubredditAccess): "owner" | "moderator" {
  if (access.viewerRole !== "owner" && access.viewerRole !== "moderator") {
    throw new AppError(403, "moderator_required", "Moderator access is required");
  }
  return access.viewerRole;
}

export async function requireSubredditByName(
  db: Database,
  name: string,
  viewerId: string | null,
): Promise<SubredditAccess> {
  const access = await subredditByName(db, name, viewerId);
  if (!access) throw new AppError(404, "subreddit_not_found", "Subreddit not found");
  return access;
}
