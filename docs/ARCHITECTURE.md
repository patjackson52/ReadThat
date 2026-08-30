# Client and backend architecture

This document is the enforceable architecture contract for the sample. The
Android client uses feature-oriented Gradle modules, layered MVVM, unidirectional
data flow, a memory-plus-disk cache for structured data, and durable background
writes. The feed is server-driven; post detail and comments remain typed domain
UI.

## Gradle module graph

```text
:app  (composition root, navigation, workers, backend adapters)
 |
 +-- :feature:feed --------> :core:model
 |        |                  :core:data (Room schema/DAOs)
 |        +----------------> :core:media
 |
 +-- :feature:comments ----> :core:model
 |        +----------------> :core:media
 |
 +-- :feature:profile -----> :core:model
 |
 +-- :feature:search ------> :core:data
 |
 +-- :feature:communities -> :core:model + :core:data
 |
 +-- :feature:community-detail -> :core:data + :core:observability
 |
 +-- :feature:mediafeed -----> :core:data + :core:media + :core:post
 |
 +-- :core:data
 +-- :core:network
 +-- :core:media ----------> :core:model + :core:network
 +-- :core:post -----------> :core:data + :core:model + :core:observability
 +-- :core:observability (KMP event contract used by app/features/network/media)

:flows is a standalone teaching/test module and is not on the app graph.
```

The app is the composition root. Feature modules own their vertical slice and
use `ui`, `domain`, and `data` packages; `:core:data` owns shared Room schema and
DAOs, while other core modules contain capabilities that
are reused by two or more features. This keeps feature builds independent
without creating tiny modules for every layer. When the codebase grows, split a
feature's implementation into `:feature:x:api` and `:feature:x:impl` only after
there is a real cross-feature API or build-parallelism benefit.

Shared Android settings live in the included `build-logic` build, and dependency
versions have a single owner in `gradle/libs.versions.toml`. Gradle configuration
cache and parallel execution are enabled. KMP contracts compile for Android,
iOS device/simulator, and browser JS.

Performance definitions, percentile SLOs, Analytics Engine schema/queries, and
the observability runbook are in
[`PERFORMANCE_OBSERVABILITY.md`](PERFORMANCE_OBSERVABILITY.md).
The search interaction, backend indexing, paging, ACL, and navigation contract
is in [`SEARCH_ARCHITECTURE.md`](SEARCH_ARCHITECTURE.md).
The immersive media UX, typed API/cursor, Room projection, snap behavior,
cache/player lifecycle, and telemetry contract are in
[`MEDIA_FEED.md`](MEDIA_FEED.md).

Allowed dependency direction:

```text
UI -> domain <- data -> network/platform
app -> feature -> core
```

Domain code does not depend on Compose, Room, HTTP, or platform classes. UI
never calls a network client or DAO. Network DTOs are converted at the data
boundary. Cross-feature navigation passes identifiers rather than mutable
objects.

## MVVM and unidirectional data flow

Each screen has one observable state and explicit intents:

```text
Compose event -> ViewModel intent -> repository command
                                      |
                                      v
network response -> Room transaction -> Flow/StateFlow -> ViewModel state -> Compose
```

Room is the authoritative stream for persisted structured data. A successful
network response is committed before it becomes visible. Durable optimistic
actions such as feed votes write both visible local state and an outbox in one
transaction; a worker reconciles the result. Compose collects lifecycle-aware
flows and does not maintain a second mutable copy of repository data.

The root `AppViewModel` restores the cached account first and models startup as
state rather than navigation side effects. A transient refresh failure does not
log the user out or discard cached content. Only an authoritative `401` clears
the session.

## Two-tier cache contract

Every user-visible data family has an L1 memory tier and an L2 durable tier.
All structured keys include the account identity so signing out or switching
accounts cannot expose another account's data.

