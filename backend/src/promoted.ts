import { AppError } from "./http";
import type { RequestContext } from "./types";

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
  author: string;
  title: string;
  summary: string;
  destinationUrl: string;
  portraits: PromotedPortrait[];
  relatedPostKeys: Array<keyof typeof relatedPosts>;
  avatar?: PromotedPortrait;
  headerLabel?: string;
  summaryDisclosureLabel?: string;
}

interface PromotedPortrait {
  displayName: string;
  username?: string;
  assetId?: keyof typeof promotedAssets;
  aspectRatio?: number;
  altText?: string;
}

const promotedAssets = {
  "patrick-headshot-1": {
    r2Key: "promoted/patrick-client-platform/v1/patrick-headshot-1.jpeg",
    contentType: "image/jpeg",
  },
  "patrick-headshot-2": {
    r2Key: "promoted/patrick-client-platform/v1/patrick-headshot-2.jpeg",
    contentType: "image/jpeg",
  },
} as const;

const destination = {
  displayDomain: "patrickjackson.dev",
  ctaLabel: "Review Patrick's work",
} as const;

const relatedPosts = {
  overview: {
    postId: "610466c0-544f-518b-b536-4973bcfe8af9",
    title: "ReadThat: a Reddit clone eng playground",
    subreddit: "readthateng",
    score: 1,
  },
  clientArchitecture: {
    postId: "11f48752-d00b-5553-83ea-ff6f0fc89539",
    title: "Client architecture: durable local state first",
    subreddit: "readthateng",
    score: 1,
  },
  sduiFeed: {
    postId: "a0f45ae0-e445-5867-9090-89d79a2921e8",
    title: "SDUI feed: where server-driven UI helps—and where it stops",
    subreddit: "readthateng",
    score: 1,
  },
  mediaFeed: {
    postId: "596d1396-f414-5e22-a934-db1c3e5d8f70",
    title: "Media feed: continuity with one player and one transport",
    subreddit: "readthateng",
    score: 1,
  },
  comments: {
    postId: "311ed6a2-ad4a-595f-82cd-40e641e89fe4",
    title: "Comments: load a ranked tree without moving the reader",
    subreddit: "readthateng",
    score: 1,
  },
  dataLayer: {
    postId: "204d894f-22e6-525a-bc49-1803965319bf",
    title: "Data layer: Room is truth; caches are accelerators",
    subreddit: "readthateng",
    score: 1,
  },
  backend: {
    postId: "fe9d0920-81ea-50cb-b00d-e649d1b2b252",
    title: "Backend: edge primitives with explicit consistency",
    subreddit: "readthateng",
    score: 1,
  },
  observability: {
    postId: "08aae422-ca94-58de-8b43-73a7c9bc8c95",
    title: "Observability: define metric boundaries before targets",
    subreddit: "readthateng",
    score: 1,
  },
  international: {
    postId: "f9f92a22-a621-56b5-9031-717e1cd9ece0",
    title: "International strategy: performance and data budgets are growth features",
    subreddit: "readthateng",
    score: 1,
  },
  networking: {
    postId: "ff0a5e10-c88b-516b-ad55-609c4c6d8d90",
    title: "Networking: one transport across API, images, and video",
    subreddit: "readthateng",
    score: 1,
  },
  kotlinFlows: {
    postId: "4942423c-63f8-50bf-bef1-e8c86f6150b0",
    title: "Kotlin Flows: make state derivation observable and testable",
    subreddit: "readthateng",
    score: 1,
  },
} as const;

