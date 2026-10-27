import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AdminAuditPage, auditParams } from "./AdminAuditPage";

const PAGE = {
  total: 2,
  indexedUpTo: null,
  facets: { group: { "Sign-in": 2 }, outcome: { refused: 1, success: 1 } },
  nextCursor: null,
  events: [
    {
      id: "e2", seq: 2, ts: "2026-10-27T09:40:55.120Z", node: "fdrop-1", event: "app.login", group: "Sign-in",
      user: "erp-export", ip: "198.51.100.23", protocol: "HTTP", outcome: "refused",
      summary: "login refused: address not allowed",
    },
    {
      id: "e1", seq: 1, ts: "2026-10-27T09:30:00.000Z", node: "fdrop-1", event: "login", group: "Sign-in",
      user: "alice", ip: "10.0.0.5", protocol: "HTTP", outcome: "success", summary: "signed in",
    },
  ],
};

const DETAIL = {
  ...PAGE.events[0],
  data: { ts: "2026-10-27T09:40:55.120Z", event: "app.login", user: "erp-export", ip: "198.51.100.23" },
  chain: { node: "fdrop-1", nodeSeq: 2, prevHash: "aa", hash: "bb" },
};

function setup() {
  const calls: string[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementation(async (url: string) => {
      calls.push(url);
      const body = url.startsWith("/api/v1/admin/audit/events")
        ? { events: [{ event: "login", group: "Sign-in", count: 1 }] }
        : url.startsWith("/api/v1/admin/audit/e2")
          ? DETAIL
          : url.startsWith("/api/v1/admin/audit/index")
            ? { available: true, documents: 2, sizeBytes: 1, appliedSeq: 2, latestSeq: 2, lagSeconds: 0, rebuilding: false }
            : PAGE;
      return new Response(JSON.stringify(body), { status: 200 });
    }),
  );
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <AdminAuditPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return calls;
}

afterEach(() => vi.unstubAllGlobals());

describe("audit log page", () => {
  it("lists events with facet counts and searches with the field syntax", async () => {
    const calls = setup();
    await screen.findAllByText("login refused: address not allowed");
    const table = screen.getByRole("table");
    expect(within(table).getByText("Refused")).toBeTruthy();
    expect(screen.getByRole("button", { name: /Sign-in\s*2/ })).toBeTruthy();

    fireEvent.change(screen.getByRole("searchbox", { name: "Search the audit log" }), {
      target: { value: "user:erp-export outcome:refused" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Search" }));
    await waitFor(() => expect(calls.some((c) => c.includes("q=user%3Aerp-export+outcome%3Arefused"))).toBe(true));
    // follow live is for the plain list only
    expect((screen.getByRole("checkbox", { name: "Follow live" }) as HTMLInputElement).disabled).toBe(true);
  });

  it("opens an event with every field and links to the same user", async () => {
    const calls = setup();
    fireEvent.click(await screen.findByRole("button", { name: "app.login" }));
    const dialog = await screen.findByRole("dialog");
    await waitFor(() => expect(within(dialog).getByText(/Event 2 on fdrop-1/)).toBeTruthy());
    fireEvent.click(within(dialog).getByRole("button", { name: "Events by this user" }));
    await waitFor(() => expect(calls.some((c) => c.includes("q=user%3Aerp-export"))).toBe(true));
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("builds the export and list parameters from the filters", () => {
    const now = Date.parse("2026-10-27T10:00:00Z");
    const p = auditParams(
      { q: " ip:10.0.0.0/8 ", groups: ["Sign-in", "Files"], outcomes: ["refused"], event: "", range: "1h", from: "", to: "" },
      now,
    );
    expect(p.get("q")).toBe("ip:10.0.0.0/8");
    expect(p.get("group")).toBe("Sign-in,Files");
    expect(p.get("outcome")).toBe("refused");
    expect(p.get("from")).toBe("2026-10-27T09:00:00.000Z");
    expect(p.has("to")).toBe(false);
  });
});
