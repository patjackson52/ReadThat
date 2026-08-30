import path from "node:path";
import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [
    cloudflareTest(async () => {
      const migrations = await readD1Migrations(path.join(import.meta.dirname, "migrations"));
      return {
        miniflare: {
          bindings: {
            AUTH_PEPPER: "test-only-auth".padEnd(32, "x"),
            ANALYTICS_ID_PEPPER: "test-only-analytics".padEnd(32, "x"),
            CURSOR_SECRET: "test-only-cursor".padEnd(32, "x"),
            MEDIA_SIGNING_SECRET: "test-only-media".padEnd(32, "x"),
            VIDEO_TRANSCODING: "passthrough",
            IMAGE_DELIVERY: "passthrough",
            IMAGES_ACCOUNT_HASH: "test-account-hash",
            IMAGES_SIGNING_KEY: "test-only-images".padEnd(32, "x"),
            STREAM_WEBHOOK_SECRET: "test-only-stream".padEnd(32, "x"),
            TEST_MIGRATIONS: migrations,
          },
        },
        wrangler: { configPath: "./wrangler.jsonc" },
      };
    }),
  ],
  test: { setupFiles: ["./test/apply-migrations.ts"] },
});
