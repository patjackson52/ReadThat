import { describe, expect, it } from "vitest";
import { optimizedStreamPosterUrl, streamThumbnailTimestampPct } from "../src/media";

describe("Stream video posters", () => {
  it("selects the playback start frame for every video duration", () => {
    expect(streamThumbnailTimestampPct(null)).toBe(0);
    expect(streamThumbnailTimestampPct(5)).toBe(0);
    expect(streamThumbnailTimestampPct(100)).toBe(0);
  });

  it("creates an aspect-correct bounded poster URL for existing Stream videos", () => {
    const poster = new URL(optimizedStreamPosterUrl(
      "https://customer.example.cloudflarestream.com/video/thumbnails/thumbnail.jpg",
      1_920,
      1_080,
      30,
    )!);

    expect(poster.searchParams.get("time")).toBe("0s");
    expect(poster.searchParams.get("width")).toBe("1080");
    expect(poster.searchParams.get("height")).toBe("608");
    expect(poster.searchParams.get("fit")).toBe("crop");
  });

  it("uses even dimensions accepted by Stream for awkward source aspect ratios", () => {
    const landscape = new URL(optimizedStreamPosterUrl(
      "https://customer.example.cloudflarestream.com/video/thumbnails/thumbnail.jpg",
      2_562,
      1_440,
      12,
    )!);
    const portrait = new URL(optimizedStreamPosterUrl(
      "https://customer.example.cloudflarestream.com/video/thumbnails/thumbnail.jpg",
      1_080,
      1_920,
      34,
    )!);

    expect(landscape.searchParams.get("width")).toBe("1080");
    expect(landscape.searchParams.get("height")).toBe("608");
    expect(portrait.searchParams.get("width")).toBe("608");
    expect(portrait.searchParams.get("height")).toBe("1080");
  });

  it("leaves non-Stream poster URLs unchanged", () => {
    const url = "https://example.com/poster.jpg";
    expect(optimizedStreamPosterUrl(url, 1_920, 1_080, 30)).toBe(url);
  });
});
