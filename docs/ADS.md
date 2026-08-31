# Server-driven portfolio promotions

This sample now supports Reddit-shaped promoted units without pretending to be a
full advertising platform. There is no auction, targeting, billing, attribution
SDK, or third-party tracker. The Worker owns a small editorial catalog whose
purpose is to present Patrick Jackson's Android platform work inside the feed.

The live Reddit Android reference review and captured screens are documented in
[`reddit-ads-reference/README.md`](reddit-ads-reference/README.md).

## Product behavior

A promoted unit is one normal SDUI group. The Worker controls the cell order and
the Android client resolves each cell through the existing converter registry and
flattener:

```text
WireGroup("promoted:patrick-rick-verdict-01")
  ad_header
  ad_title
  ad_media          one item or a horizontal carousel
  ad_summary
  ad_related_posts  horizontal carousel
  ad_actionbar
  divider           client-generated group boundary
```

The Android home request opts in with `includePromoted=true`; this prevents other
clients that do not yet implement these cell types from receiving empty groups.
The home feed interleaves the seven demo units after global organic positions 3,
7, 10, 14, 17, 21, and 24, alternating three- and four-post gaps across cursor
pages. They are not inserted in subreddit feeds and never participate in ranked
pagination. The signed cursor carries only the organic offset needed to continue
placement without repeats; legacy cursors safely skip ads. The campaign order is:

1. Patrick Jackson — shared client-platform leverage across product teams
2. Rick Sanchez — platform architecture and offline correctness
3. Patrick Jackson — resilient networking, caching, and media continuity
4. Evil Morty — explicit contracts and organizational leverage
5. Dr. Wong — observability and healthy feedback loops
6. Space Beth — resilience under hostile conditions
7. Unity — shared capabilities without erasing team autonomy

The five character units use hosted ReadThat portraits, identify themselves as
unofficial fan-demo ads, and label their endorsements as AI-written. The two
first-party units use Patrick's versioned R2-backed headshots at their natural
portrait ratio, identify themselves as portfolio demos, and retain an AI-copy
disclosure. Every unit carries three curated `r/readthateng` evidence cards
with real deterministic post IDs. Tapping a card navigates to ReadThat's native
post detail, whose compact Markdown summary links to the matching deep dive on
patrickjackson.dev. Across the seven ads, the cards cover all 11 pages in the
ReadThat case-study series.

The current content source is
[`backend/src/promoted.ts`](../backend/src/promoted.ts), while the post catalog
and deterministic ID source live in
[`backend/fixtures/readthat-case-study.json`](../backend/fixtures/readthat-case-study.json).
Update both when the portfolio evidence changes.
Keep each `adId` stable across copy/media revisions if the unit is logically the
same campaign; use a new `creativeId` when a media asset changes.

The destination is `patrickjackson.dev`, verified in the embedded lower pane during
the August 29 device smoke test. If the site is temporarily unavailable, detail
keeps the video usable and presents a branded retry state instead of WebView's
raw network-error page.

## Wire contract

The media cell carries all destination data required for a self-contained tap:

```json
{
  "type": "ad_media",
  "cellId": "media",
  "adId": "patrick-rick-verdict-01",
  "destinationUrl": "https://patrickjackson.dev",
  "displayDomain": "patrickjackson.dev",
  "ctaLabel": "Review Patrick's work",
  "items": [
    {
      "creativeId": "patrick-rick-verdict-01:portrait:0",
      "kind": "image",
      "aspectRatio": 1,
      "imageUrl": "https://…/v1/users/rick_sanchez/avatar",
      "cacheKey": "ad:patrick-rick-verdict-01:portrait:rick_sanchez"
    }
  ]
}
```

`adId` is the unit and analytics identity. `creativeId` is the selected media,
navigation, and cache identity. A media list of length one renders as a normal
stage; a longer list automatically renders as a snapping pager with an `n/total`
counter. All items in one carousel should use the same aspect ratio to avoid
layout movement while swiping.

Unknown future cell types still follow the existing forward-compatibility rule:
they are dropped and counted while the rest of the group renders.

## Feed and detail interaction

- Feed video autoplays muted only while selected by the existing visibility
  policy. Images and non-active carousel pages render posters.
- Tapping media or the CTA opens a typed `AdDetailRoute` carrying the selected
  creative and destination.
- Feed and ad detail use the same process-wide Media3 coordinator, stable cache
  key, and player. `PlayerView.switchTargetView` transfers the decoder so a
  playing video continues rather than restarting or creating a second player.
