import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { FolderDestination, RemoteListing } from "../api/types";
import { RemoteTab, listedAgo } from "./RemoteTab";

const DEST: FolderDestination = { id: "dst1", name: "Partner A – production", paused: false, browsable: true };

const LISTING: RemoteListing = {
  destinationId: "dst1",
  destinationName: "Partner A – production",
  remoteDir: "/inbound/invoices",
  path: "",
  listedAt: new Date(Date.now() - 12_000).toISOString(),
  truncated: false,
  maxEntries: 10000,
  entries: [
    { name: "archive", path: "archive", type: "folder", modifiedAt: "2026-10-03T08:00:00Z" },
    {
      name: "INV-0418.pdf", path: "INV-0418.pdf", type: "file", size: 1200, modifiedAt: "2026-10-29T09:42:00Z",
      cameFrom: "SENT_BY_FDROP",
      retrieval: { id: "ret1", destinationId: "dst1", path: "INV-0418.pdf", state: "READY", size: 1200, bytesDone: 1200 },
    },
    {
      name: "INV-0417.pdf", path: "INV-0417.pdf", type: "file", size: 1000, modifiedAt: "2026-10-28T16:05:00Z",
      cameFrom: "CHANGED_SINCE_SENT",
      retrieval: { id: "ret2", destinationId: "dst1", path: "INV-0417.pdf", state: "RETRIEVING", size: 1000, bytesDone: 450 },
    },
    {
      name: "remittance.csv", path: "remittance.csv", type: "file", size: 46, modifiedAt: "2026-10-27T18:30:00Z",
      cameFrom: "NOT_FROM_FDROP",
    },
    {
      name: "INV-0415.pdf", path: "INV-0415.pdf", type: "file", size: 10, modifiedAt: "2026-10-24T14:51:00Z",
      cameFrom: "SENT_BY_FDROP",
      retrieval: { id: "ret3", destinationId: "dst1", path: "INV-0415.pdf", state: "FAILED", size: 10, bytesDone: 0, error: "connection reset" },
    },
  ],
};

function setup(dest = DEST) {
  const calls: { url: string; method: string; body?: string }[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      calls.push({ url, method: init?.method ?? "GET", body: init?.body as string | undefined });
      if (init?.method === "POST") {
        return new Response(
          JSON.stringify({ id: "ret9", destinationId: "dst1", path: "remittance.csv", state: "QUEUED", size: 46, bytesDone: 0 }),
          { status: 202 },
        );
      }
      const sub = new URL(url, "https://x").searchParams.get("path");
      const body = sub ? { ...LISTING, path: sub, entries: [] } : LISTING;
      return new Response(JSON.stringify(body), { status: 200 });
    }),
  );
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <RemoteTab folderId="fld1" destination={dest} />
    </QueryClientProvider>,
  );
  return calls;
}

afterEach(() => vi.unstubAllGlobals());

describe("a destination tab", () => {
  it("lists the remote folder with where each file came from and what can be done with it", async () => {
    setup();
    expect((await screen.findAllByText("INV-0418.pdf")).length).toBeGreaterThan(0);
    expect(screen.getAllByText("/inbound/invoices").length).toBeGreaterThan(0);
    expect(screen.getAllByText("On the destination server · read-only").length).toBe(1);
    expect(screen.getAllByText("Sent by fdrop").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Changed since sent").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Not from fdrop").length).toBeGreaterThan(0);
    expect(screen.getAllByRole("button", { name: "Download INV-0418.pdf" }).length).toBeGreaterThan(0);
    expect(screen.getAllByText("Retrieving · 45%").length).toBeGreaterThan(0);
    expect(screen.getAllByRole("button", { name: "Get remittance.csv" }).length).toBeGreaterThan(0);
    expect(screen.getAllByText("Couldn't retrieve: connection reset").length).toBeGreaterThan(0);
    expect(screen.getAllByRole("button", { name: "Try again to get INV-0415.pdf" }).length).toBeGreaterThan(0);
  });

  it("gets a file when its name or button is clicked, and opens folders", async () => {
    const calls = setup();
    await screen.findAllByText("INV-0418.pdf");
    fireEvent.click(screen.getAllByRole("button", { name: "Get remittance.csv" })[0]);
    await waitFor(() => expect(calls.some((c) => c.method === "POST")).toBe(true));
    const post = calls.find((c) => c.method === "POST")!;
    expect(post.url).toBe("/api/v1/folders/fld1/destinations/dst1/retrievals");
    expect(JSON.parse(post.body!)).toEqual({ path: "remittance.csv" });

    fireEvent.click(screen.getAllByRole("button", { name: "Open" })[0]);
    await waitFor(() => expect(calls.some((c) => c.url.includes("path=archive"))).toBe(true));
    expect(await screen.findByText("This folder on Partner A – production is empty.")).toBeInTheDocument();
  });

  it("says a destination that is not available can't be listed, without calling it", () => {
    const calls = setup({ ...DEST, paused: true });
    expect(screen.getByText("Not available")).toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });

  it("says when the folder was listed", () => {
    const now = Date.parse("2026-10-29T10:00:00Z");
    expect(listedAgo("2026-10-29T09:59:48Z", now)).toBe("12 seconds ago");
    expect(listedAgo("2026-10-29T09:57:00Z", now)).toBe("3 minutes ago");
    expect(listedAgo("2026-10-29T10:00:00Z", now)).toBe("just now");
  });
});
