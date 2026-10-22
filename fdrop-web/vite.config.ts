import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// The build lands in target/classes/static, which Maven packages into the fdrop-web jar.
// `npm run dev` serves on 5173 and proxies the API and login routes to the fdrop server on 8080.
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
    proxy: {
      "/api": "http://localhost:8080",
      "/auth": "http://localhost:8080",
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["src/test/setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
