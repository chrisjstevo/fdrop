import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end tests against a running stack (fdrop on 8080, Keycloak on 8180 with the dev realm), e.g. inside the
 * dev container after `java -jar fdrop-server/target/fdrop-server.jar`. Every test runs at phone, tablet and
 * desktop sizes. Run with `npm run e2e`; set FDROP_URL to test another address.
 */
export default defineConfig({
  testDir: "e2e",
  timeout: 60_000,
  retries: process.env.CI ? 1 : 0,
  use: {
    baseURL: process.env.FDROP_URL ?? "http://localhost:8080",
    trace: "retain-on-failure",
  },
  projects: [
    { name: "phone", use: { ...devices["Pixel 7"], viewport: { width: 390, height: 844 } } },
    { name: "tablet", use: { viewport: { width: 820, height: 1180 }, hasTouch: true } },
    { name: "desktop", use: { viewport: { width: 1440, height: 900 } } },
  ],
});
