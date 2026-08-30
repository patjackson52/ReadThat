# ReadThat web PWA

`www` is the production React client for the SDUI Reddit sample. Cloudflare
Workers Static Assets serves the compiled app from the same origin as the API.

## Develop and verify

```bash
cd backend
npm run dev -- --port 8788

# second terminal
cd www
npm install
npm run dev

npm run check
npm test
npm run build
```

Set `VITE_API_PROXY=https://another-worker.example` when the development UI
should read from another API. The production client always uses same-origin
`/v1/*` routes and does not embed an environment-specific API URL.

Deploy from `backend` with `npm run deploy`. That command builds this directory
before Wrangler uploads both the immutable hashed assets and Worker code.

## Client policy

- Feed pages use signed cursor pagination and an intersection sentinel. Cached
  pages render immediately and refresh in the background.
- Sessions, D1 bookmarks, feeds, post details, and the mutation outbox are kept
  in IndexedDB. Authenticated writes are isolated by account and replay on
  reconnect; failed permanent commands remain inspectable instead of vanishing.
- SDUI rendering has an explicit cell allowlist. Post detail and comments keep
  their typed recursive domain model.
- Navigation opts into native view transitions and fully respects reduced
  motion. Layout, focus, touch targets, and navigation adapt at mobile widths.
- Safari uses native HLS. Other capable browsers lazy-load `hls.js`; only the
  most visible video plays and nearby media is attached conservatively.
- The service worker precaches the app shell and runtime-caches same-origin
  images. Signed Cloudflare Images URLs stay on the browser network path so
  opaque cross-origin responses do not pass through the service worker. It does
  not precache the lazy HLS parser or dynamic manifests. It caches those
  same-origin images and immutable video segments with quota-aware LRU limits,
  and never stores range responses, HLS manifests, or DASH manifests.
