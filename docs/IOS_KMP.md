# iOS and Kotlin Multiplatform architecture

ReadThat's iOS app is a native SwiftUI lifecycle host around a static
`ReadThatShared` framework. Product models, Room persistence, API DTO mapping,
repositories, lifecycle ViewModel state, cache policy, telemetry, and Compose UI
live in Kotlin Multiplatform. Native code is limited to capabilities where the
Apple implementation is the optimization: Keychain, PhotosUI, share sheets,
AVPlayer/HLS, AVAsset downloads, and the Xcode lifecycle.

## Module ownership

```text
iosApp (SwiftUI lifecycle, signing, native pick/share/HLS shims)
  -> composeApp (thin framework host and platform graph entrypoints)
      -> feature:app-ui (shared application coordinator and navigation host)
        -> feature:*-ui (KMP feed/detail/search/profile/creation/shell screens)
      -> core:image-ui (HTTPS/cache/prefetch contract; native decoders)
      -> core:media-ui (ownership/preload contract; AVPlayer/Media3 views)
      -> core:media-acquisition (selection/size/MIME policy; native staging boundary)
      -> core:media-acquisition-ui (shared request lifecycle; Activity Result/PhotosUI actuals)
      -> core:sharing (typed share payload policy; native presentation boundary)
      -> core:sharing-ui (shared Compose contract; Android chooser/iOS host actuals)
      -> core:navigation (destinations, bounded history, versioned restoration)
      -> core:client (API, offline-first repositories, lifecycle ViewModels)
          -> core:data (Room 3.0.2 KMP database + outboxes)
          -> core:network (shared request/cache contract + platform transport)
          -> core:observability (bounded cross-platform event contract)
          -> core:model/shared (typed product and SDUI models)
```

The same `feature:app-ui`, KMP `feature:*-ui`, `core:client`, `core:data`,
`core:network`, `core:image-ui`, `core:media-ui`, `core:navigation`, and model
code compile for Android and iOS. The shared coordinator is now the default
Android application surface as well as the iOS surface. Mature Android feature
modules remain compiled as a visual, behavioral, and rollback reference; build
with `-PREADTHAT_USE_SHARED_APP=false` to select that root explicitly. Their
feed, MediaFeed, detail/comments, search, profile, community, and ad references
consume the promoted shared UI or image/media contracts and use the same Room
schema, domain models, and process-wide Android transport.

The feed-cell platform adapter and the post-detail media gallery now live in
their KMP feature modules rather than being repeated in the Android and
shared application coordinators. They share stable-key mapping, native-player roles, bounded
preload windows, poster cache requests, promoted playback state, and gallery
chrome. Feed actions, comment controls, and promoted-detail iconography are feature-owned common
UI; hosts retain only actions and the iOS image-byte loader.
Post-detail comment search, root-thread sort selection, match navigation, and overflow actions are
also `:feature:detail-ui` state. Both roots expose the same working toolbar instead of
platform-local or inert controls; replies and load-more cursors remain attached during sorting.
Media3 remains above its poster for native control hit testing; AVPlayer keeps a
decoded poster above UIKit interop only until `readyForDisplay`, preserving the
no-black-frame handoff without forking the gallery implementation.
The immersive MediaFeed and promoted-detail native-media adapters are likewise
feature-owned: stable-key fallback, first-frame queries, player roles, preload
requests, and playback-state conversion are no longer duplicated in the two
application roots. MediaFeed's accessible play/pause, mute, replay, and overflow
controls are common UI. Autoplay preference/network policy suppresses unsolicited
playback, but an explicit Play action remains available and reuses the same
AVPlayer/Media3 owner, prefetched asset/source, HTTPS boundary, and adaptive
buffer/bitrate policy. `:feature:ad-ui` also owns the secure landing capability:
common code enforces structurally valid HTTPS destinations and shares failure,
retry, and telemetry behavior, while target actuals retain hardened Android
WebView and iOS WKWebView implementations.
Home-feed account chrome, detail/comment identity images, search thumbnails,
profile avatars, and community-detail avatars now follow the same rule. Remote
image request kind/cache identity and fallback UI live in their KMP feature.
Picker/camera request ownership lives in `:core:media-acquisition-ui`; only the
Swift PhotosUI/camera presentation and decoding a newly staged local file remain
in the Apple host. Share request ownership likewise lives in `:core:sharing-ui`:
feed, detail, MediaFeed, and promoted content invoke one typed Compose capability,
while Swift only turns the iOS actual's notification into `UIActivityViewController`.

