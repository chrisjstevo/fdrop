import { useCallback, useEffect, useRef, useState, type DragEvent, type ReactNode } from "react";
import { ApiError, post } from "../api/client";
import type { Me } from "../api/types";
import { useMe } from "../auth/useMe";
import { useUploadQueue } from "../upload/UploadContext";
import { UploadIcon } from "./icons";
import { formatBytes, join } from "../util/format";
import s from "./DropZone.module.css";

export interface Rejection {
  name: string;
  reason: string;
}

interface Collected {
  files: { file: File; rel: string }[];
  dirs: string[];
}

/** Reads dropped items, walking dropped folders so their sub-folder structure is kept. */
export async function collect(dt: DataTransfer): Promise<Collected> {
  const out: Collected = { files: [], dirs: [] };
  const entries: FileSystemEntry[] = [];
  const loose: File[] = [];
  for (const item of Array.from(dt.items ?? [])) {
    if (item.kind !== "file") {
      continue;
    }
    const entry = typeof item.webkitGetAsEntry === "function" ? item.webkitGetAsEntry() : null;
    if (entry) {
      entries.push(entry);
    } else {
      const f = item.getAsFile();
      if (f) {
        loose.push(f);
      }
    }
  }
  if (entries.length === 0 && loose.length === 0) {
    Array.from(dt.files ?? []).forEach((f) => out.files.push({ file: f, rel: f.name }));
    return out;
  }
  loose.forEach((f) => out.files.push({ file: f, rel: f.name }));
  for (const e of entries) {
    await walk(e, "", out);
  }
  return out;
}

async function walk(entry: FileSystemEntry, prefix: string, out: Collected): Promise<void> {
  const rel = prefix ? `${prefix}/${entry.name}` : entry.name;
  if (entry.isFile) {
    const file = await new Promise<File>((resolve, reject) => (entry as FileSystemFileEntry).file(resolve, reject));
    out.files.push({ file, rel });
    return;
  }
  if (entry.isDirectory) {
    out.dirs.push(rel);
    const reader = (entry as FileSystemDirectoryEntry).createReader();
    // readEntries returns results in batches until it returns an empty one
    for (;;) {
      const batch = await new Promise<FileSystemEntry[]>((resolve, reject) => reader.readEntries(resolve, reject));
      if (batch.length === 0) {
        break;
      }
      for (const child of batch) {
        await walk(child, rel, out);
      }
    }
  }
}

/** Checks size and quota before any bytes are sent. */
export function check(files: File[], me: Me | undefined, targetPath: string): { ok: File[]; rejected: Rejection[] } {
  const ok: File[] = [];
  const rejected: Rejection[] = [];
  if (!me) {
    return { ok: files, rejected };
  }
  let room = me.quotaBytes - me.usedBytes - me.reservedBytes;
  const ownQuota = targetPath === "/home" || targetPath.startsWith("/home/");
  for (const f of files) {
    if (f.size > me.settings.maxFileSize) {
      rejected.push({ name: f.name, reason: `larger than the ${formatBytes(me.settings.maxFileSize)} limit` });
    } else if (ownQuota && f.size > room) {
      rejected.push({ name: f.name, reason: `not enough quota left (${formatBytes(Math.max(0, room))} free)` });
    } else {
      ok.push(f);
      room -= f.size;
    }
  }
  return { ok, rejected };
}

function useCoarsePointerWithoutDrag() {
  const [coarse, setCoarse] = useState(false);
  useEffect(() => {
    const m = window.matchMedia?.("(pointer: coarse)");
    const ipadLike = navigator.maxTouchPoints > 1 && /Macintosh|iPad/.test(navigator.userAgent);
    setCoarse(!!m?.matches && !ipadLike && window.innerWidth < 640);
  }, []);
  return coarse;
}

/**
 * The drop zone above a folder's file list, and the drop target that covers the list while files are dragged
 * over the window. Dropping on a row marked with `data-drop-path` uploads into that sub-folder.
 */
