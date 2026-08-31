import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiClient } from "./api";

function json(value: unknown): Response {
  return new Response(JSON.stringify(value), { status: 200, headers: { "content-type": "application/json" } });
}

afterEach(() => {
  vi.unstubAllGlobals();
  sessionStorage.clear();
});

describe("current backend contracts", () => {
  it("opens a typed media feed at the selected post and keeps cursors opaque", async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(json({ schemaVersion: 1, feedId: "media:home", snapshotAt: 1, anchorIncluded: true, items: [], nextCursor: "opaque" }))
      .mockResolvedValueOnce(json({ schemaVersion: 1, feedId: "media:home", snapshotAt: 1, anchorIncluded: false, items: [], nextCursor: null }));
    vi.stubGlobal("fetch", fetch);
    const client = new ApiClient();

    await client.mediaFeed(null, { anchorPostId: "post one", subreddit: "photos" });
    await client.mediaFeed("opaque", { anchorPostId: "post one", subreddit: "photos" });

    expect(fetch.mock.calls[0]?.[0]).toBe("/v1/feeds/media?limit=8&subreddit=photos&anchorPostId=post+one");
    expect(fetch.mock.calls[1]?.[0]).toBe("/v1/feeds/media?limit=8&cursor=opaque&subreddit=photos");
  });

  it("sends the full search filter set and requests typeahead separately", async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(json({ query: "video", type: "media", items: [], nextCursor: null }))
      .mockResolvedValueOnce(json({ query: "vid", completions: ["video"], communities: [], profiles: [] }));
    vi.stubGlobal("fetch", fetch);
    const client = new ApiClient();

    await client.searchPage({ query: "video", type: "media", sort: "top", time: "week", safe: false });
    await client.typeahead("vid");

    expect(fetch.mock.calls[0]?.[0]).toBe("/v1/search?q=video&type=media&sort=top&time=week&safe=false&limit=20");
    expect(fetch.mock.calls[1]?.[0]).toBe("/v1/search/typeahead?q=vid&limit=8");
  });
});