The normal `./gradlew :app:assembleDebug` build makes the Android application
host the same `AndroidReadThatGraph`, lifecycle ViewModel, and `feature:app-ui`
surface through the thin `composeApp` entrypoint. The shared path is the strict
default; only the exact value `-PREADTHAT_USE_SHARED_APP=false` selects the
retained mature reference. Android session tokens and the consistency
bookmark migrate from the established Keystore envelope and are dual-written
during this transition so upgrade and rollback remain authenticated.

## Source of truth and cache tiers

Structured state follows one rule: network responses commit to Room and UI
state observes Room or a retained repository flow. Account identity is part of
every personalized cache key.

| Data | L1 | L2 | Offline behavior |
|---|---|---|---|
| Feed, posts, comments, search, communities, profiles | retained `StateFlow` | Room 3 tables and account-scoped `cached_documents` | cached state renders first; refresh failure keeps it visible |
| Votes and membership | current Room projection | coalesced Room outbox | optimistic update survives process death and replays on the next sync |
| Community/post creation | shared draft state | Room outbox plus app-private staged media | text, link, photo gallery, video, and community commands resume with stable mutation IDs |
| Images and video posters | 64 MiB decoded image LRU plus shared 32 MiB byte LRU | 512 MiB app-private stable-key file cache | stale compressed bytes are allowed when the network fails; decoded memory is disposable |
| HLS playback | one owner-coordinated `AVPlayer` plus bounded adjacent `AVURLAsset` window | native AVFoundation buffers/asset facilities | posters remain available offline; explicit full-HLS downloads use `AVAssetDownloadURLSession` |
| Performance telemetry | nonblocking recorder | bounded Room outbox | events upload in batches and remain durable after failure |

Signed CDN URLs are not cache identities. `:core:image-ui` separates the stable
compressed-byte key from the decoded still/video-preview/avatar variant, so URL
rotation does not invalidate offline bytes or accidentally reuse the wrong
decode. The common contract rejects cleartext and malformed URLs, deduplicates
and bounds the visible prefetch window, and cancels work that leaves it. On iOS
the supplied byte loader uses the process-scoped `ReadThatClient`; decode runs
off-main with two bounded prefetch workers. On Android it delegates to the
process Coil singleton backed by `UnifiedTransport`. Media-feed loading eagerly
prefetches the first adjacent preview window. The iOS stable-byte L2 persists
recency in file metadata, so LRU ordering survives app relaunches. Capacity
trimming reads only file size and modification metadata rather than loading
every cached payload into memory; even a full cache therefore remains a bounded
metadata scan instead of a second media decode-sized allocation wave.
Account-scoped shared documents are capped at 512 and entries older than 30 days
are removed at startup. Feed/community dwell prefetches only the first eight
comments after 600 ms; detail joins that in-flight request at the Room boundary
and progressively expands to the full tree.
Multipart uploads read only one server-sized part from the staged file at a
time; a 100 MiB video is never copied into a 100 MiB Kotlin byte array.

## One connection topology

`IosReadThatGraph` constructs exactly one long-lived `URLSession` transport and
wraps it in the shared memory/disk cache. API data, authentication, image bytes,
video posters, media upload parts, and telemetry all use the same
`ReadThatClient`, which centralizes access-token refresh and the monotonic D1
bookmark. `assumesHTTP3Capable` is enabled only for known HTTPS origins and
URLSession retains normal HTTP/2 fallback, TLS tickets, Alt-Svc state, path
migration, and connection pooling. The session requires TLS 1.2 or newer,
negotiates TLS 1.3 when the origin supports it, waits for transient connectivity,
does not retain HTTP cookies or shared URL credentials, rejects disallowed
redirects (including every TLS-to-cleartext redirect in Release), and refuses
authenticated redirects across hosts. Negotiated `h3`,
`h2`, or HTTP/1.1 protocol names are captured from URLSession task metrics.

