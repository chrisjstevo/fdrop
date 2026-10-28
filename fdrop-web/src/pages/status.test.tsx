import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { ForwardJob, JobState } from "../api/types";
import { FileForwardBadge, groupJobs, overallState, sentCount } from "./status";

const job = (over: Partial<ForwardJob>): ForwardJob => ({
  id: "j",
  destinationId: "d",
  destinationName: "Partner A",
  fileName: "a.pdf",
  size: 1,
  state: "QUEUED",
  attempts: 0,
  createdAt: "2026-10-28T10:00:00Z",
  ...over,
});

describe("forward status with several destinations", () => {
  it("puts the worst state first, as the server does", () => {
    expect(overallState([])).toBeUndefined();
    expect(overallState(["SENT", "FAILED", "PAUSED"])).toBe("FAILED");
    expect(overallState(["SENT", "PAUSED", "SENDING"])).toBe("PAUSED");
    expect(overallState(["QUEUED", "RETRY_WAIT"])).toBe("SENDING");
    expect(overallState(["SENT", "QUEUED"])).toBe("QUEUED");
    expect(overallState(["SENT", "CANCELLED"])).toBe("SENT");
    expect(overallState(["CANCELLED"])).toBe("CANCELLED");
  });

  it("counts sent destinations only while a file with several is part-way", () => {
    expect(sentCount(["SENT", "QUEUED", "FAILED"])).toBe("1 of 3 sent");
    expect(sentCount(["SENT", "SENT"])).toBeUndefined();
    expect(sentCount(["SENT", "CANCELLED"])).toBeUndefined();
    expect(sentCount(["QUEUED"])).toBeUndefined();
  });

  it("groups jobs by file, keeping the newest file first", () => {
    const groups = groupJobs([
      job({ id: "2a", uploadRecordId: "r2", fileName: "b.pdf", destinationId: "a" }),
      job({ id: "2b", uploadRecordId: "r2", fileName: "b.pdf", destinationId: "b" }),
      job({ id: "1a", uploadRecordId: "r1", fileName: "a.pdf", destinationId: "a" }),
      job({ id: "old" }),
    ]);
    expect(groups.map((g) => g.fileName)).toEqual(["b.pdf", "a.pdf", "a.pdf"]);
    expect(groups[0].jobs.map((j) => j.id)).toEqual(["2a", "2b"]);
  });

  it("shows the overall badge with the count", () => {
    const states: JobState[] = ["SENT", "SENDING", "QUEUED"];
    render(<FileForwardBadge states={states} />);
    expect(screen.getByText("Sending")).toBeInTheDocument();
    expect(screen.getByText(/1 of 3 sent/)).toBeInTheDocument();
  });
});
