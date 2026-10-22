/// <reference lib="webworker" />
/**
 * The upload worker: runs resumable chunked uploads off the main thread. It uses XMLHttpRequest rather than fetch
 * because only XHR reports upload progress. Chunks go up to `maxParallel` at a time, each with its SHA-256; failures
 * retry with backoff (1 s doubling to 60 s, with jitter); while the browser is offline it waits for the `online`
 * event. The whole-file hash is computed incrementally while chunks are read, for files under `hashMax`.
 */
import { Sha256, sha256HexOf } from "./sha256";
import type { FromWorker, StartMessage, ToWorker, WorkerState } from "./protocol";

declare const self: DedicatedWorkerGlobalScope;

interface Job {
  start: StartMessage;
  uploadId?: string;
  chunkSize: number;
  chunkCount: number;
  received: Set<number>;
  inflight: Map<number, { xhr: XMLHttpRequest; loaded: number }>;
  retryAt: Map<number, number>;
  attempts: Map<number, number>;
  ackBytes: number;
  paused: boolean;
  cancelled: boolean;
  failed: boolean;
  hasher?: Sha256;
  wake?: () => void;
  lastProgressAt: number;
}

const jobs = new Map<string, Job>();

function post(msg: FromWorker) {
  self.postMessage(msg);
}

function wake(job: Job) {
  const w = job.wake;
  job.wake = undefined;
  w?.();
}

function sleepUntilWoken(job: Job, ms?: number): Promise<void> {
  return new Promise((resolve) => {
    job.wake = resolve;
    if (ms !== undefined) {
      setTimeout(() => wake(job), Math.max(0, ms));
    }
  });
}

function setState(job: Job, state: WorkerState) {
  post({ key: job.start.key, type: "state", state });
}

function progress(job: Job, force = false) {
  const now = Date.now();
  if (!force && now - job.lastProgressAt < 250) {
    return;
  }
  job.lastProgressAt = now;
  let sent = job.ackBytes;
  for (const f of job.inflight.values()) {
    sent += f.loaded;
  }
  post({ key: job.start.key, type: "progress", sent });
}

class HttpError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}

async function call(job: Job, method: string, path: string, body?: unknown): Promise<any> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  if (job.start.csrf) {
    headers["X-Fdrop-CSRF"] = job.start.csrf;
  }
  const r = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    credentials: "same-origin",
  });
  const text = await r.text();
  let json: any = undefined;
  try {
    json = text ? JSON.parse(text) : undefined;
  } catch {
    // not JSON
  }
  if (!r.ok) {
    throw new HttpError(r.status, json?.detail ?? json?.title ?? r.statusText);
  }
  return { status: r.status, body: json };
}

function chunkRange(job: Job, i: number): [number, number] {
  const start = i * job.chunkSize;
  return [start, Math.min(job.start.file.size, start + job.chunkSize)];
}

async function readChunk(job: Job, i: number): Promise<ArrayBuffer> {
  const [a, b] = chunkRange(job, i);
  return job.start.file.slice(a, b).arrayBuffer();
}

function backoff(attempt: number): number {
  const base = Math.min(60_000, 1000 * 2 ** Math.max(0, attempt - 1));
  return base / 2 + Math.random() * (base / 2);
}

function sendChunk(job: Job, i: number, buf: ArrayBuffer, sha: string): Promise<void> {
  return new Promise((resolve) => {
    const xhr = new XMLHttpRequest();
    const entry = { xhr, loaded: 0 };
    job.inflight.set(i, entry);
    xhr.open("PUT", `/api/v1/uploads/${encodeURIComponent(job.uploadId!)}/chunks/${i}`);
    xhr.setRequestHeader("X-Chunk-SHA256", sha);
    if (job.start.csrf) {
      xhr.setRequestHeader("X-Fdrop-CSRF", job.start.csrf);
    }
    xhr.upload.onprogress = (e) => {
      entry.loaded = Math.min(e.loaded, buf.byteLength);
      progress(job);
    };
    const settle = (ok: boolean, status: number, message: string) => {
      job.inflight.delete(i);
      if (ok) {
        job.received.add(i);
        job.ackBytes += buf.byteLength;
        job.attempts.delete(i);
      } else if (!job.cancelled && !job.paused) {
        if (status === 401) {
          fail(job, "your session expired; sign in again to resume", 401);
        } else if (status === 400 || status === 403 || status === 404 || status === 413 || status === 507
            || (status === 409 && !message.includes("being uploaded"))) {
          fail(job, message || `chunk ${i} was refused (${status})`, status);
        } else {
          const n = (job.attempts.get(i) ?? 0) + 1;
          job.attempts.set(i, n);
          job.retryAt.set(i, Date.now() + backoff(n));
          setState(job, self.navigator.onLine ? "retrying" : "offline");
        }
      }
      progress(job, true);
      wake(job);
      resolve();
    };
    xhr.onload = () => {
      let message = "";
      try {
        message = JSON.parse(xhr.responseText || "{}").detail ?? "";
      } catch {
        // not JSON
      }
      settle(xhr.status >= 200 && xhr.status < 300, xhr.status, message);
    };
    xhr.onerror = () => settle(false, 0, "network error");
    xhr.ontimeout = () => settle(false, 0, "timed out");
    xhr.onabort = () => settle(false, -1, "aborted");
    xhr.timeout = 10 * 60 * 1000;
    xhr.send(buf);
  });
}

function fail(job: Job, message: string, status?: number) {
  if (job.failed) {
    return;
  }
  job.failed = true;
  for (const f of job.inflight.values()) {
    f.xhr.abort();
  }
  post({ key: job.start.key, type: "error", message, status });
  setState(job, "failed");
  wake(job);
}

