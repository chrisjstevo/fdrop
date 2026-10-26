import { afterEach, describe, expect, it } from "vitest";
import { currentTheme, initTheme, setTheme, storedTheme } from "./theme";

describe("light and dark theme", () => {
  afterEach(() => setTheme(null));

  it("follows the system until chosen, then remembers the choice", () => {
    initTheme();
    // jsdom has no prefers-color-scheme: the system is light
    expect(document.documentElement.dataset.theme).toBe("light");
    expect(storedTheme()).toBeNull();

    setTheme("dark");
    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(window.localStorage.getItem("fdrop-theme")).toBe("dark");
    expect(currentTheme()).toBe("dark");

    setTheme(null);
    expect(document.documentElement.dataset.theme).toBe("light");
    expect(window.localStorage.getItem("fdrop-theme")).toBeNull();
  });

  it("ignores a stored value it does not know", () => {
    window.localStorage.setItem("fdrop-theme", "sepia");
    expect(storedTheme()).toBeNull();
    expect(currentTheme()).toBe("light");
  });
});