AVPlayer is intentionally a native exception: adaptive HLS playback, AirPlay,
audio routes, background media behavior, and FairPlay belong to AVFoundation.
It cannot be injected with the API `URLSession`, so it receives only
HTTPS-policy-approved server HLS/fallback URLs while poster traffic still uses
the shared client/cache. One process player is leased by owner token and surface
priority (ad detail, detail, media feed, feed); disposal of an outgoing feed can
therefore neither pause the incoming detail owner nor clear its owner-scoped
preload window. The exact prefetched `AVURLAsset` becomes the `AVPlayerItem`, and
same-URL owner handoff preserves the player item, position, decoder, buffer, and
mute state. Playback position and time-control state are sampled by one
process-scoped publisher at 100 ms only while the foreground owner has an active
play intent; attach, pause, seek, handoff, end, and error still publish immediate
edge snapshots. Retained/offscreen Compose cells therefore never create their
own progress timers, and non-owning first-frame checks suspend until ownership
returns instead of polling.
An HLS failure gets one bounded attempt with the HTTPS fallback asset. Android
keeps its existing HttpEngine/OkHttp + Media3 adapters and process-wide
player/cache coordinator.

## Shared functional surface

The iOS Compose application implements registration/login/logout, personalized
feed and paging, SDUI post/promoted cells, post detail, recursive comments,
load-more/reply/vote, post voting, native sharing and resharing, search and
typed result navigation, community discovery/detail/feed/rules/joining,
offline community creation, text/link/photo-gallery/video post creation,
vertical image/video media feed, public and editable profiles with avatar
upload, and durable settings. Shared MediaFeed keeps the mature vertical snap,
horizontal gallery, pinch/pan/double-tap zoom, chrome toggle, bounded adjacent
preload, single-player autoplay/manual controls, a working post/community/share
overflow menu, and Room-first comments bottom sheet on both platforms.
Home and community feed metadata expose the same accessible post overflow on
both targets: open post/comments, reshare, native share, and community
navigation. Community chrome owns its refresh, creation, and membership
overflow policy in the same KMP feature module; hosts no longer render inert
more-buttons or decide which actions exist.
Promoted-video completion exposes the same replay and CTA affordances
as the mature Android UI; replay seeks the leased native player instead of
constructing another decoder.

The shared host lifecycle is also part of the media contract: backgrounding
pauses the leased player and disables speculative preload work, foregrounding
reconciles the existing owner/window, and Android/iOS memory-pressure callbacks
clear decoded previews plus inactive native preload assets. Viewport-prefetch
cancellation propagates to the pooled transport rather than falling through to
stale-cache handling or continuing obsolete requests.
Feed autoplay and viewport prefetch now consume one platform-resolved KMP
policy on both hosts. Network.framework and Android connectivity facts suppress
HLS and poster speculation on constrained/metered paths while retaining bounded
still-image warming for the offline-first feed. iOS also supplies real physical
memory and cache-volume free space to that policy, so older devices receive the
low-memory ABR profile and constrained paths cap AVPlayer's forward buffer as
well as bitrate.

Background maintenance follows the same split. `SharedBackgroundMaintenance`
owns the account-scoped outbox drain and Room feed transaction, while a KMP
`BackgroundFeedMediaPlan` owns feed ordering, stable still/poster identities,
first-frame normalization, deduplication, and bounds. Android WorkManager maps
that plan to Coil and a two-second Media3 warmup. iOS `BGProcessingTask` drains
all Room outboxes, refreshes the same feed, flushes telemetry, and warms the
bounded photo/gallery/ad/video-poster plan through the process-scoped shared
client with expensive and Low Data Mode access disabled. Mutation commits
request an urgent coalesced task; entering the background schedules hourly
maintenance and each execution reschedules itself.

Session restoration paints a layout-matched shared startup shell immediately;
Keychain, Room, and network work are outside the first-frame path. Public post
and comment deep links remain readable while signed out, while all mutations and
account/community navigation stay authentication-gated. The onboarding and
credential forms use the shared auth reducer, validation contract, and an
in-flight request guard instead of platform-local form state.

