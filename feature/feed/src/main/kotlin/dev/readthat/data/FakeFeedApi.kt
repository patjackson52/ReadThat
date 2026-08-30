package dev.readthat.data

import dev.readthat.domain.WireCell
import dev.readthat.domain.WireFeedPage
import dev.readthat.domain.WireGroup
import dev.readthat.domain.WireAdMediaItem
import dev.readthat.domain.WireAdMediaKind
import dev.readthat.domain.WireRelatedPost
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * THE FAKE "SERVER".
 *
 * Entirely in-process. No sockets, no INTERNET permission, nothing to reject a
 * connection because no connection is ever attempted.
 *
 * It exists to make the SDUI contract concrete: it emits ordered Groups of Cells and
 * an opaque cursor, exactly as a real backend would, and the client renders whatever
 * it is given.
 *
 * Deterministic by seed so tests and screenshots are reproducible.
 */
class FakeFeedApi(
    private val seed: Int = 42,
    private val pageSize: Int = 6,
    private val totalPages: Int = 5,
    /** Artificial latency, in ms. Set to 0 in tests. */
    private val latencyMs: Long = 350L,
    /**
     * When true, page 3 includes a cell type this client build does not know about,
     * to exercise forward-compatibility. This is the interesting demo.
     */
    private val injectUnknownCellType: Boolean = true,
) {

    suspend fun loadPage(cursor: String?): WireFeedPage {
        if (latencyMs > 0) delay(latencyMs)

        val pageIndex = cursor?.removePrefix(CURSOR_PREFIX)?.toIntOrNull() ?: 0
        val rng = Random(seed + pageIndex)

        val groups = buildList {
            if (pageIndex == 0) {
                add(
                    WireGroup(
                        groupId = "announcement",
                        cells = listOf(
                            WireCell.Announcement(
                                cellId = "c0",
                                text = "Server-driven feed demo — every cell below was described by the (fake) backend.",
                            ),
                        ),
                    )
                )
            }

            repeat(pageSize) { i ->
                val n = pageIndex * pageSize + i
                add(buildPostGroup(n, rng, pageIndex))
                if (pageIndex == 0 && i == 2) add(promotedVideoGroup())
                if (pageIndex == 0 && i == 4) add(promotedCarouselGroup())
            }
        }

        val next = if (pageIndex + 1 < totalPages) CURSOR_PREFIX + (pageIndex + 1) else null
        return WireFeedPage(groups = groups, nextCursor = next)
    }

    private fun buildPostGroup(n: Int, rng: Random, pageIndex: Int): WireGroup {
        val subreddit = SUBREDDITS[n % SUBREDDITS.size]
        val kind = n % 3

        val cells = buildList {
            add(
                WireCell.Metadata(
                    cellId = "meta",
                    subreddit = subreddit,
                    postedAgo = "${1 + (n % 23)}h ago",
                    pinned = n == 0,
                )
            )
            add(WireCell.Title(cellId = "title", text = TITLES[n % TITLES.size]))

            when (kind) {
                0 -> add(
                    WireCell.Text(
                        cellId = "body",
                        body = BODIES[n % BODIES.size],
                        maxLines = 3,
                    )
                )
                1 -> add(
                    WireCell.Image(
                        cellId = "media",
                        placeholderColor = PALETTE[n % PALETTE.size],
                        aspectRatio = 16f / 9f,
                        altText = "Placeholder image for ${TITLES[n % TITLES.size]}",
                    )
                )
                else -> add(
                    WireCell.Video(
                        cellId = "media",
                        placeholderColor = PALETTE[n % PALETTE.size],
                        aspectRatio = 16f / 9f,
                        // Third draw of the per-post rng — see the ActionBar note.
                        durationSeconds = postDuration(seed, n),
                        altText = "Placeholder video for ${TITLES[n % TITLES.size]}",
                    )
                )
            }

            // Forward-compatibility demo: on page 2 the "server" starts sending a
            // cell type this build has never heard of. The app must degrade
            // gracefully — render everything else, drop this, and report it.
            if (injectUnknownCellType && pageIndex == 2 && n % 3 == 0) {
                add(WireCell.Unknown(cellId = "poll", typeName = "PollCell_v2"))
            }

            // Score and comment count come from a per-post rng, NOT the shared page
            // rng: that makes them a pure function of the post index, so
            // headerFor() can recompute the exact same numbers for the detail
            // screen without replaying the page's draw order.
            add(
                WireCell.ActionBar(
                    cellId = "actions",
                    score = postRng(n).nextInt(12, 48_000),
                    commentCount = postRng(n).let { it.nextInt(12, 48_000); it.nextInt(0, 2_400) },
                )
            )
        }

        return WireGroup(groupId = "post_$n", cells = cells)
    }

    private fun postRng(n: Int) = postRng(seed, n)

    private fun promotedVideoGroup() = WireGroup(
        groupId = "promoted:patrick-platform-01",
        cells = promotedCells(
            adId = "patrick-platform-01",
            title = "I build Android client platforms that let product teams ship safely at scale.",
            summary = "Patrick's work connects server-driven UI, resilient media playback, offline data, and privacy-bounded observability into one production-shaped Android platform.",
            media = listOf(adVideo(
                creativeId = "platform-story",
                hlsUrl = "https://customer-lmyzrnktg8ca0fds.cloudflarestream.com/f248c2e7535860d780f3d1ad17b6eba6/manifest/video.m3u8",
                posterUrl = "https://customer-lmyzrnktg8ca0fds.cloudflarestream.com/f248c2e7535860d780f3d1ad17b6eba6/thumbnails/thumbnail.jpg?time=1s&width=608&height=760&fit=crop",
            )),
        ),
    )

    private fun promotedCarouselGroup() = WireGroup(
        groupId = "promoted:patrick-systems-02",
        cells = promotedCells(
            adId = "patrick-systems-02",
            title = "Platform engineering is a product: fast paths, safe defaults, and evidence.",
            summary = "A three-card tour of the system: adaptive delivery, deterministic SDUI rendering, and telemetry that survives offline use without collecting content.",
            media = listOf(
                adVideo(
                    creativeId = "adaptive-media",
                    hlsUrl = "https://customer-lmyzrnktg8ca0fds.cloudflarestream.com/e8d1b8d8c94a74e5c1b7b4c04beb0366/manifest/video.m3u8",
                    posterUrl = "https://customer-lmyzrnktg8ca0fds.cloudflarestream.com/e8d1b8d8c94a74e5c1b7b4c04beb0366/thumbnails/thumbnail.jpg?time=2s&width=608&height=760&fit=crop",
                ),
                WireAdMediaItem(
                    creativeId = "sdui-architecture",
                    kind = WireAdMediaKind.Image,
                    placeholderColor = 0xFF0B3D5C,
                    aspectRatio = 4f / 5f,
                    altText = "Placeholder for Patrick's server-driven UI architecture case study",
                    imageUrl = "https://customer-lmyzrnktg8ca0fds.cloudflarestream.com/d9016d79b650b07823b0ef418f99052d/thumbnails/thumbnail.jpg?time=2s&width=608&height=760&fit=crop",
                    cacheKey = "ad:patrick-systems-02:sdui-architecture",
                ),
                adVideo(
                    creativeId = "observability",
                    hlsUrl = "https://customer-lmyzrnktg8ca0fds.cloudflarestream.com/b533d69cd1f62698ebc68e216074006d/manifest/video.m3u8",
                    posterUrl = "https://customer-lmyzrnktg8ca0fds.cloudflarestream.com/b533d69cd1f62698ebc68e216074006d/thumbnails/thumbnail.jpg?time=2s&width=608&height=760&fit=crop",
                ),
            ),
        ),
    )

    private fun promotedCells(
        adId: String,
        title: String,
        summary: String,
        media: List<WireAdMediaItem>,
    ): List<WireCell> = listOf(
        WireCell.AdHeader("header", adId, author = "patrickjackson"),
        WireCell.AdTitle("title", adId, title),
        WireCell.AdMedia(
            cellId = "media",
            adId = adId,
            items = media,
            destinationUrl = "https://patrickjackson.dev",
            displayDomain = "patrickjackson.dev",
            ctaLabel = "View work",
        ),
        WireCell.AdSummary("summary", adId, summary),
        WireCell.AdRelatedPosts(
            cellId = "related",
            adId = adId,
            posts = listOf(
                WireRelatedPost("post_0", "Flattening a nested feed model", "RedditEng", 896),
                WireRelatedPost("post_1", "Why render decisions belong on the server", "androiddev", 642),
                WireRelatedPost("post_2", "One player across feed and detail", "Kotlin", 511),
            ),
        ),
        WireCell.AdActionBar("ad_actions", adId),
    )

    private fun adVideo(creativeId: String, hlsUrl: String, posterUrl: String) = WireAdMediaItem(
        creativeId = creativeId,
        kind = WireAdMediaKind.Video,
        placeholderColor = 0xFF102A43,
        aspectRatio = 4f / 5f,
        altText = "Placeholder video for Patrick Jackson's Android platform portfolio",
        hlsUrl = hlsUrl,
        posterUrl = posterUrl,
        durationSeconds = 30,
        cacheKey = "ad:patrick:$creativeId",
    )

    companion object {
        const val CURSOR_PREFIX = "page:"

        private fun postRng(seed: Int, n: Int) = Random(seed * 31 + n)

        private fun postDuration(seed: Int, n: Int): Int =
            postRng(seed, n).let { it.nextInt(12, 48_000); it.nextInt(0, 2_400); 30 + it.nextInt(300) }

        /**
         * The canonical catalog entry for a feed post — recomputed, not stored, so
         * it CANNOT drift from what loadPage emits. This is what CommentsGraph
         * injects into the comments fake: one source of truth, two "endpoints".
         */
        fun headerFor(postId: String, seed: Int = 42): dev.readthat.shared.PostHeader? {
            val n = postId.removePrefix("post_").toIntOrNull() ?: return null
            val rng = postRng(seed, n)
            val score = rng.nextInt(12, 48_000)
            val commentCount = rng.nextInt(0, 2_400)
            // Same kind switch as buildPostGroup — content coherence by recomputation.
            val body = if (n % 3 == 0) BODIES[n % BODIES.size] else null
            val media = when (n % 3) {
                1 -> dev.readthat.shared.PostMedia(
                    placeholderColor = PALETTE[n % PALETTE.size],
                    aspectRatio = 16f / 9f,
                    isVideo = false,
                )
                2 -> dev.readthat.shared.PostMedia(
                    placeholderColor = PALETTE[n % PALETTE.size],
                    aspectRatio = 16f / 9f,
                    isVideo = true,
                    durationSeconds = postDuration(seed, n),
                )
                else -> null
            }
            return dev.readthat.shared.PostHeader(
                postId = postId,
                title = TITLES[n % TITLES.size],
                author = "u/op_$n",
                subreddit = "r/${SUBREDDITS[n % SUBREDDITS.size]}",
                score = score,
                commentCount = commentCount,
                body = body,
                media = media,
            )
        }

        private val SUBREDDITS = listOf(
            "androiddev", "Kotlin", "programming", "compose", "RedditEng", "gamedev",
        )

        private val TITLES = listOf(
            "Flattening a nested feed model into a single render list",
            "Why we moved the render decision to the server",
            "Stable keys, scroll restoration, and the recomposition tax",
            "Converters as pure functions: the cheapest tests you will ever write",
            "Forward compatibility: what an old client does with a new cell",
            "Cursor pagination without duplicate keys",
        )

        private val BODIES = listOf(
            "The client used to hold a fat Post object and infer what to draw. That logic drifted between platforms and every new post type meant a release.",
            "Sending an ordered list of typed cells means the backend can ship a new unit without a client change — and both platforms stay identical by construction.",
            "The cost is real: you trade client-side type safety and easy offline caching for velocity and consistency. Say so out loud in the interview.",
            "Measure Time To Interact as app init plus first page latency plus first render. Optimise the query strictly for that first frame.",
            "Lazy-load anything only analytics needs. It does not belong on the critical path to pixels.",
            "A converter that returns null is not an error. It is a client politely declining to render something it does not understand yet.",
        )

        private val PALETTE = listOf(
            0xFFD93A00, 0xFF0045AC, 0xFF006A5B, 0xFF7E1E9C, 0xFFAA5B00, 0xFF23386B,
        )
    }
}
