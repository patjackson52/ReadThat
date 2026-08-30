import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      strategies: "injectManifest",
      srcDir: "src",
      filename: "sw.ts",
      registerType: "prompt",
      injectRegister: false,
      manifest: false,
      injectManifest: {
        globPatterns: ["**/*.{js,css,html,svg,png,webmanifest,woff2}"],
        // HLS support is intentionally lazy. Precaching its large parser would make
        // every installation pay the video cost before the first video is viewed.
        globIgnores: ["**/hls-*.js"],
        maximumFileSizeToCacheInBytes: 4 * 1024 * 1024,
      },
      devOptions: { enabled: false },
    }),
  ],
  server: {
    port: 8080,
    proxy: {
      "/v1": {
        target: process.env.VITE_API_PROXY ?? "http://127.0.0.1:8788",
        changeOrigin: true,
      },
      "/health": {
        target: process.env.VITE_API_PROXY ?? "http://127.0.0.1:8788",
        changeOrigin: true,
      },
    },
  },
  build: {
    target: "es2022",
    sourcemap: false,
  },
  test: {
    environment: "jsdom",
    setupFiles: "./src/test/setup.ts",
    css: true,
  },
});
