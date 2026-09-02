# ReadThat backend

This directory contains the working Cloudflare backend for the Android and web
clients. The same Worker serves the PWA as static assets and routes `/v1/*` and
`/health` through the API. Examples use `https://readthat-api.example.com` as a
non-routable placeholder for your deployed origin.

It implements:

- salted, deployment-peppered password auth; opaque hashed access/refresh
  tokens; atomic registration; rotating refresh tokens; logout/revocation;
- public profiles with authenticated display-name/bio editing and owned
  Cloudflare Images avatars;
- public, restricted, and private subreddits with owner, moderator, approved
  member, subscriber, and banned roles;
- text, link, image, video, and reshare posts;
- resumable R2 media staging (single or multipart), Cloudflare Images ingest
  with signed 1080/2048 px variants, and Cloudflare Stream adaptive HLS/DASH;
- signed R2 fallback reads with conditional requests, `HEAD`, and byte ranges;
- arbitrary-depth comment writes (bounded at 1,000) and Reddit-style best-first
  tree reads with `load_more` cursors, fixed 8/200 phases, and depth 10;
- explicit upvote/downvote/remove-vote state with idempotent mutation ledgers,
  transactional aggregates, and karma;
- a personalized, cursor-paged SDUI feed whose first page always starts with
  the pinned public ReadThat overview post; post detail remains a domain model;
- an ACL-filtered account drawer with ETag/keyset-paged memberships and
  idempotent offline visit/remove/clear synchronization;
- ACL-aware FTS5 search across posts, media, comments, communities, and
  profiles, with typeahead, discovery, safe-search filtering, and signed
  snapshot/keyset cursors;
- per-post Hibernating WebSockets with ordered event sequence numbers, bounded
  replay, and gap detection;
- Durable Object rate limiting, structured logs, D1 Sessions bookmarks, CORS,
  bounded request bodies, Workers traces, and privacy-bounded client performance
  distributions in Analytics Engine.

## Run and verify

```bash
cd backend
npm install
cp .dev.vars.example .dev.vars
npm run db:migrate:local
npm run check
npm test
npm run dev -- --port 8788
```

Run the Worker command here and `npm run dev` from `../www` in a second terminal
for local full-stack development. Vite proxies API calls to port 8788 by
default.

In a second terminal:

```bash
npm run smoke
```

The test suite runs inside the Workers runtime with real local D1, R2, and
Durable Object bindings. The smoke script exercises the public HTTP surface and
accepts another target:

```bash
API_BASE_URL=https://your-worker.example npm run smoke
API_BASE_URL=https://your-worker.example npm run smoke:creation
API_BASE_URL=https://your-worker.example npm run smoke:image -- /path/to/image.jpg
API_BASE_URL=https://your-worker.example npm run smoke:video -- /path/to/video.mp4
```

It creates unique disposable accounts and a community, so do not point it at a
database where synthetic rows are unwanted.

### Licensed portrait-video fixtures

[`fixtures/pexels-motion-portrait.json`](fixtures/pexels-motion-portrait.json)
records the source page, creator, Pexels license snapshot, encoding metadata,
byte size, and SHA-256 digest for ten 1080×1920 motion clips. The source MP4s
are intentionally not checked into Git. After downloading the named files into
one directory, seed them through the same production media and post APIs used
by the clients:

```bash
API_BASE_URL=https://your-worker.example \
  npm run seed:pexels-motion -- /path/to/pexels-motion-videos
```

The command verifies every source digest, creates real video posts in
`r/motion`, waits for Cloudflare Stream, validates each adaptive HLS master
playlist and poster, and finally checks the SDUI feed contract. Its JSON output
contains the media/post IDs and CDN HLS, DASH, poster, and preview URLs.
The current production IDs and delivery URLs are recorded in
[`fixtures/pexels-motion-deployment.json`](fixtures/pexels-motion-deployment.json).

### Licensed parkour-video fixtures