export function DropTarget({
  targetPath,
  label,
  filesOnly,
  disabled,
  disabledReason,
  expiresIn,
  chooseRef,
  children,
}: {
  targetPath: string;
  /** Receives the function that opens the file picker, for an Upload button elsewhere on the page. */
  chooseRef?: { current: (() => void) | undefined };
  /** The expiry chosen for files dropped now; undefined for the folder's default. */
  expiresIn?: string;
  label: string;
  filesOnly?: boolean;
  disabled?: boolean;
  disabledReason?: string;
  children: ReactNode;
}) {
  const { queue } = useUploadQueue();
  const me = useMe();
  const input = useRef<HTMLInputElement>(null);
  const depth = useRef(0);
  const [over, setOver] = useState(false);
  const [overPath, setOverPath] = useState(targetPath);
  const [rejected, setRejected] = useState<Rejection[]>([]);
  const [error, setError] = useState<string>();
  const touchOnly = useCoarsePointerWithoutDrag();

  const enqueue = useCallback(
    async (c: Collected, dest: string) => {
      setError(undefined);
      if (filesOnly && c.dirs.length > 0) {
        setRejected(c.dirs.filter((d) => !d.includes("/")).map((d) => ({ name: d, reason: "forward folders accept files only" })));
        c = { files: c.files.filter((f) => !f.rel.includes("/")), dirs: [] };
      } else {
        setRejected([]);
      }
      const { ok, rejected: bad } = check(c.files.map((f) => f.file), me.data, dest);
      if (bad.length) {
        setRejected((r) => [...r, ...bad]);
      }
      const dirs = [...c.dirs].sort((a, b) => a.split("/").length - b.split("/").length);
      for (const d of dirs) {
        try {
          await post("/api/v1/files/mkdir", { path: join(dest, d) });
        } catch (e) {
          if (!(e instanceof ApiError && e.status === 409)) {
            setError(`Could not create folder ${d}: ${(e as Error).message}`);
            return;
          }
        }
      }
      const okSet = new Set(ok);
      for (const { file, rel } of c.files) {
        if (okSet.has(file)) {
          queue.add(file, join(dest, rel), { expiresIn });
        }
      }
    },
    [filesOnly, me.data, queue, expiresIn],
  );

  const pathFor = (target: EventTarget | null) => {
    const el = (target as HTMLElement | null)?.closest?.("[data-drop-path]") as HTMLElement | null;
    return el?.dataset.dropPath ?? targetPath;
  };

  const isFiles = (e: DragEvent) => Array.from(e.dataTransfer?.types ?? []).includes("Files");

  const onDragEnter = (e: DragEvent) => {
    if (!isFiles(e)) {
      return;
    }
    e.preventDefault();
    depth.current++;
    setOver(true);
  };
  const onDragOver = (e: DragEvent) => {
    if (!isFiles(e)) {
      return;
    }
    e.preventDefault();
    e.dataTransfer.dropEffect = disabled ? "none" : "copy";
    setOverPath(pathFor(e.target));
  };
  const onDragLeave = (e: DragEvent) => {
    if (!isFiles(e)) {
      return;
    }
    depth.current = Math.max(0, depth.current - 1);
    if (depth.current === 0) {
      setOver(false);
    }
  };
  const onDrop = async (e: DragEvent) => {
    e.preventDefault();
    depth.current = 0;
    setOver(false);
    if (disabled) {
      return;
    }
    const dest = pathFor(e.target);
    await enqueue(await collect(e.dataTransfer), dest);
  };

  const choose = () => input.current?.click();
  if (chooseRef) {
    chooseRef.current = disabled ? undefined : choose;
  }

  return (
    <div
      data-drop-target=""
      className={[s.target, over ? (disabled ? s.blocked : s.active) : ""].join(" ")}
      onDragEnter={onDragEnter}
      onDragOver={onDragOver}
      onDragLeave={onDragLeave}
      onDrop={(e) => void onDrop(e)}
    >
      {disabled && disabledReason && <p className={s.disabled}>{disabledReason}</p>}
      {children}
      {!disabled && (
        <button type="button" className={[s.zone, over ? s.zoneOver : ""].join(" ")} onClick={choose}>
          <span className={s.mark} aria-hidden="true" />
          <UploadIcon size={18} />
          <span className={s.text}>
            {over ? `Drop to upload to ${overPath}` : touchOnly ? "Tap to choose files" : label}
          </span>
        </button>
      )}
      <input
        ref={input}
        type="file"
        multiple
        className="visually-hidden"
        tabIndex={-1}
        aria-hidden="true"
        onChange={(e) => {
          const files = Array.from(e.target.files ?? []);
          e.target.value = "";
          void enqueue({ files: files.map((f) => ({ file: f, rel: f.name })), dirs: [] }, targetPath);
        }}
      />
      {(rejected.length > 0 || error) && (
        <div className={s.rejections} role="alert">
          {error && <p>{error}</p>}
          {rejected.length > 0 && <p className={s.rejTitle}>Not uploaded:</p>}
          <ul>
            {rejected.map((r) => (
              <li key={r.name + r.reason}>
                <strong>{r.name}</strong>: {r.reason}
              </li>
            ))}
          </ul>
          <button type="button" className={s.dismiss} onClick={() => { setRejected([]); setError(undefined); }}>
            Dismiss
          </button>
        </div>
      )}
    </div>
  );
}
