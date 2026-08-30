# ReadThat

ReadThat is a playful, independent Reddit-inspired Android client and React PWA
backed by a Cloudflare Worker. The feed is server-driven, while post detail and
recursive comments use typed domain models. Configure the Android client with
the `READTHAT_API_BASE_URL` Gradle property; backend setup lives in
[`backend/README.md`](backend/README.md).

The product surface now includes onboarding, registration, login/logout,
encrypted session refresh, profile viewing/editing, settings, offline-first
community creation, the personalized feed, text/image-gallery/video/link post creation,
full community details with offline membership, full post detail, root and deeply
nested comments, three-state voting, resharing, and Android sharing. It is an
independent implementation informed by Reddit's public engineering writing and
visual inspection of the public Reddit Android UI.

The Android client also includes `MediaFeed`: tapping normal-feed media opens
the exact item in a spinner-free, vertically snapping image/video pager with
autoplay, pinch zoom, media-only chrome, profile navigation, and the reused post
detail/comments composition in a bottom sheet. Its complete contract is in
[`docs/MEDIA_FEED.md`](docs/MEDIA_FEED.md).

Home feed SDUI also supports Reddit-shaped editorial promotions: single-video or
mixed-media carousels, completed-video replay/CTA overlays, related-post cards,
privacy-bounded funnel/watch analytics, and a same-player hybrid video + website
detail. The contract, content replacement points, telemetry matrix, and physical
Pixel reference captures are in [`docs/ADS.md`](docs/ADS.md).

The complete module graph, cache contract, UDF sequence, transport policy, and
consistency model are in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). Metric
boundaries, percentile SLOs, sampled Cloudflare queries, privacy rules, and the
incident runbook are in
[`docs/PERFORMANCE_OBSERVABILITY.md`](docs/PERFORMANCE_OBSERVABILITY.md).

Built as preparation for the Reddit *Mobile Design* interview, whose prep sheet names the concepts to focus on: *"Architectural patterns, futureproofing, API design, tradeoffs, **model layers, flattening**."* This repo is those six things, compiled and tested.

> **This is not Reddit's code.** Nothing here is decompiled or copied from the Reddit app. It is an independent implementation of the architecture Reddit has published on r/RedditEng, written from those public posts.

---

## Verified state

| | |
|---|---|
| Build | ✅ `assembleDebug` |
| Tests | ✅ Android feature suites, Android+iOS+JS KMP suites, Android lint, Room creation/drawer outbox tests, an on-device API/image/video transport probe, and 24 Workers-runtime integration scenarios |
| Toolchain | AGP 9.3.2 · Kotlin 2.3.21 · Gradle 9.5 · JDK 17 |
| SDK | **compileSdk/targetSdk 37**, minSdk 26 |
| UI | Jetpack Compose (BOM 2026.08.00), Material 3 |
| Web | React 19 + Vite PWA; cursor paging, IndexedDB cache/outbox, native view transitions, adaptive HLS, bounded segment prefetch |
| Paging | **Paging 3.5.1 + `RemoteMediator` over Room 2.8.4** — the DB is the paging source of truth |
| KMP | `:core:model` compiles/tests for Android, iOS device/simulator, and browser JS |
| On-device | ✅ registration, live SDUI feed, post detail/comments, create/profile/settings verified on a physical Pixel 10 Pro |
| Source | Android/Kotlin client + React/TypeScript web client + TypeScript Cloudflare Worker |
| Backend | ✅ One Worker origin for the PWA/API + D1 + R2 staging + Durable Objects + Images + Stream; public configuration uses safe placeholder resource IDs |
| Network | One pooled API/Coil/Media3 engine; API and Images verified over HTTP/3 on Pixel 10 Pro, Stream over HTTP/2 fallback |

