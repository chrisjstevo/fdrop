import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useLocation, useSearchParams } from "react-router";
import { del, downloadUrl, get, post, q } from "../api/client";
import type { Entry, FolderView, ForwardJob, HistoryPage, JobState, Listing, RemoteListing } from "../api/types";
import { DropTarget } from "../components/DropZone";
import { ExpiresCell, ExpiryPicker, SetExpiryDialog, type ExpiryChoice } from "../components/Expiry";
import { useProvideFolderActions } from "../components/FolderActions";
import { FileIcon, FolderIcon, ForwardIcon, ServerIcon } from "../components/icons";
import {
  Badge,
  Breadcrumbs,
  Button,
  Dialog,
  DialogActions,
  Empty,
  ErrorNote,
  Field,
  LinkButton,
  RowMenu,
} from "../components/ui";
import { formatBytes, formatDate, join, parentOf } from "../util/format";
import { RemoteTab, remoteKey, remoteUrl } from "./RemoteTab";
import { FileForwardBadge, groupJobs } from "./status";
import s from "./pages.module.css";

const TITLES: Record<string, string> = { "/home": "Home", "/shared": "Shared folders", "/forward": "Forward folders" };

export function filesLink(path: string) {
  return "/files" + path.split("/").map((p) => (p ? encodeURIComponent(p) : p)).join("/");
}

/** The file, folder or forward-arrow icon on its tile. */
function EntryIcon({ e }: { e: Entry }) {
  return (
    <span className={e.type === "FORWARD_FOLDER" ? `${s.icon} ${s.iconForward}` : s.icon} aria-hidden="true">
      {e.type === "FILE" ? <FileIcon size={20} /> : e.type === "FORWARD_FOLDER" ? <ForwardIcon size={20} /> : <FolderIcon size={20} />}
    </span>
  );
}

/** The last few uploads, as cards on Home. */
function Recent() {
  const recent = useQuery({
    queryKey: ["recent-uploads"],
    queryFn: () => get<HistoryPage>("/api/v1/me/uploads?limit=8"),
  });
  const rows = (recent.data?.rows ?? []).filter((r) => r.canDownload && r.currentPath && r.wentTo !== "FORWARD").slice(0, 4);
  if (rows.length === 0) {
    return null;
  }
  return (
    <section aria-labelledby="recent-heading">
      <h2 id="recent-heading" className={s.label}>
        Recent
      </h2>
      <div className={s.recent}>
        {rows.map((r) => (
          <Link key={r.id} to={filesLink(parentOf(r.currentPath!))} className={s.tile}>
            <span className={s.tileArt} aria-hidden="true">
              <FileIcon size={32} />
            </span>
            <span className={s.tileName}>{r.fileName}</span>
            <span className={s.tileMeta}>
              {formatBytes(r.size)} · {formatDate(r.completedAt)}
            </span>
          </Link>
        ))}
      </div>
    </section>
  );
}

