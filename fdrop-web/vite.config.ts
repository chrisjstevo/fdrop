import { existsSync, readFileSync } from "node:fs";
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// The build lands in target/classes/static, which Maven packages into the fdrop-web jar.
// `npm run dev` serves https://localhost:5173 with the dev certificates the Compose `certs` step makes, and proxies
// the API and login routes to the fdrop server on https://localhost:8443.
const certs = new URL("../.devcontainer/certs/", import.meta.url);
const https = existsSync(new URL("server.crt", certs))
  ? { cert: readFileSync(new URL("server.crt", certs)), key: readFileSync(new URL("server.key", certs)) }
  : undefined;
// fdrop's certificate is from the dev CA, which Node does not trust; this proxy only ever talks to localhost
const fdrop = { target: "https://localhost:8443", secure: false };

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "target/classes/static",
    emptyOutDir: true,
    assetsDir: "assets",
    sourcemap: false,
  },
  server: {
    port: 5173,
    strictPort: true,
    https,
    proxy: {
      "/api": fdrop,
      "/auth": fdrop,
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["src/test/setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
