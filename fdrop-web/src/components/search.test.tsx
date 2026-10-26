import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes, useLocation } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SearchPage } from "../pages/SearchPage";
import { SearchBox } from "./SearchBox";

function Where() {
  const l = useLocation();
  return <div data-testid="where">{l.pathname + l.search}</div>;
}

function setup(path: string, body: unknown, ui: React.ReactNode) {
  vi.stubGlobal("fetch", vi.fn().mockImplementation(async () => new Response(JSON.stringify(body), { status: 200 })));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        {ui}
        <Routes>
          <Route path="*" element={<Where />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

afterEach(() => vi.unstubAllGlobals());

describe("search box", () => {
  it("suggests names as you type and searches contents on Enter", async () => {
    setup(
      "/files/home",
      [{ path: "/shared/Invoices/credit-note.pdf", name: "credit-note.pdf", location: "Shared: Invoices", folder: false }],
      <SearchBox />,
    );
    const box = screen.getByRole("combobox", { name: "Search files and contents" });
    fireEvent.focus(box);
    fireEvent.change(box, { target: { value: "cred" } });
    await waitFor(() => expect(screen.getByRole("option", { name: /credit-note.pdf/ })).toBeTruthy());
    expect(screen.getByRole("option", { name: /Search contents for “cred”/ })).toBeTruthy();
    // typing in a folder still narrows it by name
    expect(screen.getByTestId("where").textContent).toBe("/files/home?q=cred");

    fireEvent.keyDown(box, { key: "ArrowDown" });
    expect(box.getAttribute("aria-activedescendant")).toBeTruthy();
    fireEvent.keyDown(box, { key: "Enter" });
    expect(screen.getByTestId("where").textContent).toBe("/files/shared/Invoices?highlight=credit-note.pdf");
  });

  it("opens the results page on Enter, remembering the folder it came from", () => {
    setup("/files/home/reports", [], <SearchBox />);
    const box = screen.getByRole("combobox");
    fireEvent.change(box, { target: { value: "acme invoice" } });
    fireEvent.keyDown(box, { key: "Enter" });
    expect(screen.getByTestId("where").textContent).toBe("/search?q=acme%20invoice&from=%2Fhome%2Freports");
  });
});

describe("results page", () => {
  it("shows matches marked, as text, with their location", async () => {
    setup(
      "/search?q=acme",
      {
        total: 1,
        results: [
          {
            path: "/home/note.txt",
            name: [{ text: "note.txt", match: false }],
            location: "Home",
            type: "text",
            folder: false,
            size: 10,
            snippets: [[{ text: "<b>issued to</b> ", match: false }, { text: "Acme", match: true }]],
            contents: "ok",
          },
        ],
      },
      <SearchPage />,
    );
    await waitFor(() => expect(screen.getByText("1 file")).toBeTruthy());
    expect(screen.getByText("Acme").tagName).toBe("MARK");
    // a document's markup is shown as text, not rendered
    expect(screen.getByText("<b>issued to</b>")).toBeTruthy();
    expect(screen.getByText("Home")).toBeTruthy();
  });
});
