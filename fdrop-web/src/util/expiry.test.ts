import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { expiryFrom, longerThan, parseExpiry, timeLeft } from "./expiry";

interface Cases {
  defaultFrom: string;
  valid: { input: string; canonical: string; from?: string; to: string | null }[];
  invalid: { input: string; message: string }[];
}

// The cases every copy of the parser must pass (server, CLI and this one).
const cases: Cases = JSON.parse(
  readFileSync(resolve(process.cwd(), "../fdrop-core/src/test/resources/expiry-syntax-cases.json"), "utf8"),
);

describe("expiry syntax", () => {
  for (const c of cases.valid) {
    it(`accepts ${JSON.stringify(c.input)}`, () => {
      const canonical = parseExpiry(c.input);
      expect(canonical).toBe(c.canonical);
      const to = expiryFrom(canonical, new Date(c.from ?? cases.defaultFrom));
      expect(to === null ? null : to.toISOString().replace(".000Z", "Z")).toBe(c.to);
    });
  }
  for (const c of cases.invalid) {
    it(`refuses ${JSON.stringify(c.input)}`, () => {
      expect(() => parseExpiry(c.input)).toThrow(c.message);
    });
  }

  it("compares lengths and shows time left", () => {
    expect(longerThan("never", "100 years")).toBe(true);
    expect(longerThan("7 days", "1 week")).toBe(false);
    const now = Date.parse("2026-10-26T09:00:00Z");
    expect(timeLeft("2026-10-26T09:40:00Z", now)).toBe("in 40 min");
    expect(timeLeft("2026-10-31T09:00:00Z", now)).toBe("in 5 days");
    expect(timeLeft("2026-10-26T08:00:00Z", now)).toBe("expired");
  });
});
