import { Badge } from "../components/ui";
import type { ForwardJob, JobState } from "../api/types";

const JOB: Record<JobState, { label: string; kind: "ok" | "neutral" | "warn" | "fail" }> = {
  QUEUED: { label: "Queued", kind: "neutral" },
  SENDING: { label: "Sending", kind: "neutral" },
  RETRY_WAIT: { label: "Retrying", kind: "warn" },
  SENT: { label: "Sent", kind: "ok" },
  FAILED: { label: "Failed", kind: "fail" },
  PAUSED: { label: "Paused", kind: "warn" },
  CANCELLED: { label: "Stopped", kind: "neutral" },
};

/**
 * A file's overall forward state from its jobs (one per destination), the worst first: failed, paused, sending
 * (also while one waits to retry), queued; sent once every job is sent or stopped, and stopped if none was sent.
 * Matches the server's RecordRepo.overall.
 */
export function overallState(states: JobState[]): JobState | undefined {
  if (states.length === 0) {
    return undefined;
  }
  for (const s of ["FAILED", "PAUSED"] as const) {
    if (states.includes(s)) {
      return s;
    }
  }
  if (states.includes("SENDING") || states.includes("RETRY_WAIT")) {
    return "SENDING";
  }
  if (states.includes("QUEUED")) {
    return "QUEUED";
  }
  return states.includes("SENT") ? "SENT" : "CANCELLED";
}

/** "2 of 3 sent" while a file with several destinations is part-way; nothing otherwise. */
export function sentCount(states: JobState[]): string | undefined {
  const sent = states.filter((s) => s === "SENT").length;
  const open = states.filter((s) => s !== "SENT" && s !== "CANCELLED").length;
  return states.length > 1 && open > 0 ? `${sent} of ${states.length} sent` : undefined;
}

/** A file's overall badge, with the count while part-way. */
export function FileForwardBadge({ states }: { states: JobState[] }) {
  const overall = overallState(states);
  if (!overall) {
    return null;
  }
  const count = sentCount(states);
  return (
    <span>
      <JobBadge state={overall} />
      {count && <span className="muted"> · {count}</span>}
    </span>
  );
}

export function JobBadge({ state }: { state: JobState }) {
  const j = JOB[state] ?? { label: state, kind: "neutral" as const };
  return <Badge kind={j.kind}>{j.label}</Badge>;
}

const RECORD: Record<string, { label: string; kind: "ok" | "neutral" | "warn" | "fail" }> = {
  STORED: { label: "Stored", kind: "ok" },
  MOVED: { label: "Moved", kind: "neutral" },
  DELETED: { label: "Deleted", kind: "neutral" },
  EXPIRED: { label: "Expired", kind: "neutral" },
  ...Object.fromEntries(Object.entries(JOB)),
};

export function RecordBadge({ status }: { status: string }) {
  const r = RECORD[status] ?? { label: status, kind: "neutral" as const };
  return <Badge kind={r.kind}>{r.label}</Badge>;
}

/** One file in a forward folder with its jobs, one per destination. */
export interface FileJobs {
  key: string;
  fileName: string;
  size: number;
  createdAt: string;
  jobs: ForwardJob[];
}

/** Groups jobs by file (newest first), keeping each file's destinations in order. */
export function groupJobs(jobs: ForwardJob[]): FileJobs[] {
  const byFile = new Map<string, FileJobs>();
  for (const j of jobs) {
    const key = j.uploadRecordId ?? j.id;
    const g = byFile.get(key) ?? { key, fileName: j.fileName, size: j.size, createdAt: j.createdAt, jobs: [] };
    g.jobs.push(j);
    byFile.set(key, g);
  }
  return [...byFile.values()];
}
