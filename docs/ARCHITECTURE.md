# Client and backend architecture

This document is the enforceable architecture contract for the sample. The
Android and iOS clients use feature-oriented Gradle modules, layered MVVM,
unidirectional data flow, a memory-plus-disk cache for structured data, and
durable writes. Room 3, shared repositories/ViewModels, and the Compose
Multiplatform surface form the iOS implementation; the established Android
feature UI remains the production entrypoint during incremental migration. The
feed is server-driven; post detail and comments remain typed domain UI.

## Gradle module graph

```text
:app  (composition root, navigation, native worker scheduling, legacy references)
 |
 +-- :feature:feed --------> :feature:feed-ui (KMP presentation)
 |        |                  :core:data (Room schema/DAOs)
 |        +----------------> :core:image-ui + :core:media-ui
 |
 +-- :feature:comments ----> :feature:detail-ui (KMP presentation)
 |        +----------------> :core:image-ui + :core:media-ui
 |
 +-- :feature:profile -----> :feature:profile-ui + :core:image-ui
 |
 +-- :feature:search ------> :feature:search-ui + :core:data
 |
 +-- :feature:communities -> :core:model + :core:data
 |
 +-- :feature:community-detail -> :feature:community-ui + :core:data
 |
 +-- :feature:mediafeed -----> :feature:mediafeed-ui + :core:data
 |                              + :core:image-ui + :core:media-ui
 |
 +-- :feature:ad-ui ---------> shared promoted detail + native secure landing actuals
 |
 +-- :core:data
 +-- :core:network
 +-- :core:navigation -----> shared destinations + bounded/versioned restoration
 +-- :core:media ----------> :core:model + :core:network
 +-- :core:media-ui -------> platform Media3/AVPlayer engines
 +-- :core:image-ui -------> platform Coil/shared-byte decoders
 +-- :core:media-acquisition -> shared selection/size/MIME policy + Android staging
 +-- :core:media-acquisition-ui -> shared picker/camera lifecycle + platform launchers
 +-- :core:sharing --------> typed text/subject/MIME payload policy
 +-- :core:sharing-ui -----> shared presentation contract + Android/iOS native actuals
 +-- :core:post -----------> :core:data + :core:model + :core:observability
 +-- :core:observability (KMP event contract used by app/features/network/media)

:iosApp -> :composeApp (thin framework host)
              +-- :feature:app-ui (KMP application coordinator)
                    +-- :feature:*-ui (KMP screens)
                    +-- :feature:shell-ui (KMP IA/scaffold)
                    +-- :core:image-ui + :core:media-ui
                    +-- :core:media-acquisition + :core:media-acquisition-ui
                    +-- :core:sharing + :core:sharing-ui
                    +-- :core:navigation
                    +-- :core:client (shared MVVM/repositories)
                          +-- :core:data (Room 3 KMP)
                          +-- :core:network
                          +-- :core:observability

:flows is a standalone teaching/test module and is not on the app graph.
```

Android `:app` remains the packaging, WorkManager, and process-lifecycle host,
but its default product surface is the same `:feature:app-ui` coordinator used
by iOS. `:composeApp` contains only target entrypoints, graph lifetime, and the
Android saved-state adapter. The mature Android root and feature implementations
remain compiled as a reference and explicit rollback path selected with
`-PREADTHAT_USE_SHARED_APP=false`; they are no longer the default renderer.
Feature modules own their
vertical slice and use `ui`, `domain`, and `data` packages; `:core:data` owns shared Room schema and
DAOs, while other core modules contain capabilities that
are reused by two or more features. This keeps feature builds independent
without creating tiny modules for every layer. When the codebase grows, split a
feature's implementation into `:feature:x:api` and `:feature:x:impl` only after
there is a real cross-feature API or build-parallelism benefit.

Shared Android settings live in the included `build-logic` build, and dependency
versions have a single owner in `gradle/libs.versions.toml`. Gradle configuration
cache and parallel execution are enabled. KMP contracts compile for Android,
iOS device/simulator, and browser JS.

