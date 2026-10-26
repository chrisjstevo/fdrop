import { useMutation } from "@tanstack/react-query";
import { useEffect, useId, useState } from "react";
import { patch, q } from "../api/client";
import type { Entry, FolderExpiry } from "../api/types";
import { EXPIRY_PRESETS, expiryProblem, longerThan, parseExpiry, timeLeft } from "../util/expiry";
import { formatDate } from "../util/format";
import { HourglassIcon } from "./icons";
import { Button, Dialog, DialogActions, ErrorNote, Field } from "./ui";
import s from "./Expiry.module.css";

/** What the picker currently holds: undefined value means the folder's default; `error` blocks using it. */
export interface ExpiryChoice {
  value?: string;
  error?: string;
}

const DEFAULT = "";
const CUSTOM = "custom";

/** "longer than the folder's maximum" in words, or undefined. */
function overMax(value: string, folder?: FolderExpiry): string | undefined {
  const max = folder?.max ?? folder?.serverMax;
  if (!max || value === undefined) {
    return undefined;
  }
  if (value === "never" && folder?.max) {
    return `Files in this folder must expire within '${folder.max}'`;
  }
  return value !== "never" && longerThan(value, max)
    ? `'${value}' is longer than ${folder?.max ? "this folder's" : "the server"} maximum of '${max}'`
    : undefined;
}

/**
 * The "Expires" control: the folder default, presets, Never (unless the folder has a maximum) and a custom value
 * checked as the user types. Presets longer than the maximum are disabled.
 */
export function ExpiryPicker({
  folder,
  onChange,
  label = "Expires",
  noDefault,
}: {
  folder?: FolderExpiry;
  onChange: (c: ExpiryChoice) => void;
  label?: string;
  /** For changing an existing file's expiry, where "the folder default" means nothing. */
  noDefault?: boolean;
}) {
  const id = useId();
  const [mode, setMode] = useState(noDefault ? (EXPIRY_PRESETS.find((p) => !overMax(p, folder)) ?? CUSTOM) : DEFAULT);
  const [custom, setCustom] = useState("");
  const defaultLabel = folder?.default ? `folder default, ${folder.default}` : folder?.max ? `folder maximum, ${folder.max}` : "never (folder default)";

  useEffect(() => {
    if (mode === DEFAULT) {
      onChange({});
    } else if (mode === CUSTOM) {
      const problem = custom.trim() ? expiryProblem(custom) : "Type an expiry, such as '6 days'";
      onChange(problem ? { error: problem } : { value: parseExpiry(custom), error: overMax(parseExpiry(custom), folder) });
    } else {
      onChange({ value: mode });
    }
  }, [mode, custom, folder?.default, folder?.max]);

  const customProblem = mode === CUSTOM && custom.trim() ? expiryProblem(custom) ?? overMax(parseExpiry(custom), folder) : undefined;
  return (
    <div className={s.picker}>
      <label htmlFor={id} className={s.label}>
        {label}
      </label>
      <select id={id} value={mode} onChange={(e) => setMode(e.target.value)}>
        {!noDefault && <option value={DEFAULT}>{defaultLabel}</option>}
        {EXPIRY_PRESETS.map((p) => (
          <option key={p} value={p} disabled={!!overMax(p, folder)}>
            {p}
            {overMax(p, folder) ? ` (over the ${folder?.max ?? folder?.serverMax} maximum)` : ""}
          </option>
        ))}
        {!folder?.max && <option value="never">Never</option>}
        <option value={CUSTOM}>Custom…</option>
      </select>
      {mode === CUSTOM && (
        <input
          type="text"
          aria-label="Custom expiry"
          placeholder="e.g. 6 days, 2weeks, 36h"
          value={custom}
          onChange={(e) => setCustom(e.target.value)}
          aria-invalid={!!customProblem}
          autoFocus
        />
      )}
      {customProblem && (
        <p className={s.problem} role="alert">
          {customProblem}
        </p>
      )}
    </div>
  );
}

/** Time left before a file is deleted, with the exact time on hover; empty for files that do not expire. */
export function ExpiresCell({ entry }: { entry: Entry }) {
  if (!entry.expiresAt) {
    return null;
  }
  const soon = new Date(entry.expiresAt).getTime() - Date.now() < 3_600_000;
  return (
    <span className={s.cell} title={`Deleted automatically on ${formatDate(entry.expiresAt)}`}>
      <span className={soon ? s.soon : s.icon} aria-hidden="true">
        <HourglassIcon size={14} />
      </span>
      <span>{timeLeft(entry.expiresAt)}</span>
    </span>
  );
}

/** "Set expiry…": a duration from now, a date, or no expiry. */
export function SetExpiryDialog({
  entry,
  folder,
  onClose,
  onDone,
}: {
  entry: Entry;
  folder?: FolderExpiry;
  onClose: () => void;
  onDone: () => void;
}) {
  const [choice, setChoice] = useState<ExpiryChoice>({});
  const [onDate, setOnDate] = useState("");
  const m = useMutation({
    mutationFn: (body: Record<string, unknown>) => patch<Entry>(`/api/v1/files?path=${q(entry.path)}`, body),
    onSuccess: () => {
      onClose();
      onDone();
    },
  });
  const save = () => {
    if (onDate) {
      m.mutate({ expiresAt: new Date(onDate).toISOString() });
    } else if (choice.value) {
      m.mutate({ expiresIn: choice.value });
    }
  };
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={`Expiry of ${entry.name}`}
      description={
        entry.expiresAt
          ? `Now deleted automatically on ${formatDate(entry.expiresAt)}. A new expiry counts from now.`
          : "This file does not expire. A new expiry counts from now."
      }
    >
      <form
        onSubmit={(e) => {
          e.preventDefault();
          save();
        }}
      >
        <ExpiryPicker folder={folder} onChange={setChoice} label="Delete after" noDefault />
        <Field label="Or on a date" htmlFor="expiry-date" hint="In your time zone.">
          <input id="expiry-date" type="datetime-local" value={onDate} onChange={(e) => setOnDate(e.target.value)} />
        </Field>
        <ErrorNote>{m.error?.message}</ErrorNote>
        <DialogActions>
          {entry.expiresAt && !folder?.max && (
            <Button variant="ghost" onClick={() => m.mutate({ expiresAt: null })} disabled={m.isPending}>
              Remove expiry
            </Button>
          )}
          <Button onClick={onClose}>Cancel</Button>
          <Button
            type="submit"
            variant="primary"
            disabled={m.isPending || (!onDate && (!choice.value || !!choice.error))}
          >
            Save
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
