import { z } from "zod";
import { hashPassword, keyedHash, randomToken, verifyPassword } from "./crypto";
import { AppError, isUniqueConstraint, jsonResponse, readJson } from "./http";
import type { Database, RequestContext, Viewer } from "./types";

const username = z.string().trim().regex(/^[A-Za-z0-9_]{3,24}$/u, "Use 3-24 letters, digits, or underscores");
const password = z.string().min(10).max(128);

const registerSchema = z.object({
  username,
  password,
  displayName: z.string().trim().min(1).max(50).optional(),
}).strict();

const loginSchema = z.object({ username, password }).strict();
const refreshSchema = z.object({ refreshToken: z.string().min(32).max(256) }).strict();
const updateProfileSchema = z.object({
  displayName: z.string().trim().min(1).max(50).optional(),
  bio: z.string().trim().max(500).optional(),
  avatarMediaId: z.string().uuid().nullable().optional(),
}).strict().refine((value) => Object.keys(value).length > 0, "At least one field is required");

interface AuthUserRow {
  id: string;
  username: string;
  display_name: string;
  bio: string;
  avatar_url: string | null;
  avatar_media_id: string | null;
  karma: number;
  password_hash: string;
  password_salt: string;
  password_iterations: number;
  created_at: number;
  updated_at: number;
}

interface SessionViewerRow {
  session_id: string;
  user_id: string;
  username: string;
  display_name: string;
}

interface RefreshSessionRow extends SessionViewerRow {
  refresh_expires_at: number;
}

interface PublicUserRow {
  id: string;
  username: string;
  display_name: string;
  bio: string;
  avatar_url: string | null;
  avatar_media_id: string | null;
  karma: number;
  created_at: number;
  updated_at: number;
}

/**
 * Editorial identities can have a useful public profile before they create a
 * first-party account in the clone. A database user always wins, so registering
 * the same username later replaces this preview without a migration or deploy.
 */
const editorialProfiles: Readonly<Record<string, PublicUserRow>> = {
  patrickjackson: {
    id: "editorial:patrickjackson",
    username: "patrickjackson",
    display_name: "Patrick Jackson",
    bio: "Android client platform engineer building server-driven UI, resilient media, and privacy-bounded observability.",
    avatar_url: null,
    avatar_media_id: null,
    karma: 0,
    created_at: Date.UTC(2026, 7, 29),
    updated_at: Date.UTC(2026, 7, 29),
  },
};

interface TokenPair {
  accessToken: string;
  refreshToken: string;
  accessExpiresAt: number;
  refreshExpiresAt: number;
}

