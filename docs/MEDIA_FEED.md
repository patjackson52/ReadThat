# MediaFeed architecture and UX contract

MediaFeed is the immersive image/video projection of the ranked feed. Tapping
media in Home or a community opens the exact tapped post at its media-relative
index; tapping the same post's title or body continues to open ordinary post
detail. Media immediately before and after it are the same media units that
precede and follow it in that normal-feed generation.

## UI behavior

- `VerticalPager` renders one edge-to-edge item at a time on black chrome.
- A slow drag commits after crossing 50% of a page. Velocity can commit a
  shorter fling, but `PagerSnapDistance.atMost(1)` prevents skipping more than
  one item. An incomplete gesture springs back without bounce.
- The normal feed supplies a synchronous ordered navigation snapshot, so the
  anchor and adjacent pixels never wait for Room or network. Room then pages
  around the anchor's absolute index and replaces navigation values with the
  persisted typed representation.
- Paging uses an eight-item page and a six-item prefetch distance. There is no
  append spinner; cached content stays visible if an append fails.
- One tap hides or restores community, close control, username, title/body, and
  vote/comment metadata. Hidden mode is media only.
- Images use `ContentScale.Fit` and two-pointer scale/pan from 1x through 4x.
  Multi-photo posts add a nested horizontal pager and an `n/total` counter;
  vertical paging remains the one-finger gesture between posts.
- Only the current video leases the process player. It autoplays immediately;
  adjacent sources and posters are warmed before they become current. Video uses
  aspect-ratio-preserving fit: landscape fills width, portrait fills height, and
  the other axis is letterboxed so the complete encoded frame remains visible.
- Username opens public profile. Back/X returns to the prior feed and its saved
  scroll state. Pager position is stored in `SavedStateHandle`.
- Title, body, and comments open an app-hosted Material bottom sheet. The sheet
  uses `PostDetailScreen`'s header, vote, comments, composer, and continuation
  components with `MediaBottomSheet` presentation. Hero media is omitted, so
  the MediaFeed player/image remains the sole media owner.

## Gradle and layering

```text
:app (navigation, bottom-sheet host, BackendClient adapter)
  +-- :feature:mediafeed (UI, typed domain model, Pager/RemoteMediator)
  +-- :feature:feed      (SDUI normal feed)
  +-- :feature:comments  (reused detail/comment composition)
  +-- :core:post         (shared optimistic post mutations)
  +-- :core:data         (shared Room schema)
  +-- :core:media        (one player and video L2)
  +-- :core:network      (one process transport)
```

There are no feature-to-feature dependencies. `:app` adapts the shared
`BackendClient` to `MediaFeedRemoteSource` and hosts cross-feature navigation
and the bottom sheet. The feature owns typed media models because this surface
has a stable client-known composition; the heterogeneous normal feed remains
SDUI.

### Cross-surface ordering handoff

On a media tap, `FeedRepository` reads ordered `feed_groups`, joined optimistic
state, and the feed's next cursor in one Room transaction. It filters that exact
ranked generation to media, records the anchor index, and returns an ephemeral
`NormalFeedMediaContext`. The payload is held by the app navigation host rather
than serialized into a route/Bundle. Only its opaque `snapshotId` is a route
argument; after Activity or process recreation that small token reconnects the
destination to the already-persisted Room scope and its saved pager position.

`:app` converts the context to typed `MediaFeedItem`s and persists a new,
snapshot-scoped MediaFeed membership before its `Pager` starts. The anchor does
not move to index zero: prior cached media remains available by swiping back,
and later cached media remains available by swiping forward. A fresh persisted
remote key suppresses an independent initial refresh that could reorder the
handoff. Snapshot scopes remain immutable across stale-time and process
recreation; they append from their captured cursor but are never independently
refreshed from the head.

