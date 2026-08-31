import { describe, expect, it } from "vitest";
import { commentPermalink, focusedCommentId } from "./deep-links";

describe("comment deep links", () => {
  const postId = "610466c0-544f-518b-b536-4973bcfe8af9";
  const commentId = "59a7588e-832b-4286-a3b4-edb1f40cc561";

  it("builds the canonical route", () => {
    expect(commentPermalink(postId, commentId)).toBe(`/post/${postId}/comment/${commentId}`);
  });

  it("prefers a route parameter and supports existing query and fragment links", () => {
    expect(focusedCommentId(commentId, "?commentId=ignored", "#comment-ignored")).toBe(commentId);
    expect(focusedCommentId(undefined, `?commentId=${commentId}`)).toBe(commentId);
    expect(focusedCommentId(undefined, "", `#comment-${commentId}`)).toBe(commentId);
  });

  it("rejects unsafe or malformed identifiers", () => {
    expect(() => commentPermalink("../admin", commentId)).toThrow();
    expect(focusedCommentId("%2e%2e", "?commentId=%2e%2e", "#comment-")).toBeUndefined();
  });
});
