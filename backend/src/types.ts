export type AppEnv = Env & {
  /** Present after the account webhook is configured. Polling remains a fallback. */
  STREAM_WEBHOOK_SECRET?: string;
};

export type Database = Pick<D1DatabaseSession, "prepare" | "batch">;

export interface Viewer {
  id: string;
  username: string;
  displayName: string;
  sessionId: string;
}

export interface RequestContext {
  request: Request;
  url: URL;
  env: AppEnv;
  execution: ExecutionContext;
  db: D1DatabaseSession;
  requestId: string;
  viewer: Viewer | null;
}

export type RouteParams = Readonly<Record<string, string>>;

export type RouteHandler = (
  context: RequestContext,
  params: RouteParams,
) => Promise<Response>;

export interface LiveEvent {
  type: "comment.created" | "vote.changed" | "post.reshared";
  postId: string;
  actorId: string;
  entityId: string;
  occurredAt: number;
  payload: Record<string, string | number | boolean | null>;
}

export interface LiveEnvelope extends LiveEvent {
  sequence: number;
}