Feed and detail rendering no longer stop at a callback-shaped common layout.
`:feature:feed-ui` owns the complete organic/promoted cell adapter, including
stable media mapping, poster/first-frame handoff, native player configuration,
image cache requests, and canonical action iconography. Hosts inject navigation/share actions
only. `:feature:detail-ui` likewise owns the post media gallery,
adjacent-video preload window, duration/page chrome, and image/video handoff.
Its toolbar owns working comment search navigation, root-thread sorting, and the post overflow
menu. Sorting preserves each reply subtree and maps the visible window back to the authoritative
Room-backed row order before requesting cursor prefetch, so presentation choices cannot corrupt
comment paging or cache identity. Search navigation and sort selection also use the existing
bounded interaction-to-next-frame metric, without recording search text.
The media actual declares the one real layering difference: Android leaves
`PlayerView` above its poster so controls retain hit testing, while iOS keeps the
decoded poster above opaque UIKit interop until AVPlayer reports its first frame.
Comment controls and promoted-detail mute/replay chrome use the same KMP-owned Material vectors on
Android and iOS, so production UI never falls back to platform-dependent Unicode glyphs.
The same feature-owned adapter boundary now covers home-feed account chrome,
detail/comment identity images, search thumbnails, profile avatars/editing, and
community-detail avatars. Hosts inject only a shared-client byte loader on iOS.
`:core:media-acquisition-ui` owns the common picker/camera request lifecycle and
its Android Activity Result and iOS PhotosUI-bridge actuals, so mature Android
and `:feature:app-ui` no longer register separate launchers. `:core:media-acquisition`
owns the selection bounds, byte limits,
MIME rules, optional pixel-dimension bounds, validation, and Android app-private
staging used by post creation and profile editing. Its dedicated avatar policy
allows one decodable image up to 10 MiB and 20,000 pixels per side on both
platforms. Swift reads the same exported policy before PhotosUI/AVFoundation
work and Kotlin validates its result again before shared draft/editor state. Android camera
capture uses a cache-scoped `FileProvider` URI with the full-resolution
`TakePicture` contract, persists only an opaque token across host recreation,
and moves a validated success into no-backup outbox storage. Android cancellation
after staging, camera launch failure, invalid results, iOS overflow, request
replacement, and composition disposal all remove still-owned temporary files.
The KMP selection accumulator enforces all-or-nothing delivery and is tested
independently of either native picker. Likewise,
`:core:sharing` owns typed text, subject, MIME, canonical post-link, and safe promoted-link
payloads. `:core:sharing-ui` owns the Compose capability used by both application roots;
its Android actual presents the system chooser and its iOS actual signals the Swift host's
`UIActivityViewController`. The mature Android feed now consumes the shared group title and
canonical payload rather than constructing raw post IDs or unvalidated ad links.

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

The retained mature Android root delegates authentication and settings to the same
KMP controllers used by `ReadThatViewModel`; it retains only host bridges for
legacy header snapshots and Android background-work resumption. Both hosts
restore the cached account first and model startup as state rather than
navigation side effects. Product destinations, bounded history, validation, and
the versioned opaque restoration codec live in `:core:navigation`. The shared
Android host stores that payload in `SavedStateHandle`; iOS stores the identical
payload per scene with `@SceneStorage`. The mature reference host retains typed
Navigation Compose routes, but a tested lossless adapter maps every route and
navigation action to the same shared contract. `AppNavigationPolicy` owns the
canonical Home/Create/Activity/Profile order, persistent-root behavior, bottom
navigation and community-drawer visibility, detail/immersive chrome, community
name normalization, and deep-link-to-destination conversion. Platform hosts
retain only their back-stack and state-registry adapters. `:feature:app-ui`
wraps each destination in a stable `SaveableStateProvider`, pins the three
persistent IA roots, and keeps an LRU of twelve transient routes. Feed,
community, detail/comment, search, and pager position therefore survive Back on
both platforms without allowing disposed UI state to grow without bound; Room
and the shared controllers remain authoritative after a local-state eviction.
The Android actual also enters the platform/predictive Back dispatcher for every
non-Home destination, routing it through the same bounded KMP history as toolbar
Back. The iOS actual collects a bounded bridge from one native left-edge pan
recognizer; shared policy gates the recognizer at roots and its completed
request enters the same handler as toolbar Back. Home keeps that recognizer
disabled so the community drawer retains the edge.
Corrupt, oversized, and future restoration payloads fail closed to Home instead
of becoming a second route parser. Remaining host orchestration is not permission to duplicate
feature state: feature screens consume shared controllers and Room-backed
repositories. A transient refresh failure does not log the user out or discard
cached content. Only an authoritative `401` clears the session.

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
| Post uploads | ViewModel `StateFlow` | Room `post_outbox` plus app-private staged file | shared processor uploads and creates with a stable mutation ID; native schedulers resume it after process death |
| Community creation | outbox `Flow` rendered as the optimistic community | Room `subreddit_outbox` plus optimistic `subreddits` row | one transaction makes the owner view immediate; exact UUID retry replays and permanent failure rolls back only the matching optimistic row |
| Community drawer | eager account-scoped `StateFlow` | Room memberships, recents, sync validator, and ordered visit outbox | render disk immediately; silent five-minute refresh uses ETag + signed keyset pages; visit/remove/clear update UI and outbox atomically |
| Community detail | ViewModel `StateFlow` over a Room `Flow` | Room `subreddits`, shared drawer membership, and coalescing membership outbox | render disk first; metadata/rules refresh through Room; join/leave updates detail + drawer + desired-state command atomically |
| Comments/post headers | bounded account/post LRU | bounded normalized Room `comment_threads`, `comment_nodes`, `post_headers` | L1, then Room, then network; persist before emission; in-flight requests coalesce |
| Subreddits/ACL view | bounded account/subreddit LRU | Room `subreddits` | observe Room, refresh network in background, update both tiers |
| Search | 32-entry query/result LRU and retained Paging flows | Room snapshots, ordered results, remote keys, and recent queries | debounced typeahead (180 ms in the shared surface); render fresh disk pages immediately; refresh stale data and append with signed cursors |
| Local settings | shared controller `StateFlow` | Room `app_settings` | both hosts observe one row; update L1 immediately and use a conflated writer; Android's retired preferences importer is insert-only |
| Images | bounded decoded cache (`Coil` on Android, KMP LRU on iOS) | stable-key Coil/shared-client disk cache | `:core:image-ui` owns HTTPS filtering, decoded identity, cancellation, and bounded preload windows; the stable content key is independent of an expiring signed URL |
| Video | one process player + owner-scoped adjacent preload queues | bounded Media3 cache / native AVFoundation facilities | `:core:media-ui` shares ownership and preload policy while retaining the optimized native engines; feed/MediaFeed/detail share a media-stable key |

