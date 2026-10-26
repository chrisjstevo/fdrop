import { useInfiniteQuery } from "@tanstack/react-query";
import { Link, useNavigate, useSearchParams } from "react-router";
import { downloadUrl, get, q as enc } from "../api/client";
import type { SearchHit, SearchResult, Segment } from "../api/types";
import { ExpiresCell } from "../components/Expiry";
import { FileIcon, FolderIcon } from "../components/icons";
import { ProblemNote } from "../components/TwoFactor";
import { Button, Empty, RowMenu } from "../components/ui";
import { formatBytes, formatCount, formatDate, parentOf } from "../util/format";
import { filesLink } from "./FilesPage";
import s from "./pages.module.css";

const TYPES: [string, string][] = [
  ["", "All"],
  ["pdf", "PDFs"],
  ["doc", "Documents"],
  ["sheet", "Spreadsheets"],
  ["slides", "Presentations"],
  ["text", "Text"],
  ["email", "Email"],
  ["other", "Other"],
];

const MODIFIED: [string, string][] = [
  ["", "Any time"],
  ["7", "Last 7 days"],
  ["30", "Last 30 days"],
  ["year", "This year"],
];

const WHY: Record<string, string> = {
  unsupported: "This kind of file has no searchable text",
  too_large: "Too large to search its contents",
  protected: "Password-protected",
  failed: "Its contents could not be read",
  pending: "Its contents are still being read",
};

function since(v: string): string | undefined {
  if (v === "7" || v === "30") {
    return new Date(Date.now() - Number(v) * 86_400_000).toISOString();
  }
  if (v === "year") {
    return new Date(Date.UTC(new Date().getUTCFullYear(), 0, 1)).toISOString();
  }
  return undefined;
}

/** Matched words in bold; the text stays text, never HTML. */
function Marked({ segments }: { segments: Segment[] }) {
  return (
    <>
      {segments.map((seg, i) => (seg.match ? <mark key={i}>{seg.text}</mark> : <span key={i}>{seg.text}</span>))}
    </>
  );
}

