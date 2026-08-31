const SAFE_ID = /^[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$/;

export function commentPermalink(postId: string, commentId: string): string {
  if (!SAFE_ID.test(postId) || !SAFE_ID.test(commentId)) throw new Error("Invalid permalink identifier");
  return `/post/${postId}/comment/${commentId}`;
}

export function focusedCommentId(
  pathCommentId?: string,
  search = "",
  hash = "",
): string | undefined {
  if (pathCommentId && SAFE_ID.test(pathCommentId)) return pathCommentId;
  const fromQuery = new URLSearchParams(search).get("commentId");
  if (fromQuery && SAFE_ID.test(fromQuery)) return fromQuery;
  const fromHash = hash.startsWith("#comment-") ? hash.slice("#comment-".length) : "";
  return SAFE_ID.test(fromHash) ? fromHash : undefined;
}