[`fixtures/pexels-parkour.json`](fixtures/pexels-parkour.json) records five
ground-level parkour clips from the requested Pexels search, including each
source page, creator, license snapshot, exact source dimensions and duration,
byte size, SHA-256 digest, and an explicit publication timestamp. Download the
catalog's `sourceFileUrl` values to their named files in one directory, then
preview or publish the set:

```bash
npm run seed:pexels-parkour -- --plan

D1_DATABASE=sdui-reddit \
API_BASE_URL=https://your-worker.example \
  npm run seed:pexels-parkour -- /path/to/pexels-parkour-videos
```

The seeder uses the normal authenticated multipart upload and post APIs, waits
for Cloudflare Stream, checks adaptive HLS and posters, and then verifies each
community and anonymous-home SDUI cell. It also reapplies each explicit
`publishedAt` after post creation. With the current
`score * 1_000_000_000 + created_at` hot rank, the production posts land at
positions 15, 19, 28, 39, and 47 in the first 50 anonymous organic groups
instead of forming a video block. Exact deployed IDs, URLs, positions, and
verification results are recorded in
[`fixtures/pexels-parkour-deployment.json`](fixtures/pexels-parkour-deployment.json).

### Licensed demo personas and image posts

[`fixtures/pexels-demo-personas.json`](fixtures/pexels-demo-personas.json)
defines two fictional users, their profiles and communities, and ten processed
Pexels images: two 800×800 avatars and eight image posts up to 1800 pixels wide.
The catalog preserves every Pexels source page, creator, license snapshot,
processed-file dimensions, byte size, and SHA-256 digest. The fictional names
do not identify the people shown in the stock photos.

Download each catalog `sourceFileUrl` to its `fileName` in one directory, then
seed the set through the production profile, community, media, and post APIs:

```bash
API_BASE_URL=https://your-worker.example \
  npm run seed:pexels-personas -- /path/to/pexels-persona-images
```

The command rejects changed files, uploads originals through R2 staging,
confirms synchronous Cloudflare Images ingest and source eviction, assigns both
avatars, creates `r/pnwtrails` and `r/roboticslab`, publishes four image posts
per community, and fetches the avatar, feed, and detail CDN renditions. Its JSON
output contains the stable profile/post API URLs and the current signed Images
URLs. Signed delivery URLs rotate; clients should refresh them through the
stable API URLs rather than persisting an expired signature.
The current production IDs and stable API URLs are recorded in
[`fixtures/pexels-demo-personas-deployment.json`](fixtures/pexels-demo-personas-deployment.json).

### PNW outdoor carousel fixtures