The normal feed cursor is still opaque. `HttpMediaFeedRemoteSource` namespaces
it as `ranked-feed-v1:` and, when MediaFeed reaches the cached tail, continues
through `/v1/feed` while projecting out non-media groups. This is the
compatibility bridge that guarantees one timeline today. A future typed API can
replace the bridge only when it accepts a signed feed-generation handoff token;
an independently anchored media query is not equivalent because ranking may
have changed between requests.

## Backend/client contract

`GET /v1/feeds/media` is a separate typed projection over the same ranking and
ACL policy as `GET /v1/feed`.

Query parameters:

| Parameter | Contract |
|---|---|
| `limit` | 2-20; Android requests 8 |
| `subreddit` | optional community scope |
| `anchorPostId` | optional on page one; must be a visible image/video post |
| `cursor` | opaque signed continuation; clients do not decode it |

Representative response:

```json
{
  "schemaVersion": 1,
  "feedId": "media:home",
  "snapshotAt": 1787961600000,
  "anchorIncluded": true,
  "items": [
    {
      "id": "post-id",
      "subreddit": "WestSeattleWA",
      "author": "MsFoxieMoxie",
      "kind": "image",
      "title": "Found Orca & other cards",
      "score": 30,
      "commentCount": 12,
      "viewerVote": 0,
      "media": {
        "id": "media-id",
        "width": 1200,
        "height": 1600,
        "url": "signed-detail-rendition",
        "cacheKey": "image:media-id:etag:detail"
      },
      "mediaItems": [
        { "id": "media-id", "url": "signed-detail-rendition" },
        { "id": "media-id-2", "url": "signed-detail-rendition-2" }
      ]
    }
  ],
  "nextCursor": "opaque"
}
```

`media` remains the ordered first item for older clients and cache records.
New clients use `mediaItems` when present and fall back to `[media]`, so
single-photo and pre-gallery payloads retain identical behavior.

For deep links or entry points without a normal-feed context, the first typed
response places a valid anchor first and excludes it from the ranked walk. The
signed cursor binds schema version, snapshot admission time,
personalized rank, post-id tie breaker, community scope, anchor, and a keyed
viewer audience. Replaying it under another viewer or scope returns
`400 invalid_cursor`. A non-advancing cursor is rejected client-side.

This is a separate endpoint, not a separate ranking service. It avoids shipping
and decoding text/link SDUI units merely to discard them, while preserving one
ranking/ACL source of truth. Feed delivery does not use WebSockets: speculative
rank changes would reorder a gesture-driven surface and make cursors unstable.
The existing per-post WebSocket remains limited to post/comment live mutations;
REST plus Room is authoritative after reconnect.

During a staggered rollout, an explicit `404` from the typed endpoint activates
a bounded compatibility projection over `/v1/feed`; all other errors propagate.
The same projection is deliberately used to resume a normal-feed launch from
its captured cursor. Its cursor is client-namespaced to prevent accidental
cross-contract reuse.

## Room and concurrency

Room is the paging source of truth:

```text
HTTP cursor page -> MediaFeedRemoteMediator -> Room transaction
                                              |
                         media_post_content <-+-> media_feed_entries
                                              +-> media_feed_remote_keys
                                              +-> shared item_state
                                                        |
                                                        v
                                  PagingSource<MediaFeedRow> -> VerticalPager
```

- `media_post_content` stores one typed JSON payload per account/post.
- `media_feed_entries` stores ordered membership per account/scope/anchor and
  normal-feed snapshot handoff.
- `media_feed_remote_keys` persists the opaque cursor and freshness.
- Votes reuse shared `item_state` and `vote_outbox`; page reads cannot overwrite
  a newer optimistic vote.
- Refresh writes only after a successful response, so an offline refresh keeps
  the navigation seed/cached generation visible.
- A bounded striped mutex serializes competing post mutations. A separate
  bounded striped account/feed mutex serializes competing Pagers, which re-read
  the cursor after acquiring it. Late ACKs cannot overwrite newer outbox intent.
