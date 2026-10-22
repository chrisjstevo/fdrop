/**
 * In-progress uploads remembered in IndexedDB (upload id, file name, size, lastModified, chunk size), so that after
 * a reload the queue can offer to resume once the user re-selects the same file. Every call tolerates IndexedDB
 * being unavailable (private windows, blocked storage): the queue then simply forgets on reload.
 */
export interface PendingUpload {
  key: string;
  uploadId: string;
  name: string;
  size: number;
  lastModified: number;
  chunkSize: number;
  path: string;
  createdAt: number;
}

const DB = "fdrop-uploads";
const STORE = "pending";

function open(): Promise<IDBDatabase | null> {
  return new Promise((resolve) => {
    try {
      const req = indexedDB.open(DB, 1);
      req.onupgradeneeded = () => {
        req.result.createObjectStore(STORE, { keyPath: "key" });
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => resolve(null);
    } catch {
      resolve(null);
    }
  });
}

async function tx<T>(mode: IDBTransactionMode, fn: (s: IDBObjectStore) => IDBRequest<T>): Promise<T | undefined> {
  const db = await open();
  if (!db) {
    return undefined;
  }
  return new Promise((resolve) => {
    try {
      const t = db.transaction(STORE, mode);
      const req = fn(t.objectStore(STORE));
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => resolve(undefined);
    } catch {
      resolve(undefined);
    }
  });
}

export async function savePending(p: PendingUpload) {
  await tx("readwrite", (s) => s.put(p));
}

export async function removePending(key: string) {
  await tx("readwrite", (s) => s.delete(key));
}

export async function listPending(): Promise<PendingUpload[]> {
  const all = await tx<PendingUpload[]>("readonly", (s) => s.getAll() as IDBRequest<PendingUpload[]>);
  return all ?? [];
}

/** The pending upload a re-selected file continues, matched by name, size and last-modified time. */
export function matchPending(pending: PendingUpload[], file: File): PendingUpload | undefined {
  return pending.find((p) => p.name === file.name && p.size === file.size && p.lastModified === file.lastModified);
}
