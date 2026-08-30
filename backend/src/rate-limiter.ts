import { DurableObject } from "cloudflare:workers";

export interface RateLimitDecision {
  allowed: boolean;
  remaining: number;
  resetAt: number;
}

interface BucketRow {
  [key: string]: SqlStorageValue;
  count: number;
  window_started_at: number;
}

export class RateLimiter extends DurableObject<Env> {
  constructor(context: DurableObjectState, env: Env) {
    super(context, env);
    void context.blockConcurrencyWhile(async () => {
      this.ctx.storage.sql.exec(`
        CREATE TABLE IF NOT EXISTS rate_bucket (
          singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
          count INTEGER NOT NULL,
          window_started_at INTEGER NOT NULL
        )
      `);
    });
  }

  async consume(limit: number, windowMs: number, now = Date.now()): Promise<RateLimitDecision> {
    const boundedLimit = Math.max(1, Math.min(limit, 10_000));
    const boundedWindow = Math.max(1_000, Math.min(windowMs, 24 * 60 * 60 * 1000));
    this.ctx.storage.sql.exec(
      `INSERT INTO rate_bucket (singleton, count, window_started_at)
       VALUES (1, 1, ?)
       ON CONFLICT(singleton) DO UPDATE SET
         count = CASE
           WHEN rate_bucket.window_started_at + ? <= ? THEN 1
           ELSE rate_bucket.count + 1
         END,
         window_started_at = CASE
           WHEN rate_bucket.window_started_at + ? <= ? THEN ?
           ELSE rate_bucket.window_started_at
         END`,
      now,
      boundedWindow,
      now,
      boundedWindow,
      now,
      now,
    );
    const row = this.ctx.storage.sql.exec<BucketRow>(
      "SELECT count, window_started_at FROM rate_bucket WHERE singleton = 1",
    ).one();
    return {
      allowed: row.count <= boundedLimit,
      remaining: Math.max(0, boundedLimit - row.count),
      resetAt: row.window_started_at + boundedWindow,
    };
  }
}
