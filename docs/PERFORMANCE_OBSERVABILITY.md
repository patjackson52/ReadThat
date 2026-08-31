# Performance and observability contract

This is the production measurement contract for Android, iOS, browser, and the
Cloudflare edge. It fixes timer boundaries, dimensions, SLOs, privacy rules,
sampling-aware queries, and incident ownership. A number without the boundary
and segment defined here is not the same metric.

The initial SLOs are engineering budgets, not claims about a statistically
significant production population. Re-baseline them after enough release
traffic is available, but do not weaken a target solely because a release
misses it.

## Vocabulary and timer boundaries

The names deliberately follow Reddit's public engineering vocabulary:

- **Home TTI** starts at application initialization and ends after the first
  real Home Feed content is rendered. Reddit defines it as App Initialization
  Time plus Home Feed Page 1 response latency and UI render. This app stops the
  Android timer on the first frame after a non-placeholder feed unit is present.
- **Comments TTI** starts when the user taps a post in Home Feed and ends on the
  first frame containing a real comment. This is the boundary used in Reddit's
  Instant Comment Loading work.
- **Community TTI** starts when community navigation begins and ends on the
  first frame containing its real identity/membership snapshot. `cache_tier`
  separates Room-first from network-first loads; ranked posts load independently.
- **MediaFeed TTI** starts in the normal-feed media tap handler and ends after
  the first frame eligible to show the exact navigation seed or Room item.
  Network refresh is not on this boundary. `cache_tier` is `navigation_seed` or
  `room`; the selected item must remain spinner-free while refresh runs.
- **Feed load** uses Reddit's `feed-load-success` / `feed-load-fail` names and
  `load_type`: `Organic First Page`, `Next Page`, `User Refresh`,
  `Refresh Pill`, or `Error Retry`.
- **Initial comments** means the latency-optimized first eight comments.
  **Full comments** is the progressive request for up to 200 nodes. A more-node
  continuation has its own network-route sample and never extends Comments TTI.
- **Local commit** starts on intent receipt and ends when the optimistic L1 or
  Room/outbox state is visible. **Server ACK** uses the same mutation's original
  monotonic/wall-clock start and ends on an authoritative response. A queued
  offline mutation is not reported as a successful ACK.
- **Touch to next frame** starts in the pointer action and ends after the next
  Compose frame. It is the native interaction feedback measure; it is not
  equivalent to browser INP.

Sources:

