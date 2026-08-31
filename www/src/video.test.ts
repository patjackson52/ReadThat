import { describe, expect, it } from "vitest";
import { formatVideoElapsed } from "./video";

describe("video transport", () => {
  it("formats compact elapsed time", () => {
    expect(formatVideoElapsed(-1)).toBe("0:00");
    expect(formatVideoElapsed(3.9)).toBe("0:03");
    expect(formatVideoElapsed(65)).toBe("1:05");
    expect(formatVideoElapsed(3_723)).toBe("1:02:03");
  });
});