function userJson(context: RequestContext, row: PublicUserRow) {
  const avatarUrl = row.avatar_media_id
    ? `${context.url.origin}/v1/users/${encodeURIComponent(row.username)}/avatar?v=${row.updated_at}`
    : row.avatar_url;
  return {
    id: row.id,
    username: row.username,
    displayName: row.display_name,
    bio: row.bio,
    avatarUrl,
    karma: row.karma,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

function viewerJson(viewer: Viewer) {
  return { id: viewer.id, username: viewer.username, displayName: viewer.displayName };
}

async function issueTokenPair(pepper: string): Promise<TokenPair & { accessHash: string; refreshHash: string }> {
  const accessToken = randomToken(32);
  const refreshToken = randomToken(48);
  const now = Date.now();
  const [accessHash, refreshHash] = await Promise.all([
    keyedHash(pepper, `access:${accessToken}`),
    keyedHash(pepper, `refresh:${refreshToken}`),
  ]);
  return {
    accessToken,
    refreshToken,
    accessHash,
    refreshHash,
    accessExpiresAt: now + 15 * 60 * 1_000,
    refreshExpiresAt: now + 30 * 24 * 60 * 60 * 1_000,
  };
}

async function insertSession(
  db: Database,
  pepper: string,
  userId: string,
): Promise<TokenPair & { sessionId: string }> {
  const tokens = await issueTokenPair(pepper);
  const sessionId = crypto.randomUUID();
  const now = Date.now();
  await db.prepare(
    `INSERT INTO sessions (
       id, user_id, access_hash, refresh_hash, access_expires_at,
       refresh_expires_at, created_at, last_seen_at
     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
  ).bind(
    sessionId,
    userId,
    tokens.accessHash,
    tokens.refreshHash,
    tokens.accessExpiresAt,
    tokens.refreshExpiresAt,
    now,
    now,
  ).run();
  return {
    sessionId,
    accessToken: tokens.accessToken,
    refreshToken: tokens.refreshToken,
    accessExpiresAt: tokens.accessExpiresAt,
    refreshExpiresAt: tokens.refreshExpiresAt,
  };
}

export async function resolveViewer(
  request: Request,
  db: Database,
  pepper: string,
): Promise<Viewer | null> {
  const authorization = request.headers.get("authorization");
  if (!authorization) return null;
  const [scheme, token, extra] = authorization.split(/\s+/u);
  if (scheme?.toLowerCase() !== "bearer" || !token || extra || token.length < 32 || token.length > 256) {
    throw new AppError(401, "invalid_authorization", "Authorization must be a Bearer token");
  }
  const tokenHash = await keyedHash(pepper, `access:${token}`);
  const row = await db.prepare(
    `SELECT s.id AS session_id, u.id AS user_id, u.username, u.display_name
     FROM sessions s
     JOIN users u ON u.id = s.user_id
     WHERE s.access_hash = ? AND s.revoked_at IS NULL AND s.access_expires_at > ?`,
  ).bind(tokenHash, Date.now()).first<SessionViewerRow>();
  if (!row) throw new AppError(401, "invalid_session", "Session is expired or invalid");
  return {
    id: row.user_id,
    username: row.username,
    displayName: row.display_name,
    sessionId: row.session_id,
  };
}

export function requireViewer(context: RequestContext): Viewer {
  if (!context.viewer) throw new AppError(401, "authentication_required", "Authentication is required");
  return context.viewer;
}

export async function register(context: RequestContext): Promise<Response> {
  const input = await readJson(context.request, registerSchema);
  const normalizedUsername = input.username.toLowerCase();
  const digest = await hashPassword(input.password, context.env.AUTH_PEPPER);
  const userId = crypto.randomUUID();
  const tokens = await issueTokenPair(context.env.AUTH_PEPPER);
  const sessionId = crypto.randomUUID();
  const now = Date.now();
  try {
    await context.db.batch([
      context.db.prepare(
        `INSERT INTO users (
           id, username, display_name, password_hash, password_salt,
           password_iterations, created_at, updated_at
         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
      ).bind(
        userId,
        normalizedUsername,
        input.displayName ?? input.username,
        digest.hash,
        digest.salt,
        digest.iterations,
        now,
        now,
      ),
      context.db.prepare(
        `INSERT INTO sessions (
           id, user_id, access_hash, refresh_hash, access_expires_at,
           refresh_expires_at, created_at, last_seen_at
         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
      ).bind(
        sessionId,
        userId,
        tokens.accessHash,
        tokens.refreshHash,
        tokens.accessExpiresAt,
        tokens.refreshExpiresAt,
        now,
        now,
      ),
    ]);
  } catch (error) {
    if (isUniqueConstraint(error)) {
      throw new AppError(409, "username_taken", "That username is already registered");
    }
    throw error;
  }
  return jsonResponse({
    user: { id: userId, username: normalizedUsername, displayName: input.displayName ?? input.username },
    session: {
      sessionId,
      accessToken: tokens.accessToken,
      refreshToken: tokens.refreshToken,
      accessExpiresAt: tokens.accessExpiresAt,
      refreshExpiresAt: tokens.refreshExpiresAt,
    },
  }, { status: 201 });
}

export async function login(context: RequestContext): Promise<Response> {
  const input = await readJson(context.request, loginSchema);
  const row = await context.db.prepare(
    `SELECT id, username, display_name, bio, avatar_url, avatar_media_id, karma,
            password_hash, password_salt, password_iterations, created_at, updated_at
     FROM users WHERE username = ?`,
  ).bind(input.username.toLowerCase()).first<AuthUserRow>();

  if (!row || !(await verifyPassword(input.password, context.env.AUTH_PEPPER, {
    hash: row.password_hash,
    salt: row.password_salt,
    iterations: row.password_iterations,
  }))) {
    throw new AppError(401, "invalid_credentials", "Username or password is incorrect");
  }
  const tokens = await insertSession(context.db, context.env.AUTH_PEPPER, row.id);
  return jsonResponse({ user: userJson(context, row), session: tokens });
}

export async function refresh(context: RequestContext): Promise<Response> {
  const input = await readJson(context.request, refreshSchema);
  const refreshHash = await keyedHash(context.env.AUTH_PEPPER, `refresh:${input.refreshToken}`);
  const row = await context.db.prepare(
    `SELECT s.id AS session_id, s.user_id, s.refresh_expires_at,
            u.username, u.display_name
     FROM sessions s
     JOIN users u ON u.id = s.user_id
     WHERE s.refresh_hash = ? AND s.revoked_at IS NULL AND s.refresh_expires_at > ?`,
  ).bind(refreshHash, Date.now()).first<RefreshSessionRow>();
  if (!row) throw new AppError(401, "invalid_refresh_token", "Refresh token is expired or invalid");

  const next = await issueTokenPair(context.env.AUTH_PEPPER);
  const result = await context.db.prepare(
    `UPDATE sessions
     SET access_hash = ?, refresh_hash = ?, access_expires_at = ?,
         refresh_expires_at = ?, last_seen_at = ?
     WHERE id = ? AND refresh_hash = ? AND revoked_at IS NULL`,
  ).bind(
    next.accessHash,
    next.refreshHash,
    next.accessExpiresAt,
    next.refreshExpiresAt,
    Date.now(),
    row.session_id,
    refreshHash,
  ).run();
  if (result.meta.changes !== 1) {
    throw new AppError(409, "refresh_raced", "Refresh token was already rotated; sign in again");
  }
  return jsonResponse({
    user: viewerJson({
      id: row.user_id,
      username: row.username,
      displayName: row.display_name,
      sessionId: row.session_id,
    }),
    session: {
      sessionId: row.session_id,
      accessToken: next.accessToken,
      refreshToken: next.refreshToken,
      accessExpiresAt: next.accessExpiresAt,
      refreshExpiresAt: next.refreshExpiresAt,
    },
  });
}

export async function logout(context: RequestContext): Promise<Response> {
  const viewer = requireViewer(context);
  await context.db.prepare(
    "UPDATE sessions SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL",
  ).bind(Date.now(), viewer.sessionId).run();
  return new Response(null, { status: 204, headers: { "cache-control": "no-store" } });
}

export async function getMe(context: RequestContext): Promise<Response> {
  const viewer = requireViewer(context);
  const row = await context.db.prepare(
    `SELECT id, username, display_name, bio, avatar_url, avatar_media_id, karma, created_at, updated_at
     FROM users WHERE id = ?`,
  ).bind(viewer.id).first<PublicUserRow>();
  if (!row) throw new AppError(404, "user_not_found", "User no longer exists");
  return jsonResponse({ user: userJson(context, row) });
}

export async function updateMe(context: RequestContext): Promise<Response> {
  const viewer = requireViewer(context);
  const input = await readJson(context.request, updateProfileSchema);
  const existing = await context.db.prepare(
    `SELECT id, username, display_name, bio, avatar_url, avatar_media_id, karma, created_at, updated_at
     FROM users WHERE id = ?`,
  ).bind(viewer.id).first<PublicUserRow>();
  if (!existing) throw new AppError(404, "user_not_found", "User no longer exists");
  const changesAvatar = Object.prototype.hasOwnProperty.call(input, "avatarMediaId");
  if (input.avatarMediaId) {
    const avatar = await context.db.prepare(
      `SELECT id FROM media
       WHERE id = ? AND uploader_id = ? AND kind = 'image' AND status = 'ready'
         AND delivery_provider = 'images' AND image_status = 'ready'
         AND image_uid IS NOT NULL AND byte_size <= ?`,
    ).bind(input.avatarMediaId, viewer.id, 10 * 1024 * 1024).first<{ id: string }>();
    if (!avatar) {
      throw new AppError(
        422,
        "avatar_media_not_ready",
        "Avatar must be an uploaded, processed image owned by the current user",
      );
    }
  }
  const now = Date.now();
  await context.db.prepare(
    `UPDATE users SET display_name = ?, bio = ?, avatar_url = ?, avatar_media_id = ?, updated_at = ?
     WHERE id = ?`,
  ).bind(
    input.displayName ?? existing.display_name,
    input.bio ?? existing.bio,
    changesAvatar ? null : existing.avatar_url,
    changesAvatar ? input.avatarMediaId ?? null : existing.avatar_media_id,
    now,
    viewer.id,
  ).run();
  return jsonResponse({ user: userJson(context, {
    ...existing,
    display_name: input.displayName ?? existing.display_name,
    bio: input.bio ?? existing.bio,
    avatar_url: changesAvatar ? null : existing.avatar_url,
    avatar_media_id: changesAvatar ? input.avatarMediaId ?? null : existing.avatar_media_id,
    updated_at: now,
  }) });
}

export async function getUser(context: RequestContext, requestedUsername: string): Promise<Response> {
  const normalizedUsername = requestedUsername.toLowerCase();
  const row = await context.db.prepare(
    `SELECT id, username, display_name, bio, avatar_url, avatar_media_id, karma, created_at, updated_at
     FROM users WHERE username = ?`,
  ).bind(normalizedUsername).first<PublicUserRow>();
  const profile = row ?? editorialProfiles[normalizedUsername];
  if (!profile) throw new AppError(404, "user_not_found", "User not found");
  return jsonResponse({ user: userJson(context, profile) });
}