- The completed feed overlay offers `REPLAY VIDEO` and the configured CTA.
  Replay enters detail at the beginning; the CTA preserves the completed state.
- Detail uses a square top stage, centers the 4:5 creative inside it, then shows
  a 48 dp secure-domain strip and an in-app `WebView` below. The WebView disables
  file/content access and mixed content, enables Safe Browsing, and is destroyed
  when the destination leaves composition.

## Analytics contract

All events enter the existing Room outbox, batch uploader, strict Worker schema,
and Cloudflare Analytics Engine dataset. The Worker pseudonymizes account/install
and content IDs before storage and rejects titles, raw URLs, and other unbounded
attributes.

| Event | Trigger | Measurements |
|---|---|---|
| `ad_impression` | Media cell remains at least 50% visible through the 600 ms dwell gate | stable `adId`, surface |
| `ad_view_time` | A viewable media cell exits or the feed leaves composition | active milliseconds |
| `ad_click` | Media or replay overlay tap | carousel page in `position` |
| `ad_cta_click` | CTA strip or overlay tap | carousel page in `position` |
| `ad_carousel_swipe` | Selected media page changes after initial display | page in `position` |
| `ad_related_click` | Related-post card tap | stable `adId` |
| `ad_video_play` | Playback enters playing | starting media milliseconds in `position` |
| `ad_video_watch` | Playback pauses, ends, transfers surface, or disposes | active milliseconds, start position, completion percent, reason |
| `ad_video_complete` | Playback first reaches ended | 100% completion |
| `ad_detail_view` | Hybrid detail enters composition | stable `adId` |
| `ad_landing_load` | Main-frame WebView load finishes or fails | load milliseconds and error reason |

`ad_impression` and `ad_detail_view` are session-deduplicated by the Android
recorder. Plays, time, clicks, carousel movement, and landing loads remain
counted events. The funnel and watch-depth queries are in
[`backend/analytics/product-queries.sql`](../backend/analytics/product-queries.sql).

## Implementation map

- Wire schema: [`feature/feed/src/main/kotlin/dev/readthat/domain/Wire.kt`](../feature/feed/src/main/kotlin/dev/readthat/domain/Wire.kt)
- Pure conversion/UI models: [`feature/feed/src/main/kotlin/dev/readthat/domain/Converters.kt`](../feature/feed/src/main/kotlin/dev/readthat/domain/Converters.kt)
- Shared feed rendering, media carousel, and playback analytics: [`feature/feed-ui/src/commonMain/kotlin/dev/readthat/feed/ui/PromotedFeedCell.kt`](../feature/feed-ui/src/commonMain/kotlin/dev/readthat/feed/ui/PromotedFeedCell.kt)
- Android native image/video and preload adapter: [`feature/feed/src/main/kotlin/dev/readthat/ui/FeedScreen.kt`](../feature/feed/src/main/kotlin/dev/readthat/ui/FeedScreen.kt)
- Shared application adapter: [`feature/app-ui/src/commonMain/kotlin/dev/readthat/compose/ReadThatApp.kt`](../feature/app-ui/src/commonMain/kotlin/dev/readthat/compose/ReadThatApp.kt)
- Viewability integration: [`feature/feed/src/main/kotlin/dev/readthat/ui/FeedScreen.kt`](../feature/feed/src/main/kotlin/dev/readthat/ui/FeedScreen.kt)
- Hybrid destination: [`app/src/main/kotlin/dev/readthat/ui/ads/AdDetailScreen.kt`](../app/src/main/kotlin/dev/readthat/ui/ads/AdDetailScreen.kt)
- Worker catalog/insertion: [`backend/src/promoted.ts`](../backend/src/promoted.ts)
- Analytics validation: [`backend/src/product-analytics.ts`](../backend/src/product-analytics.ts)

## Rendered smoke captures

These were rendered from the local Worker contract on an Android 17 emulator;
the media is intentionally placeholder content.

| Single-video unit | Carousel, page 2 | Hybrid detail + live portfolio site |
|---|---|---|
| ![Implemented promoted video card](reddit-ads-reference/implementation/single-video-feed.png) | ![Implemented promoted carousel page two](reddit-ads-reference/implementation/carousel-page-2.png) | ![Implemented hybrid ad detail](reddit-ads-reference/implementation/hybrid-detail.png) |
