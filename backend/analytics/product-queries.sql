-- Cloudflare Analytics Engine dataset: readthat_product_analytics
-- Always keep a bounded timestamp predicate. Counts/sums use _sample_interval
-- so they remain correct if Analytics Engine samples at higher volume.
--
-- Mapping:
-- index1 principal pseudonym (authenticated user, otherwise installation)
-- blob1 event name          blob2 platform       blob3 app version
-- blob4 build type          blob5 engagement session UUID
-- blob6 surface             blob7 content pseudonym
-- blob8 content type        blob9 reason         blob10 principal type
-- blob11 colo               blob12 country
-- double1 recorded-at ms    double2 duration/active ms
-- double3 media position ms double4 completion percent

-- Feed posts viewed per session (events are deduplicated per session/post).
SELECT
  blob5 AS session_id,
  SUM(_sample_interval) AS feed_post_views
FROM readthat_product_analytics
WHERE timestamp >= NOW() - INTERVAL '1' DAY
  AND blob1 = 'post_impression'
GROUP BY session_id
ORDER BY feed_post_views DESC;

-- Detail and comments views per session.
SELECT
  blob5 AS session_id,
  sumIf(_sample_interval, blob1 = 'post_detail_view') AS detail_views,
  sumIf(_sample_interval, blob1 = 'comments_view') AS comments_views
FROM readthat_product_analytics
WHERE timestamp >= NOW() - INTERVAL '1' DAY
  AND blob1 IN ('post_detail_view', 'comments_view')
GROUP BY session_id
ORDER BY detail_views DESC;

-- Comment creation actions per session (offline optimistic commits included).
SELECT
  blob5 AS session_id,
  SUM(_sample_interval) AS comments_created
FROM readthat_product_analytics
WHERE timestamp >= NOW() - INTERVAL '1' DAY
  AND blob1 = 'comment_create'
GROUP BY session_id
ORDER BY comments_created DESC;

-- Total active media play time per session, split by surface.
SELECT
  blob5 AS session_id,
  blob6 AS surface,
  SUM(double2 * _sample_interval) / 1000.0 AS media_play_seconds
FROM readthat_product_analytics
WHERE timestamp >= NOW() - INTERVAL '1' DAY
  AND blob1 = 'media_playback'
GROUP BY session_id, surface
ORDER BY media_play_seconds DESC;

-- Media play time per pseudonymous user/install and session.
SELECT
  index1 AS principal_id,
  blob10 AS principal_type,
  blob5 AS session_id,
  SUM(double2 * _sample_interval) / 1000.0 AS media_play_seconds
FROM readthat_product_analytics
WHERE timestamp >= NOW() - INTERVAL '7' DAY
  AND blob1 = 'media_playback'
GROUP BY principal_id, principal_type, session_id
ORDER BY media_play_seconds DESC;

-- Promoted-content funnel by stable pseudonymous ad id. The raw ad id never
-- leaves the ingestion request; blob7 contains its keyed pseudonym.
SELECT
  blob7 AS ad_id_pseudonym,
  sumIf(_sample_interval, blob1 = 'ad_impression') AS impressions,
  sumIf(_sample_interval, blob1 IN ('ad_click', 'ad_cta_click')) AS clicks,
  sumIf(_sample_interval, blob1 = 'ad_detail_view') AS detail_views,
  sumIf(_sample_interval, blob1 = 'ad_landing_load') AS landing_loads,
  sumIf(_sample_interval, blob1 = 'ad_video_complete') AS video_completions,
  SUM(IF(blob1 = 'ad_video_watch', double2 * _sample_interval, 0)) / 1000.0 AS watch_seconds
FROM readthat_product_analytics
WHERE timestamp >= NOW() - INTERVAL '7' DAY
  AND blob8 = 'AD'
GROUP BY ad_id_pseudonym
ORDER BY impressions DESC;

-- Watch depth split between feed playback and the hybrid ad detail screen.
SELECT
  blob6 AS surface,
  SUM(double2 * _sample_interval) / 1000.0 AS watch_seconds,
  AVG(double4) AS average_completion_percent
FROM readthat_product_analytics
WHERE timestamp >= NOW() - INTERVAL '7' DAY
  AND blob1 = 'ad_video_watch'
GROUP BY surface
ORDER BY watch_seconds DESC;

-- Approximate DAU. COUNT(DISTINCT) is exact only while the dataset is unsampled;
-- retain _sample_interval in event-volume queries and use a D1 rollup if exact
-- billing-grade uniques are required.
SELECT
  toStartOfInterval(toDateTime(double1 / 1000), INTERVAL '1' DAY) AS activity_day,
  COUNT(DISTINCT index1) AS daily_active_principals
FROM readthat_product_analytics
WHERE timestamp >= NOW() - INTERVAL '30' DAY
  AND blob1 IN ('session_start', 'session_foreground')
GROUP BY activity_day
ORDER BY activity_day;

-- Sessions and foreground entries by day.
SELECT
  toStartOfInterval(toDateTime(double1 / 1000), INTERVAL '1' DAY) AS activity_day,
  sumIf(_sample_interval, blob1 = 'session_start') AS sessions,
  sumIf(_sample_interval, blob1 IN ('session_start', 'session_foreground')) AS foreground_entries
FROM readthat_product_analytics
WHERE timestamp >= NOW() - INTERVAL '30' DAY
  AND blob1 IN ('session_start', 'session_foreground')
GROUP BY activity_day
ORDER BY activity_day;

-- Average active foreground session length. A checkpoint is emitted at every
-- background transition; summary is emitted on timeout/account rotation.
SELECT
  SUM(sample_weight) AS observed_sessions,
  SUM(active_ms * sample_weight) / SUM(sample_weight) / 1000.0 AS avg_active_session_seconds,
  quantileExactWeighted(0.50)(active_ms, sample_weight) / 1000.0 AS p50_active_session_seconds,
  quantileExactWeighted(0.95)(active_ms, sample_weight) / 1000.0 AS p95_active_session_seconds
FROM (
  SELECT
    blob5 AS session_id,
    MAX(double2) AS active_ms,
    MAX(_sample_interval) AS sample_weight
  FROM readthat_product_analytics
  WHERE timestamp >= NOW() - INTERVAL '7' DAY
    AND blob1 IN ('session_checkpoint', 'session_summary')
  GROUP BY session_id
);

-- One specific session: complete event timeline for support/debug analysis.
-- Replace the UUID before running.
SELECT
  toDateTime(double1 / 1000) AS recorded_at,
  timestamp AS ingested_at,
  blob1 AS event_name,
  blob6 AS surface,
  blob7 AS content_id_pseudonym,
  blob8 AS content_type,
  blob9 AS reason,
  double2 AS duration_ms,
  double3 AS position_ms,
  double4 AS completion_percent
FROM readthat_product_analytics
WHERE timestamp >= NOW() - INTERVAL '7' DAY
  AND blob5 = '00000000-0000-0000-0000-000000000000'
ORDER BY double1;