| Data | L1 | L2 / source of truth | Refresh and mutation rule |
|---|---|---|---|
| Session/profile | retained `StateFlow` and repository value | encrypted token store plus Room `accounts` | restore locally, refresh in background; clear only on authoritative auth failure |
| Personalized SDUI feed | bounded Paging cache (ten pages) and retained flows | Room `feed_groups`, `item_state`, `remote_keys`, `sync_metadata` | render Room immediately; `RemoteMediator`/worker replace server blobs transactionally |
| Typed MediaFeed | bounded Paging cache, navigation seed, adjacent media windows | Room `media_post_content`, `media_feed_entries`, `media_feed_remote_keys`, shared `item_state` | anchor is page zero; render seed/Room while cursor pages refresh and append invisibly |
| Votes | current Room-backed page state | Room `item_state` plus coalescing `vote_outbox` | optimistic state and outbox are one transaction; stable mutation ID makes retry idempotent |
| Post uploads | ViewModel `StateFlow` | Room `post_outbox` plus staged file in `noBackupFilesDir` | worker uploads and creates post with a stable mutation ID; process death is safe |
| Community creation | outbox `Flow` rendered as the optimistic community | Room `subreddit_outbox` plus optimistic `subreddits` row | one transaction makes the owner view immediate; exact UUID retry replays and permanent failure rolls back only the matching optimistic row |
| Community drawer | eager account-scoped `StateFlow` | Room memberships, recents, sync validator, and ordered visit outbox | render disk immediately; silent five-minute refresh uses ETag + signed keyset pages; visit/remove/clear update UI and outbox atomically |
| Community detail | ViewModel `StateFlow` over a Room `Flow` | Room `subreddits`, shared drawer membership, and coalescing membership outbox | render disk first; metadata/rules refresh through Room; join/leave updates detail + drawer + desired-state command atomically |
| Comments/post headers | bounded account/post LRU | bounded normalized Room `comment_threads`, `comment_nodes`, `post_headers` | L1, then Room, then network; persist before emission; in-flight requests coalesce |
| Subreddits/ACL view | bounded account/subreddit LRU | Room `subreddits` | observe Room, refresh network in background, update both tiers |
| Search | 32-entry query/result LRU and retained Paging flows | Room snapshots, ordered results, remote keys, and recent queries | 250 ms typeahead debounce; render fresh disk pages immediately; refresh stale data and append with signed cursors |
| Local settings | retained `StateFlow` | Room `app_settings` | update L1 immediately; a conflated single writer preserves durable ordering |
| Images | Coil memory cache | bounded Coil disk cache | stable content cache key is independent of expiring signed URL |
| Video | one process player + owner-scoped adjacent preload queues | bounded Media3 segment cache | cache media segments, not dynamic HLS/DASH manifests; feed/MediaFeed/detail share a media-stable key |

Comment trees are normalized rather than stored as one recursive blob. Encoding
uses iterative preorder traversal and decoding is iterative bottom-up, so a
deep thread does not consume the call stack. The memory cache is bounded and
thread-safe instead of retaining every visited post for the process lifetime.

Profile editing is a feature module with callback-only boundaries into the app
composition root. Android's system photo picker grants access to one selected
image without storage permissions. The app streams a size-bounded no-backup
staging file through the shared transport, then publishes only the resulting
owned media ID. D1 stores that ID rather than an expiring CDN URL; the public,
versioned avatar endpoint rotates the private Images signature while preserving
Coil memory/disk cache identity.

## Fast startup and background refresh

`MainActivity` renders a static/cached shell immediately and reports fully drawn
at that boundary. No network request is on the first-frame path. An empty cold
database shows layout-matched skeletons; cached content remains visible during
refresh, so startup and pull-to-refresh never replace the screen with a spinner.

Android schedules one unique, network-constrained periodic `FeedRefreshWorker`
that refreshes Room first. On an unmetered, validated network with autoplay
enabled, the same worker then asks Media3 `PreCacheHelper` to persist only the
first two seconds of the first ready video. This best-effort step never changes
the refresh result, preserves coroutine cancellation, runs on one lazy
background playback looper, and never creates an ExoPlayer.

## Video preloading and feed-to-detail handoff

The feed passes its loaded video order and next likely index to Media3
`DefaultPreloadManager`. Before playback, the focused offscreen video loads three
seconds. During playback, only the immediate next/previous videos load samples;
the next distance tier selects tracks or prepares a manifest. The tracked window
is bounded, aggregate preload SampleQueue memory is capped at 12 MiB, and all
distant items return `PRELOAD_STATUS_NOT_PRELOADED`. Autoplay ownership is
separate from warming: a video must reach half of its maximum possible viewport
exposure, then the candidate with the largest visible area owns the player. A
departing sliver therefore cannot block the already-warm next item.

`VideoPlaybackCoordinator` lazily creates at most one ExoPlayer. Feed and detail
own lightweight PlayerViews, not players. Both use a TextureView and the same
stable media key; `PlayerView.switchTargetView` attaches the destination before
detaching the source, preserving decoder, playback position, buffer, and mute
state. A Compose `sharedBounds` container animates the media geometry, while an
ephemeral feed preview gives detail final-shaped title/media pixels before its
post-header read completes. Reduced-motion settings disable the shared-bounds
animation without changing playback ownership.

The one process `SimpleCache` is also lazy, but its directory/index scan runs on
an IO dispatcher behind a shared initialization future. UI leases queue briefly
until it completes, so entering a feed cannot perform cache filesystem work on
the main thread. A cache-open failure (or a zero-byte policy) degrades to the
same pooled network data source without creating another player.