- Duplicate/overlapping append rows are ignored, position is monotonic, and the
  `(accountId, feedId, position)` index is unique.
- Eight recent MediaFeed scopes are retained per account; unreferenced content
  is pruned. This bounds anchor-specific offline generations.
- Normal-feed launch payloads remain ephemeral; large post lists are not placed
  in Navigation saved state. Paging uses placeholders plus the known navigation
  snapshot to keep absolute indices stable while Room loads around the anchor.
  The app host drops its handoff after ViewModel creation, and the ViewModel
  drops its first-frame fallback as soon as Paging materializes the anchor, so
  the full snapshot is never retained as a second long-lived in-memory feed.

## Media and lifecycle policy

Images reuse the app singleton Coil `ImageLoader`: decoded L1 is 12% of memory
on low-RAM devices and 20% otherwise; L2 is quota-aware and capped at
64/128/256 MiB. Stable cache keys are independent of expiring delivery URLs and
identify the rendition (`feed` versus `detail`). MediaFeed warms every photo in
the current/adjacent gallery window plus upcoming image/poster requests, retaining their disposable
handles and cancelling requests as the window moves or the screen disposes.

Videos reuse one `VideoPlaybackCoordinator`, one lazy ExoPlayer, the shared
network engine, and one bounded Media3 `SimpleCache`. MediaFeed has higher
ownership priority than inline Feed and lower priority than full Detail. Owner-
scoped preload requests prevent one disposed destination from clearing another
destination's window. At most one decoder exists; the current and adjacent
window uses Media3 preload tiers and a 12 MiB aggregate preload target.

Playback requires both a RESUMED Compose lifecycle and focused window. Process
background pauses playback and disables preload traffic. `TRIM_MEMORY_UI_HIDDEN`
detaches the Activity-backed `PlayerView`, releases ExoPlayer/decoder resources,
and keeps only lightweight requests needed to recreate on return. Network calls
and pre-cache helpers propagate coroutine cancellation.

## Telemetry

| Event/metric | Boundary |
|---|---|
| `media_feed_tti` | media tap to the first frame eligible to show the seeded/Room item |
| `post_impression`, surface `MEDIA` | current item remains selected for 600 ms |
| `comments_view`, surface `MEDIA` | MediaFeed detail bottom sheet composes for a post |
| `media_feed_time_spent` | monotonic duration for each visible MediaFeed visit segment |
| `video_time_to_first_frame` | player prepare/switch to first rendered frame, surface `MEDIA` |
| `video_rebuffer` | post-first-frame BUFFERING to READY, surface `MEDIA` |

Only allowlisted names and bounded dimensions are uploaded. Content identifiers
use the existing server-side pseudonymization path; titles, usernames, URLs,
cursor values, and signed media tokens are never telemetry dimensions.

## Verification

`MediaLaunchContextTest` verifies normal-feed sort order, media filtering,
anchor index, optimistic-state merge, and cursor capture. `MediaFeedRepositoryTest`
verifies snapshot seeding plus continuation from the translated normal-feed
cursor. `MediaFeedRemoteMediatorTest` covers anchor ordering, cursor append,
overlap, offline seed retention, optimistic-state precedence, non-advancing
cursors, and competing Pager serialization. `PostInteractionRepositoryTest` covers atomic
state/outbox commit, cancellation, offline retention, and late-ACK races. The
Worker integration test covers typed media filtering, exact anchor placement,
pagination without duplicates, stable media cache keys, and viewer-bound cursor
rejection.

## Primary references

- [Compose Pager and fling customization](https://developer.android.com/develop/ui/compose/layouts/pager)
- [Paging from network and Room with RemoteMediator](https://developer.android.com/topic/libraries/architecture/paging/v3-network-db)
- [Media3 preload manager concepts](https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager/concepts)
- [Media3 preload manager creation and shared-player configuration](https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager/create)
