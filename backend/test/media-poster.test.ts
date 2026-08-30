import { describe, expect, it } from "vitest";
import { optimizedStreamPosterUrl, streamThumbnailTimestampPct } from "../src/media";

describe("Stream video posters", () => {
  it("selects an early non-zero thumbnail without seeking deep into long videos", () => {
    expect(streamThumbnailTimestampPct(null)).toBe(0.2);
    expect(streamThumbnailTimestampPct(5)).toBe(0.2);
    expect(streamThumbnailTimestampPct(100)).toBe(0.05);
  });

  it("creates an aspect-correct bounded poster URL for existing Stream videos", () => {
    const poster = new URL(optimizedStreamPosterUrl(
      "https://customer.example.cloudflarestream.com/video/thumbnails/thumbnail.jpg",
      1_920,
      1_080,
      30,
    )!);

    expect(poster.searchParams.get("time")).toBe("5s");
    expect(poster.searchParams.get("width")).toBe("1080");
    expect(poster.searchParams.get("height")).toBe("608");
    expect(poster.searchParams.get("fit")).toBe("crop");
  });

  it("leaves non-Stream poster URLs unchanged", () => {
    const url = "https://example.com/poster.jpg";
    expect(optimizedStreamPosterUrl(url, 1_920, 1_080, 30)).toBe(url);
  });
});
