/**
 * Small editorial catalog for the portfolio demo. This deliberately is not an
 * ads platform: no auction, targeting, billing, or third-party tracking. The
 * backend still owns content, order, stable ad/creative ids, and destinations,
 * so the Android feed remains genuinely server-driven.
 */
export interface PromotedFeedGroup {
  groupId: string;
  cells: Array<Record<string, unknown>>;
}

interface PromotedCreative {
  adId: string;
  title: string;
  summary: string;
  media: Array<Record<string, unknown>>;
}

const destination = {
  destinationUrl: "https://patrickjackson.dev",
  displayDomain: "patrickjackson.dev",
  ctaLabel: "View work",
} as const;

const relatedPosts = [
  { postId: "622735f9-e923-4a05-ad42-514d1b2fe921", title: "Flattening a nested feed model", subreddit: "RedditEng", score: 896 },
  { postId: "781cabc6-bb35-4f17-adaf-979b52176ff9", title: "Why render decisions belong on the server", subreddit: "androiddev", score: 642 },
  { postId: "d482af5f-ae5e-44fc-a27f-3c56a14b8de3", title: "One player across feed and detail", subreddit: "Kotlin", score: 511 },
] as const;

const creatives: readonly PromotedCreative[] = [{
  adId: "patrick-platform-01",
  title: "I build Android client platforms that let product teams ship safely at scale.",
  summary: "Patrick's work connects server-driven UI, resilient media playback, offline data, and privacy-bounded observability into one production-shaped Android platform.",
  media: [{
    creativeId: "platform-story",
    kind: "video",
    placeholderColor: 0xff102a43,
    aspectRatio: 4 / 5,
    altText: "Placeholder video for Patrick Jackson's Android platform portfolio",
    hlsUrl: "https://customer-lmyzrnktg8ca0fds.cloudflarestream.com/f248c2e7535860d780f3d1ad17b6eba6/manifest/video.m3u8",
    dashUrl: "https://customer-lmyzrnktg8ca0fds.cloudflarestream.com/f248c2e7535860d780f3d1ad17b6eba6/manifest/video.mpd",
    posterUrl: "https://customer-lmyzrnktg8ca0fds.cloudflarestream.com/f248c2e7535860d780f3d1ad17b6eba6/thumbnails/thumbnail.jpg?time=1s&width=608&height=760&fit=crop",
    durationSeconds: 30,
    cacheKey: "ad:patrick-platform-01:platform-story",
  }],
}, {
  adId: "patrick-systems-02",
  title: "Platform engineering is a product: fast paths, safe defaults, and evidence.",
  summary: "A three-card tour of the system: adaptive delivery, deterministic SDUI rendering, and telemetry that survives offline use without collecting content.",
  media: [{
    creativeId: "adaptive-media",
    kind: "video",
    placeholderColor: 0xff102a43,
    aspectRatio: 4 / 5,
    altText: "Placeholder video for Patrick's adaptive media work",
    hlsUrl: "https://customer-lmyzrnktg8ca0fds.cloudflarestream.com/e8d1b8d8c94a74e5c1b7b4c04beb0366/manifest/video.m3u8",
    dashUrl: "https://customer-lmyzrnktg8ca0fds.cloudflarestream.com/e8d1b8d8c94a74e5c1b7b4c04beb0366/manifest/video.mpd",
    posterUrl: "https://customer-lmyzrnktg8ca0fds.cloudflarestream.com/e8d1b8d8c94a74e5c1b7b4c04beb0366/thumbnails/thumbnail.jpg?time=2s&width=608&height=760&fit=crop",
    durationSeconds: 30,
    cacheKey: "ad:patrick-systems-02:adaptive-media",
  }, {
    creativeId: "sdui-architecture",
    kind: "image",
    placeholderColor: 0xff0b3d5c,
    aspectRatio: 4 / 5,
    altText: "Placeholder for Patrick's server-driven UI architecture case study",
    imageUrl: "https://customer-lmyzrnktg8ca0fds.cloudflarestream.com/d9016d79b650b07823b0ef418f99052d/thumbnails/thumbnail.jpg?time=2s&width=608&height=760&fit=crop",
    cacheKey: "ad:patrick-systems-02:sdui-architecture",
  }, {
    creativeId: "observability",
    kind: "video",
    placeholderColor: 0xff102a43,
    aspectRatio: 4 / 5,
    altText: "Placeholder video for Patrick's mobile observability work",
    hlsUrl: "https://customer-lmyzrnktg8ca0fds.cloudflarestream.com/b533d69cd1f62698ebc68e216074006d/manifest/video.m3u8",
    dashUrl: "https://customer-lmyzrnktg8ca0fds.cloudflarestream.com/b533d69cd1f62698ebc68e216074006d/manifest/video.mpd",
    posterUrl: "https://customer-lmyzrnktg8ca0fds.cloudflarestream.com/b533d69cd1f62698ebc68e216074006d/thumbnails/thumbnail.jpg?time=2s&width=608&height=760&fit=crop",
    durationSeconds: 30,
    cacheKey: "ad:patrick-systems-02:observability",
  }],
}] as const;

function groupFor(creative: PromotedCreative): PromotedFeedGroup {
  return {
    groupId: `promoted:${creative.adId}`,
    cells: [{
      type: "ad_header",
      cellId: "header",
      adId: creative.adId,
      author: "patrickjackson",
      avatarUrl: null,
      label: "Ad",
    }, {
      type: "ad_title",
      cellId: "title",
      adId: creative.adId,
      text: creative.title,
    }, {
      type: "ad_media",
      cellId: "media",
      adId: creative.adId,
      items: creative.media,
      ...destination,
    }, {
      type: "ad_summary",
      cellId: "summary",
      adId: creative.adId,
      text: creative.summary,
      disclosureLabel: "AI summary",
    }, {
      type: "ad_related_posts",
      cellId: "related",
      adId: creative.adId,
      posts: relatedPosts,
      disclosureLabel: "About ReadThat Highlights",
    }, {
      type: "ad_actionbar",
      cellId: "ad_actions",
      adId: creative.adId,
      commentCount: 0,
    }],
  };
}

export function promotedFeedGroups(): PromotedFeedGroup[] {
  return creatives.map(groupFor);
}

/** Places editorial units after organic items 3 and 8 without affecting cursors. */
export function interleavePromotedGroups<T extends PromotedFeedGroup>(
  organic: T[],
  promoted: PromotedFeedGroup[],
): Array<T | PromotedFeedGroup> {
  if (promoted.length === 0) return organic;
  if (organic.length === 0) return promoted;
  const placements = [3, 8];
  const result: Array<T | PromotedFeedGroup> = [];
  let promotedIndex = 0;
  organic.forEach((group, index) => {
    result.push(group);
    if (promotedIndex < promoted.length && index + 1 === placements[promotedIndex]) {
      const unit = promoted[promotedIndex];
      if (unit) {
        result.push(unit);
        promotedIndex += 1;
      }
    }
  });
  while (promotedIndex < promoted.length) {
    const unit = promoted[promotedIndex];
    if (!unit) break;
    result.push(unit);
    promotedIndex += 1;
  }
  return result;
}