- [Rewriting Home Feed on Android & iOS](https://www.reddit.com/r/RedditEng/comments/1btowiw/rewriting_home_feed_on_android_ios/)
- [Instrumenting Home Feed on Android & iOS](https://www.reddit.com/r/RedditEng/comments/1c6cn8w/instrumenting_home_feed_on_android_ios/)
- [Instant Comment Loading on Android & iOS](https://www.reddit.com/r/RedditEng/comments/1cwqqtp/instant_comment_loading_on_android_ios/)
- [Evolving Reddit's Feed Architecture](https://www.reddit.com/r/RedditEng/comments/158f8o3/evolving_reddits_feed_architecture/)
- [Reddit Recap: State of Mobile Platforms](https://www.reddit.com/r/RedditEng/comments/18aptg2/reddit_recap_state_of_mobile_platforms_edition/)

Reddit reported comments TTI baselines of about 2.3 seconds on iOS and 2.6
seconds on Android at its per-user p90/day aggregation, then roughly 60%
improvements from a small prefetched first comment page and progressive full
load. Its instrumentation post also notes that a 0.15% feed-load error rate was
associated with about 5% fewer posts viewed. Those are useful sensitivity
signals, not a substitute for this app's own distribution.

## Surface SLOs

Evaluate latency at p50, p90, and p99 over raw event-weighted samples. For each
release, platform, surface, start type, network type, cache tier, and outcome
must remain separate until the owner has inspected them. Report the all-traffic
rollup only after the segments.

| Surface / metric | p50 | p90 | p99 | Additional objective |
|---|---:|---:|---:|---|
| Home TTI, cold authenticated launch | 600 ms | 1,200 ms | 2,500 ms | no network on the cached first-frame path |
| Home TTI, warm with Room content | **100 ms** | 250 ms | 500 ms | cached content first; silent refresh never replaces it with a spinner |
| Feed initial fetch, request + decode | 250 ms | 600 ms | 1,500 ms | failure rate < 0.10% |
| Feed page decoded payload | 64 KiB | 100 KiB | 150 KiB | flat SDUI groups; no detail-only fields |
| Community TTI, Room snapshot | 100 ms | 250 ms | 500 ms | identity/membership first; ranked SDUI feed may still append |
| Community detail fetch | 250 ms | 600 ms | 1,500 ms | stale Room detail remains visible on failure |
| MediaFeed TTI, navigation seed/Room | **100 ms** | 250 ms | 500 ms | exact tapped media first; no loading spinner |
| Comments TTI, prefetched/cache hit | 300 ms | **1,000 ms** | 2,000 ms | render initial eight, then progressively merge full data |
| Initial comments fetch (8) | 200 ms | 500 ms | 1,000 ms | independent of full-tree latency |
| Full comments fetch (up to 200) | 500 ms | 1,200 ms | 2,500 ms | no main-thread recursive tree work |
| Vote/comment optimistic local commit | 8 ms | 16 ms | 50 ms | visible before network ACK; failure reconciles |
| Post Room + outbox local commit | 16 ms | 50 ms | 100 ms | selected media is staged off-main |
| Vote/comment/text/link post server ACK | 300 ms | 800 ms | 2,000 ms | idempotent retry and read-your-writes bookmark |
| Touch to next native frame | 50 ms | 100 ms | 200 ms | no interaction may block on network |
| Video time to first frame | 500 ms | 1,000 ms | 2,500 ms | split by cached/network and transport |
| Video rebuffer duration | 250 ms | 1,000 ms | 2,500 ms | rebuffer ratio < 1%; startup is not rebuffering |

Media post ACK includes upload/processing time and must be charted separately by
content kind, byte band, and network type; mixing it into a text mutation chart
would make both series misleading.

Rendering health:

- `screen_frame_summary` is emitted per surface after 300 frames or when the app
  pauses. Target jank below 1%, zero frozen frames, and p95 frame duration under
  20 ms on 60 Hz devices. The `fps` side measurement is an estimate derived
  from average frame duration, so frame duration and JankStats' `isJank` are the
  authoritative gates.
- Android considers a frame slow after 16 ms and frozen after 700 ms. Track user-
  perceived ANR below an internal 0.10% target; the Play bad-behavior threshold
  is 0.47% overall and 8% per device model.
- A native discrete interaction should respond within 100 ms. At 60/120 Hz,
  main-thread work should normally fit inside the display interval and ideally
  stay below 5 ms.
- Browser p75 targets are LCP <= 2.5 seconds, INP <= 200 ms, and CLS <= 0.1.

Platform references:

- [Android app startup: TTID, TTFD, cold/warm/hot starts](https://developer.android.com/topic/performance/vitals/launch-time)
- [Android JankStats](https://developer.android.com/topic/performance/jankstats)
- [Android slow and frozen rendering](https://developer.android.com/topic/performance/vitals/render)
- [Android ANR vitals](https://developer.android.com/topic/performance/vitals/anr)
- [Apple app responsiveness](https://developer.apple.com/documentation/xcode/improving-app-responsiveness)
- [Apple MetricKit](https://developer.apple.com/documentation/metrickit)
- [Web Vitals](https://web.dev/articles/vitals)

## What is implemented

```text
Android features/iOS adapter/browser adapter
            |
            v
vendor-neutral PerformanceEvent (KMP contract)
            |
      L1 bounded queue
            |
      L2 durable outbox
            |
POST /v1/telemetry/performance (max 50 events / 64 KiB)
            |
Cloudflare Worker strict allowlist + per-IP limit
            |
     Analytics Engine distributions
            +---- Workers Logs (near-real-time accepted-batch audit)
            +---- Workers Traces (sampled backend dependency latency)
```

`:core:observability` compiles for Android, iOS device/simulator, and browser
JS. It intentionally exposes a small OpenTelemetry-shaped event contract rather
than importing the current OpenTelemetry Kotlin Multiplatform SDK, whose traces,
metrics, and logs are still marked development/experimental. Moving the exporter
to OTLP later does not require feature-module changes.

Android is fully wired:

- A nonblocking bounded `Channel` is L1; Room `performance_outbox` is L2.
- WorkManager drains up to 50 records per request immediately when connected and
  also installs a 15-minute safety sweep. The UI thread never serializes or sends.
- JankStats reports per-frame state, surface summaries, slow/frozen frames, and
  interaction feedback. Feed, detail/comments, post creation, votes, API/image
  requests, video playback, Home TTI, Comments TTI, Community TTI, community
  dwell, membership, and post-open-from-community are instrumented.
- The network event records a route template, protocol, status class, payload
  bytes, and Cloudflare `Server-Timing: edge` duration. It never records a URL.

The active iOS Compose target uses the same KMP recorder, Room outbox, sanitizer,
batching, and shared HTTP client. A lifecycle-scoped native CADisplayLink is only
the timing source; frame batching, jank/slow/frozen thresholds,
p95 math, surface attribution, and wire fields are the same KMP policy consumed
by Android JankStats. Lifecycle stop flushes the partial batch without creating
a second Swift telemetry or networking stack. The browser adapter uses the same
envelope and installs LCP, INP, and CLS observers. MetricKit remains a useful
release-diagnostics complement for daily launch/hang payloads; it is not the
near-real-time product/frame event path.

## Privacy, abuse, and cardinality policy

Allowed data is intentionally less expressive than arbitrary analytics:

- No account, post, comment, subreddit, URL, body, search text, IP-derived client
  identifier, advertising identifier, or persistent installation/device ID.
- `sessionId` is a random process UUID. It exists only as the Analytics Engine
  sampling index and changes at each process start.
- Routes are low-cardinality templates such as `/v1/posts/{id}/comments`.
- Metric, attribute, measurement, platform, unit, surface, outcome, app version,
  and build type are strict enums/regular expressions at the Worker boundary.
- Unknown fields are rejected; attribute values are capped at 80 characters;
  events at 50; request body at 64 KiB; ingestion at 300 requests/hour/IP.
- Offline age is recoverable from `recordedAtEpochMs`, but user identity is not.
- Country and colo are added only at the edge. Raw client IP is not stored in
  the Analytics Engine datapoint.
- Workers Logs/Traces can retain Cloudflare's normal request metadata, including
  source IP, for the plan's short retention window. Restrict dashboard access
  and do not export that metadata into the performance dataset.

Keep event recording enabled in release builds, but use server-side/adaptive
sampling if volume grows. Never sample failures, frozen frames, ANRs, or p99
investigation cohorts on the client. Analytics Engine may sample at scale; every
query below therefore weights by `_sample_interval`.

## Analytics Engine schema

Dataset: `readthat_performance`

| Column | Meaning | Column | Meaning |
|---|---|---|---|
| `index1` | random process session UUID | `double1` | primary metric value |
| `blob1` | metric name | `double2` | client recorded-at epoch ms |
| `blob2` | platform | `double3` | frame count |
| `blob3` | app version | `double4` | jank count |
| `blob4` | surface | `double5` | slow-frame count |
| `blob5` | outcome | `double6` | frozen-frame count |
| `blob6` | start type | `double7` | estimated FPS |
| `blob7` | load type | `double8` | response bytes |
| `blob8` | mutation type | `double9` | request bytes |
| `blob9` | cache tier | `double10` | Worker edge duration ms |
| `blob10` | protocol | `double11` | cache-hit flag (0/1) |
| `blob11` | route template | `double12` | dropped count |
| `blob12` | network type |  |  |
| `blob13` | content kind |  |  |
| `blob14` | phase |  |  |
| `blob15` | interaction type |  |  |
| `blob16` | from-prefetch |  |  |
| `blob17` | status class |  |  |
| `blob18` | build type |  |  |
| `blob19` | Cloudflare colo |  |  |
| `blob20` | Cloudflare country |  |  |

## Dashboard queries

Run SQL in the Cloudflare dashboard or post it to the [Analytics Engine SQL
API](https://developers.cloudflare.com/analytics/analytics-engine/sql-api/).
The token needs only `Account Analytics: Read`. Cloudflare documents
`quantileExactWeighted(q)(value, _sample_interval)` for sampled percentiles.

Home TTI by platform, cold/warm start, and cache tier:

```sql
SELECT
  blob2 AS platform,
  blob6 AS start_type,
  blob9 AS cache_tier,
  quantileExactWeighted(0.50)(double1, _sample_interval) AS p50_ms,
  quantileExactWeighted(0.90)(double1, _sample_interval) AS p90_ms,
  quantileExactWeighted(0.99)(double1, _sample_interval) AS p99_ms,
  sum(_sample_interval) AS samples
FROM readthat_performance
WHERE timestamp >= NOW() - INTERVAL '1' DAY
  AND blob1 = 'home_tti'
  AND blob5 = 'SUCCESS'
GROUP BY platform, start_type, cache_tier
ORDER BY platform, start_type, cache_tier
```

Feed load failure by Reddit load type:

```sql
SELECT
  blob2 AS platform,
  blob7 AS load_type,
  100.0 * sumIf(_sample_interval, blob1 = 'feed-load-fail')
    / sum(_sample_interval) AS failure_percent,
  sum(_sample_interval) AS attempts
FROM readthat_performance
WHERE timestamp >= NOW() - INTERVAL '1' DAY
  AND blob1 IN ('feed-load-success', 'feed-load-fail')
GROUP BY platform, load_type
ORDER BY failure_percent DESC
```

Optimistic visibility versus server ACK:

```sql
SELECT
  blob1 AS metric,
  blob8 AS mutation_type,
  blob13 AS content_kind,
  blob5 AS outcome,
  quantileExactWeighted(0.50)(double1, _sample_interval) AS p50_ms,
  quantileExactWeighted(0.90)(double1, _sample_interval) AS p90_ms,
  quantileExactWeighted(0.99)(double1, _sample_interval) AS p99_ms
FROM readthat_performance
WHERE timestamp >= NOW() - INTERVAL '1' DAY
  AND blob1 IN ('mutation_local_commit', 'mutation_server_ack')
GROUP BY metric, mutation_type, content_kind, outcome
ORDER BY metric, mutation_type, content_kind, outcome
```

Surface frame health:

```sql
SELECT
  blob2 AS platform,
  blob4 AS surface,
  100.0 * sum(_sample_interval * double4)
    / sum(_sample_interval * double3) AS jank_percent,
  sum(_sample_interval * double6) AS frozen_frames,
  quantileExactWeighted(0.95)(double1, _sample_interval) AS p95_frame_ms,
  quantileExactWeighted(0.50)(double7, _sample_interval) AS median_estimated_fps
FROM readthat_performance
WHERE timestamp >= NOW() - INTERVAL '1' DAY
  AND blob1 = 'screen_frame_summary'
GROUP BY platform, surface
ORDER BY jank_percent DESC
```

Transport and edge split:

```sql
SELECT
  blob2 AS platform,
  blob10 AS protocol,
  blob11 AS route,
  blob17 AS status_class,
  quantileExactWeighted(0.90)(double1, _sample_interval) AS client_p90_ms,
  quantileExactWeighted(0.90)(double10, _sample_interval) AS edge_p90_ms,
  sum(_sample_interval * double8) AS response_bytes,
  sum(_sample_interval) AS requests
FROM readthat_performance
WHERE timestamp >= NOW() - INTERVAL '1' DAY
  AND blob1 = 'network_request'
GROUP BY platform, protocol, route, status_class
ORDER BY requests DESC
```

Use `client_p90_ms - edge_p90_ms` only as a directional transport/queue signal;
the difference of percentiles is not the percentile of per-request differences.
For rigorous analysis, emit or query a per-event derived transport duration.

## Platform decision

The selected default is Cloudflare Analytics Engine + Workers Logs + Workers
Traces. It is already in the request path, supports near-real-time ingest, keeps
the mobile contract vendor-neutral, and is the lowest-cost useful option for
this deployment. Analytics Engine is currently not billed; Cloudflare's
projected free allowance is 100,000 datapoints and 10,000 queries/day. Workers
Logs currently include 200,000 events/day with three-day retention on Free.
Check current pricing before treating these values as permanent.

| Option | Android / iOS / web | KMP posture | Near real time | Free/near-free fit | Decision |
|---|---|---|---|---|---|
| **Cloudflare AE + Logs + Traces** | one custom envelope across all three | shared contract/exporters | yes | excellent in this stack | **selected** |
| Grafana Cloud | broad OTel/web support; native mobile requires plumbing | use this contract + OTLP | yes | generous free telemetry quotas, 3 users | best external dashboard/long-retention runner-up |
| Sentry | strong Android/iOS; KMP JS/Wasm is no-op | partial | yes | pricing/quotas vary | excellent crash/mobile product, not one KMP target here |
| Firebase Performance | strong native + web automatic metrics | wrappers per platform | yes | good | useful native supplement, not Cloudflare/KMP-native |
| Embrace | excellent mobile + web RUM | platform SDK wrappers | yes | free session tier; custom metrics paid | strongest turnkey mobile UX; cost grows for this contract |
| Honeycomb | excellent OTel event analysis | exporter works | yes | large free event/metric tier | strong backend exploration; no mobile RUM/SLOs on free |
| New Relic | mature mobile/browser/APM | platform SDK wrappers | yes | free ingest, one full user | broad but heavier/vendor-specific |
| PostHog | product analytics and replay | platform wrappers | yes | generous event tier | engagement complement, not frame/network observability |

Relevant current references:

- [Analytics Engine pricing](https://developers.cloudflare.com/analytics/analytics-engine/pricing/)
- [Analytics Engine limits](https://developers.cloudflare.com/analytics/analytics-engine/limits/)
- [Workers Logs](https://developers.cloudflare.com/workers/observability/logs/workers-logs/)
- [Workers Traces](https://developers.cloudflare.com/workers/observability/traces/)
- [Workers OpenTelemetry export](https://developers.cloudflare.com/workers/observability/exporting-opentelemetry-data/)
- [OpenTelemetry Kotlin Multiplatform status](https://opentelemetry.io/docs/languages/kotlin/)
- [Grafana Cloud pricing](https://grafana.com/pricing/?tab=free)
- [Sentry Kotlin Multiplatform SDK](https://github.com/getsentry/sentry-kotlin-multiplatform)
- [Firebase Performance Monitoring](https://firebase.google.com/docs/perf-mon)
- [Embrace pricing](https://embrace.io/pricing/)
- [Honeycomb pricing](https://www.honeycomb.io/pricing)
- [New Relic pricing](https://newrelic.com/pricing)
- [PostHog pricing](https://posthog.com/)

If richer dashboards or 14-day retention become necessary, point Grafana at
Analytics Engine's SQL API or enable Workers OTLP export. Feature modules and
event names stay unchanged.

## Release gates and incident runbook

Release comparison uses the previous healthy version and the SLO, both at the
same platform/start/cache/network segments:

1. Block rollout for a >10% p90 Home/Comments TTI regression, feed-load failure
   >=0.10%, any repeatable frozen frame, lost optimistic rollback, or telemetry
   schema rejection from a production build.
2. Halt and inspect for a >20% p90 network regression on two or more major API
   routes, HTTP/3 adoption collapse on an origin known to advertise h3, or a
   material client-minus-edge increase after a client release.
3. Correlate Workers Traces by deployment and route. Use Workers Logs only for
   request/batch investigation; use Analytics Engine for distributions.
4. For Home TTI, inspect app initialization, initial fetch, cache tier, response
   bytes, and screen frames independently. For Comments TTI, split prefetched
   from demand-loaded and initial eight from full 200.
5. For mutation regressions, compare local commit and ACK. A fast ACK with a slow
   local commit is a client state/storage bug; a fast local commit with a slow ACK
   preserves UX but indicates network/edge/database work.
6. If the exporter is offline, Android/iOS L2 data remains bounded and retries.
   Check `recordedAtEpochMs` age and dropped/outbox counts before concluding the
   product surface itself regressed.

Do not alert on p99 until the sample population is meaningful. Always publish
sample count beside a percentile, annotate app/backend deployments, and keep
debug/staging traffic out of release SLO panels.

## Verification evidence

Verified on 2026-08-28 against the deployed Worker:

- Wrangler generated the Analytics Engine binding, passed dry-run/startup
  analysis, and deployed Worker version
  `079ee297-311e-4ad2-9c5d-d6896925cd7c` with a 31 ms reported startup time.
- A bounded synthetic batch returned HTTP `202`, `Server-Timing`, and an h3
  advertisement; Workers Tail observed the matching accepted-batch record.
- Analytics Engine Studio showed `readthat_performance` and returned the
  synthetic `home_tti = 87.25 ms` warm/Room row.
- The debug app was installed on the Pixel 9 Pro Android 17 emulator. A cold
  launch completed without a spinner-path network dependency, its telemetry
  upload returned `202` over negotiated HTTP/3, and Analytics Engine returned
  the emitted `screen_frame_summary` row. That single emulator sample is proof
  of transport/schema wiring, not a production performance baseline.

The emulator run also caught and fixed an initialization-order defect:
JankStats must be attached only after Compose installs the Window DecorView.
This is why a device launch remains part of the release gate even when unit,
KMP, lint, and Worker-runtime tests are green.