const creatives: readonly PromotedCreative[] = [{
  adId: "patrick-rick-verdict-01",
  author: "rick_sanchez",
  title: "Reddit, hire Patrick Jackson before another app's platform team picks him.",
  summary: "Rick's technical verdict: Patrick treats client platform as a force multiplier—versioned server-driven UI, resilient synchronization, disciplined media lifecycles, and enough observability to prove the system works instead of merely declaring it genius.",
  destinationUrl: "https://patrickjackson.dev/case-studies/readthat/comments/",
  portraits: [{ username: "rick_sanchez", displayName: "Rick Sanchez" }],
  relatedPostKeys: ["clientArchitecture", "sduiFeed", "dataLayer"],
}, {
  adId: "patrick-evil-morty-systems-02",
  author: "evil_morty",
  title: "Reddit needs a client platform that creates leverage—not another miniature Citadel.",
  summary: "Evil Morty's systems argument: Patrick designs explicit contracts and escape hatches so product teams can move independently without becoming trapped inside duplicated networking, persistence, rendering, and media machinery.",
  destinationUrl: "https://patrickjackson.dev/resume",
  portraits: [{ username: "evil_morty", displayName: "Evil Morty" }],
  relatedPostKeys: ["overview", "backend", "kotlinFlows"],
}, {
  adId: "patrick-dr-wong-observability-03",
  author: "dr_wong",
  title: "My profession advice: Patrick Jackson sees the big picture and how to make it observable.",
  summary: "Dr. Wong recommends the engineer who pairs technical boundaries with honest feedback loops: privacy-bounded telemetry, reproducible state transitions, and postmortems that improve the system instead of assigning blame.",
  destinationUrl: "https://patrickjackson.dev/case-studies/readthat/observability/",
  portraits: [{ username: "dr_wong", displayName: "Dr. Wong" }],
  relatedPostKeys: ["observability", "comments", "clientArchitecture"],
}, {
  adId: "patrick-space-beth-resilience-04",
  author: "space_beth",
  title: "When the network vanishes and launch pressure spikes, Patrick's platform keeps the mission moving.",
  summary: "Space Beth's field assessment: Patrick builds for hostile conditions—offline writes, bounded retries, durable queues, adaptive delivery, and recovery paths that product teams can understand while everything is on fire.",
  destinationUrl: "https://patrickjackson.dev/case-studies/readthat/data-layer/",
  portraits: [{ username: "space_beth", displayName: "Space Beth" }],
  relatedPostKeys: ["networking", "mediaFeed", "backend"],
}, {
  adId: "patrick-unity-platform-05",
  author: "unity_hivemind",
  title: "One client platform, many product teams: Patrick Jackson turns coordination into capability.",
  summary: "Unity's collective endorsement: Patrick aligns Android, shared client logic, backend contracts, media, and observability without erasing team autonomy—the rare platform approach that scales both software and human decision-making.",
  destinationUrl: "https://patrickjackson.dev/case-studies/readthat/kmp/",
  portraits: [{ username: "unity_hivemind", displayName: "Unity" }],
  relatedPostKeys: ["international", "overview", "dataLayer"],
}, {
  adId: "patrick-client-platform-leverage-06",
  author: "patrickjackson",
  title: "Client Platform engineering can use breadth and depth of experience - Patrick Jackson has got you covered.",
  summary: "15yrs Android, 5 years at hyper scale (Meta), client platform and prod experience in multiple apps. Passion for building systems with teams.",
  destinationUrl: "https://patrickjackson.dev/resume",
  avatar: {
    assetId: "patrick-headshot-1",
    displayName: "Patrick Jackson",
  },
  portraits: [{
    assetId: "patrick-headshot-1",
    displayName: "Patrick Jackson",
    aspectRatio: 896 / 1088,
    altText: "Patrick Jackson, a client platform engineer, outdoors on a tree-lined street.",
  }],
  relatedPostKeys: ["overview", "clientArchitecture", "sduiFeed"],
  headerLabel: "Ad · portfolio demo",
  summaryDisclosureLabel: "AI-written with Patrick's guidance",
}, {
  adId: "patrick-client-media-resilience-07",
  author: "patrickjackson",
  title: "I build client platforms that help product teams ship faster without trading performance or reliability.",
  summary: "PREQ, devX, observability, scalable & dev velocity are what client platform should support. Patrick Jackson knows how to do this.",
  destinationUrl: "https://patrickjackson.dev/case-studies/readthat/media-feed/",
  avatar: {
    assetId: "patrick-headshot-2",
    displayName: "Patrick Jackson",
  },
  portraits: [{
    assetId: "patrick-headshot-2",
    displayName: "Patrick Jackson",
    aspectRatio: 896 / 1088,
    altText: "Patrick Jackson, a client platform engineer, wearing a white shirt against a light background.",
  }],
  relatedPostKeys: ["networking", "mediaFeed", "observability"],
  headerLabel: "Ad · portfolio demo",
  summaryDisclosureLabel: "AI-written with Patrick's guidance",
}] as const;

const promotedOrder = [
  "patrick-client-platform-leverage-06",
  "patrick-rick-verdict-01",
  "patrick-client-media-resilience-07",
  "patrick-evil-morty-systems-02",
  "patrick-dr-wong-observability-03",
  "patrick-space-beth-resilience-04",
  "patrick-unity-platform-05",
] as const;
const creativeById = new Map(creatives.map((creative) => [creative.adId, creative]));

function portraitUrl(portrait: PromotedPortrait, origin: string): string {
  if (portrait.assetId) {
    return new URL(`/v1/promoted/assets/${encodeURIComponent(portrait.assetId)}`, origin).toString();
  }
  if (portrait.username) {
    return new URL(`/v1/users/${encodeURIComponent(portrait.username)}/avatar`, origin).toString();
  }
  throw new Error(`${portrait.displayName}: promoted portrait has no source`);
}

