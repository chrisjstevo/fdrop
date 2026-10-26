import { useSyncExternalStore } from "react";

/**
 * Light or dark: the user's choice, kept in this browser, or the system setting until they choose. The resolved theme
 * is set as `data-theme` on <html>, which tokens.css (and the few component rules with dark variants) key off.
 */
export type Theme = "light" | "dark";

const KEY = "fdrop-theme";
const listeners = new Set<() => void>();
const media = typeof window !== "undefined" ? window.matchMedia?.("(prefers-color-scheme: dark)") : undefined;

/** The stored choice, or null to follow the system. Storage may be unavailable (private windows): then null. */
export function storedTheme(): Theme | null {
  try {
    const v = window.localStorage.getItem(KEY);
    return v === "light" || v === "dark" ? v : null;
  } catch {
    return null;
  }
}

function systemTheme(): Theme {
  return media?.matches ? "dark" : "light";
}

export function currentTheme(): Theme {
  return storedTheme() ?? systemTheme();
}

function apply() {
  const t = currentTheme();
  document.documentElement.dataset.theme = t;
  listeners.forEach((l) => l());
}

/** Sets the theme and remembers it; null forgets the choice and follows the system again. */
export function setTheme(t: Theme | null) {
  try {
    if (t) {
      window.localStorage.setItem(KEY, t);
    } else {
      window.localStorage.removeItem(KEY);
    }
  } catch {
    // not remembered, but still applied for this page
    document.documentElement.dataset.theme = t ?? systemTheme();
    listeners.forEach((l) => l());
    return;
  }
  apply();
}

/** Called once before the first render, so the page never flashes the wrong theme. */
export function initTheme() {
  apply();
  media?.addEventListener?.("change", () => {
    if (!storedTheme()) {
      apply();
    }
  });
}

function subscribe(l: () => void) {
  listeners.add(l);
  return () => listeners.delete(l);
}

/** The theme shown now, and whether it was chosen here rather than taken from the system. */
export function useTheme(): { theme: Theme; chosen: boolean } {
  const theme = useSyncExternalStore(subscribe, () => (document.documentElement.dataset.theme as Theme) ?? "light");
  const chosen = useSyncExternalStore(subscribe, () => storedTheme() !== null);
  return { theme, chosen };
}
