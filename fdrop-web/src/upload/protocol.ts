/** Messages between the upload queue (main thread) and the upload worker. */

export interface StartMessage {
  type: "start";
  key: string;
  file: File;
  path: string;
  overwrite: boolean;
  /** The expiry to give the file ("6 days", "never"), or undefined for the folder's default. */
  expiresIn?: string;
  csrf?: string;
  maxParallel: number;
  hashMax: number;
  /** Resume an existing server session instead of creating one. */
  uploadId?: string;
}

export interface ControlMessage {
  type: "pause" | "resume" | "cancel";
  key: string;
}

export type ToWorker = StartMessage | ControlMessage;

export type WorkerState = "uploading" | "paused" | "offline" | "retrying" | "finishing" | "done" | "failed" | "cancelled";

export interface FromWorker {
  key: string;
  type: "created" | "progress" | "state" | "done" | "error";
  uploadId?: string;
  chunkSize?: number;
  /** Bytes in acknowledged chunks plus bytes sent so far in chunks in flight. */
  sent?: number;
  state?: WorkerState;
  message?: string;
  status?: number;
  path?: string;
}