Navigation restoration is also shared policy. `:core:navigation` serializes the
current destination and bounded Back history into one validated, versioned
opaque payload. Android's shared host places it in `SavedStateHandle`; the Apple
host places the same payload in per-scene `@SceneStorage`. Hosts do not parse
routes, and corrupt, oversized, or future payloads fall back to Home. A restored
detail, thread, community, media, pending-mutation, profile, search, or promoted
destination reactivates its Room-first controller rather than restoring a stale
screen-shaped object. If an uncached restored post is then definitively rejected
with HTTP 404, shared navigation returns to its saved history (or Home) instead
of trapping the next launch on an empty detail. Cached/projected posts and
offline, TLS, timeout, or other transient failures stay on the restored detail;
an intentional in-session or deep-link navigation is never auto-dismissed.
Within a running scene, `:feature:app-ui` supplies stable destination keys to a
shared `SaveableStateHolder`: persistent IA roots are pinned and twelve
transient destinations are retained by LRU. Back therefore restores feed,
community, detail/comment, search, and pager position like the mature Android
Navigation Compose stack, while old local UI state is bounded independently of
Room/controller state. A narrow target actual connects non-Home destinations to
Android system/predictive Back. On iOS, one native left-edge pan recognizer
offers a request only after crossing its completion threshold and only while the
shared destination policy enables Back; the KMP handler still decides what is
popped. Home leaves the recognizer disabled so the same edge remains available
to the community drawer. Toolbar and gesture paths both pop the same KMP
history. The same module owns root IA and chrome policy: the
Home/Create/Activity/Profile order, root history semantics, bottom navigation,
community-drawer eligibility, detail/immersive presentation, community-name
normalization, and deep-link mapping cannot drift between Android and iOS.

Home, comments, community, and MediaFeed TTI are recorded after Compose has
painted a frame, with the initial Room/network cache tier attached. Network
duration is recorded separately so repository latency cannot be mislabeled as
UI render time. A lifecycle-scoped iOS CADisplayLink feeds the same KMP
frame-health aggregator used by Android JankStats; 300-frame and lifecycle-flush
summaries therefore share p95, jank/slow/frozen, FPS, surface, privacy, Room
outbox, and pooled-export semantics without a second Swift telemetry client.

Platform shims are deliberately narrow:

- Apple Keychain stores tokens; Room never stores secrets.
- PhotosUI stages selected media in Application Support before shared draft and
  outbox code sees it. Selection counts, per-item byte limits, MIME defaults,
  optional pixel-dimension bounds, error copy, and result validation come from
  the exported KMP `MediaAcquisitionPolicy`; Kotlin validates the staged
  descriptor again. Profile editing uses the dedicated single-image, 10 MiB,
  20,000-pixel avatar policy rather than inheriting post-gallery limits. A KMP
  request accumulator owns staged results until delivery; overflow, validation
  failure, request replacement, and UI disposal return all still-owned paths for
  native deletion.
- AVPlayer renders adaptive HLS; `StreamAssetDownloadManager.swift` owns
  explicit offline HLS packages.
- `UIActivityViewController` provides native sharing for a typed KMP payload.
- Android uses Photo Picker/share intents and the existing Media3 player; all
  Android composers share the same app-private staging implementation. Camera
  capture uses full-resolution `TakePicture` output through a cache-scoped
  `FileProvider`, restores by opaque token, and promotes only validated output
  into no-backup outbox storage.