```bash
./gradlew :feature:feed:testDebugUnitTest      # SDUI + Room/Paging/outbox
./gradlew :feature:comments:testDebugUnitTest  # normalized deep comments
./gradlew :feature:search:testDebugUnitTest    # cached search + Paging mediator
./gradlew :feature:communities:testDebugUnitTest # drawer L1/L2 + offline outboxes
./gradlew :feature:community-detail:testDebugUnitTest # cached detail + membership outbox
./gradlew :feature:mediafeed:testDebugUnitTest # media Room/Paging/cursor concurrency
./gradlew :core:post:testDebugUnitTest         # shared optimistic vote/outbox races
./gradlew :core:model:allTests                 # Android, iOS, and JS
./gradlew :flows:testDebugUnitTest             # standalone Flow patterns
./gradlew :app:lintDebug :feature:feed:lintDebug :feature:comments:lintDebug \
  :core:data:lintDebug :core:network:lintDebug :core:media:lintDebug
./gradlew :app:assembleDebug                    # APK
./gradlew installDebug               # to a device/emulator

cd backend
npm test && npm run check             # Worker integration tests + TypeScript

cd ../www
npm test && npm run check && npm run build # PWA logic, TypeScript, production bundle
```

## Web PWA

The production web client lives in [`www`](www/README.md). It shares the Worker
origin with `/v1/*` and `/health`, so authentication, D1 bookmarks, the service
worker, and browser security policy do not depend on cross-origin exceptions.
It implements registration/login, profiles, communities, text/link/image/video
posting, nested comments, voting, search, an infinite cursor feed, responsive
navigation, and accessible view transitions. Cached feeds and details render
from IndexedDB first; supported mutations use an account-scoped outbox and
replay on reconnect. The service worker precaches only the small app shell,
caches images, and applies a quota-aware LRU only to immutable video segments.

## Modules

| Module | What it is | Tests |
|---|---|---|
| **`:app`** | Thin composition root: navigation, auth/create/profile UI, backend adapters, WorkManager workers |
| **`:feature:feed`** | SDUI feed UI/domain/data, ordered photo and promoted-media carousels, flattening, Paging 3, and synchronization policy |
| **`:feature:comments`** | Typed post detail, normalized deeply nested comments, bounded L1 and Room L2 |
| **`:feature:search`** | Reddit-style discovery, typeahead, section previews, tabbed Paging 3 results, and L1/Room L2 caches |
| **`:feature:communities`** | Supported Reddit-style drawer, recent/community L1+Room L2 state, use cases, UDF, and offline creation/visit commands |
| **`:feature:community-detail`** | Typed community chrome, avatar/rules/membership state, Room-first UDF, and a coalescing join/leave outbox above the reused SDUI ranked feed |
| **`:feature:mediafeed`** | Typed image/gallery/video Pager, exact-anchor Room/Paging, horizontal gallery + vertical post snapping, zoom/chrome UI, and bounded prefetch |
| **`:core:model`** | KMP auth/post/profile/settings/video-policy contracts and pure reducers |
| **`:core:data`** | Shared Room schema/DAOs for account-scoped feed/profiles/subreddits, settings, and durable outboxes |
| **`:core:network`** | One Android API/Coil/Media3 transport with HTTP/3 and pooled HTTP/2 fallback |
| **`:core:media`** | Media3 playback, ABR/data-saver policy, stable segment cache identity |
| **`:core:post`** | Shared account-scoped optimistic post state and coalescing durable vote outbox |
| **`:core:observability`** | KMP Android/iOS/browser performance event contract and monotonic timers |
| **`:flows`** | [Standalone Kotlin Flow patterns](flows/README.md); intentionally outside the app graph |

Features are vertical slices with `ui`, `domain`, and `data` packages. Core
modules hold capabilities reused by multiple features, and `:app` wires the
implementations together. `:feature:comments` remains navigation-agnostic:
callbacks and identifiers are its boundary, so feed dwell-prefetch and detail
share one retained repository without a feature-to-feature dependency.
Search follows the same boundary: result selection emits a post, focused
comment, community, or username identifier and the app owns navigation. See
[`docs/SEARCH_ARCHITECTURE.md`](docs/SEARCH_ARCHITECTURE.md).