The shared Compose surface preserves this ownership contract on iOS as well.
Image bytes travel through the same process-scoped shared client used by API,
upload, poster, and telemetry traffic; decode work is off-main and only the
bounded decoded L1 is released under memory pressure. On Android the same
contract delegates to the process Coil singleton, whose fetcher uses
`UnifiedTransport`, so the migration does not create a second connection pool.
The canonical feed-cell and post-detail gallery adapters always route image
requests through this contract and video requests through the single native
player owner; moving those pixels into KMP does not introduce another decoder,
HTTP client, cache, or player. The immersive MediaFeed and promoted-detail
surfaces use the same feature-owned adapters for stable media identity,
first-frame reuse, playback-state projection, and bounded preload requests;
application hosts supply only platform actions and the iOS byte loader. Secure
MediaFeed play/pause, mute, replay, and overflow state is owned by
`:feature:mediafeed-ui` as well. Autoplay policy still suppresses unsolicited
work, while an explicit Play request bypasses only that gate and continues to
use the resolved bitrate/buffer policy, HTTPS source, singleton native player,
and already-warmed source/asset. Every control emits the bounded
interaction-to-next-frame metric without media ids or URLs.
The iOS stable-byte file cache uses persisted filesystem modification dates for
LRU order and metadata-only size accounting during trim. Relaunching the app
therefore cannot flatten cache recency, and enforcing the 512 MiB bound never
deserializes the entire cache just to decide which entries to evict.
Organic feed metadata overflow and community-header overflow are also owned by
the shared feed feature. They dispatch only existing navigation, reshare,
platform-share, refresh, creation, and membership intents, so Android and iOS
cannot drift into inert chrome or bypass the Room/outbox state machines.
Promoted landing content is feature-owned as well: `:feature:ad-ui` shares URL
policy, retry/error UI, and monotonic telemetry while retaining hardened Android
WebView and iOS WKWebView actuals.
Feed viewport warming is also feature-owned: both hosts execute the same bounded
still/poster/HLS plan, and `:core:media-ui` resolves platform connectivity, Data
Saver/Low Data Mode, device tier, and storage facts into one `VideoPlaybackPolicy`.
That single result gates both autoplay and speculative poster/HLS work; still
images remain eligible so offline-first feed rendering is not weakened.

For video, one `AVPlayer`, surface-priority leases, and owner-scoped adjacent
`AVURLAsset` windows prevent navigation disposal races. Native player URLs pass the same
HTTPS-only policy boundary as shared requests, HLS gets one HTTPS fallback
attempt, and posters/previews remain in the shared 32 MiB memory + 512 MiB disk
cache. AVFoundation owns adaptive segment connections because AVPlayer cannot
consume the application `URLSession`; API, image, poster, upload, and telemetry
traffic still share that single long-lived session.

The shared document fallback is not an unbounded key/value store: writes prune
each account to 512 documents and startup removes entries older than 30 days.
Search recents are additionally capped at ten. Feed and community dwell start
a small eight-comment prefetch after 600 ms; a post-detail navigation joins the
same in-flight request and consumes the committed Room result instead of racing
a duplicate call. The full comment tree then refreshes progressively while the
cached header and first phase remain visible.

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
Coil memory/disk cache identity. The previous bitmap/staging editor remains a
compiled `LegacyAndroidProfileEditor` reference, but it is no longer constructed
by the Android root and therefore cannot create alternate profile state.

