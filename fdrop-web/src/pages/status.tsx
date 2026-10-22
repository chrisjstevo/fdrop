import { Badge } from "../components/ui";
import type { JobState } from "../api/types";

const JOB: Record<JobState, { label: string; kind: "ok" | "neutral" | "warn" | "fail" }> = {
  QUEUED: { label: "Queued", kind: "neutral" },
  SENDING: { label: "Sending", kind: "neutral" },
  RETRY_WAIT: { label: "Retrying", kind: "warn" },
  SENT: { label: "Sent", kind: "ok" },
  FAILED: { label: "Failed", kind: "fail" },
  PAUSED: { label: "Paused", kind: "warn" },
};

export function JobBadge({ state }: { state: JobState }) {
  const j = JOB[state] ?? { label: state, kind: "neutral" as const };
  return <Badge kind={j.kind}>{j.label}</Badge>;
}

const RECORD: Record<string, { label: string; kind: "ok" | "neutral" | "warn" | "fail" }> = {
  STORED: { label: "Stored", kind: "ok" },
  MOVED: { label: "Moved", kind: "neutral" },
  DELETED: { label: "Deleted", kind: "neutral" },
  ...Object.fromEntries(Object.entries(JOB)),
};

export function RecordBadge({ status }: { status: string }) {
  const r = RECORD[status] ?? { label: status, kind: "neutral" as const };
  return <Badge kind={r.kind}>{r.label}</Badge>;
}
