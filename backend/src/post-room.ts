import { DurableObject } from "cloudflare:workers";
import type { LiveEnvelope, LiveEvent } from "./types";

interface SequenceRow { [key: string]: SqlStorageValue; value: number }
interface StoredEventRow { [key: string]: SqlStorageValue; payload: string }
interface OldestEventRow { [key: string]: SqlStorageValue; sequence: number | null }
interface ConnectionAttachment { userId: string; connectedAt: number }

export class PostRoom extends DurableObject<Env> {
  constructor(context: DurableObjectState, env: Env) {
    super(context, env);
    void context.blockConcurrencyWhile(async () => {
      this.ctx.storage.sql.exec(`
        CREATE TABLE IF NOT EXISTS room_meta (
          key TEXT PRIMARY KEY,
          value INTEGER NOT NULL
        );
        INSERT OR IGNORE INTO room_meta (key, value) VALUES ('sequence', 0);
        CREATE TABLE IF NOT EXISTS room_events (
          sequence INTEGER PRIMARY KEY,
          payload TEXT NOT NULL,
          created_at INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS room_events_created ON room_events(created_at);
      `);
    });
  }

  async publish(event: LiveEvent): Promise<number> {
    const sequence = this.ctx.storage.sql.exec<SequenceRow>(
      "UPDATE room_meta SET value = value + 1 WHERE key = 'sequence' RETURNING value",
    ).one().value;
    const envelope: LiveEnvelope = { ...event, sequence };
    const payload = JSON.stringify(envelope);
    this.ctx.storage.sql.exec(
      "INSERT INTO room_events (sequence, payload, created_at) VALUES (?, ?, ?)",
      sequence,
      payload,
      event.occurredAt,
    );
    this.ctx.storage.sql.exec(
      "DELETE FROM room_events WHERE sequence <= ?",
      Math.max(0, sequence - 256),
    );

    for (const socket of this.ctx.getWebSockets()) {
      if (socket.readyState !== WebSocket.OPEN) continue;
      try {
        socket.send(payload);
      } catch (error) {
        console.error(JSON.stringify({
          level: "warn",
          message: "post room broadcast failed",
          sequence,
          error: error instanceof Error ? error.message : String(error),
        }));
      }
    }
    return sequence;
  }

  override async fetch(request: Request): Promise<Response> {
    if (request.headers.get("upgrade")?.toLowerCase() !== "websocket") {
      return new Response("Expected Upgrade: websocket", { status: 426 });
    }
    const userId = request.headers.get("x-authenticated-user");
    if (!userId) return new Response("Missing authenticated user", { status: 401 });

    const after = Math.max(0, Number(new URL(request.url).searchParams.get("after") ?? "0") || 0);
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    server.serializeAttachment({ userId, connectedAt: Date.now() } satisfies ConnectionAttachment);
    this.ctx.acceptWebSocket(server, [`user:${userId}`]);

    const sequence = this.currentSequence();
    const oldest = this.ctx.storage.sql.exec<OldestEventRow>(
      "SELECT MIN(sequence) AS sequence FROM room_events",
    ).one().sequence;
    if (after > 0 && oldest !== null && after < oldest - 1) {
      server.send(JSON.stringify({
        type: "resync_required",
        sequence,
        oldestAvailableSequence: oldest,
      }));
    } else {
      const missed = this.ctx.storage.sql.exec<StoredEventRow>(
        "SELECT payload FROM room_events WHERE sequence > ? ORDER BY sequence LIMIT 256",
        after,
      ).toArray();
      for (const event of missed) server.send(event.payload);
    }
    // `ready` is the replay barrier: every event through this sequence has
    // either been delivered above or the client was explicitly told to resync.
    server.send(JSON.stringify({ type: "ready", sequence }));

    return new Response(null, { status: 101, webSocket: client });
  }

  override async webSocketMessage(socket: WebSocket, message: string | ArrayBuffer): Promise<void> {
    if (typeof message !== "string" || message.length > 4_096) {
      socket.close(1009, "Message too large");
      return;
    }
    try {
      const parsed: unknown = JSON.parse(message);
      if (
        typeof parsed === "object" &&
        parsed !== null &&
        "type" in parsed &&
        parsed.type === "ping"
      ) {
        socket.send(JSON.stringify({ type: "pong", sequence: this.currentSequence() }));
      }
    } catch {
      socket.send(JSON.stringify({ type: "error", code: "invalid_message" }));
    }
  }

  override async webSocketClose(
    _socket: WebSocket,
    _code: number,
    _reason: string,
    _wasClean: boolean,
  ): Promise<void> {}

  override async webSocketError(_socket: WebSocket, error: unknown): Promise<void> {
    console.error(JSON.stringify({
      level: "warn",
      message: "post room websocket error",
      error: error instanceof Error ? error.message : String(error),
    }));
  }

  private currentSequence(): number {
    return this.ctx.storage.sql.exec<SequenceRow>(
      "SELECT value FROM room_meta WHERE key = 'sequence'",
    ).one().value;
  }
}