Post resharing from feed, detail, thread, and MediaFeed also stays inside the
shared repository/client graph. Android's mature shell supplies only the native
share intent and snackbar; it does not create a second backend connection pool
for those actions.

## Fast startup and background refresh

`MainActivity` renders a static/cached shell immediately and reports fully drawn
at that boundary. No network request is on the first-frame path. An empty cold
database shows layout-matched skeletons; cached content remains visible during
refresh, so startup and pull-to-refresh never replace the screen with a spinner.
The Compose Multiplatform root follows the same rule on Android and iOS: session
restoration renders a static shell synchronously, then swaps to Room-backed state
without putting Keychain or HTTP on the first-frame path.

Android schedules one unique, network-constrained periodic `FeedRefreshWorker`
whose platform adapter calls `SharedBackgroundMaintenance` for the authenticated
repository and Room transaction. The refreshed wire page is projected through
one KMP `BackgroundFeedMediaPlan`: feed order, stable cache identities,
first-frame poster normalization, deduplication, and bounds are identical on
Android and iOS. Android warms the selected stills/posters through Coil and, on
an unmetered validated network with autoplay enabled, asks Media3
`PreCacheHelper` to persist only the first two seconds of the first ready video.
These best-effort native steps never change the refresh result, preserve
coroutine cancellation, run on one lazy background playback looper, and never
create an ExoPlayer.

iOS registers one network-constrained `BGProcessingTask`. Mutation commits
coalesce an urgent request; every background transition schedules an hourly
maintenance request, and each OS-granted execution schedules its successor.
The task restores the account, drains every Room outbox, refreshes the home feed
through the same `SharedBackgroundMaintenance`, and flushes both telemetry
queues. Its bounded photo/gallery/ad/video-poster window is written through the
process-scoped shared client's compressed L1/L2 cache with expensive and Low
Data Mode access disabled. Foreground AVPlayer remains the owner of adaptive
HLS preloading; an optional full offline HLS package remains native
`AVAssetDownloadURLSession` work rather than pretending a manifest byte cache is
a playable download.

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

`:feature:app-ui` installs the shared Compose lifecycle bridge around the same
ownership model. Background transitions pause playback and remove speculative preload
work; memory pressure releases decoded image entries and inactive native media
assets. Cancellation from a changed feed viewport propagates through preview
fetches, so an obsolete warm-up cannot consume the next batch's bandwidth.

This follows the current Android guidance for dynamic-list
[preload managers](https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager),
[Navigation 2 shared elements](https://developer.android.com/develop/ui/compose/animation/shared-elements/navigation),
and [Media3 surface selection](https://developer.android.com/media/media3/ui/surface).

Android schedules one unique, network-constrained periodic `FeedRefreshWorker`
with flex and exponential backoff. Vote, community, and post outboxes have
unique one-time workers. A post targeting a locally pending community waits at
that explicit ordering barrier; successful community reconciliation immediately
re-enqueues dependent posts instead of leaving them under exponential backoff.
Those Android classes now own only WorkManager constraints, backoff, and native
follow-up work. Feed/vote/visit/membership execution and
`SharedCreationOutboxProcessor` use the process-wide KMP client, Room DAOs,
retry classification, stable idempotency keys, and telemetry. Post publication
has one process-wide shared lane, so a UI retry cannot race a background upload.
The processor drains both the current shared media descriptor and the mature
Android descriptor already persisted by older builds; an upgrade cannot strand
an image or video command.
iOS retains the same Room outboxes and resumes them on launch/foreground sync
and through its registered `BGProcessingTask`; telemetry flushes opportunistically
and remains durable if the OS suspends the app. `BGTaskScheduler` and WorkManager
are now scheduling/constraint adapters over the same KMP maintenance executor,
not alternate data paths.

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

iOS uses one long-lived HTTP/3-capable `URLSession` for API, authentication,
image, preview, upload, and telemetry traffic, with native connection migration
and HTTP/2 fallback. A 32 MiB memory cache and 512 MiB stable-key app-private
file cache sit above it and are the only shared media-byte cache tiers. Native
`URLCache` is disabled so expiring signed delivery URLs cannot duplicate those
payloads under unstable disk identities. On upgrade, the transport removes only
the obsolete `dev.readthat.http-cache` directory; Room and the stable-key media
cache are not touched.
Structured API resources are also committed to Room rather than relying on an
opaque HTTP cache. Adaptive playback stays native in AVPlayer, and persistent
offline HLS uses `AVAssetDownloadURLSession`; native URL loading is not treated
as a durable HLS package store. The exact iOS boundary is documented in
[`IOS_KMP.md`](IOS_KMP.md).

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
that independently advance image, video, and other-content ranks under one
snapshot and editorial offset. Comment continuations retain
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