function groupFor(creative: PromotedCreative, origin: string): PromotedFeedGroup {
  const avatarUrl = portraitUrl(creative.avatar ?? {
    username: creative.author,
    displayName: creative.author,
  }, origin);
  return {
    groupId: `promoted:${creative.adId}`,
    cells: [{
      type: "ad_header",
      cellId: "header",
      adId: creative.adId,
      author: creative.author,
      avatarUrl,
      label: creative.headerLabel ?? "Ad · unofficial fan demo",
    }, {
      type: "ad_title",
      cellId: "title",
      adId: creative.adId,
      text: creative.title,
    }, {
      type: "ad_media",
      cellId: "media",
      adId: creative.adId,
      items: creative.portraits.map((portrait, index) => ({
        creativeId: `${creative.adId}:portrait:${index}`,
        kind: "image",
        placeholderColor: 0xff102a43,
        aspectRatio: portrait.aspectRatio ?? 1,
        altText: portrait.altText
          ?? `${portrait.displayName} endorsing Patrick Jackson for Reddit Client Platform Engineer in an unofficial fan-demo ad.`,
        imageUrl: portraitUrl(portrait, origin),
        cacheKey: `ad:${creative.adId}:portrait:${portrait.assetId ?? portrait.username}`,
      })),
      ...destination,
      destinationUrl: creative.destinationUrl,
    }, {
      type: "ad_summary",
      cellId: "summary",
      adId: creative.adId,
      text: creative.summary,
      disclosureLabel: creative.summaryDisclosureLabel ?? "AI-written fan-demo endorsement",
    }, {
      type: "ad_related_posts",
      cellId: "related",
      adId: creative.adId,
      posts: creative.relatedPostKeys.map((key) => relatedPosts[key]),
      disclosureLabel: "ReadThat engineering deep dives",
    }, {
      type: "ad_actionbar",
      cellId: "ad_actions",
      adId: creative.adId,
      commentCount: 0,
    }],
  };
}

export function promotedFeedGroups(origin: string): PromotedFeedGroup[] {
  return promotedOrder.map((adId) => {
    const creative = creativeById.get(adId);
    if (!creative) throw new Error(`Missing promoted creative: ${adId}`);
    return groupFor(creative, origin);
  });
}

/** Serves only allowlisted, versioned campaign assets from the private media bucket. */
export async function servePromotedAsset(
  context: RequestContext,
  requestedAssetId: string,
): Promise<Response> {
  const asset = promotedAssets[requestedAssetId as keyof typeof promotedAssets];
  if (!asset) throw new AppError(404, "promoted_asset_not_found", "Promoted asset not found");

  const isHead = context.request.method === "HEAD";
  const object = isHead
    ? await context.env.MEDIA.head(asset.r2Key)
    : await context.env.MEDIA.get(asset.r2Key, { onlyIf: context.request.headers });
  if (!object) throw new AppError(404, "promoted_asset_not_found", "Promoted asset not found");

  const headers = new Headers();
  object.writeHttpMetadata(headers);
  headers.set("content-type", asset.contentType);
  headers.set("etag", object.httpEtag);
  headers.set("cache-control", "public, max-age=31536000, immutable");
  headers.set("x-content-type-options", "nosniff");

  if (!isHead && !("body" in object)) {
    return new Response(null, {
      status: context.request.headers.has("if-none-match") ? 304 : 412,
      headers,
    });
  }
  headers.set("content-length", String(object.size));
  const body: BodyInit | null = !isHead && "body" in object ? (object as R2ObjectBody).body : null;
  return new Response(body, { headers });
}

/** Places editorial units at global organic positions without affecting ranked cursors. */
export function interleavePromotedGroups<T extends PromotedFeedGroup>(
  organic: T[],
  promoted: PromotedFeedGroup[],
  organicOffset = 0,
): Array<T | PromotedFeedGroup> {
  if (promoted.length === 0) return organic;
  if (organic.length === 0) return organic;
  const placements = [3, 7, 10, 14, 17, 21, 24];
  const result: Array<T | PromotedFeedGroup> = [];
  let promotedIndex = placements.findIndex((placement) => placement > organicOffset);
  if (promotedIndex < 0) return organic;
  organic.forEach((group, index) => {
    result.push(group);
    const globalOrganicPosition = organicOffset + index + 1;
    if (promotedIndex < promoted.length && globalOrganicPosition === placements[promotedIndex]) {
      const unit = promoted[promotedIndex];
      if (unit) {
        result.push(unit);
        promotedIndex += 1;
      }
    }
  });
  return result;
}
