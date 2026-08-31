import { describe, expect, it } from "vitest";
import {
  MEDIA_FEED_PATTERN,
  mergeShowcaseLanes,
  STANDARD_FEED_PATTERN,
} from "../src/feed-showcase";

function items(prefix: string, count: number, rankStart: number) {
  return Array.from({ length: count }, (_, index) => ({
    id: `${prefix}-${index + 1}`,
    rank_value: rankStart - index,
  }));
}

describe("showcase feed composition", () => {
  it("alternates image, text, and video lanes while preserving rank within each lane", () => {
    const merged = mergeShowcaseLanes({
      image: items("image", 4, 300),
      video: items("video", 4, 200),
      other: items("other", 6, 400),
    }, STANDARD_FEED_PATTERN, 0, 11);

    expect(merged.items.map((item) => item.id)).toEqual([
      "image-1", "other-1", "video-1", "other-2", "image-2", "other-3", "video-2",
      "image-3", "other-4", "video-3", "other-5",
    ]);
    expect(merged.consumedLast).toMatchObject({
      image: { id: "image-3" },
      video: { id: "video-3" },
      other: { id: "other-5" },
    });
    expect(merged.hasMore).toBe(true);
  });

  it("places videos at alternating three/four media-feed intervals", () => {
    const merged = mergeShowcaseLanes({
      image: items("image", 8, 300),
      video: items("video", 4, 200),
    }, MEDIA_FEED_PATTERN, 0, 12);

    expect(merged.items.map((item) => item.id)).toEqual([
      "image-1", "image-2", "video-1", "image-3", "image-4", "image-5", "video-2",
      "image-6", "image-7", "video-3", "image-8", "video-4",
    ]);
  });

  it("fills sparse feeds from the highest-ranked available lane", () => {
    const merged = mergeShowcaseLanes({
      image: [],
      video: items("video", 2, 200),
      other: items("other", 3, 300),
    }, STANDARD_FEED_PATTERN, 0, 5);

    expect(merged.items.map((item) => item.id)).toEqual([
      "other-1", "other-2", "video-1", "other-3", "video-2",
    ]);
    expect(merged.hasMore).toBe(false);
  });
});