`BackendClient` keeps access and refresh tokens encrypted under a non-exportable
Android Keystore AES-GCM key and propagates D1 session bookmarks for read-your-
writes consistency. Feed votes use an optimistic, coalescing Room outbox.
Selected media is first copied off-main to process-safe staged files and an ordered Room
post-outbox payload. WorkManager can then resume each upload and post creation after
process death using stable idempotency keys. The Worker and client enforce the
same 20 MB-per-image / 100 MB video limits and a 20-photo gallery maximum.

Community creation uses the same command model: Room atomically writes a
client-generated UUID command and an optimistic owner-visible subreddit. Posts
can be queued against that local community while offline; the post worker treats
community reconciliation as an ordering barrier and publishes the dependent
post immediately after the server assigns the community ID. Exact retries replay
the original entity, while UUID reuse with changed input is rejected with `409`.

The home menu is intentionally narrower than Reddit's full drawer: it includes
only **Start a community**, **Recently Visited**, and **Your Communities**, all
of which this sample supports end to end. Opening a community writes its local
recent row and ordered visit outbox command in one Room transaction. Visit,
remove, and clear commands coalesce locally, replay by UUID through WorkManager,
and use timestamp-bounded server semantics so a late removal cannot erase a
newer visit. The drawer renders its account-scoped Room snapshot immediately,
then uses a private ETag and signed keyset cursor for a silent background sync.
The details destination renders its Room snapshot immediately, refreshes metadata
and rules in the background, and inserts that typed chrome above the existing
subreddit-scoped SDUI `FeedScreen`. Join/leave atomically updates both the page
and drawer before a process-safe WorkManager outbox reconciles the server.

⚠️ **`:feature:comments` is deliberately not SDUI.** Reddit's feed is server-driven; its post-detail screen is a recursive domain model. Knowing *where SDUI stops* — and why — is the more interesting answer. See [`comments/README.md`](comments/README.md).

---

## The idea in one diagram

The server sends a two-level structure. A `LazyColumn` wants one dimension. **Flattening** is the bridge.

```
SERVER (wire model)                     CLIENT (render list)

[ Group("post_1", [                     [ Metadata  key="post_1/meta"
      Metadata,                           Title     key="post_1/title"
      Title,                              Media     key="post_1/media"
      Image,          ──── flatten ───>   ActionBar key="post_1/actions"
      ActionBar ]),                       Divider   key="post_1/divider"
  Group("post_2", [                       Metadata  key="post_2/meta"
      Metadata,                           Title     key="post_2/title"
      Title,                              ...
      Video,
      ActionBar ]) ]
```

The client never decides *what* to draw or *in what order* — the server does. The client owns *how*.

---

## Layers

```
:feature:feed/ui      Compose + FeedViewModel intents
             /domain wire/UI models, converters, flattener (pure Kotlin)
             /data   repository, sync engine, Room, RemoteMediator

:feature:comments/ui      typed detail Compose + CommentsViewModel intents
                 /domain iterative tree operations (pure Kotlin)
                 /data   repository, bounded L1, normalized Room L2

:core:network        process-wide HTTP engine and Coil/Media3 adapters
:core:media          player, ABR policy, bounded segment cache
:core:model          KMP contracts and reducers

:app/data/backend    HTTP adapters, encrypted session, D1 bookmarks
:app/data/sync       periodic refresh, vote drain, durable post upload
:app/ui              root UDF/navigation plus auth/create/profile/settings
```

The domain layer is still **pure functions over plain Kotlin data** — converters and the flattener need no emulator, no Robolectric and no mocking framework. The data layer is not, and is not pretended to be: the Room/Paging tests run under Robolectric against a real in-memory SQLite database, because `INSERT OR IGNORE`, transaction boundaries and `ORDER BY` are exactly the things a hand-written fake DAO lets you get wrong silently.

---

## The five things this demonstrates in an interview

### 1. Flattening, with keys that actually work
`FeedFlattener` collapses `List<WireGroup>` → `List<CellUi>`, assigning each item a **composite key** `groupId/cellId`.

Why composite: the same `cellId` legitimately recurs across groups (every post has a cell called `meta`). `cellId` alone is not unique, and **duplicate keys are a crash in Compose, not a cosmetic bug**. There's a test for exactly this (`identical cell ids in different groups do not collide`).

