import { describe, expect, it } from "vitest";
import { flattenSearchSections } from "./api";
import { collapsedCommentCountLabel, loadedTree, mergeGroups, postGroup, replaceCursor, updateNode } from "./logic";
import type { FeedGroup, LoadMoreNode, Post } from "./types";

const group = (groupId: string, title: string): FeedGroup => ({
  groupId,
  cells: [{ type: "title", cellId: `title-${groupId}`, text: title }],
});

describe("feed pagination", () => {
  it("deduplicates cursor overlap while preserving feed order", () => {
    const merged = mergeGroups(
      [group("a", "first"), group("b", "stale")],
      [group("b", "fresh"), group("c", "third")],
    );

    expect(merged.map(({ groupId }) => groupId)).toEqual(["a", "b", "c"]);
    expect(merged[1]?.cells[0]).toMatchObject({ type: "title", text: "fresh" });
  });
});

describe("search sections", () => {
  it("does not render a media-backed post twice", () => {
    const post = {
      type: "post" as const, id: "video-post", subreddit: "videos", author: "tester", kind: "video" as const,
      title: "Adaptive video", body: null, url: null, score: 1, commentCount: 0, viewerVote: 0 as const, createdAt: 1,
    };
    const flattened = flattenSearchSections({ posts: [post], media: [post], comments: [] });

    expect(flattened).toEqual([post]);
  });
});

describe("comment tree updates", () => {
  const comments = [
    { id: "root", parentId: null, author: "u/root", body: "Root", score: 3, viewerVote: 0 as const, createdAt: 1, createdAgoMin: 1, descendantCount: 1 },
    { id: "child", parentId: "root", author: "u/child", body: "Child", score: 2, viewerVote: 0 as const, createdAt: 2, createdAgoMin: 1, descendantCount: 0 },
  ];
  const cursor: LoadMoreNode = { type: "load_more", id: "more", parentId: "root", remainingCount: 2, childIds: ["later"] };

  it("reconstructs nesting and attaches branch cursors", () => {
    const tree = loadedTree(comments, [cursor]);
    expect(tree).toHaveLength(1);
    expect(tree[0]).toMatchObject({ type: "comment", id: "root" });
    expect(tree[0]?.type === "comment" ? tree[0].children.map(({ id }) => id) : []).toEqual(["child", "more"]);
  });

  it("replaces a nested cursor and updates only the selected comment", () => {
    const initial = loadedTree(comments, [cursor]);
    const replacement = loadedTree([
      { id: "later", parentId: null, author: "u/later", body: "Later", score: 1, viewerVote: 0, createdAt: 3, createdAgoMin: 0, descendantCount: 0 },
    ], []);
    const expanded = replaceCursor(initial, "more", replacement);
    const voted = updateNode(expanded, "child", (node) => ({ ...node, score: node.score + 1, viewerVote: 1 }));
    const root = voted[0];

    expect(root?.type === "comment" ? root.children.map(({ id }) => id) : []).toEqual(["child", "later"]);
    expect(root?.type === "comment" ? root.descendantCount : 0).toBe(2);
    expect(root?.type === "comment" && root.children[0]?.type === "comment" ? root.children[0].score : 0).toBe(3);
  });

  it("formats the server-authored collapse count without walking the tree", () => {
    expect(collapsedCommentCountLabel(0)).toBeNull();
    expect(collapsedCommentCountLabel(1)).toBe("Show 1 hidden reply");
    expect(collapsedCommentCountLabel(15)).toBe("Show 15 hidden replies");
  });
});

describe("post SDUI projection", () => {
  it("turns a ready video post into metadata, media, and action cells", () => {
    const post: Post = {
      id: "post-1", subreddit: "videos", author: "u/tester", authorId: "user-1", kind: "video", title: "A clip",
      body: null, url: null, crosspostParentId: null, score: 7, upvotes: 8, downvotes: 1, commentCount: 4,
      viewerVote: 1, version: 3, createdAt: 1_700_000_000_000, updatedAt: 1_700_000_000_000,
      media: {
        id: "media-1", contentType: "video/mp4", width: 1920, height: 1080, durationSeconds: 12, altText: "A test clip",
        url: null, hlsUrl: "https://example.test/video.m3u8", dashUrl: null, posterUrl: "https://example.test/poster.jpg",
        previewUrl: null, fallbackUrl: "https://example.test/video.mp4", deliveryStatus: "ready", processingProgress: 100,
        cachePolicy: "segments_only", cacheKey: "media-1-v1",
      },
    };

    const projected = postGroup(post);
    expect(projected.groupId).toBe(post.id);
    expect(projected.cells.map(({ type }) => type)).toEqual(["metadata", "title", "video", "actionbar"]);
    expect(projected.cells[2]).toMatchObject({ type: "video", aspectRatio: 16 / 9, cachePolicy: "segments_only" });
  });

  it("projects an ordered typed photo gallery into one carousel cell", () => {
    const image = (id: string, width: number, height: number) => ({
      id, contentType: "image/png", width, height, durationSeconds: null, altText: id,
      url: `https://example.test/${id}.png`, zoomUrl: `https://example.test/${id}-detail.png`,
      hlsUrl: null, dashUrl: null, posterUrl: null, previewUrl: null, fallbackUrl: null,
      deliveryStatus: "ready", processingProgress: 100, cachePolicy: "private_immutable", cacheKey: `image:${id}`,
    });
    const gallery = [image("one", 1200, 900), image("two", 900, 1200)];
    const post: Post = {
      id: "gallery", subreddit: "pics", author: "u/tester", authorId: "user-1", kind: "image", title: "Photos",
      body: null, url: null, crosspostParentId: null, score: 1, upvotes: 1, downvotes: 0, commentCount: 0,
      viewerVote: 0, version: 1, createdAt: 1_700_000_000_000, updatedAt: 1_700_000_000_000,
      media: gallery[0]!, mediaItems: gallery,
    };

    const projected = postGroup(post);

    expect(projected.cells.map(({ type }) => type)).toEqual(["metadata", "title", "image_carousel", "actionbar"]);
    expect(projected.cells[2]).toMatchObject({
      type: "image_carousel",
      items: [{ mediaId: "one" }, { mediaId: "two" }],
    });
  });
});
