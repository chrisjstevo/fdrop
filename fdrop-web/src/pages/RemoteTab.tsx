import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { get, post, q } from "../api/client";
import type { CameFrom, FolderDestination, RemoteEntry, RemoteListing, Retrieval } from "../api/types";
import { FileIcon, FolderIcon, ServerIcon } from "../components/icons";
import { Badge, Button, Empty, ErrorNote, ProgressBar } from "../components/ui";
import { formatBytes, formatDate } from "../util/format";
import s from "./pages.module.css";

/** The query key of one remote directory's listing; the tab row reads the root's for its counts. */
export const remoteKey = (folderId: string, destinationId: string, path = "") => ["remote", folderId, destinationId, path];

export function remoteUrl(folderId: string, destinationId: string, path = "", refresh = false) {
  return `/api/v1/folders/${folderId}/destinations/${destinationId}/remote?path=${q(path)}${refresh ? "&refresh=true" : ""}`;
}

export function retrievedContentUrl(folderId: string, retrievalId: string) {
  return `/api/v1/folders/${folderId}/retrievals/${retrievalId}/content`;
}

/** "12 seconds ago", "3 minutes ago", or the time for anything older than an hour. */
export function listedAgo(iso: string, now: number) {
  const secs = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000));
  if (secs < 60) {
    return secs <= 1 ? "just now" : `${secs} seconds ago`;
  }
  const mins = Math.round(secs / 60);
  if (mins < 60) {
    return mins === 1 ? "a minute ago" : `${mins} minutes ago`;
  }
  return "at " + formatDate(iso);
}

const CAME_FROM: Record<CameFrom, { label: string; kind: "folder" | "neutral" | "warn" }> = {
  SENT_BY_FDROP: { label: "Sent by fdrop", kind: "folder" },
  CHANGED_SINCE_SENT: { label: "Changed since sent", kind: "warn" },
  NOT_FROM_FDROP: { label: "Not from fdrop", kind: "neutral" },
};

const busy = (r?: Retrieval) => r?.state === "QUEUED" || r?.state === "RETRIEVING";

/**
 * One destination's tab in a forward folder: its remote directory, read-only. A file's Get file copies it into fdrop
 * (for 24 hours by default) and downloads it once it is there.
 */