### 2. Forward compatibility — the futureproofing story
`WireCell.Unknown` is a first-class member of the sealed hierarchy. When a newer server sends a cell this build has never heard of:

- the converter registry returns `null`
- the flattener **drops it and counts it by type name**
- the rest of the feed renders normally
- a group that rendered *nothing* emits no stray divider

The dropped-type counts surface in `RenderList.droppedCellTypes` — in production that's your alerting metric, and the signal that tells you when it's safe to sunset an old build. The fake API injects a `PollCell_v2` on page 2 so you can see it happen in the running app.

**This is the single most important behaviour in the repo.** SDUI's whole value proposition is "ship a new post type without a client release," and that only holds if old clients degrade instead of crashing.

### 3. "The first converter that can handle it"
Reddit's published iOS pipeline is *Services → Converters → Diffing Engine*, where converters "work in parallel, each feed element is transformed into an appropriate view model by the first converter that can handle it."

`CellConverterRegistry` implements that rule literally: walk an ordered list, take the first non-null. Registration order is part of the contract, and there's a test asserting it.

### 4. Paging 3 + `RemoteMediator`, with the database as the source of truth

This started as a hand-rolled cursor loop — a `nextCursor` in the state object, a `PREFETCH_THRESHOLD`, a `canLoadMore` guard, an `isAppending` flag and a reducer that de-duplicated by `groupId` on append. It worked. It is also the wrong answer at IC5, and every piece of it is now gone:

| Hand-rolled | Paging 3 |
|---|---|
| `PREFETCH_THRESHOLD = 4` + `derivedStateOf` over scroll geometry | `PagingConfig.prefetchDistance` |
| `canLoadMore` guard against overlapping requests | Paging serialises loads per `LoadType` |
| dedupe by `groupId` in the reducer | `groupId` is the **primary key** and `(accountId, feedId, sortIndex)` is unique |
| cursor in a `StateFlow`, lost on process death | cursor is a **row** in `remote_keys` |
| list in memory, refetched from page 1 on rotation | `cachedIn(viewModelScope)` |
| offline = empty screen | offline = the DB keeps serving |

```
FeedRemoteSource ──► FeedRemoteMediator ──► Room ──► PagingSource ──► PagingData<CellUi> ──► LazyColumn
                (runs only when the        ▲                       (map → flatMap)
                 DB runs out of pages)     │
                              toggleLike ──┘  writes item_state + vote_outbox; the page re-emits
```

**The inversion is the point.** A `RemoteMediator` does not serve pages to the UI — Room's `PagingSource` does. The mediator only runs when the DB runs dry. The UI has no *direct* code path to either the fixture or HTTP source, so offline is a property of the architecture rather than a feature someone remembered to add.

Three decisions worth defending out loud:

- **Page groups, flatten afterwards.** `PagingData<GroupWithState>` → `.map(::decode)` → `.flatMap { it.toCells() }`. A `Group` is the unit the cursor addresses, so a page boundary must never land *inside* a post — otherwise a post renders its header on one page and its action bar on the next. Flattening after paging makes that impossible by construction.
- **Keyset order is stable at both ends.** The Worker signs an audience-bound
  `(snapshotAt, personalizedRank, postId)` cursor; `postId` is the deterministic
  tie-breaker and new posts cannot enter an existing walk. A live score change
  can still make ranked pages overlap, so append updates an existing post at its
  first-seen position and allocates positions only to unseen groups. Refresh is
  the explicit point at which the cached head is re-ranked.
- **Memory is bounded without sacrificing reverse scroll.** `maxSize = 120`
  retains ten 12-group pages in `PagingData`; dropped pages remain in Room and
  rehydrate locally when the user scrolls back.
- **Fresh Room rows skip a blocking initial refresh.** Stale rows remain visible
  while refresh runs through `RemoteMediator`, app-active refresh, or the
  periodic worker. No network request is part of the first-frame contract.
- **A failed refresh with rows on disk is not an error screen.** `LoadState.Error` only takes the full screen when `itemCount == 0`. Otherwise the stale feed stays up and the error is a retry row.