This follows the current Android guidance for dynamic-list
[preload managers](https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager),
[Navigation 2 shared elements](https://developer.android.com/develop/ui/compose/animation/shared-elements/navigation),
and [Media3 surface selection](https://developer.android.com/media/media3/ui/surface).

Android schedules one unique, network-constrained periodic `FeedRefreshWorker`
with flex and exponential backoff. Vote, community, and post outboxes have
unique one-time workers. A post targeting a locally pending community waits at
that explicit ordering barrier; successful community reconciliation immediately
re-enqueues dependent posts instead of leaving them under exponential backoff.
iOS uses `BGAppRefreshTask`; its refresh closure must commit to the
platform database before completion. Both platforms also refresh opportunistically
when the app becomes active.

Paging and WorkManager create independent sync objects, so feed serialization
uses a process-wide account/feed mutex. An append re-reads its cursor after it
acquires that mutex; a cursor captured before a competing refresh can therefore
never append an older generation after the replacement transaction. Dynamic
rank overlap is de-duplicated transactionally: existing groups keep their
first-seen position, new groups use `MAX(sortIndex) + 1`, and a unique
`(accountId, feedId, sortIndex)` index enforces the ordering invariant. A
non-advancing server cursor fails the append instead of causing a request loop.
The cursor snapshot freezes post admission but not vote-driven ranks; strict
historical ranking would require a materialized per-viewer feed generation.
This design accepts that storage/cost tradeoff and reconciles rank drift at the
next silent or user refresh while preserving the order already on screen.

Measured on the attached Pixel 10 Pro after warming the cached process, ten
`am start -W` samples were 9, 11, 11, 11, 11, 11, 14, 16, 16, and 17 ms
(`p50 = 11 ms`). This measurement gates cached-open latency, not a cold
process-creation claim. A cold process sample was 575 ms.

## Network and media transport

Android creates one process-wide transport. On API 34+ it uses `HttpEngine` for
QUIC/HTTP/3; older versions use one shared OkHttp connection pool. API calls,
Coil, and Media3 adapters all reuse that engine. TLS is at least 1.2, QUIC hints
are limited to known HTTPS origins, connection migration is enabled, and the
stack falls back to HTTP/2 when an origin does not advertise HTTP/3. The app does
not race Wi-Fi and cellular or retry non-idempotent mutations without a stable
idempotency key.

iOS uses one long-lived `URLSession` with TLS 1.2 minimum, protocol caching only
for cacheable media, and native connection migration. Structured API responses
remain under the repository/Room-equivalent cache rather than a second opaque
HTTP cache. Persistent offline HLS uses `AVAssetDownloadURLSession`; `URLCache`
is not treated as a durable video store.

The on-device transport probe issues API, image, and Cloudflare Stream HLS
requests, opens Media3 through the same adapter, and asserts a stable engine
identity. On the Pixel, API and image delivery negotiated `h3`; the Stream
manifest negotiated the correct `h2` fallback for that origin.

## Backend topology and consistency

The Cloudflare Worker is stateless request orchestration:

```text
mobile client -> Worker REST API -> D1 (users/posts/comments/votes/ACL/outboxes)
                         |       -> Images binding (responsive image delivery)
                         |       -> Stream binding (direct video upload + ABR)
                         |       -> R2 (staging/fallback objects)
                         +------ -> Durable Objects (post event sequence/WebSocket,
                                                       distributed rate limits)
```

Feed payloads are flat groups of small render cells and omit detail-only fields.
Post detail/comments use typed endpoints and normalized trees. Cursor pagination
never cuts through a feed group. Feed cursors are signed, viewer-bound keysets
over `(snapshot time, personalized rank, post id)`. Comment continuations retain
tree structure, cap each more-children payload at 100 IDs, and hydrate votes in
bounded D1 batches. D1 Sessions and the `X-D1-Bookmark` header
provide read-your-writes across requests. Aggregate vote/comment counts are
updated transactionally; client mutation IDs make retries safe. WebSocket events
carry a monotonic post-room sequence, while reconnect always reconciles from the
REST/Room source of truth.

Images and Stream uploads use direct creator upload flows so large bodies do not
buffer in Worker memory. The Worker validates media ownership and subreddit ACL
again when the post is committed; a successful upload is not authorization to
publish.

## Verification

```bash
./gradlew :core:model:allTests
./gradlew :feature:comments:testDebugUnitTest \
  :feature:feed:testDebugUnitTest \
  :feature:search:testDebugUnitTest \
  :app:assembleDebug
./gradlew :app:lintDebug :feature:feed:lintDebug \
  :feature:comments:lintDebug :feature:profile:lintDebug :feature:search:lintDebug :core:data:lintDebug \
  :core:network:lintDebug :core:media:lintDebug

cd backend
npm test
npm run check
npm run deploy:dry-run
npm run startup
```

The backend smoke flow additionally covers registration/login, restricted
subreddit ACLs, text/link/image posts, a range media read, twelve nested comment
levels, idempotent voting, resharing, a 200-comment tree, and personalized SDUI
feed output.
