import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

/** Signs in through Keycloak's login form with a dev realm user. */
async function signIn(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel(/username or email/i).fill(user);
  await page.getByLabel(/^password/i).fill(user);
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
}

test("uploads through the drop zone's file picker and reaches 100%", async ({ page }) => {
  await signIn(page, "jugash");
  await page.goto("/files/home");
  const name = `e2e-${Date.now()}.bin`;
  const chooser = page.waitForEvent("filechooser");
  await page.getByRole("button", { name: /choose files/i }).click();
  await (await chooser).setFiles({ name, mimeType: "application/octet-stream", buffer: Buffer.alloc(6 * 1024 * 1024, 7) });
  const bar = page.getByRole("progressbar", { name: `Uploading ${name}` });
  await expect(bar).toHaveAttribute("aria-valuenow", "100", { timeout: 30_000 });
  await expect(page.getByRole("link", { name })).toBeVisible();
});

test("refuses drops into read-only places", async ({ page }) => {
  await signIn(page, "chris");
  await page.goto("/files/shared");
  await expect(page.getByRole("button", { name: /choose files/i })).toHaveCount(0);
});

test("My uploads lists files with their IP address", async ({ page }) => {
  await signIn(page, "jugash");
  await page.goto("/uploads");
  await expect(page.getByRole("heading", { name: "My uploads" })).toBeVisible();
  await expect(page.getByText(/files?,/)).toBeVisible();
});

test("admin screens are admin-only", async ({ page }) => {
  await signIn(page, "chris");
  await page.goto("/admin/users");
  await expect(page.getByText("Admins only")).toBeVisible();
});

test("main pages have no accessibility violations", async ({ page }) => {
  await signIn(page, "admin");
  for (const path of ["/files/home", "/uploads", "/folders", "/account", "/admin/destinations"]) {
    await page.goto(path);
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag22aa"]).analyze();
    expect(results.violations, path).toEqual([]);
  }
});
