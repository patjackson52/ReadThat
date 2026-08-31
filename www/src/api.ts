import {
  listOutbox,
  loadAuthState,
  putOutbox,
  removeOutbox,
  saveAuthState,
} from "./db";
import type {
  ApiErrorBody,
  AuthState,
  CommentTree,
  CommunityDrawer,
  FeedPage,
  MediaFeedPage,
  OutboxEntry,
  Post,
  SearchItem,
  SearchSections,
  SearchDiscover,
  SearchPageResponse,
  SearchSort,
  SearchTime,
  SearchType,
  SearchTypeahead,
  Session,
  Subreddit,
  UploadSession,
  UploadedMedia,
  User,
  VoteValue,
} from "./types";

export function flattenSearchSections(sections: Record<string, SearchItem[]> | SearchSections): SearchItem[] {
  const seen = new Set<string>();
  return Object.values(sections).flat().filter((item) => {
    const key = `${item.type}:${item.id}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly details?: unknown,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

type AuthListener = (state: AuthState | null) => void;
type OutboxListener = (count: number) => void;

function isNetworkError(error: unknown): boolean {
  return error instanceof TypeError || (error instanceof DOMException && error.name === "NetworkError");
}

async function responseError(response: Response): Promise<ApiError> {
  let body: ApiErrorBody = {};
  try { body = await response.json() as ApiErrorBody; } catch { /* A proxy may return an empty error page. */ }
  const code = body.error?.code ?? body.code ?? `http_${response.status}`;
  const message = body.error?.message ?? body.message ?? `Request failed with HTTP ${response.status}`;
  return new ApiError(response.status, code, message, body.error?.details);
}

export class ApiClient {
  private auth: AuthState | null = null;
  private readonly authListeners = new Set<AuthListener>();
  private readonly outboxListeners = new Set<OutboxListener>();
  private refreshPromise: Promise<boolean> | null = null;
  private bookmark = sessionStorage.getItem("readthat-d1-bookmark");

  get authState(): AuthState | null { return this.auth; }

  async restore(): Promise<AuthState | null> {
    this.auth = await loadAuthState();
    this.emitAuth();
    await this.emitOutbox();
    if (this.auth && this.auth.session.refreshExpiresAt <= Date.now()) await this.setAuth(null);
    return this.auth;
  }

  subscribeAuth(listener: AuthListener): () => void {
    this.authListeners.add(listener);
    return () => this.authListeners.delete(listener);
  }

  subscribeOutbox(listener: OutboxListener): () => void {
    this.outboxListeners.add(listener);
    return () => this.outboxListeners.delete(listener);
  }

  async register(input: { username: string; password: string; displayName?: string }): Promise<AuthState> {
    const state = await this.request<AuthState>("/v1/auth/register", {
      method: "POST",
      body: JSON.stringify(input),
    }, false);
    await this.setAuth(state);
    return state;
  }

  async login(input: { username: string; password: string }): Promise<AuthState> {
    const state = await this.request<AuthState>("/v1/auth/login", {
      method: "POST",
      body: JSON.stringify(input),
    }, false);
    await this.setAuth(state);
    return state;
  }

  async logout(): Promise<void> {
    try { await this.request<void>("/v1/auth/logout", { method: "POST" }, false); } catch { /* Local logout must succeed. */ }
    await this.setAuth(null);
    this.bookmark = null;
    sessionStorage.removeItem("readthat-d1-bookmark");
  }

  async me(): Promise<User> {
    return (await this.request<{ user: User }>("/v1/me")).user;
  }

  async user(username: string): Promise<User> {
    return (await this.request<{ user: User }>(`/v1/users/${encodeURIComponent(username)}`)).user;
  }

  async feed(cursor: string | null, subreddit?: string): Promise<FeedPage> {
    const parameters = new URLSearchParams({ limit: "12" });
    if (cursor) parameters.set("cursor", cursor);
    if (subreddit) parameters.set("subreddit", subreddit);
    else parameters.set("includePromoted", "true");
    return this.request<FeedPage>(`/v1/feed?${parameters.toString()}`);
  }

  async mediaFeed(cursor: string | null, options: { subreddit?: string; anchorPostId?: string } = {}): Promise<MediaFeedPage> {
    const parameters = new URLSearchParams({ limit: "8" });
    if (cursor) parameters.set("cursor", cursor);
    if (options.subreddit) parameters.set("subreddit", options.subreddit);
    if (!cursor && options.anchorPostId) parameters.set("anchorPostId", options.anchorPostId);
    return this.request<MediaFeedPage>(`/v1/feeds/media?${parameters.toString()}`);
  }

  async post(id: string): Promise<Post> {
    return (await this.request<{ post: Post }>(`/v1/posts/${encodeURIComponent(id)}`)).post;
  }

  async comments(postId: string, focusCommentId?: string): Promise<CommentTree> {
    const parameters = new URLSearchParams({ count: "100", depth: "10" });
    if (focusCommentId) parameters.set("focusCommentId", focusCommentId);
    return this.request<CommentTree>(
      `/v1/posts/${encodeURIComponent(postId)}/comments?${parameters.toString()}`,
    );
  }

  async loadMoreComments(postId: string, childIds: string[]): Promise<{
    comments: Array<Omit<import("./types").CommentNode, "type" | "children"> & { parentId: string | null }>;
    cursors: import("./types").LoadMoreNode[];
  }> {
    return this.request(`/v1/posts/${encodeURIComponent(postId)}/comments/more`, {
      method: "POST",
      body: JSON.stringify({ childIds, limit: 100, maxDepth: 10 }),
    });
  }

  async subreddit(name: string): Promise<Subreddit> {
    return (await this.request<{ subreddit: Subreddit }>(`/v1/subreddits/${encodeURIComponent(name)}`)).subreddit;
  }

  async drawer(): Promise<CommunityDrawer> {
    return this.request<CommunityDrawer>("/v1/me/community-drawer?limit=100");
  }

  async search(query: string): Promise<SearchItem[]> {
    const response = await this.searchPage({ query });
    return flattenSearchSections(response.sections ?? {});
  }

  async searchPage(input: {
    query: string;
    type?: SearchType;
    sort?: SearchSort;
    time?: SearchTime;
    safe?: boolean;
    subreddit?: string;
    cursor?: string | null;
  }): Promise<SearchPageResponse> {
    const parameters = new URLSearchParams({
      q: input.query,
      type: input.type ?? "all",
      sort: input.sort ?? "relevance",
      time: input.time ?? "all",
      safe: String(input.safe ?? true),
      limit: "20",
    });
    if (input.subreddit) parameters.set("subreddit", input.subreddit);
    if (input.cursor) parameters.set("cursor", input.cursor);
    return this.request<SearchPageResponse>(`/v1/search?${parameters.toString()}`);
  }

  async typeahead(query: string): Promise<SearchTypeahead> {
    return this.request<SearchTypeahead>(`/v1/search/typeahead?q=${encodeURIComponent(query)}&limit=8`);
  }

  async discover(): Promise<SearchDiscover> {
    return this.request<SearchDiscover>("/v1/search/discover");
  }

  async updateProfile(input: { displayName?: string; bio?: string; avatarMediaId?: string | null }): Promise<User> {
    const user = (await this.request<{ user: User }>("/v1/me", {
      method: "PATCH",
      body: JSON.stringify(input),
    })).user;
    if (this.auth) await this.setAuth({ ...this.auth, user });
    return user;
  }

  async mutate<T>(entry: Omit<OutboxEntry, "id" | "accountId" | "createdAt" | "attempts">): Promise<T | null> {
    const account = this.auth?.user.id;
    if (!account) throw new ApiError(401, "authentication_required", "Sign in to continue");
    const queued: OutboxEntry = {
      ...entry,
      id: crypto.randomUUID(),
      accountId: account,
      createdAt: Date.now(),
      attempts: 0,
    };
    if (!navigator.onLine) {
      await this.enqueue(queued);
      return null;
    }
    try {
      return await this.request<T>(entry.path, { method: entry.method, body: JSON.stringify(entry.body) });
    } catch (error) {
      if (isNetworkError(error) || (error instanceof ApiError && error.status >= 500)) {
        await this.enqueue(queued);
        return null;
      }
      throw error;
    }
  }

  async vote(target: "post" | "comment", id: string, value: VoteValue): Promise<{ vote: { value: VoteValue; score: number; version: number } } | null> {
    return this.mutate({
      kind: "vote",
      path: `/v1/${target === "post" ? "posts" : "comments"}/${encodeURIComponent(id)}/vote`,
      method: "PUT",
      body: { value, clientMutationId: crypto.randomUUID() },
    });
  }

  async createComment(postId: string, body: string, parentId: string | null): Promise<{ comment: { id: string } } | null> {
    return this.mutate({
      kind: "comment",
      path: `/v1/posts/${encodeURIComponent(postId)}/comments`,
      method: "POST",
      body: { body, parentId, clientMutationId: crypto.randomUUID() },
    });
  }

  async createCommunity(input: { name: string; displayName: string; description: string; accessType: Subreddit["accessType"] }): Promise<{ subreddit: Subreddit } | null> {
    return this.mutate({
      kind: "community",
      path: "/v1/subreddits",
      method: "POST",
      body: { ...input, clientMutationId: crypto.randomUUID() },
    });
  }

  async createPost(input: { subreddit: string; kind: string; title: string; body?: string; url?: string; mediaId?: string; mediaIds?: string[] }): Promise<{ post: Post } | null> {
    return this.mutate({
      kind: "post",
      path: "/v1/posts",
      method: "POST",
      body: { ...input, clientMutationId: crypto.randomUUID() },
    });
  }

  async joinCommunity(name: string): Promise<void> {
    await this.request(`/v1/subreddits/${encodeURIComponent(name)}/join`, { method: "POST" });
  }

  async leaveCommunity(name: string): Promise<void> {
    await this.request(`/v1/subreddits/${encodeURIComponent(name)}/join`, { method: "DELETE" });
  }

  async markCommunityVisited(name: string): Promise<void> {
    if (!this.auth) return;
    await this.mutate({
      kind: "visit",
      path: "/v1/me/community-visits",
      method: "PUT",
      body: { commands: [{ id: crypto.randomUUID(), operation: "visit", name, occurredAt: Date.now() }] },
    });
  }

  async uploadMedia(
    file: File,
    kind: "image" | "video",
    metadata: { width?: number; height?: number; durationSeconds?: number; altText: string },
    onProgress: (progress: number) => void,
  ): Promise<UploadedMedia> {
    const { upload } = await this.request<{ upload: UploadSession }>("/v1/media/uploads", {
      method: "POST",
      body: JSON.stringify({
        kind,
        contentType: file.type,
        byteSize: file.size,
        ...metadata,
      }),
    });
    const headers = { "content-type": file.type, "x-upload-token": upload.uploadToken };
    if (upload.mode === "single") {
      const response = await this.request<{ media: UploadedMedia }>(upload.uploadPath, {
        method: "PUT",
        headers,
        body: file,
      });
      onProgress(100);
      return response.media;
    }
    const partSize = upload.partSize ?? file.size;
    for (let index = 0; index < upload.partCount; index += 1) {
      const part = file.slice(index * partSize, Math.min(file.size, (index + 1) * partSize), file.type);
      await this.request(upload.uploadPath.replace("{partNumber}", String(index + 1)), {
        method: "PUT",
        headers,
        body: part,
      });
      onProgress(Math.round(((index + 1) / upload.partCount) * 95));
    }
    const complete = await this.request<{ media: UploadedMedia }>(upload.completePath ?? "", {
      method: "POST",
      headers: { "x-upload-token": upload.uploadToken },
    });
    onProgress(100);
    return complete.media;
  }

  async flushOutbox(): Promise<void> {
    const accountId = this.auth?.user.id;
    if (!accountId || !navigator.onLine) return;
    const entries = await listOutbox(accountId);
    for (const entry of entries) {
      try {
        await this.request(entry.path, { method: entry.method, body: JSON.stringify(entry.body) });
        await removeOutbox(entry.id);
      } catch (error) {
        const retryable = isNetworkError(error) || (error instanceof ApiError && (error.status >= 500 || error.status === 429));
        if (!retryable) {
          await putOutbox({ ...entry, attempts: entry.attempts + 1, lastError: error instanceof Error ? error.message : "Mutation failed" });
        }
        break;
      }
    }
    await this.emitOutbox();
  }

  async request<T>(path: string, init: RequestInit = {}, allowRefresh = true): Promise<T> {
    const headers = new Headers(init.headers);
    if (init.body && !(init.body instanceof Blob) && !headers.has("content-type")) {
      headers.set("content-type", "application/json");
    }
    const anonymousAuthRoute = path === "/v1/auth/login" || path === "/v1/auth/register" || path === "/v1/auth/refresh";
    if (this.auth?.session.accessToken && !anonymousAuthRoute) {
      headers.set("authorization", `Bearer ${this.auth.session.accessToken}`);
    }
    if (this.bookmark) headers.set("x-d1-bookmark", this.bookmark);
    const response = await fetch(path, { ...init, headers, credentials: "same-origin" });
    const nextBookmark = response.headers.get("x-d1-bookmark");
    if (nextBookmark) {
      this.bookmark = nextBookmark;
      sessionStorage.setItem("readthat-d1-bookmark", nextBookmark);
    }
    if (response.status === 401 && allowRefresh && this.auth?.session.refreshToken && !path.endsWith("/refresh")) {
      if (await this.refreshSession()) return this.request<T>(path, init, false);
    }
    if (!response.ok) throw await responseError(response);
    if (response.status === 204 || response.headers.get("content-length") === "0") return undefined as T;
    return response.json() as Promise<T>;
  }

  private async refreshSession(): Promise<boolean> {
    this.refreshPromise ??= (async () => {
      const refreshToken = this.auth?.session.refreshToken;
      if (!refreshToken) return false;
      try {
        const next = await this.request<{ user: User; session: Session }>("/v1/auth/refresh", {
          method: "POST",
          body: JSON.stringify({ refreshToken }),
        }, false);
        await this.setAuth({ user: { ...this.auth?.user, ...next.user } as User, session: next.session });
        return true;
      } catch {
        await this.setAuth(null);
        return false;
      } finally {
        this.refreshPromise = null;
      }
    })();
    return this.refreshPromise;
  }

  private async enqueue(entry: OutboxEntry): Promise<void> {
    await putOutbox(entry);
    await this.emitOutbox();
    const registration = await navigator.serviceWorker?.ready.catch(() => null);
    if (registration && "sync" in registration) {
      await (registration as ServiceWorkerRegistration & { sync: { register(tag: string): Promise<void> } }).sync
        .register("readthat-outbox")
        .catch(() => undefined);
    }
  }

  private async setAuth(state: AuthState | null): Promise<void> {
    this.auth = state;
    await saveAuthState(state);
    this.emitAuth();
    await this.emitOutbox();
  }

  private emitAuth(): void { this.authListeners.forEach((listener) => listener(this.auth)); }

  private async emitOutbox(): Promise<void> {
    const count = this.auth ? (await listOutbox(this.auth.user.id)).length : 0;
    this.outboxListeners.forEach((listener) => listener(count));
  }
}

export const api = new ApiClient();