[`fixtures/pnw-carousel-personas.json`](fixtures/pnw-carousel-personas.json)
defines three additional fictional outdoor users, three public communities, and
six multi-image posts about PNW hiking, mountaineering, climbing approaches, and
scrambling. Every post contains an ordered three- or four-photo carousel. The
catalog records the requested [Pexels PNW search](https://www.pexels.com/search/pnw/),
individual source pages, contributor credits, processed dimensions, byte sizes,
and SHA-256 digests for all fourteen source images.

Download each catalog `sourceFileUrl` to its `fileName` in one directory, then
seed the collection through the production auth, profile, community, media,
flair, and post APIs:

```bash
API_BASE_URL=https://your-worker.example \
  npm run seed:pnw-carousels -- /path/to/pnw-carousel-images
```

The seeder validates the licensed sources before its first write, uploads each
persona's owned media to Cloudflare Images, creates the fictional profiles and
communities, and publishes ordered galleries. It then verifies the profile
avatar redirect, post-detail media order, SDUI `image_carousel` order, flair,
and every feed/detail CDN rendition. Current production IDs are recorded in
[`fixtures/pnw-carousel-deployment.json`](fixtures/pnw-carousel-deployment.json).

### Persona text conversations

[`fixtures/persona-conversations.json`](fixtures/persona-conversations.json)
adds two moderate and two long-form text posts to `r/pnwtrails` and
`r/roboticslab`. Each post includes Markdown structure and HTTPS references,
plus an on-topic comment from the other fictional persona. The comments cover
one, three, five, and seven prose paragraphs for varied rendering and scrolling
tests.

Seed or safely re-run the deterministic fixture against the configured D1
database with:

```bash
npm run seed:persona-conversations
```

The script validates the source, inserts the posts and author votes using stable
IDs, then reads every post and comment through the live API. It uses direct,
scoped D1 writes because the disposable persona passwords are intentionally not
retained. Current production IDs and page URLs are recorded in
[`fixtures/persona-conversations-deployment.json`](fixtures/persona-conversations-deployment.json).

### Deeply nested discussion fixtures

[`fixtures/deeply-nested-discussions.mjs`](fixtures/deeply-nested-discussions.mjs)
defines eight expert, long-form posts in `r/deeply_nested` about fetch budgets,
load-more accounting, storage and client data structures, payload/cache sizing,
offline-first policy, rendering, REST versus WebSocket, and performance testing.
The 1,883 generated comments form three wide forests with 50, 250, and 1,000
roots; three chains with 10, 15, and 20 levels; and two hybrid breadth/depth
workloads with side replies.

Validate the complete deterministic plan without writing, or idempotently seed
the configured D1 database and verify every live response:

```bash
npm run seed:deeply-nested -- --plan
npm run seed:deeply-nested
```

The seeder validates body lengths and parent/depth invariants before its first
write, chunks SQL below the D1 statement-size limit, verifies stored root/count/
depth metrics, measures the 8-comment and 200-comment API shapes in raw and gzip
bytes, and rejects cursors containing more than 100 IDs. Idempotent verification
counts deterministic fixture comments separately from organic replies, so real
discussion activity is preserved and reported instead of treated as corruption.
Current production IDs,
page URLs, workload shapes, and measured payloads are recorded in
[`fixtures/deeply-nested-deployment.json`](fixtures/deeply-nested-deployment.json).

### Rick and Morty character and episode fixtures

[`fixtures/rick-and-morty-demo.json`](fixtures/rick-and-morty-demo.json)
defines 22 clearly fictional fan-demo profiles: the six principal family
identities (including Space Beth), major recurring characters, and the variants
needed for five episode conversations. All 22 checksum-pinned portraits come
from the public [Rick and Morty API](https://rickandmortyapi.com/api), whose
character schema provides 300×300 avatar portraits. Six are also reused as
image-post media.

The five episode communities follow the top five in a June 2025 aggregation of
29 ranked episode lists: *Total Rickall*, *Pickle Rick*, *The Ricklantis Mixup*,
*Rick Potion #9*, and *Meeseeks and Destroy*. A sixth Dimension C-137 lounge
brings the main and recurring cast together. The fixture contains 11 original
fan-fiction posts, 61 nested comments, and six checksum-pinned image posts; it
does not copy episode dialogue or still frames.

Feed-balance revision 2 assigns explicit `publishedAt` values across August
28–30 and keeps only one or two intentional seed upvotes per post. This matters
because the current hot rank is `score * 1_000_000_000 + created_at`: one score
point is worth about 11.6 days of recency. The Worker preserves that order inside
image, video, and other-content lanes, then merges those signed keysets in a
seven-slot showcase pattern: image, other, video, other, image, other, video.
Sparse communities fall back to the highest-ranked available lane instead of
mixing content from another community.

The current production IDs, page URLs, counts, Worker version, and verification
results are recorded in
[`fixtures/rick-and-morty-demo-deployment.json`](fixtures/rick-and-morty-demo-deployment.json).

The home feed also includes seven explicitly labeled portfolio-demo ads for
Patrick Jackson as a Reddit Client Platform Engineer. Five AI-written fan-demo
endorsements use the hosted portraits of Rick Sanchez, Evil Morty, Dr. Wong,
Space Beth, and Unity. Two first-party portfolio units use Patrick's versioned
headshots from the private R2 media bucket. Each ad carries three curated
`r/readthateng` case-study cards that navigate to real in-app posts, and the
catalog collectively covers all 11 engineering deep dives. Campaign copy,
stable ad IDs, related post IDs, media keys, and placement live in
[`src/promoted.ts`](src/promoted.ts).
The campaign alternates three- and four-organic-post gaps across signed cursor
pages, starting headshot → Rick → headshot before the remaining character ads.

Validate every API record, relationship, body bound, and portrait checksum
without writing, or idempotently seed the configured D1 database and R2 bucket:

```bash
npm run seed:rick-and-morty -- --plan

D1_DATABASE=sdui-reddit \
R2_BUCKET=sdui-reddit-media \
API_BASE_URL=https://your-worker.example \
  npm run seed:rick-and-morty

# Reapply only deterministic dates, seed votes, and related D1 metadata.
D1_DATABASE=sdui-reddit \
API_BASE_URL=https://your-worker.example \
  npm run seed:rick-and-morty -- --rebalance-feed
```

The seeder rejects username/community identity collisions before its first
write, uses deterministic IDs, uploads media under a versioned R2 prefix, and
then verifies all profiles, community feeds, posts, recursive comment trees,
and image bytes through the deployed API. These are content-only personas with
non-login password placeholders, matching the existing disposable persona
fixture policy. Re-running the same fixture version is safe.

### ReadThat engineering case-study fixtures

[`fixtures/readthat-case-study.json`](fixtures/readthat-case-study.json) maps
the overview and ten pages at
[patrickjackson.dev/case-studies/readthat](https://patrickjackson.dev/case-studies/readthat/)
to 11 deterministic text posts in `r/readthateng`, all authored by
`u/patrickjackson`. Each post contains an original Problem / Tradeoff / Solution
TLDR plus a Markdown link to its matching deep dive. Feed-balance revision 1
places one post on August 30 and distributes the rest through August 28 against
existing score-one content, so the current score-plus-recency ranking mixes the
series into the demo feed instead of presenting an 11-post block.

Preview the IDs without writing, or seed and verify the live community and post
detail endpoints:

```bash
npm run seed:readthat-case-study -- --plan

D1_DATABASE=sdui-reddit \
WRANGLER_CONFIG=/path/to/live-wrangler.jsonc \
API_BASE_URL=https://your-worker.example \
  npm run seed:readthat-case-study

# After deploying the ad catalog, also verify all seven ad carousels.
D1_DATABASE=sdui-reddit \
WRANGLER_CONFIG=/path/to/live-wrangler.jsonc \
API_BASE_URL=https://your-worker.example \
  npm run seed:readthat-case-study -- --verify-ads
```

If `u/patrickjackson` already exists, the seeder preserves that account and uses
its ID. Otherwise it materializes the existing editorial profile as a
content-only database identity; it never writes a usable password. Re-running
the same fixture version updates content in place after rejecting ID/name
collisions.

The current production community, post IDs and URLs, Worker version, ad-link
coverage, feed positions, and verification results are recorded in
[`fixtures/readthat-case-study-deployment.json`](fixtures/readthat-case-study-deployment.json).

## Deploy

The checked-in `wrangler.jsonc` targets the existing production Worker,
`sdui-reddit-api`, and its `sdui-reddit` D1 database and
`sdui-reddit-media` R2 bucket. Resource IDs and the Images account hash are
public identifiers; credentials remain Worker secrets. For a new account,
create equivalent resources and replace those identifiers before deploying:

```bash
npx wrangler d1 create sdui-reddit
npx wrangler r2 bucket create sdui-reddit-media
openssl rand -base64 48 | npx wrangler secret put AUTH_PEPPER
openssl rand -base64 48 | npx wrangler secret put ANALYTICS_ID_PEPPER
openssl rand -base64 48 | npx wrangler secret put CURSOR_SECRET
openssl rand -base64 48 | npx wrangler secret put MEDIA_SIGNING_SECRET
npx wrangler secret put IMAGES_SIGNING_KEY
npm run db:migrate:remote
npm run deploy # builds ../www, uploads its static assets, then deploys the API
```

`IMAGES_SIGNING_KEY` is the default key from **Images → Hosted Images → Keys**;
it is never returned to a client or committed. Enable Images storage and Stream,
create private `feed` (1080×1080 scale-down) and `detail` (2048×2048
scale-down) variants, and set the account hash in `wrangler.jsonc`. Configure the
Stream webhook to `/v1/media/stream/webhook` and store its generated secret as
`STREAM_WEBHOOK_SECRET`; status polling remains a fallback.

Never reuse the local values in `.dev.vars` for production.

## Android live mode

Without configuration the app uses its deterministic fixtures. Set only the
base URL to read the real public feed and details anonymously:

```bash
./gradlew :app:installDebug \
  -PREADTHAT_API_BASE_URL=https://readthat-api.example.com
```

For a personalized feed and real votes, first register an account through the
API, then add these build-time demo properties:

```text
READTHAT_API_BASE_URL=https://readthat-api.example.com
READTHAT_DEMO_USERNAME=your_username
READTHAT_DEMO_PASSWORD=your_password
```

Put them in the untracked `local.properties`, or pass them as `-P` arguments.
The credentials are a demo convenience, not an acceptable production login UX.
After login, the app stores only rotating tokens, encrypted by a non-exportable
Android Keystore AES-GCM key. It persists the latest D1 bookmark for monotonic
read-after-write behavior.

## HTTP API

All JSON writes require `Content-Type: application/json`. Authenticated routes
accept `Authorization: Bearer <accessToken>`. Mobile clients should echo the
latest `X-D1-Bookmark` response header on later requests.

| Method and path | Purpose |
|---|---|
| `GET /health` | Liveness and environment |
| `POST /v1/auth/register` | Create user and session atomically |
| `POST /v1/auth/login` | Create session |
| `POST /v1/auth/refresh` | Rotate both opaque tokens |
| `POST /v1/auth/logout` | Revoke current session |
| `GET/PATCH /v1/me` | Current profile |
| `GET /v1/users/:username` | Public profile |
| `GET /v1/users/:username/avatar` | Stable avatar URL redirecting to a short-lived signed Images rendition |
| `POST /v1/subreddits` | Create subreddit; creator becomes owner |
| `GET /v1/me/community-drawer` | Compact private memberships + recents; ETag and signed keyset cursor |
| `PUT /v1/me/community-visits` | Apply up to 50 ordered, idempotent visit/remove/clear commands |
| `GET/PATCH /v1/subreddits/:name` | Read/update subreddit |
| `POST/DELETE /v1/subreddits/:name/join` | Join/leave |
| `PUT/DELETE /v1/subreddits/:name/members/:username` | Moderate membership/role |
| `POST /v1/media/uploads` | Create single/multipart R2 upload session |
| `PUT /v1/media/uploads/:id` | Stream a small object to R2 |
| `PUT /v1/media/uploads/:id/parts/:number` | Upload resumable 8 MiB part |
| `POST /v1/media/uploads/:id/complete` | Complete multipart upload |
| `POST /v1/media/uploads/:id/refresh` | Refresh Cloudflare Stream processing state |
| `DELETE /v1/media/uploads/:id` | Abort upload |
| `GET/HEAD /v1/media/:id?...` | Signed conditional/range media read |
| `POST /v1/posts` | Create text/link/image/video post |
| `GET /v1/posts/:id` | Domain-shaped post detail |
| `POST /v1/posts/:id/reshare` | Crosspost/reshare |
| `PUT /v1/posts/:id/vote` | Set vote to `-1`, `0`, or `1` |
| `POST /v1/posts/:id/comments` | Create root or nested comment |
| `GET /v1/posts/:id/comments` | Recursive tree; `sort=best|top|qa|controversial|new|old` |
| `POST /v1/posts/:id/comments/more` | Sort-aware flat parent-linked continuation |
| `PUT /v1/comments/:id/vote` | Set comment vote |
| `GET /v1/feed` | Personalized SDUI groups/cells |
| `GET /v1/feeds/media` | Typed media-only ranked page; optional exact first-page anchor and signed viewer-bound cursor |
| `GET /v1/search/discover` | Safe trending posts and readable communities for the empty search screen |
| `GET /v1/search/typeahead?q=...` | Query completions plus community/profile matches |
| `GET /v1/search?q=...&type=...` | Search `all`, `posts`, `communities`, `comments`, `media`, or `profiles` |
| `GET /v1/posts/:id/live?after=N` | Authenticated WebSocket replay/live stream |
| `POST /v1/telemetry/performance` | Anonymous, allowlisted Android/iOS/web performance batch |

Subreddit creation and every post/comment/vote mutation accept a client-minted
`clientMutationId`. Reusing a subreddit or post creation ID with exactly the
same command returns the original entity and `replayed: true`; reusing it with
different normalized input returns `409 mutation_id_reused`.

`PATCH /v1/me` accepts `displayName`, `bio`, and `avatarMediaId`. An avatar ID
must name a ready image uploaded by the authenticated user and is capped at
10 MiB; `null` removes it. Arbitrary external avatar URLs are rejected. Profile
payloads use a versioned Worker URL so mobile caches stay stable while the
private Cloudflare Images delivery signature rotates.

The telemetry route accepts no content or identity dimensions, caps a batch at
50 events/64 KiB, and is rate-limited independently. The Analytics Engine
column map, weighted percentile queries, SLOs, and incident runbook are in
[`../docs/PERFORMANCE_OBSERVABILITY.md`](../docs/PERFORMANCE_OBSERVABILITY.md).

## Data and consistency model

| Concern | Mechanism | Guarantee |
|---|---|---|
| Read-after-write | D1 Sessions + client bookmark | Monotonic session reads across replicas |
| Registration | D1 batch | User and first session both commit or neither does |
| Subreddit creation | Unique creator mutation id + D1 batch | Community, owner membership, and audit entry commit once; exact retries replay |
| Post/comment creation | Unique author mutation id + D1 batch | At-most-one logical entity per retry id |
| Votes | Mutation ledger + `INSERT OR IGNORE` + aggregate triggers | Exactly one effect per user mutation id under concurrent retries |
| Counts/karma | SQLite triggers in the write transaction | Aggregate and source vote/comment commit together |
| Comment cache | `(post, count, depth, root)` key + post version | Five-minute reusable shape; writes/votes invalidate by version |
| Feed paging | Signed keyset cursor + creation-time ceiling | Stable insert boundary; rank changes can reorder across pages |
| Feed context | Validated public post ID/community/title outside ranked candidates | Overview is first on every home/community first page without duplicates or cursor drift |
| Search paging | FTS5 rank/sort keyset + signed query/viewer-bound snapshot cursor | No offset drift or cross-query/private-viewer cursor reuse |
| Live detail | One Durable Object per post | Total event order per post; last 256 events replayable |
| Mobile votes | Room state + coalescing durable outbox | Instant UI, process-death survival, safe retry/reconcile |
| Mobile community/post creation | Account-scoped Room outboxes + WorkManager dependency barrier | Immediate optimistic UI offline; community publishes before dependent post; UUIDs reconcile in place |
| Community drawer | Per-user revision + signed keyset cursor + mutation ledger | Cheap 304 validation, viewer-bound paging, ACL-safe recents, and exactly-once visit command effects |

Feed ranking treats subscribed communities as a high-priority source and
discovery as a second source inside each image, video, and other-content lane.
Every lane reads a precomputed indexed rank and supplies at most `limit + 1`
candidates; a seven-slot editorial merge showcases media without sorting the
entire corpus. Each signed cursor advances the three keysets independently, so
the merge cannot skip a deferred high-ranked post. The public ReadThat overview
is a separate first-page context candidate at position zero and is excluded
from every ranked lane. Already-issued pre-showcase cursors continue through a
legacy keyset path until refresh. Because vote-driven rank is mutable, a post
can still move across a long pagination session; Room primary keys de-duplicate
it client-side. A large-scale product that requires a strict frozen feed should
materialize feed IDs/versioned rank snapshots rather than pretend a D1 bookmark
freezes ranking.

## Why REST, HTTP/3, and WebSockets are split this way

REST is used for durable commands and cacheable/keyset-paged reads. Cloudflare
terminates transport and advertises HTTP/3 on the deployed route; API semantics
do not depend on whether a client negotiated HTTP/2 or HTTP/3. Android 14+ uses
one process-wide platform `HttpEngine` for API, Coil, uploads, and Media3, with
QUIC hints for the Worker and Images origins, pooled TLS sessions, Brotli, and
default-path QUIC migration. Android 8–13 uses one restricted-modern-TLS OkHttp
HTTP/2 pool. iOS uses one long-lived URLSession for API/images and AVPlayer for
native HLS. All paths fall back to HTTP/2 when an origin does not offer HTTP/3.

WebSockets are scoped to an open post detail screen, where vote/comment changes
have immediate UI value. The home feed does not keep a socket: ranked feeds are
better refreshed/paged, and a global socket would waste radio, memory, and
battery for mostly invisible events.

## Performance envelope and scaling path

- Feed output contains render-only fields, groups are the pagination unit, and
  unknown cells degrade independently. It is JSON today because the existing
  client already models polymorphic JSON; edge compression removes most field
  repetition. Add a compact field dictionary or Protobuf only after telemetry
  shows payload decode/network dominates release velocity and tooling cost.
- First comment phase is 8 nodes, second is 200, render depth is 10. The Worker
  caps its in-memory corpus at 5,000 comments per post and emits
  `corpusTruncated`; beyond that, move comment candidate generation to
  precomputed/sharded trees rather than raising the heap cap.
- R2 receives request streams directly. Uploads above 10 MiB use resumable 8
  MiB parts; video/image limits are 100/20 MiB. Completed originals are ingested
  into Images or Stream and the staging object is evicted only after delivery is
  ready. Processing/error rows retain the byte-range fallback for resume.
- Feed responses have viewer-aware ETags and a 15-second private cache window.
  Signed Images URLs use hourly expiry buckets plus versioned stable client cache
  keys, preventing URL rotation from duplicating Coil memory/disk entries. HLS
  manifests are not persisted; only bounded media segments are cached.
- This deployment uses one D1 database and is a production-shaped sample, not a
  claim that one SQLite writer is Reddit-scale. The next boundary is directory
  routing by user/community/post shard, asynchronous fanout/rank pipelines via
  Queues/Workflows, and an analytical store for recommendations—not weakening
  transaction semantics inside one logical shard.

## Intentional limits

Search is documented separately in
[`../docs/SEARCH_ARCHITECTURE.md`](../docs/SEARCH_ARCHITECTURE.md), including
the mobile cache/Paging path, navigation behavior, ACL rules, and query-plan
verification.

This is a backend sample, not a public identity/media safety product. Before
opening registration broadly, add email verification, password reset, MFA or an
external identity provider, abuse/spam systems, account deletion, content
editing/deletion, media signature/transcode/moderation, legal retention rules,
expired-session/mutation cleanup, and operational SLO alerts. Moderator controls
are deliberately minimal and there is no admin role.

## Public engineering references

The feed and comment choices are based on Reddit's public engineering writing:

- [Evolving Reddit's Feed Architecture](https://www.reddit.com/r/RedditEng/comments/158f8o3/)
- [Rewriting Home Feed on Android & iOS](https://www.reddit.com/r/RedditEng/comments/1btowiw/)
- [Instant Comment Loading on Android & iOS](https://www.reddit.com/r/RedditEng/comments/1cwqqtp/)

Cloudflare implementation guidance:

- [D1 read replication and Sessions API](https://developers.cloudflare.com/d1/best-practices/read-replication/)
- [Durable Objects WebSocket hibernation](https://developers.cloudflare.com/durable-objects/best-practices/websockets/)
- [R2 Workers API](https://developers.cloudflare.com/r2/api/workers/workers-api-reference/)
- [Cloudflare Images hosted-image binding](https://developers.cloudflare.com/images/storage/binding/)
- [Serve private Images with signed URLs](https://developers.cloudflare.com/images/optimization/hosted-images/serve-private-images/)
- [Cloudflare Stream HLS playback](https://developers.cloudflare.com/stream/viewing-videos/using-own-player/)
