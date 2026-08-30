/// <reference lib="webworker" />
import { clientsClaim, setCacheNameDetails } from "workbox-core";
import { ExpirationPlugin } from "workbox-expiration";
import { cleanupOutdatedCaches, createHandlerBoundToURL, precacheAndRoute } from "workbox-precaching";
import { NavigationRoute, registerRoute } from "workbox-routing";
import { CacheFirst } from "workbox-strategies";
import { shouldRuntimeCacheImage } from "./sw-policy";

declare const self: ServiceWorkerGlobalScope & { __WB_MANIFEST: Array<import("workbox-build").ManifestEntry> };

setCacheNameDetails({ prefix: "readthat", precache: "shell", runtime: "runtime" });
precacheAndRoute(self.__WB_MANIFEST);
cleanupOutdatedCaches();
clientsClaim();

registerRoute(new NavigationRoute(createHandlerBoundToURL("index.html"), {
  denylist: [/^\/v1\//u, /^\/health$/u],
}));

registerRoute(
  ({ request, url }) => shouldRuntimeCacheImage(request, url, self.location.origin),
  new CacheFirst({
    cacheName: "readthat-images-v1",
    plugins: [new ExpirationPlugin({ maxEntries: 180, maxAgeSeconds: 30 * 24 * 60 * 60, purgeOnQuotaError: true })],
  }),
);

const VIDEO_CACHE = "readthat-video-segments-v1";
let videoBudgetBytes = 64 * 1024 * 1024;

interface VideoCacheRecord { url: string; byteSize: number; lastAccessed: number }

function openVideoDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open("readthat-video-cache", 1);
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains("segments")) {
        const store = request.result.createObjectStore("segments", { keyPath: "url" });
        store.createIndex("last-accessed", "lastAccessed");
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function videoRecords(): Promise<VideoCacheRecord[]> {
  const database = await openVideoDatabase();
  return new Promise((resolve, reject) => {
    const transaction = database.transaction("segments", "readonly");
    const request = transaction.objectStore("segments").getAll();
    request.onsuccess = () => resolve(request.result as VideoCacheRecord[]);
    request.onerror = () => reject(request.error);
    transaction.oncomplete = () => database.close();
  });
}

async function putVideoRecord(record: VideoCacheRecord): Promise<void> {
  const database = await openVideoDatabase();
  await new Promise<void>((resolve, reject) => {
    const transaction = database.transaction("segments", "readwrite");
    transaction.objectStore("segments").put(record);
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error);
  });
  database.close();
}

async function deleteVideoRecord(url: string): Promise<void> {
  const database = await openVideoDatabase();
  await new Promise<void>((resolve, reject) => {
    const transaction = database.transaction("segments", "readwrite");
    transaction.objectStore("segments").delete(url);
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error);
  });
  database.close();
}

async function pruneVideoCache(cache: Cache): Promise<void> {
  const records = (await videoRecords()).sort((left, right) => left.lastAccessed - right.lastAccessed);
  let total = records.reduce((sum, record) => sum + record.byteSize, 0);
  for (const record of records) {
    if (total <= videoBudgetBytes) break;
    await cache.delete(record.url);
    await deleteVideoRecord(record.url);
    total -= record.byteSize;
  }
}

function isVideoSegment(url: URL): boolean {
  const path = url.pathname.toLowerCase();
  if (path.endsWith(".m3u8") || path.endsWith(".mpd")) return false;
  return /\.(?:m4s|ts|cmfv|cmfa|aac)(?:$|\?)/u.test(`${path}${url.search}`);
}

registerRoute(
  ({ request, url }) => request.method === "GET" && !request.headers.has("range") && isVideoSegment(url),
  async ({ request }) => {
    const cache = await caches.open(VIDEO_CACHE);
    const cached = await cache.match(request);
    if (cached) {
      const length = Number(cached.headers.get("content-length") ?? "0");
      const prior = (await videoRecords()).find((record) => record.url === request.url);
      await putVideoRecord({ url: request.url, byteSize: prior?.byteSize ?? (Number.isFinite(length) ? length : 0), lastAccessed: Date.now() });
      return cached;
    }
    const response = await fetch(request);
    const byteSize = Number(response.headers.get("content-length") ?? "0");
    if (response.status === 200 && Number.isSafeInteger(byteSize) && byteSize > 0 && byteSize <= videoBudgetBytes) {
      await cache.put(request, response.clone());
      await putVideoRecord({ url: request.url, byteSize, lastAccessed: Date.now() });
      await pruneVideoCache(cache);
    }
    return response;
  },
);

self.addEventListener("message", (event: ExtendableMessageEvent) => {
  const data = event.data as { type?: string; bytes?: number } | undefined;
  if (data?.type === "SKIP_WAITING") {
    event.waitUntil(self.skipWaiting());
    return;
  }
  if (data?.type === "VIDEO_CACHE_BUDGET" && Number.isSafeInteger(data.bytes) && (data.bytes ?? 0) > 0) {
    videoBudgetBytes = Math.min(384 * 1024 * 1024, data.bytes ?? videoBudgetBytes);
  }
});

interface BackgroundSyncEvent extends ExtendableEvent { tag: string }

self.addEventListener("sync", (event) => {
  const syncEvent = event as BackgroundSyncEvent;
  if (syncEvent.tag !== "readthat-outbox") return;
  syncEvent.waitUntil(self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((clients) => {
    clients.forEach((client) => client.postMessage({ type: "FLUSH_OUTBOX" }));
  }));
});
