import { describe, expect, it } from "vitest";
import { shouldRuntimeCacheImage } from "./sw-policy";

const imageRequest = { method: "GET", destination: "image" };
const origin = "https://readthat-api.example";

describe("service-worker image caching policy", () => {
  it("caches same-origin images", () => {
    expect(shouldRuntimeCacheImage(imageRequest, new URL(`${origin}/images/avatar.jpg`), origin)).toBe(true);
  });

  it("leaves signed Cloudflare Images URLs on the browser network path", () => {
    const signedImage = new URL("https://imagedelivery.net/account/image/detail?exp=123&sig=abc");
    expect(shouldRuntimeCacheImage(imageRequest, signedImage, origin)).toBe(false);
  });

  it("rejects non-image and non-GET requests", () => {
    expect(shouldRuntimeCacheImage({ method: "GET", destination: "video" }, new URL(`${origin}/clip.mp4`), origin)).toBe(false);
    expect(shouldRuntimeCacheImage({ method: "POST", destination: "image" }, new URL(`${origin}/image`), origin)).toBe(false);
  });
});
