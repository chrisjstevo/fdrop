import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { del, get, post } from "../api/client";
import type { EncryptionStatus } from "../api/types";
import { ProblemNote } from "../components/TwoFactor";
import { Badge, Button, Dialog, DialogActions, Empty, ErrorNote, Field, Notice, ProgressBar } from "../components/ui";
import { formatBytes, formatCount, formatDate } from "../util/format";
import s from "./pages.module.css";

const URL = "/api/v1/admin/storage/encryption";

const REASONS: Record<string, string> = {
  header_mac: "Header altered",
  segment_auth: "Content altered or corrupted",
  truncated: "Cut short",
  unknown_key: "Key not available",
  plaintext_refused: "Not encrypted",
};

/** Admin: encryption at rest. Status, objects per key, re-encryption jobs and recent decryption failures. */
export function AdminEncryptionPage() {
  const client = useQueryClient();
  const status = useQuery({
    queryKey: ["encryption"],
    queryFn: () => get<EncryptionStatus>(URL),
    refetchInterval: (q) => (q.state.data?.job && ["SCANNING", "RUNNING"].includes(q.state.data.job.state) ? 3000 : false),
  });
  const [starting, setStarting] = useState<{ fromKeyId?: string; plaintext?: boolean }>();
  const refresh = (data: EncryptionStatus) => client.setQueryData(["encryption"], data);
  const inventory = useMutation({ mutationFn: () => post<EncryptionStatus>(URL + "/inventory"), onSuccess: refresh });
  const cancel = useMutation({ mutationFn: () => del<EncryptionStatus>(URL + "/reencrypt"), onSuccess: refresh });

  if (status.isError) {
    return (
      <>
        <h1>Encryption</h1>
        <ProblemNote error={status.error} />
      </>
    );
  }
  const st = status.data;
  const job = st?.job;
  const running = !!job && (job.state === "SCANNING" || job.state === "RUNNING");
  const plaintext = st?.inventory.find((i) => i.keyId === "plaintext");
  const percent = job && job.objectsTotal > 0 ? Math.floor((100 * (job.objectsDone + job.objectsFailed)) / job.objectsTotal) : 0;

  return (
    <>
      <div className={s.head}>
        <h1>Encryption</h1>
        <div className={s.actions}>
          <Button onClick={() => inventory.mutate()} disabled={inventory.isPending}>
            {inventory.isPending ? "Counting…" : "Count objects now"}
          </Button>
        </div>
      </div>
      <ErrorNote>{inventory.error?.message ?? cancel.error?.message}</ErrorNote>
      {st && !st.enabled && (
        <Notice>Encryption at rest is turned off: new files are stored unencrypted. Encrypted files are still read.</Notice>
      )}
      {st?.allowPlaintextReads && (
        <Notice>
          Unencrypted files are still served while you migrate (<code>allowPlaintextReads</code>). Encrypt them, then
          turn that setting off.
        </Notice>
      )}
      {st && (
        <dl className={s.kv}>
          <dt>Status</dt>
          <dd>{st.enabled ? <Badge kind="ok">On</Badge> : <Badge kind="warn">Off</Badge>}</dd>
          <dt>Key provider</dt>
          <dd>{st.provider ?? "None"}</dd>
          <dt>Primary key</dt>
          <dd>
            <code>{st.primaryKeyId ?? "–"}</code>
          </dd>
        </dl>
      )}

      <section className={s.section}>
        <h2>Objects by key</h2>
        {st && st.inventory.length === 0 ? (
          <Empty title="Not counted yet">The count runs daily, or now with “Count objects now”.</Empty>
        ) : (
          <div className={s.listWrap}>
            <table className={s.table}>
              <thead>
                <tr>
                  <th scope="col">Key</th>
                  <th scope="col" className={s.num}>
                    Objects
                  </th>
                  <th scope="col" className={s.num}>
                    Stored size
                  </th>
                  <th scope="col">Counted</th>
                  <th scope="col">
                    <span className="visually-hidden">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {(st?.inventory ?? []).map((i) => (
                  <tr key={i.keyId}>
                    <td>
                      {i.keyId === "plaintext" ? (
                        <Badge kind="warn">Not encrypted</Badge>
                      ) : (
                        <>
                          <code>{i.keyId}</code> {i.keyId === st?.primaryKeyId && <Badge kind="ok">Primary</Badge>}
                        </>
                      )}
                    </td>
                    <td className={s.num}>{formatCount(i.objects)}</td>
                    <td className={s.num}>{formatBytes(i.bytes)}</td>
                    <td>{formatDate(i.countedAt)}</td>
                    <td className={s.num}>
                      {st?.enabled && i.keyId !== st.primaryKeyId && i.objects > 0 && (
                        <Button
                          small
                          disabled={running}
                          onClick={() => setStarting(i.keyId === "plaintext" ? { plaintext: true } : { fromKeyId: i.keyId })}
                        >
                          {i.keyId === "plaintext" ? "Encrypt" : "Re-encrypt"}
                        </Button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <ul className={s.cards}>
              {(st?.inventory ?? []).map((i) => (
                <li key={i.keyId} className={s.card}>
                  <div className={s.cardBody}>
                    <div className={s.cardTitle}>{i.keyId === "plaintext" ? "Not encrypted" : i.keyId}</div>
                    <div className={s.cardMeta}>
                      <span>{formatCount(i.objects)} objects</span>
                      <span>{formatBytes(i.bytes)}</span>
                    </div>
                  </div>
                </li>
              ))}
            </ul>
          </div>
        )}
        {plaintext && plaintext.objects > 0 && !st?.allowPlaintextReads && (
          <p className="muted">
            Unencrypted files are refused while <code>allowPlaintextReads</code> is off.
          </p>
        )}
      </section>

      <section className={s.section}>
        <h2>Re-encryption</h2>
        {!job ? (
          <p className="muted">
            No job has run. Re-encrypting rewrites every file under one key with the primary key, so the old key can
            be retired.
          </p>
        ) : (
          <div className={s.panel}>
            <p>
              <strong>
                {job.scope === "PLAINTEXT" ? "Encrypting unencrypted files" : `Re-encrypting files under ${job.fromKeyId}`}
              </strong>{" "}
              · {job.state.toLowerCase()} · started {formatDate(job.startedAt)}
              {job.finishedAt && `, ended ${formatDate(job.finishedAt)}`}
            </p>
            {job.state !== "SCANNING" && <ProgressBar percent={percent} label="Re-encryption progress" />}
            <p className="muted">
              {formatCount(job.objectsDone)} of {formatCount(job.objectsTotal)} files, {formatBytes(job.bytesDone)}
              {job.objectsFailed > 0 && `; ${formatCount(job.objectsFailed)} failed and stay as they were`}
            </p>
            {running && (
              <Button variant="danger" onClick={() => cancel.mutate()} disabled={cancel.isPending}>
                Cancel job
              </Button>
            )}
          </div>
        )}
      </section>

      <section className={s.section}>
        <h2>Recent decryption failures</h2>
        {(st?.recentFailures ?? []).length === 0 ? (
          <p className="muted">None on this node. A failure means a stored file was altered, damaged or cut short.</p>
        ) : (
          <ul>
            {st!.recentFailures.map((f) => (
              <li key={f.storageKey + f.at}>
                <code>{f.storageKey}</code>: {REASONS[f.reason] ?? f.reason}, {formatDate(f.at)}
              </li>
            ))}
          </ul>
        )}
      </section>

      {starting && st && (
        <StartDialog
          target={starting}
          primary={st.primaryKeyId ?? ""}
          onClose={() => setStarting(undefined)}
          onStarted={(d) => {
            refresh(d);
            setStarting(undefined);
          }}
        />
      )}
    </>
  );
}

function StartDialog({
  target,
  primary,
  onClose,
  onStarted,
}: {
  target: { fromKeyId?: string; plaintext?: boolean };
  primary: string;
  onClose: () => void;
  onStarted: (d: EncryptionStatus) => void;
}) {
  const [confirm, setConfirm] = useState("");
  const word = target.plaintext ? "encrypt" : target.fromKeyId!;
  const m = useMutation({ mutationFn: () => post<EncryptionStatus>(URL + "/reencrypt", target), onSuccess: onStarted });
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={target.plaintext ? "Encrypt unencrypted files?" : `Re-encrypt files under ${target.fromKeyId}?`}
      description={`Every matching file is rewritten under the primary key, ${primary}, in the background, a file at a time. Files stay available throughout.`}
    >
      <form
        onSubmit={(e) => {
          e.preventDefault();
          m.mutate();
        }}
      >
        <Field label={`Type ${word} to confirm`} htmlFor="confirm-reencrypt">
          <input id="confirm-reencrypt" type="text" value={confirm} onChange={(e) => setConfirm(e.target.value)} autoFocus />
        </Field>
        <ErrorNote>{m.error?.message}</ErrorNote>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="primary" disabled={confirm !== word || m.isPending}>
            Start
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
