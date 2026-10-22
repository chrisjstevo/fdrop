import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router";
import { downloadUrl, get, post, q } from "../api/client";
import type { HistoryPage, HistoryRow } from "../api/types";
import { Badge, Button, Dialog, DialogActions, Empty, ErrorNote, RowMenu } from "../components/ui";
import { formatBytes, formatCount, formatDate, parentOf } from "../util/format";
import { filesLink } from "./FilesPage";
import { RecordBadge } from "./status";
import s from "./pages.module.css";

type Sort = "date_desc" | "date_asc" | "size_desc" | "size_asc" | "name_asc" | "name_desc";

function wentTo(r: HistoryRow) {
  if (r.wentTo === "FORWARD") {
    return (
      <span className={s.inline}>
        <Badge kind="forward">Forward</Badge>
        <span>
          {r.folderName ?? "(deleted folder)"}
          {r.destinationName ? ` → ${r.destinationName}` : ""}
        </span>
      </span>
    );
  }
  const where = r.wentTo === "SHARED" ? `Shared: ${r.folderName ?? "(deleted folder)"}` : homeLabel(r.pathAtUpload);
  return (
    <span className={s.inline}>
      <Badge kind="folder">Folder</Badge>
      <span>{where}</span>
    </span>
  );
}

function homeLabel(path: string) {
  const parent = parentOf(path);
  return parent === "/home" ? "Home" : "Home / " + parent.replace(/^\/home\//, "").split("/").join(" / ");
}

function fromIp(r: HistoryRow) {
  return (
    <>
      <span>{r.clientIp ?? "–"}</span>
      {r.startIp && <div className="muted">started from {r.startIp}</div>}
    </>
  );
}

export function MyUploadsPage() {
  const navigate = useNavigate();
  const client = useQueryClient();
  const [filter, setFilter] = useState<"all" | "folders" | "forward">("all");
  const [search, setSearch] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [sort, setSort] = useState<Sort>("date_desc");
  const [details, setDetails] = useState<HistoryRow>();
  const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;

  const params = new URLSearchParams();
  if (filter !== "all") {
    params.set("wentTo", filter);
  }
  if (search.trim()) {
    params.set("q", search.trim());
  }
  // whole days in the viewer's time zone
  if (from) {
    params.set("from", String(new Date(from + "T00:00:00").getTime()));
  }
  if (to) {
    params.set("to", String(new Date(to + "T00:00:00").getTime() + 86_400_000));
  }
  params.set("sort", sort);

  const pages = useInfiniteQuery({
    queryKey: ["uploads", params.toString()],
    initialPageParam: "",
    queryFn: ({ pageParam }) =>
      get<HistoryPage>(`/api/v1/me/uploads?${params.toString()}${pageParam ? "&cursor=" + q(pageParam) : ""}`),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    refetchInterval: 10_000,
  });
  const retry = useMutation({
    mutationFn: (r: HistoryRow) => post(`/api/v1/folders/${q(r.folderId!)}/forward-jobs/${q(r.jobId!)}/retry`),
    onSuccess: () => void client.invalidateQueries({ queryKey: ["uploads"] }),
  });

  const rows = pages.data?.pages.flatMap((p) => p.rows) ?? [];
  const first = pages.data?.pages[0];

  const actions = (r: HistoryRow) => {
    const a = [];
    if (r.canDownload && r.currentPath && r.status !== "SENT") {
      a.push({ label: "Download", onSelect: () => window.location.assign(downloadUrl(r.currentPath!)) });
    }
    if (r.currentPath) {
      a.push({ label: "Open folder", onSelect: () => navigate(filesLink(parentOf(r.currentPath!))) });
    }
    if (r.canRetry && r.jobId) {
      a.push({ label: "Retry forward", onSelect: () => retry.mutate(r) });
    }
    a.push({ label: "Details", onSelect: () => setDetails(r) });
    return a;
  };

  const sortHeader = (label: string, asc: Sort, desc: Sort, numeric = false) => {
    const active = sort === asc || sort === desc;
    const dir = sort === asc ? "ascending" : sort === desc ? "descending" : "none";
    return (
      <th scope="col" aria-sort={dir} className={numeric ? s.num : undefined}>
        <button type="button" onClick={() => setSort(sort === desc ? asc : desc)}>
          {label} {active ? (sort === asc ? "▲" : "▼") : ""}
        </button>
      </th>
    );
  };

  return (
    <>
      <div className={s.head}>
        <h1>My uploads</h1>
      </div>
      <div className={s.filters}>
        <div>
          <label htmlFor="went-to">Went to</label>
          <select id="went-to" value={filter} onChange={(e) => setFilter(e.target.value as typeof filter)}>
            <option value="all">All</option>
            <option value="folders">Folders</option>
            <option value="forward">Forward</option>
          </select>
        </div>
        <div>
          <label htmlFor="from">From</label>
          <input id="from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        </div>
        <div>
          <label htmlFor="to">To</label>
          <input id="to" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </div>
        <div>
          <label htmlFor="search">Name or IP address</label>
          <input
            id="search"
            type="search"
            placeholder="report.pdf or 10.20."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
      </div>
      <ErrorNote>{pages.error?.message ?? retry.error?.message}</ErrorNote>
      {first && (
        <p className={s.summary}>
          {formatCount(first.totalCount)} file{first.totalCount === 1 ? "" : "s"}, {formatBytes(first.totalBytes)}
        </p>
      )}
      {rows.length === 0 && !pages.isLoading ? (
        <Empty title="No uploads match">Files you upload over the browser or SFTP appear here.</Empty>
      ) : (
        <div className={s.listWrap}>
          <table className={s.table}>
            <thead>
              <tr>
                {sortHeader("File", "name_asc", "name_desc")}
                {sortHeader("Size", "size_asc", "size_desc", true)}
                <th scope="col">Went to</th>
                {sortHeader("Uploaded", "date_asc", "date_desc")}
                <th scope="col">Via</th>
                <th scope="col">From IP</th>
                <th scope="col">Status</th>
                <th scope="col">
                  <span className="visually-hidden">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td>
                    <span className={s.nameCell}>
                      {r.currentPath && r.canDownload && r.status !== "SENT" ? (
                        <a href={downloadUrl(r.currentPath)} download>
                          {r.fileName}
                        </a>
                      ) : (
                        r.fileName
                      )}
                    </span>
                  </td>
                  <td className={s.num} title={`${formatCount(r.size)} bytes`}>
                    {formatBytes(r.size)}
                  </td>
                  <td>{wentTo(r)}</td>
                  <td title={tz}>{formatDate(r.completedAt)}</td>
                  <td>{r.via === "HTTP" ? "Browser" : "SFTP"}</td>
                  <td>{fromIp(r)}</td>
                  <td>
                    <RecordBadge status={r.status} />
                  </td>
                  <td className={s.num}>
                    <RowMenu label={`Actions for ${r.fileName}`} actions={actions(r)} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <ul className={s.cards}>
            {rows.map((r) => (
              <li key={r.id} className={s.card}>
                <button
                  type="button"
                  className={s.cardBody}
                  style={{ textAlign: "left", background: "none", border: "none", padding: 0, color: "inherit" }}
                  onClick={() => setDetails(r)}
                >
                  <div className={s.cardTitle}>{r.fileName}</div>
                  <div className={s.cardMeta}>
                    <span>{formatBytes(r.size)}</span>
                    {r.wentTo === "FORWARD" ? <Badge kind="forward">Forward</Badge> : <Badge kind="folder">Folder</Badge>}
                    <span>{formatDate(r.completedAt)}</span>
                    <RecordBadge status={r.status} />
                  </div>
                </button>
                <RowMenu label={`Actions for ${r.fileName}`} actions={actions(r)} />
              </li>
            ))}
          </ul>
        </div>
      )}
      {pages.hasNextPage && (
        <p>
          <Button onClick={() => void pages.fetchNextPage()} disabled={pages.isFetchingNextPage}>
            Load more
          </Button>
        </p>
      )}
      {details && (
        <Dialog open onOpenChange={(o) => !o && setDetails(undefined)} title={details.fileName}>
          <dl className={s.kv}>
            <dt>Size</dt>
            <dd>
              {formatBytes(details.size)} ({formatCount(details.size)} bytes)
            </dd>
            <dt>Went to</dt>
            <dd>{wentTo(details)}</dd>
            <dt>Uploaded</dt>
            <dd>{formatDate(details.completedAt)}</dd>
            <dt>Via</dt>
            <dd>{details.via === "HTTP" ? "Browser" : "SFTP"}</dd>
            <dt>From IP</dt>
            <dd>{fromIp(details)}</dd>
            <dt>Status</dt>
            <dd>
              <RecordBadge status={details.status} />
            </dd>
            <dt>Uploaded to</dt>
            <dd>{details.pathAtUpload}</dd>
          </dl>
          <DialogActions>
            <Button onClick={() => setDetails(undefined)}>Close</Button>
          </DialogActions>
        </Dialog>
      )}
    </>
  );
}