The consistency contract is deliberate: `snapshotAt` freezes admission of new
posts, not mutable vote scores. A post that jumps upward across the cursor can
therefore be absent for that walk and returns on refresh. Eliminating that last
form of drift requires materializing a per-viewer ranked snapshot (large D1
write/storage amplification); this client instead guarantees no duplicates or
visible reordering in the loaded generation and refreshes the ranked head.

### 4b. ⭐ The storage split — how SDUI and optimistic writes coexist

The hardest genuine problem with SDUI: you are caching **UI descriptions**, not domain objects, so a like has nowhere natural to live. Patching a like count inside an opaque server blob means parse → patch → re-serialise on the interaction path, and it loses the edit on the next refresh.

The fix is to stop pretending the payload is one thing:

| Table | Owner | Mutability |
|---|---|---|
| `feed_groups` | the **server** — opaque `payloadJson` + `payloadVersion` | replaced wholesale on refresh |
| `item_state` | the **user** — score plus upvote/downvote state | never touched by a page fetch |
| `remote_keys` | Paging — the cursor | one row per feed id |

They are re-joined in SQL, in the `PagingSource` query itself:

```sql
SELECT g.groupId, g.sortIndex, g.payloadJson, g.payloadVersion,
       s.likeCount, s.liked, s.downvoted
FROM feed_groups AS g
LEFT JOIN item_state AS s ON s.itemId = g.groupId
ORDER BY g.sortIndex ASC, g.groupId ASC
```

`LEFT` and not `INNER`: a freshly fetched group has no state row yet, and an inner join would make new items invisible until somebody voted.

Two invariants fall straight out of this, both tested:

- **A page fetch never clobbers a local edit.** Seeding uses `INSERT OR IGNORE`, so an in-flight page that lands after the user taps loses to the user.
- **Pull-to-refresh throws the server blob away wholesale and the vote survives** — because the vote was never in the blob.

`payloadVersion` is the escape hatch for the other direction: a blob written by an older build decodes to `null` and that row renders as nothing, rather than throwing and failing the whole page.

### 5. Threading and recomposition discipline
- Flattening runs on `Dispatchers.Default`, not the main thread — it's O(cells) on every page load.
- `LazyColumn` gets both `key` (identity, scroll restoration) and `contentType` (composition reuse across same-shaped items).
- Paging's `map`/`flatMap` are lazy — they transform only the pages currently held, not the whole feed.
- Decoding runs off the main thread on the Paging fetch dispatcher; the payload JSON is parsed once per row and only for loaded pages.

---

## Tradeoffs — say these out loud

SDUI is not free. A Staff-level answer volunteers the costs:

| Gain | Cost |
|---|---|
| Ship new post types with no client release | Lose client-side type safety; the client can't reason about what it's showing |
| iOS/Android identical by construction | Offline caching is harder — you're caching UI descriptions, not domain objects. **This repo answers that one**: see [the storage split](#4b--the-storage-split--how-sdui-and-optimistic-writes-coexist) |
| Server-side experimentation and ranking | Payload can get chatty or over-generic if the schema isn't disciplined |
| Client code shrinks to a renderer | Accessibility must come from the server (note `altText` on the media cell — the client no longer knows what the image *is*) |
| One rendering path to optimise | Failure surface moves to the backend; a bad deploy breaks every client at once |
| | Deep client-side interactivity gets awkward |

---

## Where this mirrors Reddit's published work

From *Evolving Reddit's Feed Architecture* and *Rewriting Home Feed on Android & iOS* (r/RedditEng):

- They moved from **one fat `Post` object** — which left *"cumbersome logic to infer what should actually be shown in the UI... tangled, fragile, and out of sync between iOS and Android"* — to sending **the exact UI elements to render, in server-controlled order**.
- Each post unit became a generic **`Group`** holding an array of **`Cell`** objects — the same abstraction covering announcements and carousels. This repo uses those names deliberately.
- Their goal: *"Do as little client-side manipulation as possible, and render feed as given by the server."*
- Their metric: **`Home TTI = App Initialization Time + Home Feed Page 1 (Response Latency + UI Render)`**.
- They optimised the query **strictly for first render** and **lazy-loaded fields used only for analytics** — which is why the wire cells here carry only render fields.
- Android side: **Jetpack Compose + MVVM + server-driven components**. Internally the stack is called **Core Stack**; the project codename was **Project Fangorn**.