export function FilesPage() {
  const location = useLocation();
  const raw = decodeURIComponent(location.pathname.replace(/^\/files/, "")) || "/home";
  const path = raw.endsWith("/") && raw.length > 1 ? raw.slice(0, -1) : raw;
  const [params, setParams] = useSearchParams();
  const search = (params.get("q") ?? "").trim().toLowerCase();
  // opened from a search result: that file is marked and scrolled to
  const highlight = params.get("highlight");
  const client = useQueryClient();
  const listing = useQuery({
    queryKey: ["files", path],
    queryFn: () => get<Listing>("/api/v1/files?path=" + q(path)),
    // Forward workers remove sent files, so keep a forward folder's listing in step with its jobs.
    refetchInterval: path.startsWith("/forward/") ? 5000 : false,
  });
  const [newFolder, setNewFolder] = useState(false);
  const [renaming, setRenaming] = useState<Entry>();
  const [deleting, setDeleting] = useState<Entry>();
  const [settingExpiry, setSettingExpiry] = useState<Entry>();
  const [expiry, setExpiry] = useState<ExpiryChoice>({});
  const refresh = () => client.invalidateQueries({ queryKey: ["files"] });
  const chooseRef = useRef<(() => void) | undefined>(undefined);
  const upload = useCallback(() => chooseRef.current?.(), []);
  const openNewFolder = useCallback(() => setNewFolder(true), []);

  const parts = path.split("/").filter(Boolean);
  const area = parts[0];
  const isAreaRoot = parts.length === 1 && (area === "shared" || area === "forward");
  const inForward = area === "forward" && parts.length >= 2;
  const self = listing.data?.self;
  const folderId = self?.folderId;

  const jobs = useQuery({
    queryKey: ["jobs", folderId],
    enabled: inForward && !!folderId,
    queryFn: () => get<ForwardJob[]>(`/api/v1/folders/${folderId}/forward-jobs`),
    refetchInterval: 5000,
  });
  const folder = useQuery({
    queryKey: ["folder", folderId],
    enabled: !!folderId,
    queryFn: () => get<FolderView>(`/api/v1/folders/${folderId}`),
  });
  // a forward folder's destination tabs: the ones an admin lets folders browse
  const browsable = inForward ? (folder.data?.destinations ?? []).filter((d) => d.browsable) : [];
  const tabId = params.get("tab");
  const openTab = browsable.find((d) => d.id === tabId);
  // each tab's count is its remote directory's size, listed once (the server reuses listings for 30 s)
  const remoteCounts = useQueries({
    queries: browsable.map((d) => ({
      queryKey: remoteKey(folderId ?? "", d.id),
      enabled: !!folderId && !d.paused,
      queryFn: () => get<RemoteListing>(remoteUrl(folderId!, d.id)),
      retry: false,
      staleTime: 30_000,
    })),
  });
  const selectTab = (id?: string) => {
    const next = new URLSearchParams(params);
    if (id) {
      next.set("tab", id);
    } else {
      next.delete("tab");
    }
    setParams(next, { replace: true });
  };

  // An expired file leaves the list at its expiry: refetch when the earliest one shown passes.
  const nextExpiry = Math.min(
    ...(listing.data?.entries ?? []).filter((e) => e.expiresAt).map((e) => new Date(e.expiresAt!).getTime()),
  );
  useEffect(() => {
    if (!Number.isFinite(nextExpiry)) {
      return;
    }
    const t = setTimeout(
      () => void client.invalidateQueries({ queryKey: ["files", path] }),
      Math.min(Math.max(0, nextExpiry - Date.now()) + 500, 2 ** 31 - 1),
    );
    return () => clearTimeout(t);
  }, [nextExpiry, client, path]);

  useEffect(() => {
    if (highlight && listing.data) {
      document.getElementById("highlighted-row")?.scrollIntoView({ block: "center" });
    }
  }, [highlight, listing.data]);

  // The sidebar's Upload and New folder act on this folder when it can take them.
  const canUpload = !!self?.writable && !isAreaRoot;
  useProvideFolderActions(canUpload ? upload : undefined, canUpload && !inForward ? openNewFolder : undefined);

  if (listing.isError) {
    return (
      <>
        <h1>{TITLES[path] ?? parts[parts.length - 1]}</h1>
        <ErrorNote>{(listing.error as Error).message}</ErrorNote>
        <LinkButton to="/files/home">Go to Home</LinkButton>
      </>
    );
  }

  const title = TITLES[path] ?? parts[parts.length - 1] ?? "Files";
  const writable = !!self?.writable && !isAreaRoot;
  const allEntries = listing.data?.entries ?? [];
  const entries = search ? allEntries.filter((e) => e.name.toLowerCase().includes(search)) : allEntries;
  const folderExpiry = listing.data?.folderExpiry;
  const expiryAllowed = !!folderExpiry?.allowed;
  // each file's latest upload, with one job per destination (jobs come newest first)
  const fileStates = new Map<string, JobState[]>();
  for (const g of groupJobs(jobs.data ?? [])) {
    if (!fileStates.has(g.fileName)) {
      fileStates.set(g.fileName, g.jobs.map((j) => j.state));
    }
  }

  const actionsFor = (e: Entry) => {
    const list = [];
    if (e.type === "FILE") {
      list.push({ label: "Download", onSelect: () => window.location.assign(downloadUrl(e.path)) });
    }
    if (writable && (e.type === "FILE" || e.type === "DIR") && !inForward) {
      list.push({ label: "Rename", onSelect: () => setRenaming(e) });
    }
    if (writable && e.type === "FILE" && expiryAllowed) {
      list.push({ label: "Set expiry…", onSelect: () => setSettingExpiry(e) });
    }
    if (writable && (e.type === "FILE" || e.type === "DIR")) {
      list.push({ label: "Delete", onSelect: () => setDeleting(e), danger: true });
    }
    if (e.folderId) {
      list.push({ label: "Folder settings", onSelect: () => window.location.assign(`/folders/${e.folderId}`) });
    }
    return list;
  };

  const nameCell = (e: Entry) =>
    e.type === "FILE" ? (
      <a href={downloadUrl(e.path)} download>
        {e.name}
      </a>
    ) : (
      <Link to={filesLink(e.path)}>{e.name}</Link>
    );

  const dropPath = (e: Entry) => (writable && e.type === "DIR" ? { "data-drop-path": e.path } : {});

  const list =
    entries.length === 0 && search && !listing.isLoading ? (
      <Empty title="No matches">Nothing in this folder has “{search}” in its name.</Empty>
    ) : entries.length === 0 && !listing.isLoading ? (
      <Empty title={isAreaRoot ? "No folders yet" : "This folder is empty"}>
        {isAreaRoot
          ? area === "shared"
            ? "Create a shared folder to exchange files with other users."
            : "Create a forward folder to send files on to a partner's SFTP server."
          : writable
            ? "Drop files above, or choose them with the button."
            : undefined}
      </Empty>
    ) : (
      <div className={s.listWrap}>
        <table className={s.table}>
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col" className={s.num}>
                Size
              </th>
              <th scope="col">Modified</th>
              {!isAreaRoot && <th scope="col">Expires</th>}
              {inForward && <th scope="col">Forward status</th>}
              <th scope="col">
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {entries.map((e) => (
              <tr
                key={e.path}
                id={e.name === highlight ? "highlighted-row" : undefined}
                className={[e.type === "DIR" ? s.dropRow : "", e.name === highlight ? s.highlighted : ""].join(" ")}
                {...dropPath(e)}
              >
                <td>
                  <span className={s.nameCell}>
                    <EntryIcon e={e} />
                    {nameCell(e)}
                    {e.type === "SHARED_FOLDER" && !e.writable && <Badge kind="neutral">Read-only</Badge>}
                  </span>
                </td>
                <td className={s.num}>{e.type === "FILE" ? formatBytes(e.size) : ""}</td>
                <td>{e.modified ? formatDate(e.modified) : ""}</td>
                {!isAreaRoot && (
                  <td>
                    <ExpiresCell entry={e} />
                  </td>
                )}
                {inForward && <td>{fileStates.get(e.name) ? <FileForwardBadge states={fileStates.get(e.name)!} /> : ""}</td>}
                <td className={s.num}>
                  {actionsFor(e).length > 0 && <RowMenu label={`Actions for ${e.name}`} actions={actionsFor(e)} />}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <ul className={s.cards}>
          {entries.map((e) => (
            <li key={e.path} className={s.card} {...dropPath(e)}>
              <EntryIcon e={e} />
              <div className={s.cardBody}>
                <div className={s.cardTitle}>{nameCell(e)}</div>
                <div className={s.cardMeta}>
                  {e.type === "FILE" && <span>{formatBytes(e.size)}</span>}
                  {e.modified && <span>{formatDate(e.modified)}</span>}
                  <ExpiresCell entry={e} />
                  {inForward && fileStates.get(e.name) && <FileForwardBadge states={fileStates.get(e.name)!} />}
                </div>
              </div>
              {actionsFor(e).length > 0 && <RowMenu label={`Actions for ${e.name}`} actions={actionsFor(e)} />}
            </li>
          ))}
        </ul>
      </div>
    );

  return (
    <>
      <div className={s.head}>
        <div className={s.titleBlock}>
          {parts.length > 1 && <Breadcrumbs path={parentOf(path)} linkFor={filesLink} />}
          <h1>{title}</h1>
        </div>
        <div className={s.actions}>
          {writable && expiryAllowed && (
            <ExpiryPicker key={path} folder={folderExpiry} onChange={setExpiry} label="Expires" />
          )}
          {writable && !inForward && (
            <Button onClick={() => setNewFolder(true)}>New folder</Button>
          )}
          {isAreaRoot && (
            <LinkButton to={`/folders?create=${area}`} variant="primary">
              {area === "shared" ? "New shared folder" : "New forward folder"}
            </LinkButton>
          )}
          {folderId && <LinkButton to={`/folders/${folderId}`}>Folder settings</LinkButton>}
        </div>
      </div>
      {folder.data?.type === "FORWARD" && (
        <p className="muted">
          Files dropped here are sent to <strong>{folder.data.destinationName ?? "its destinations"}</strong>
          {folder.data.keepDays ? `, then kept for ${folder.data.keepDays} days.` : " and then removed from fdrop."}
          {folder.data.paused && " Forwarding is paused: a destination is disabled or no longer assigned."}
        </p>
      )}
      {folder.data && folder.data.role === "READ" && <p className="muted">You have read-only access to this folder.</p>}

      {browsable.length > 0 && (
        <div
          role="tablist"
          aria-label="Folder views"
          className={s.tabs}
          onKeyDown={(ev) => {
            // arrow keys move between tabs, as ARIA tabs do
            const ids = [undefined, ...browsable.map((d) => d.id)];
            const at = ids.indexOf(openTab?.id);
            const step = ev.key === "ArrowRight" ? 1 : ev.key === "ArrowLeft" ? -1 : 0;
            if (step) {
              ev.preventDefault();
              const next = ids[(at + step + ids.length) % ids.length];
              selectTab(next);
              document.getElementById(`tab-${next ?? "local"}`)?.focus();
            }
          }}
        >
          <button
            type="button"
            role="tab"
            id="tab-local"
            aria-selected={!openTab}
            aria-controls="panel-local"
            tabIndex={openTab ? -1 : 0}
            className={s.tab}
            onClick={() => selectTab(undefined)}
          >
            In fdrop <span className={s.count}>{allEntries.length}</span>
          </button>
          {browsable.map((d, i) => {
            const count = remoteCounts[i]?.data?.entries.length;
            return (
              <button
                key={d.id}
                type="button"
                role="tab"
                id={`tab-${d.id}`}
                aria-selected={openTab?.id === d.id}
                aria-controls={`panel-${d.id}`}
                tabIndex={openTab?.id === d.id ? 0 : -1}
                className={s.tab}
                onClick={() => selectTab(d.id)}
              >
                <ServerIcon size={16} />
                {d.name}
                {d.paused ? (
                  <Badge kind="warn">Not available</Badge>
                ) : count !== undefined ? (
                  <span className={s.count}>{count}</span>
                ) : null}
              </button>
            );
          })}
        </div>
      )}
      {openTab && folderId ? (
        <RemoteTab key={openTab.id} folderId={folderId} destination={openTab} />
      ) : (
        <div {...(browsable.length > 0 ? { role: "tabpanel", id: "panel-local", "aria-labelledby": "tab-local" } : {})}>
          {path === "/home" && !search && <Recent />}
          {!isAreaRoot && entries.length + allEntries.length > 0 && (
            <h2 className={s.label}>{search ? `Matching “${search}”` : "All files"}</h2>
          )}
          {isAreaRoot || !self ? (
            list
          ) : (
            <DropTarget
              targetPath={path}
              label={`Drop files anywhere, or choose files, to upload to ${title}`}
              chooseRef={chooseRef}
              filesOnly={inForward}
              expiresIn={expiryAllowed ? expiry.value : undefined}
              disabled={!writable || (inForward && folder.data?.paused) || (expiryAllowed && !!expiry.error)}
              disabledReason={
                inForward && folder.data?.paused
                  ? "Uploads are paused because one of this folder's destinations is disabled or no longer assigned."
                  : expiryAllowed && expiry.error
                    ? "Fix the expiry above to upload."
                    : undefined
              }
            >
              {list}
            </DropTarget>
          )}
        </div>
      )}

      <NewFolderDialog open={newFolder} onOpenChange={setNewFolder} parent={path} onDone={refresh} />
      {renaming && <RenameDialog entry={renaming} onClose={() => setRenaming(undefined)} onDone={refresh} />}
      {deleting && <DeleteDialog entry={deleting} onClose={() => setDeleting(undefined)} onDone={refresh} />}
      {settingExpiry && (
        <SetExpiryDialog
          entry={settingExpiry}
          folder={folderExpiry}
          onClose={() => setSettingExpiry(undefined)}
          onDone={refresh}
        />
      )}
    </>
  );
}

function NewFolderDialog({
  open,
  onOpenChange,
  parent,
  onDone,
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  parent: string;
  onDone: () => void;
}) {
  const [name, setName] = useState("");
  const m = useMutation({
    mutationFn: () => post("/api/v1/files/mkdir", { path: join(parent, name.trim()) }),
    onSuccess: () => {
      setName("");
      onOpenChange(false);
      onDone();
    },
  });
  return (
    <Dialog open={open} onOpenChange={onOpenChange} title="New folder">
      <form
        onSubmit={(e) => {
          e.preventDefault();
          m.mutate();
        }}
      >
        <Field label="Name" htmlFor="new-folder-name">
          <input id="new-folder-name" type="text" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
        </Field>
        <ErrorNote>{m.error?.message}</ErrorNote>
        <DialogActions>
          <Button onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button type="submit" variant="primary" disabled={!name.trim() || m.isPending}>
            Create
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

function RenameDialog({ entry, onClose, onDone }: { entry: Entry; onClose: () => void; onDone: () => void }) {
  const [name, setName] = useState(entry.name);
  const m = useMutation({
    mutationFn: () => post("/api/v1/files/move", { from: entry.path, to: join(parentOf(entry.path), name.trim()) }),
    onSuccess: () => {
      onClose();
      onDone();
    },
  });
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={`Rename ${entry.name}`}>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          m.mutate();
        }}
      >
        <Field label="New name" htmlFor="rename-name">
          <input id="rename-name" type="text" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
        </Field>
        <ErrorNote>{m.error?.message}</ErrorNote>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="primary" disabled={!name.trim() || name === entry.name || m.isPending}>
            Rename
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

function DeleteDialog({ entry, onClose, onDone }: { entry: Entry; onClose: () => void; onDone: () => void }) {
  const m = useMutation({
    mutationFn: () => del(`/api/v1/files?path=${q(entry.path)}`),
    onSuccess: () => {
      onClose();
      onDone();
    },
  });
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={`Delete ${entry.name}?`}
      description={entry.type === "DIR" ? "Only empty folders can be deleted." : "This cannot be undone."}
    >
      <ErrorNote>{m.error?.message}</ErrorNote>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="danger" onClick={() => m.mutate()} disabled={m.isPending}>
          Delete
        </Button>
      </DialogActions>
    </Dialog>
  );
}