The remaining platform boundary is narrow native orchestration rather than
product policy or feature rendering. Android `:app` owns WorkManager
constraints/backoff and process lifecycle callbacks, while target actuals own
native system share presentation. The retained mature reference still compiles
its thin `AppViewModel` and typed Navigation Compose back stack. Picker presentation and
lifecycle now enter through the same KMP UI-capability module in both roots. Picker validation,
staging policy, post share payloads, authentication,
settings (including root theme and media policy), profile mutation, post
resharing, and background maintenance already enter shared KMP
controllers/repositories; the retired Android settings format is handled by an
insert-only migration shim that cannot overwrite a newer Room write.
WorkManager and `BGTaskScheduler` are native scheduling adapters: feed refresh,
vote replay, community visit/membership replay, post/community creation, and
telemetry flushing execute through the shared client/repository graph.
`SharedCreationOutboxProcessor` also owns retry classification and mutation
telemetry on both targets, serializes UI and background post publication, and
decodes older mature-Android pending-media rows so installed outboxes survive
the migration.
The former Android-only profile editor is isolated as an uninstantiated compiled
reference instead of being part of root application state.
Navigation meaning is no longer duplicated: every active
Android action enters through `AppDestination`, every restored typed route maps
back losslessly, and shell IA is selected from that shared destination. The
shared Android/iOS surface uses the same KMP snapshot codec with
`SavedStateHandle`/`@SceneStorage`, while the mature reference keeps native route
serialization as its compiled reference and rollback path. Native capability
presentation now enters through small KMP capability or feature modules; target
actuals retain only OS presentation and engine code. The remaining host edges
stay native until shared contracts can replace their orchestration without
weakening background work. Legacy Android screen implementations remain
compiled for reference and rollback testing; they are not alternate data
sources and are not the default product surface.

## Build and configuration

Install XcodeGen once, then:

```bash
cd iosApp
xcodegen generate
xcodebuild -project ReadThat.xcodeproj -scheme ReadThat \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

The Xcode pre-build phase calls
`:composeApp:embedAndSignAppleFrameworkForXcode`. Set these user-defined Xcode
build settings for a live backend:

- `READTHAT_API_BASE_URL` — HTTPS Worker/PWA origin, with no trailing slash.
- `READTHAT_DEMO_USERNAME` and `READTHAT_DEMO_PASSWORD` — optional demo login.

Production and Release builds accept HTTPS only. Debug builds set
`READTHAT_ALLOW_LOCAL_HTTP=YES`, but the shared transport limits that exception
to exact loopback hosts (`localhost`, `127.0.0.0/8`, or `[::1]`). iOS declares
`NSAllowsLocalNetworking` rather than disabling ATS globally. This supports a
local Wrangler server without allowing cleartext LAN or internet traffic:

```bash
xcodebuild -project ReadThat.xcodeproj -scheme ReadThat \
  -configuration Debug -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  READTHAT_API_BASE_URL=http://127.0.0.1:8787 build
```

The generated project targets arm64 iOS devices and Apple Silicon simulators.
The Kotlin/Native framework also overrides `minVersion.ios` to `16.0`, so the
framework linker and the Swift host agree on the supported deployment target.
The checked-in `project.yml` is authoritative; regenerate the `.xcodeproj`
after changing it.

## Verification

```bash
./gradlew :core:model:allTests :core:observability:allTests \
  :core:network:allTests :core:client:allTests \
  :core:data:testAndroidHostTest :core:data:iosSimulatorArm64Test
./gradlew :composeApp:compileAndroidMain \
  :composeApp:linkDebugFrameworkIosSimulatorArm64 \
  :composeApp:linkDebugFrameworkIosArm64
./gradlew :app:assembleDebug
```

The Xcode build is the final integration gate because it compiles the SwiftUI,
PhotosUI, sharing, AVFoundation, and offline-HLS shim against the linked Kotlin
framework.

Compose Multiplatform 1.12.0's Skiko 0.150.1 simulator archive currently
contains one ICU payload object (`libicu.icudtl_dat.o`) whose build-version
load command says iOS Simulator 18.5. That object has no executable text or
undefined symbols; it contains only the ICU `__TEXT,__const` payload. Xcode can
therefore emit a newer-object warning while linking the iOS 16 simulator host.
Do not hide the warning or raise the application deployment target: the final
`ReadThat` executable and debug dylib both declare iOS Simulator 16.0, and the
signed application is exercised on the installed iOS 16.0 runtime. Re-check
the object when upgrading Skiko and remove this note once its producer stamps
the payload with the supported minimum.

`StreamAssetDownloadManager.swift` is the only compiled file under
`clients/ios`. Earlier standalone Swift networking, telemetry, background-sync,
and player references were removed after their contracts moved into the active
KMP modules; retaining them would create misleading alternate ownership paths.