export function RemoteTab({ folderId, destination }: { folderId: string; destination: FolderDestination }) {
  const client = useQueryClient();
  const [path, setPath] = useState("");
  const [now, setNow] = useState(() => Date.now());
  // retrievals started on this page: each downloads by itself once ready
  const waiting = useRef(new Set<string>());

  const listing = useQuery({
    queryKey: remoteKey(folderId, destination.id, path),
    enabled: !destination.paused,
    queryFn: () => get<RemoteListing>(remoteUrl(folderId, destination.id, path)),
    retry: false,
    refetchInterval: (query) => (query.state.data?.entries.some((e) => busy(e.retrieval)) ? 1500 : false),
  });

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 5000);
    return () => clearInterval(t);
  }, []);

  useEffect(() => {
    for (const e of listing.data?.entries ?? []) {
      const r = e.retrieval;
      if (r && waiting.current.has(r.id) && r.state !== "QUEUED" && r.state !== "RETRIEVING") {
        waiting.current.delete(r.id);
        if (r.state === "READY") {
          window.location.assign(retrievedContentUrl(folderId, r.id));
        }
      }
    }
  }, [listing.data, folderId]);

  const refresh = useMutation({
    mutationFn: () => get<RemoteListing>(remoteUrl(folderId, destination.id, path, true)),
    onSuccess: (data) => client.setQueryData(remoteKey(folderId, destination.id, path), data),
  });

  const retrieve = useMutation({
    mutationFn: (e: RemoteEntry) =>
      post<Retrieval>(`/api/v1/folders/${folderId}/destinations/${destination.id}/retrievals`, { path: e.path }),
    onSuccess: (r) => {
      if (r.state === "READY") {
        window.location.assign(retrievedContentUrl(folderId, r.id));
      } else {
        waiting.current.add(r.id);
      }
      void client.invalidateQueries({ queryKey: remoteKey(folderId, destination.id, path) });
    },
  });

  if (destination.paused) {
    return (
      <div role="tabpanel" id={`panel-${destination.id}`} aria-labelledby={`tab-${destination.id}`}>
        <Empty title="Not available">
          {destination.name} is disabled, or no longer assigned to this folder's owner, so its folder can't be listed.
          Ask an admin if you need it.
        </Empty>
      </div>
    );
  }

  const data = listing.data;
  const segments = path ? path.split("/") : [];
  const act = (e: RemoteEntry) => {
    if (e.type === "folder") {
      setPath(e.path);
    } else if (e.retrieval?.state === "READY") {
      window.location.assign(retrievedContentUrl(folderId, e.retrieval.id));
    } else if (!busy(e.retrieval)) {
      retrieve.mutate(e);
    }
  };

  const action = (e: RemoteEntry, wide = false) => {
    if (e.type === "folder") {
      return (
        <Button small onClick={() => act(e)}>
          Open
        </Button>
      );
    }
    const r = e.retrieval;
    if (busy(r)) {
      const percent = r!.size > 0 ? Math.min(100, Math.floor((r!.bytesDone / r!.size) * 100)) : 0;
      return (
        <span className={s.retrieving}>
          Retrieving · {percent}%
          <ProgressBar percent={percent} label={`Retrieving ${e.name}`} />
        </span>
      );
    }
    if (r?.state === "READY") {
      return (
        <Button small variant="ready" onClick={() => act(e)} aria-label={`Download ${e.name}`}>
          Download
        </Button>
      );
    }
    const pending = retrieve.isPending && retrieve.variables?.path === e.path;
    return (
      <span className={s.retrieveCell} style={wide ? { width: "100%" } : undefined}>
        {r?.state === "FAILED" && <span className={s.retrieveError}>Couldn't retrieve: {r.error}</span>}
        <Button
          small
          variant={r?.state === "FAILED" ? "secondary" : "primary"}
          onClick={() => act(e)}
          disabled={pending}
          aria-label={`${r?.state === "FAILED" ? "Try again to get" : "Get"} ${e.name}`}
        >
          {r?.state === "FAILED" ? "Try again" : "Get file"}
        </Button>
      </span>
    );
  };

  const name = (e: RemoteEntry) => (
    <button type="button" className={s.nameButton} onClick={() => act(e)}>
      {e.name}
    </button>
  );
  const icon = (e: RemoteEntry) => (
    <span className={s.icon} aria-hidden="true">
      {e.type === "folder" ? <FolderIcon size={20} /> : <FileIcon size={20} />}
    </span>
  );
  const cameFrom = (e: RemoteEntry) =>
    e.cameFrom ? <Badge kind={CAME_FROM[e.cameFrom].kind}>{CAME_FROM[e.cameFrom].label}</Badge> : "Folder";

  return (
    <div role="tabpanel" id={`panel-${destination.id}`} aria-labelledby={`tab-${destination.id}`}>
      <div className={s.remoteStrip}>
        <div className={s.remoteWhere}>
          <nav className={s.remotePath} aria-label={`Folder on ${destination.name}`}>
            <ServerIcon size={16} />
            {segments.length === 0 ? (
              <span>{data?.remoteDir ?? "…"}</span>
            ) : (
              <>
                <button type="button" onClick={() => setPath("")}>
                  {data?.remoteDir ?? "…"}
                </button>
                {segments.map((seg, i) => (
                  <span key={i}>
                    {" / "}
                    {i === segments.length - 1 ? (
                      <span aria-current="location">{seg}</span>
                    ) : (
                      <button type="button" onClick={() => setPath(segments.slice(0, i + 1).join("/"))}>
                        {seg}
                      </button>
                    )}
                  </span>
                ))}
              </>
            )}
          </nav>
          <span className="muted">On the destination server · read-only</span>
          {data && <span className="muted">Listed {listedAgo(data.listedAt, now)}</span>}
        </div>
        <Button small onClick={() => refresh.mutate()} disabled={refresh.isPending}>
          Refresh
        </Button>
      </div>

      <ErrorNote>{(refresh.error ?? (listing.isError ? listing.error : null))?.message}</ErrorNote>
      <ErrorNote>{retrieve.error?.message}</ErrorNote>

      {data && data.entries.length === 0 ? (
        <Empty title="Nothing here">This folder on {destination.name} is empty.</Empty>
      ) : data ? (
        <div className={s.listWrap}>
          <table className={s.table}>
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col" className={s.num}>
                  Size
                </th>
                <th scope="col">Modified</th>
                <th scope="col">Came from</th>
                <th scope="col" className={s.num}>
                  In fdrop
                </th>
              </tr>
            </thead>
            <tbody>
              {data.entries.map((e) => (
                <tr key={e.path}>
                  <td>
                    <span className={s.nameCell}>
                      {icon(e)}
                      {name(e)}
                    </span>
                  </td>
                  <td className={s.num}>{e.type === "file" ? formatBytes(e.size ?? 0) : "–"}</td>
                  <td>{e.modifiedAt ? formatDate(e.modifiedAt) : ""}</td>
                  <td>{cameFrom(e)}</td>
                  <td className={s.num}>{action(e)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <ul className={s.cards}>
            {data.entries.map((e) => (
              <li key={e.path} className={s.card} style={{ flexWrap: "wrap" }}>
                {icon(e)}
                <div className={s.cardBody}>
                  <div className={s.cardTitle}>{name(e)}</div>
                  <div className={s.cardMeta}>
                    {e.type === "file" && <span>{formatBytes(e.size ?? 0)}</span>}
                    {e.modifiedAt && <span>{formatDate(e.modifiedAt)}</span>}
                    {e.cameFrom && cameFrom(e)}
                  </div>
                  <div className={s.cardAction}>{action(e, true)}</div>
                </div>
              </li>
            ))}
          </ul>
          {data.truncated && (
            <p className="muted">Showing the first {data.maxEntries.toLocaleString()} entries, sorted by name.</p>
          )}
        </div>
      ) : listing.isLoading ? (
        <p className="muted">Listing {destination.name}…</p>
      ) : null}

      <p className="muted">
        Get file copies the file from the destination into fdrop for 24 hours, then downloads it. Nothing on the
        destination is changed.
      </p>
    </div>
  );
}
