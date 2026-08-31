import type { CommentNode, FeedCell, FeedGroup, LoadMoreNode, Post, TreeNode } from "./types";
import { formatRelative } from "./ui";

export function mergeGroups(previous: FeedGroup[], incoming: FeedGroup[]): FeedGroup[] {
  const byId = new Map(previous.map((group) => [group.groupId, group]));
  incoming.forEach((group) => byId.set(group.groupId, group));
  return [...byId.values()];
}

export function postGroup(post: Post): FeedGroup {
  const cells: FeedCell[] = [
    { type: "metadata", cellId: "meta", subreddit: post.subreddit, author: post.author, postedAgo: formatRelative(post.createdAt), createdAt: post.createdAt, pinned: false },
    { type: "title", cellId: "title", text: post.title },
  ];
  if (post.crosspostParentId) cells.push({ type: "announcement", cellId: "crosspost", text: `Reshared from post ${post.crosspostParentId}`, sourcePostId: post.crosspostParentId });
  if (post.kind === "text" && post.body) cells.push({ type: "text", cellId: "body", body: post.body, maxLines: 100_000 });
  if (post.kind === "link" && post.url) cells.push({ type: "link", cellId: "link", url: post.url, domain: new URL(post.url).hostname });
  const images = post.mediaItems?.length ? post.mediaItems : post.media ? [post.media] : [];
  if (post.kind === "image" && images.length > 1) cells.push({
    type: "image_carousel",
    cellId: "media",
    items: images.map((image) => ({
      mediaId: image.id,
      url: image.url,
      zoomUrl: image.zoomUrl ?? image.url,
      cacheKey: image.cacheKey,
      placeholderColor: 0x23386b,
      aspectRatio: (image.width && image.height) ? image.width / image.height : 16 / 9,
      altText: image.altText ?? "",
      width: image.width,
      height: image.height,
    })),
  });
  else if (post.kind === "image" && post.media?.url) cells.push({ type: "image", cellId: "media", url: post.media.url, cacheKey: post.media.cacheKey ?? post.media.id, placeholderColor: 0x23386b, aspectRatio: (post.media.width && post.media.height) ? post.media.width / post.media.height : 16 / 9, altText: post.media.altText ?? "" });
  if (post.kind === "video" && post.media) cells.push({ type: "video", cellId: "media", url: post.media.url, hlsUrl: post.media.hlsUrl, dashUrl: post.media.dashUrl, posterUrl: post.media.posterUrl, previewUrl: post.media.previewUrl, fallbackUrl: post.media.fallbackUrl, deliveryStatus: post.media.deliveryStatus as "waiting" | "processing" | "ready" | "error" | "not_applicable", processingProgress: post.media.processingProgress, cachePolicy: "segments_only", placeholderColor: 0x0045ac, aspectRatio: (post.media.width && post.media.height) ? post.media.width / post.media.height : 16 / 9, durationSeconds: post.media.durationSeconds ?? 0, altText: post.media.altText ?? "" });
  cells.push({ type: "actionbar", cellId: "actions", score: post.score, commentCount: post.commentCount, liked: post.viewerVote === 1, vote: post.viewerVote, version: post.version });
  return { groupId: post.id, cells };
}

export function updateNode(nodes: TreeNode[], id: string, update: (node: CommentNode) => CommentNode): TreeNode[] {
  return nodes.map((node) => {
    if (node.type !== "comment") return node;
    if (node.id === id) return withAccurateDescendantCount(update(node));
    return withAccurateDescendantCount({ ...node, children: updateNode(node.children, id, update) });
  });
}

export function replaceCursor(nodes: TreeNode[], cursorId: string, replacement: TreeNode[]): TreeNode[] {
  return nodes.flatMap((node) => {
    if (node.id === cursorId) return replacement;
    if (node.type !== "comment") return [node];
    return [withAccurateDescendantCount({
      ...node,
      children: replaceCursor(node.children, cursorId, replacement),
    })];
  });
}

function withAccurateDescendantCount(node: CommentNode): CommentNode {
  const descendantCount = node.children.reduce((total, child) =>
    total + (child.type === "comment" ? 1 + child.descendantCount : 0), 0);
  return descendantCount === node.descendantCount ? node : { ...node, descendantCount };
}

export function collapsedCommentCountLabel(count: number): string | null {
  if (count === 1) return "Show 1 hidden reply";
  return count > 1 ? `Show ${count.toLocaleString()} hidden replies` : null;
}

export function loadedTree(
  comments: Array<Omit<CommentNode, "type" | "children"> & { parentId: string | null }>,
  cursors: LoadMoreNode[],
): TreeNode[] {
  const map = new Map<string, CommentNode>();
  comments.forEach((comment) => map.set(comment.id, { ...comment, type: "comment", children: [] }));
  const roots: TreeNode[] = [];
  comments.forEach((comment) => {
    const node = map.get(comment.id);
    if (!node) return;
    const parent = comment.parentId ? map.get(comment.parentId) : null;
    if (parent) parent.children.push(node); else roots.push(node);
  });
  cursors.forEach((cursor) => {
    const parent = cursor.parentId ? map.get(cursor.parentId) : null;
    if (parent) parent.children.push(cursor); else roots.push(cursor);
  });
  return roots;
}
