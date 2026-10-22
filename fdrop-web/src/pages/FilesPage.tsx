import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useLocation } from "react-router";
import { del, downloadUrl, get, post, q } from "../api/client";
import type { Entry, FolderView, ForwardJob, Listing } from "../api/types";
import { DropTarget } from "../components/DropZone";
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
import { JobBadge } from "./status";
import s from "./pages.module.css";

const TITLES: Record<string, string> = { "/home": "Home", "/shared": "Shared folders", "/forward": "Forward folders" };

export function filesLink(path: string) {
  return "/files" + path.split("/").map((p) => (p ? encodeURIComponent(p) : p)).join("/");
}

function icon(e: Entry) {
  return e.type === "FILE" ? "▤" : e.type === "FORWARD_FOLDER" ? "➜" : "▣";
}

export function FilesPage() {
  const location = useLocation();
  const raw = decodeURIComponent(location.pathname.replace(/^\/files/, "")) || "/home";
  const path = raw.endsWith("/") && raw.length > 1 ? raw.slice(0, -1) : raw;
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
  const refresh = () => client.invalidateQueries({ queryKey: ["files"] });

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
  const entries = listing.data?.entries ?? [];
  const latestJob = new Map<string, ForwardJob>();
  for (const j of jobs.data ?? []) {
    if (!latestJob.has(j.fileName)) {
      latestJob.set(j.fileName, j);
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
    entries.length === 0 && !listing.isLoading ? (
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
              {inForward && <th scope="col">Forward status</th>}
              <th scope="col">
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {entries.map((e) => (
              <tr key={e.path} className={e.type === "DIR" ? s.dropRow : undefined} {...dropPath(e)}>
                <td>
                  <span className={s.nameCell}>
                    <span className={s.icon} aria-hidden="true">
                      {icon(e)}
                    </span>
                    {nameCell(e)}
                    {e.type === "SHARED_FOLDER" && !e.writable && <Badge kind="neutral">Read-only</Badge>}
                  </span>
                </td>
                <td className={s.num}>{e.type === "FILE" ? formatBytes(e.size) : ""}</td>
                <td>{e.modified ? formatDate(e.modified) : ""}</td>
                {inForward && <td>{latestJob.get(e.name) ? <JobBadge state={latestJob.get(e.name)!.state} /> : ""}</td>}
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
              <span className={s.icon} aria-hidden="true">
                {icon(e)}
              </span>
              <div className={s.cardBody}>
                <div className={s.cardTitle}>{nameCell(e)}</div>
                <div className={s.cardMeta}>
                  {e.type === "FILE" && <span>{formatBytes(e.size)}</span>}
                  {e.modified && <span>{formatDate(e.modified)}</span>}
                  {inForward && latestJob.get(e.name) && <JobBadge state={latestJob.get(e.name)!.state} />}
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
      <Breadcrumbs path={path} linkFor={filesLink} />
      <div className={s.head}>
        <h1>{title}</h1>
        <div className={s.actions}>
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
          Files dropped here are sent to <strong>{folder.data.destinationName ?? "its destination"}</strong>
          {folder.data.keepDays ? `, then kept for ${folder.data.keepDays} days.` : " and then removed from fdrop."}
          {folder.data.paused && " Forwarding is paused: the destination is disabled or no longer assigned."}
        </p>
      )}
      {folder.data && folder.data.role === "READ" && <p className="muted">You have read-only access to this folder.</p>}

      {isAreaRoot || !self ? (
        list
      ) : (
        <DropTarget
          targetPath={path}
          label="Drop files here or choose files"
          filesOnly={inForward}
          disabled={!writable || (inForward && folder.data?.paused)}
          disabledReason={
            inForward && folder.data?.paused
              ? "Uploads are paused because this folder's destination is disabled or no longer assigned."
              : undefined
          }
        >
          {list}
        </DropTarget>
      )}

      <NewFolderDialog open={newFolder} onOpenChange={setNewFolder} parent={path} onDone={refresh} />
      {renaming && <RenameDialog entry={renaming} onClose={() => setRenaming(undefined)} onDone={refresh} />}
      {deleting && <DeleteDialog entry={deleting} onClose={() => setDeleting(undefined)} onDone={refresh} />}
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
