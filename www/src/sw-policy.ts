interface ImageRequestLike {
  method: string;
  destination: string;
}

/**
 * Only same-origin image responses are safe for the runtime CacheFirst route.
 * CDN images are opaque to the service worker and already carry their own
 * Cloudflare cache policy, so they should go directly through the browser.
 */
export function shouldRuntimeCacheImage(
  request: ImageRequestLike,
  url: URL,
  serviceWorkerOrigin: string,
): boolean {
  return request.method === "GET"
    && request.destination === "image"
    && url.origin === serviceWorkerOrigin;
}