async function run(job: Job) {
  const s = job.start;
  try {
    if (!job.uploadId) {
      const r = await call(job, "POST", "/api/v1/uploads", {
        path: s.path,
        size: s.file.size,
        lastModified: s.file.lastModified,
        overwrite: s.overwrite,
      });
      job.uploadId = r.body.id;
      job.chunkSize = r.body.chunkSize;
      job.chunkCount = r.body.chunkCount;
    } else {
      const r = await call(job, "GET", `/api/v1/uploads/${encodeURIComponent(job.uploadId)}`);
      if (r.body.state === "COMPLETED") {
        post({ key: s.key, type: "done", path: r.body.path });
        setState(job, "done");
        return;
      }
      if (r.body.state !== "OPEN") {
        throw new HttpError(404, "the server no longer has this upload (" + r.body.state.toLowerCase() + ")");
      }
      job.chunkSize = r.body.chunkSize;
      job.chunkCount = r.body.chunkCount;
      for (const [a, b] of r.body.received ?? []) {
        for (let i = a; i <= b; i++) {
          job.received.add(i);
          job.ackBytes += chunkRange(job, i)[1] - chunkRange(job, i)[0];
        }
      }
    }
    post({ key: s.key, type: "created", uploadId: job.uploadId, chunkSize: job.chunkSize });
    if (s.file.size <= s.hashMax) {
      job.hasher = new Sha256();
    }
    setState(job, "uploading");
    progress(job, true);

    let next = 0;
    while (!job.cancelled && !job.failed) {
      if (job.paused) {
        setState(job, "paused");
        await sleepUntilWoken(job);
        continue;
      }
      if (!self.navigator.onLine) {
        setState(job, "offline");
        await sleepUntilWoken(job, 5000);
        continue;
      }
      // retries first, once their backoff has passed
      const now = Date.now();
      let soonest = Number.POSITIVE_INFINITY;
      for (const [i, at] of job.retryAt) {
        if (job.inflight.size >= s.maxParallel) {
          break;
        }
        if (at <= now) {
          job.retryAt.delete(i);
          const buf = await readChunk(job, i);
          void sendChunk(job, i, buf, await sha256HexOf(buf));
          setState(job, "uploading");
        } else {
          soonest = Math.min(soonest, at);
        }
      }
      while (job.inflight.size < s.maxParallel && next < job.chunkCount && !job.paused && !job.cancelled) {
        const i = next++;
        const buf = await readChunk(job, i);
        job.hasher?.update(new Uint8Array(buf));
        if (job.received.has(i)) {
          continue;
        }
        void sendChunk(job, i, buf, await sha256HexOf(buf));
      }
      if (job.inflight.size === 0 && job.retryAt.size === 0 && next >= job.chunkCount) {
        break;
      }
      await sleepUntilWoken(job, Number.isFinite(soonest) ? soonest - Date.now() : undefined);
    }
    if (job.cancelled || job.failed) {
      return;
    }

    setState(job, "finishing");
    progress(job, true);
    const body = job.hasher ? { sha256: job.hasher.digestHex() } : {};
    let r = await call(job, "POST", `/api/v1/uploads/${encodeURIComponent(job.uploadId!)}/complete`, body);
    while (r.status === 202 || r.body?.state === "ASSEMBLING") {
      await new Promise((res) => setTimeout(res, 1000));
      r = await call(job, "GET", `/api/v1/uploads/${encodeURIComponent(job.uploadId!)}`);
      if (r.body.state === "FAILED") {
        throw new HttpError(409, r.body.error ?? "assembly failed");
      }
    }
    post({ key: s.key, type: "done", path: r.body?.path ?? s.path });
    setState(job, "done");
  } catch (e) {
    if (job.cancelled) {
      return;
    }
    if (e instanceof HttpError) {
      fail(job, e.message, e.status);
    } else if (!self.navigator.onLine || e instanceof TypeError) {
      // network failure before the session exists or while completing: try again shortly
      setState(job, "offline");
      await sleepUntilWoken(job, 5000);
      if (!job.cancelled && !job.failed) {
        await run(job);
      }
    } else {
      fail(job, String(e));
    }
  } finally {
    if (!job.paused) {
      jobs.delete(job.start.key);
    }
  }
}

self.addEventListener("online", () => jobs.forEach((j) => wake(j)));

self.onmessage = (e: MessageEvent<ToWorker>) => {
  const msg = e.data;
  if (msg.type === "start") {
    const job: Job = {
      start: msg,
      uploadId: msg.uploadId,
      chunkSize: 0,
      chunkCount: 0,
      received: new Set(),
      inflight: new Map(),
      retryAt: new Map(),
      attempts: new Map(),
      ackBytes: 0,
      paused: false,
      cancelled: false,
      failed: false,
      lastProgressAt: 0,
    };
    jobs.set(msg.key, job);
    void run(job);
    return;
  }
  const job = jobs.get(msg.key);
  if (!job) {
    return;
  }
  if (msg.type === "pause") {
    job.paused = true;
    for (const [i, f] of job.inflight) {
      f.xhr.abort();
      job.retryAt.set(i, 0);
    }
    setState(job, "paused");
    wake(job);
  } else if (msg.type === "resume") {
    job.paused = false;
    setState(job, "uploading");
    wake(job);
  } else if (msg.type === "cancel") {
    job.cancelled = true;
    for (const f of job.inflight.values()) {
      f.xhr.abort();
    }
    if (job.uploadId) {
      void call(job, "DELETE", `/api/v1/uploads/${encodeURIComponent(job.uploadId)}`).catch(() => undefined);
    }
    setState(job, "cancelled");
    jobs.delete(msg.key);
    wake(job);
  }
};