/** Search results: names and contents of every file the user can see, with filters and snippets. */
export function SearchPage() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const query = params.get("q") ?? "";
  const from = params.get("from") ?? "";
  const scope = params.get("scope") === "folder" && from ? "folder" : "all";
  const type = params.get("type") ?? "";
  const modified = params.get("modified") ?? "";
  const sort = params.get("sort") === "newest" ? "newest" : "relevance";

  const set = (k: string, v: string) => {
    const next = new URLSearchParams(params);
    if (v) {
      next.set(k, v);
    } else {
      next.delete(k);
    }
    setParams(next, { replace: true });
  };

  const results = useInfiniteQuery({
    queryKey: ["search", query, scope, from, type, modified, sort],
    enabled: query.trim().length > 0,
    initialPageParam: "",
    queryFn: ({ pageParam }) => {
      const u = new URLSearchParams({ q: query, scope, sort, limit: "20" });
      if (scope === "folder") {
        u.set("path", from);
      }
      if (type) {
        u.set("type", type);
      }
      const m = since(modified);
      if (m) {
        u.set("modifiedFrom", m);
      }
      if (pageParam) {
        u.set("cursor", pageParam);
      }
      return get<SearchResult>("/api/v1/search?" + u.toString());
    },
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });

  const hits = (results.data?.pages ?? []).flatMap((p) => p.results);
  const total = results.data?.pages[0]?.total ?? 0;
  const parts = from.split("/").filter(Boolean);
  const folderName = parts.length === 1 && parts[0] === "home" ? "Home" : parts[parts.length - 1];

  const open = (h: SearchHit) =>
    navigate(h.folder ? filesLink(h.path) : `${filesLink(parentOf(h.path))}?highlight=${enc(h.path.split("/").pop()!)}`);

  const chip = (active: boolean, label: string, onClick: () => void) => (
    <Button small variant={active ? "primary" : "secondary"} aria-pressed={active} onClick={onClick}>
      {label}
    </Button>
  );

  return (
    <>
      <div className={s.head}>
        <div className={s.titleBlock}>
          <h1>{query ? `Results for “${query}”` : "Search"}</h1>
        </div>
      </div>
      {!query ? (
        <Empty title="Search your files">Type in the search box at the top and press Enter.</Empty>
      ) : (
        <>
          <div className={s.actions} role="group" aria-label="Where to search" style={{ marginBottom: 8 }}>
            {chip(scope === "all", "Everywhere", () => set("scope", ""))}
            {from && chip(scope === "folder", `In ${folderName}`, () => set("scope", "folder"))}
          </div>
          <div className={s.actions} role="group" aria-label="File type" style={{ marginBottom: 8 }}>
            {TYPES.map(([v, label]) => (
              <span key={v || "all"}>{chip(type === v, label, () => set("type", v))}</span>
            ))}
          </div>
          <div className={s.actions} style={{ marginBottom: 16 }}>
            <label htmlFor="search-modified" className="visually-hidden">
              Modified
            </label>
            <select id="search-modified" value={modified} onChange={(e) => set("modified", e.target.value)}
              style={{ width: "auto" }}>
              {MODIFIED.map(([v, label]) => (
                <option key={v} value={v}>
                  {label}
                </option>
              ))}
            </select>
            <label htmlFor="search-sort" className="visually-hidden">
              Sort
            </label>
            <select id="search-sort" value={sort} onChange={(e) => set("sort", e.target.value === "newest" ? "newest" : "")}
              style={{ width: "auto" }}>
              <option value="relevance">Best match</option>
              <option value="newest">Newest</option>
            </select>
          </div>

          {results.isError ? (
            <ProblemNote error={results.error} />
          ) : (
            <>
              <p className={s.summary} role="status" aria-live="polite">
                {results.isLoading ? "Searching…" : `${formatCount(total)} ${total === 1 ? "file" : "files"}`}
              </p>
              {!results.isLoading && hits.length === 0 ? (
                <Empty title="No matches">
                  Check the spelling, use fewer words{scope === "folder" ? ", or search everywhere" : ""}.
                </Empty>
              ) : (
                <ul className={s.results}>
                  {hits.map((h) => (
                    <li key={h.path} className={s.result}>
                      <span className={s.icon} aria-hidden="true">
                        {h.folder ? <FolderIcon size={20} /> : <FileIcon size={20} />}
                      </span>
                      <div className={s.resultBody}>
                        <Link
                          className={s.resultName}
                          to={h.folder ? filesLink(h.path) : `${filesLink(parentOf(h.path))}?highlight=${enc(h.path.split("/").pop()!)}`}
                        >
                          <Marked segments={h.name} />
                        </Link>
                        <div className={s.resultWhere}>{h.location}</div>
                        {h.snippets.map((sn, i) => (
                          <p key={i} className={s.snippet}>
                            <Marked segments={sn} />
                          </p>
                        ))}
                        {!h.folder && h.contents !== "ok" && (
                          <p className={s.resultWhere} title={WHY[h.contents]}>
                            Name match only
                          </p>
                        )}
                        <div className={s.cardMeta}>
                          {!h.folder && <span>{formatBytes(h.size)}</span>}
                          {h.modified && <span>{formatDate(h.modified)}</span>}
                          <ExpiresCell entry={{ name: "", path: h.path, type: "FILE", size: h.size, writable: false, expiresAt: h.expiresAt }} />
                        </div>
                      </div>
                      <RowMenu
                        label={`Actions for ${h.path.split("/").pop()}`}
                        actions={[
                          { label: "Open folder", onSelect: () => open(h) },
                          ...(h.folder ? [] : [{ label: "Download", onSelect: () => window.location.assign(downloadUrl(h.path)) }]),
                        ]}
                      />
                    </li>
                  ))}
                </ul>
              )}
              {results.hasNextPage && (
                <p>
                  <Button onClick={() => void results.fetchNextPage()} disabled={results.isFetchingNextPage}>
                    Load more
                  </Button>
                </p>
              )}
              <p className="muted">
                Tip: <code>"exact phrase"</code>, <code>-word</code> to leave out, <code>a | b</code> for either,{" "}
                <code>inv*</code> for words starting with, <code>name:</code> or <code>type:pdf</code>. Files uploaded
                in the last few seconds may not appear yet.
              </p>
            </>
          )}
        </>
      )}
    </>
  );
}
