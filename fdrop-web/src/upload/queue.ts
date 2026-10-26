import { getCsrfToken } from "../api/client";
import { nextPercent, overallPercent, SpeedMeter } from "./progress";
import { listPending, matchPending, removePending, savePending, type PendingUpload } from "./persist";
import type { FromWorker, ToWorker, WorkerState } from "./protocol";

export interface QueueItem {
  key: string;
  name: string;
  path: string;
  size: number;
  sent: number;
  percent: number;
  state: WorkerState | "queued";
  error?: string;
  speed: number;
  secondsLeft: number;
  /** The forward folder this went to, so the row can show forward status after upload. */
  forward: boolean;
  uploadId?: string;
  /** The expiry chosen for this file, if any. */
  expiresIn?: string;
}

export interface Settings {
  maxParallelChunks: number;
  clientHashMax: number;
}

type Listener = () => void;

/**
 * The upload queue the UI shows: one entry per file, fed by the upload worker. The percentage rules (never down,
 * 99% while finishing) live in {@link nextPercent}.
 */
export class UploadQueue {
  private items = new Map<string, QueueItem>();
  private order: string[] = [];
  private meters = new Map<string, SpeedMeter>();
  private listeners = new Set<Listener>();
  private worker: Worker | null = null;
  private files = new Map<string, File>();
  private snapshot: QueueItem[] = [];
  pending: PendingUpload[] = [];
  private settings: Settings = { maxParallelChunks: 3, clientHashMax: 2 * 1024 ** 3 };
  /** Called whenever an upload finishes, so listings can refresh. */
  onDone?: (item: QueueItem) => void;

  constructor(private readonly workerFactory: () => Worker) {
    void listPending().then((p) => {
      this.pending = p;
      this.emit();
    });
  }

  configure(s: Settings) {
    this.settings = s;
  }

  subscribe(l: Listener): () => void {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  }

  getSnapshot = (): QueueItem[] => this.snapshot;

  private emit() {
    this.snapshot = this.order.map((k) => this.items.get(k)!).filter(Boolean);
    this.listeners.forEach((l) => l());
  }

  overall(): number {
    const active = this.snapshot.filter((i) => i.state !== "cancelled" && i.state !== "failed");
    return overallPercent(active.map((i) => ({ size: i.size, sent: i.sent, done: i.state === "done" })));
  }

  private ensureWorker(): Worker {
    if (!this.worker) {
      this.worker = this.workerFactory();
      this.worker.onmessage = (e: MessageEvent<FromWorker>) => this.handle(e.data);
    }
    return this.worker;
  }

  private send(msg: ToWorker) {
    this.ensureWorker().postMessage(msg);
  }

  /** Queues a file for upload to `path`. Resumes an earlier session for the same file if one is pending. */
  add(file: File, path: string, opts: { overwrite?: boolean; forward?: boolean; expiresIn?: string } = {}): string {
    const resume = matchPending(this.pending, file);
    const key = resume?.key ?? `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
    if (resume) {
      this.pending = this.pending.filter((p) => p.key !== resume.key);
    }
    this.files.set(key, file);
    this.items.set(key, {
      key,
      name: file.name,
      path: resume?.path ?? path,
      size: file.size,
      sent: 0,
      percent: 0,
      state: "queued",
      speed: 0,
      secondsLeft: Number.POSITIVE_INFINITY,
      forward: opts.forward ?? path.startsWith("/forward/"),
      uploadId: resume?.uploadId,
      expiresIn: opts.expiresIn,
    });
    if (!this.order.includes(key)) {
      this.order.unshift(key);
    }
    this.meters.set(key, new SpeedMeter());
    this.send({
      type: "start",
      key,
      file,
      path: resume?.path ?? path,
      overwrite: !!opts.overwrite,
      expiresIn: opts.expiresIn,
      csrf: getCsrfToken(),
      maxParallel: this.settings.maxParallelChunks,
      hashMax: this.settings.clientHashMax,
      uploadId: resume?.uploadId,
    });
    this.emit();
    return key;
  }

  pause(key: string) {
    this.send({ type: "pause", key });
  }

  resume(key: string) {
    this.send({ type: "resume", key });
  }

  cancel(key: string) {
    this.send({ type: "cancel", key });
    void removePending(key);
    this.update(key, { state: "cancelled" });
  }

  dismiss(key: string) {
    this.items.delete(key);
    this.order = this.order.filter((k) => k !== key);
    this.emit();
  }

  forgetPending(key: string) {
    this.pending = this.pending.filter((p) => p.key !== key);
    void removePending(key);
    this.emit();
  }

  clearFinished() {
    for (const [k, v] of this.items) {
      if (v.state === "done" || v.state === "cancelled") {
        this.items.delete(k);
      }
    }
    this.order = this.order.filter((k) => this.items.has(k));
    this.emit();
  }

  private update(key: string, patch: Partial<QueueItem>) {
    const cur = this.items.get(key);
    if (!cur) {
      return;
    }
    this.items.set(key, { ...cur, ...patch });
    this.emit();
  }

  /** Applies one worker message. Public for tests. */
  handle(m: FromWorker) {
    const cur = this.items.get(m.key);
    if (!cur) {
      return;
    }
    switch (m.type) {
      case "created": {
        const file = this.files.get(m.key);
        if (file && m.uploadId) {
          void savePending({
            key: m.key,
            uploadId: m.uploadId,
            name: file.name,
            size: file.size,
            lastModified: file.lastModified,
            chunkSize: m.chunkSize ?? 0,
            path: cur.path,
            createdAt: Date.now(),
          });
        }
        this.update(m.key, { uploadId: m.uploadId });
        break;
      }
      case "progress": {
        const sent = m.sent ?? cur.sent;
        const meter = this.meters.get(m.key)!;
        meter.add(Date.now(), sent);
        this.update(m.key, {
          sent: Math.max(cur.sent, sent),
          percent: nextPercent(cur.percent, sent, cur.size, false),
          speed: meter.bytesPerSecond(),
          secondsLeft: meter.secondsRemaining(cur.size - sent),
        });
        break;
      }
      case "state": {
        const state = m.state!;
        if (state === "cancelled" && cur.state === "done") {
          return;
        }
        this.update(m.key, { state });
        break;
      }
      case "done": {
        void removePending(m.key);
        this.files.delete(m.key);
        const done = { ...cur, state: "done" as const, sent: cur.size, percent: 100, secondsLeft: 0 };
        this.items.set(m.key, done);
        this.emit();
        this.onDone?.(done);
        break;
      }
      case "error": {
        if (m.status !== 401) {
          void removePending(m.key);
        }
        this.update(m.key, { state: "failed", error: m.message });
        break;
      }
    }
  }
}

export function createQueue(): UploadQueue {
  return new UploadQueue(() => new Worker(new URL("./worker.ts", import.meta.url), { type: "module" }));
}
