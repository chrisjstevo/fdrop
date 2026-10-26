import { useEffect, useRef, useState } from "react";
import { useUploadQueue } from "../upload/UploadContext";
import type { QueueItem } from "../upload/queue";
import { formatBytes, formatDuration, formatSpeed } from "../util/format";
import { Badge, Button, ProgressBar } from "./ui";
import s from "./UploadQueuePanel.module.css";

const STATE_LABEL: Record<QueueItem["state"], string> = {
  queued: "Queued",
  uploading: "Uploading",
  paused: "Paused",
  offline: "Offline – will resume",
  retrying: "Retrying",
  finishing: "Finishing…",
  done: "Done",
  failed: "Failed",
  cancelled: "Cancelled",
};

/** Announces each upload every 25% and on completion, without flooding screen readers. */
function useAnnouncements(items: QueueItem[]) {
  const last = useRef(new Map<string, number>());
  const [message, setMessage] = useState("");
  useEffect(() => {
    for (const i of items) {
      const step = i.state === "done" ? 100 : Math.floor(i.percent / 25) * 25;
      const prev = last.current.get(i.key) ?? -1;
      if (step > prev) {
        last.current.set(i.key, step);
        if (prev >= 0 || step === 100) {
          setMessage(step === 100 ? `${i.name} uploaded` : `${i.name} ${step}%`);
        }
      }
    }
  }, [items]);
  return message;
}

export function UploadQueuePanel() {
  const { queue, items } = useUploadQueue();
  const [open, setOpen] = useState(true);
  const announcement = useAnnouncements(items);
  const pending = queue.pending;

  if (items.length === 0 && pending.length === 0) {
    return <div aria-live="polite" className="visually-hidden">{announcement}</div>;
  }
  const active = items.filter((i) => i.state !== "done" && i.state !== "cancelled" && i.state !== "failed");
  const overall = queue.overall();

  return (
    <section className={[s.panel, open ? s.open : s.closed].join(" ")} aria-label="Uploads">
      <div aria-live="polite" className="visually-hidden">{announcement}</div>
      <header className={s.header}>
        <button type="button" className={s.toggle} onClick={() => setOpen((o) => !o)} aria-expanded={open}>
          <span className={s.title}>
            {active.length > 0 ? `Uploading ${active.length} file${active.length === 1 ? "" : "s"}` : "Uploads"}
          </span>
          <span className={s.overall}>{active.length > 0 ? `${overall}%` : ""}</span>
          <span aria-hidden="true">{open ? "▾" : "▴"}</span>
        </button>
        {active.length > 0 && <ProgressBar percent={overall} label="All uploads" />}
      </header>
      {open && (
        <div className={s.list}>
          {pending.length > 0 && (
            <div className={s.pending}>
              <p className="section-label">Unfinished from earlier</p>
              {pending.map((p) => (
                <div key={p.key} className={s.pendingRow}>
                  <span>
                    <strong>{p.name}</strong> to {p.path}. Choose the same file again to resume.
                  </span>
                  <Button small variant="ghost" onClick={() => queue.forgetPending(p.key)}>
                    Forget
                  </Button>
                </div>
              ))}
            </div>
          )}
          {items.map((i) => (
            <div key={i.key} className={s.row}>
              <div className={s.rowTop}>
                <span className={s.name} title={i.path}>
                  {i.name}
                </span>
                <span className={s.percent}>{i.percent}%</span>
              </div>
              <ProgressBar percent={i.percent} label={`Uploading ${i.name}`} />
              <div className={s.meta}>
                <span>
                  {formatBytes(Math.min(i.sent, i.size))} of {formatBytes(i.size)}
                </span>
                {i.expiresIn && <span>{i.expiresIn === "never" ? "Never expires" : `Expires ${i.expiresIn} after upload`}</span>}
                {i.state === "uploading" && i.speed > 0 && (
                  <span>
                    {formatSpeed(i.speed)} · {formatDuration(i.secondsLeft)} left
                  </span>
                )}
                {i.state === "done" ? (
                  i.forward ? <Badge kind="forward">Queued to forward</Badge> : <Badge kind="ok">Done</Badge>
                ) : i.state === "failed" ? (
                  <Badge kind="fail">Failed</Badge>
                ) : (
                  <span className={s.state}>{STATE_LABEL[i.state]}</span>
                )}
              </div>
              {i.error && <p className={s.error}>{i.error}</p>}
              <div className={s.actions}>
                {(i.state === "uploading" || i.state === "retrying" || i.state === "offline") && (
                  <Button small variant="ghost" onClick={() => queue.pause(i.key)}>
                    Pause
                  </Button>
                )}
                {i.state === "paused" && (
                  <Button small variant="ghost" onClick={() => queue.resume(i.key)}>
                    Resume
                  </Button>
                )}
                {i.state !== "done" && i.state !== "cancelled" && i.state !== "failed" && (
                  <Button small variant="ghost" onClick={() => queue.cancel(i.key)}>
                    Cancel
                  </Button>
                )}
                {(i.state === "done" || i.state === "cancelled" || i.state === "failed") && (
                  <Button small variant="ghost" onClick={() => queue.dismiss(i.key)}>
                    Dismiss
                  </Button>
                )}
              </div>
            </div>
          ))}
          {items.some((i) => i.state === "done" || i.state === "cancelled") && (
            <Button small variant="ghost" onClick={() => queue.clearFinished()}>
              Clear finished
            </Button>
          )}
        </div>
      )}
    </section>
  );
}