Worth knowing as a tradeoff anecdote: they **evaluated Protobuf and reverted to GraphQL**, because adopting it was *"a significant cultural shift for the organization"* with tooling overhead. Architecture decisions are organizational.

---

## What this sample deliberately does not do

Named so you can answer "what would you add next?" rather than being caught out:

- **No push-notification product surface.** WebSocket post-room events support
  live reconciliation while connected; system notification routing is outside
  this sample.
- **One active video by design.** A single lazy process-scoped ExoPlayer follows
  the first visible feed video into detail without resetting its decoder,
  position, or buffer. Media3 preloads only the adjacent window; lifecycle,
  metered/data-saver policy, ABR ceilings, bounded segment cache, poster fallback,
  and user autoplay preference prevent a scrolling feed from multiplying
  decoders, radio work, and storage.
- **No DI framework.** The app is an explicit composition root with constructor
  injection. A larger product can generate the same graph with Dagger/Hilt
  without moving ownership back into feature code.
- **No screenshot tests.** Converters being pure makes them cheap to add (Paparazzi at Reddit, Roborazzi elsewhere).
- **KMP UI is not shared.** The portable module intentionally starts with
  domain state and reducers; Android remains Jetpack Compose, leaving room for
  Compose Multiplatform or native SwiftUI/web renderers later.
- **No real diffing engine.** `LazyColumn` keys do the work here; Reddit's iOS side has an explicit snapshot-diffing stage.

The backend, API contract, Cloudflare topology, consistency model, and live-app
configuration are documented in [`backend/README.md`](backend/README.md). The
client architecture and cache/transport contract are in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).


---

## Verified on device

The full app was installed on a physical Pixel 10 Pro and exercised against the
deployed Worker. A new account was registered through Compose, then the app
rendered the authenticated personalized SDUI feed, R2 image content, the typed
post-detail page, and five visible nested comment levels. The signed-in profile,
settings, create-post surface, and session restoration were also inspected.

The earlier storage/performance verification remains useful. On an emulator,
after six flings, the on-device database pulled with `run-as` showed:

```
groups=13     announcement + post_0..post_11  → two pages (7 + 6), pageSize=6
cursor=page:2 the cursor is a row, not a field in a StateFlow
sortIndex     max=12, n=13, 13 distinct       → the append landed after the first page,
                                                 not interleaved into it
```

Then the write path. Tapping ▲ on one post and diffing the framebuffer:

```
tapped action bar:  44/25840 pixels changed
control (other row):  0/24140 pixels changed
```

One row re-rendered — the one whose `item_state` row was written. Room invalidated the `PagingSource`, Paging re-emitted that page, and recomposition stayed scoped to the affected item. No manual list surgery anywhere in the app.

Finally, `am force-stop` and relaunch:

```
groups=13  cursor=page:2  liked=2
```

Both likes intact, both pages still cached, and the cursor did not reset — a `LAUNCH_INITIAL_REFRESH` would have reset this to 7 groups and `page:1`. The feed rendered from disk on a cold start.

The current cached-open timing was measured again on the attached Pixel 10 Pro.
Ten warm `am start -W` samples were 9–17 ms with an 11 ms median. The app marks
the static/cached shell fully drawn and never includes network latency in that
gate. A separate cold process sample was 575 ms; the warm figure is intentionally
not presented as cold-start performance.

The instrumentation transport probe reused one `HttpEngine` identity across the
API client, Coil, and Media3. The API and Cloudflare Images negotiated HTTP/3;
the Cloudflare Stream HLS manifest negotiated HTTP/2 and played through the
same engine, demonstrating origin-appropriate fallback rather than a second
connection stack. Android's scheduler also showed the unique, connected-network
`FeedRefreshWorker` registered on the physical device.
