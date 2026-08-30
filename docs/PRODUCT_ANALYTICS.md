# Product analytics and engagement sessions

Product analytics is intentionally separate from performance telemetry:

- `readthat_performance` contains bounded latency, frame, network, and media-start distributions.
- `readthat_product_analytics` contains sessions and user behavior.

The Android exporter uses `ProcessLifecycleOwner`, a bounded in-memory channel,
a Room outbox, and network-constrained WorkManager delivery. A session spans
short foreground/background transitions and rotates after 30 minutes of
inactivity or an account change. Background checkpoints provide cumulative
active foreground time; a stale checkpoint is finalized on the next launch.

## Identity and privacy

The app creates a random installation UUID in app-private preferences. It never
uses an advertising ID, Android ID, hardware identifier, username, or email.
The Worker chooses the authenticated user when a valid token accompanies the
original user's event; otherwise it uses the installation. Both are HMACed with
`ANALYTICS_ID_PEPPER`, and only the irreversible 32-character pseudonym is
stored as Analytics Engine `index1`. Content IDs receive a separate HMAC.

Clearing app data creates a new installation identity. Raw installation,
account, and content IDs are never written to Analytics Engine or Worker logs.
No titles, comment bodies, URLs, or auth tokens are accepted by the ingest
schema.

## Event guarantees

- `post_impression`: the post remained in the viewport through the 600 ms dwell gate; unique per session/post.
- `post_detail_view`: unique per session/post.
- `comments_view`: unique per session/post.
- `comment_create`: emitted on the optimistic local commit, preserving the session where an offline action occurred.
- `media_playback`: actual Media3 `isPlaying` time, split when playback moves between feed and detail.
- `session_checkpoint`: cumulative foreground-active milliseconds at background.
- `session_summary`: emitted when the next timeout/account boundary is observed.

The reusable, sampling-aware SQL pack is
[`../backend/analytics/product-queries.sql`](../backend/analytics/product-queries.sql).
Run it against Cloudflare's Analytics Engine SQL API for the account hosting
`readthat_product_analytics`. Keep the time predicates: Analytics Engine is
an operational product-analytics stream, not an unlimited event warehouse.

Before deploying, create the independent HMAC secret:

```bash
cd backend
openssl rand -base64 48 | npx wrangler secret put ANALYTICS_ID_PEPPER
```
