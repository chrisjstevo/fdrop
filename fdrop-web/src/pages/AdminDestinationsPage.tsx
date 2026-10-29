import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { del, get, patch, post, put, q } from "../api/client";
import type { Destination, DestinationAdminView, KeyView, TestResult } from "../api/types";
import { ProblemNote } from "../components/TwoFactor";
import { Badge, Button, Dialog, DialogActions, Empty, ErrorNote, Field, Notice, RowMenu } from "../components/ui";
import { formatDate } from "../util/format";
import s from "./pages.module.css";

interface Form {
  name: string;
  description: string;
  host: string;
  port: number;
  remoteDir: string;
  username: string;
  serverAuth: "HOST_KEY" | "HOST_CA";
  hostKeySha256: string;
  hostCaPublicKey: string;
  overwrite: boolean;
  enabled: boolean;
  browsable: boolean;
}

const EMPTY: Form = {
  name: "",
  description: "",
  host: "",
  port: 22,
  remoteDir: "/",
  username: "",
  serverAuth: "HOST_KEY",
  hostKeySha256: "",
  hostCaPublicKey: "",
  overwrite: false,
  enabled: true,
  browsable: false,
};

function fromDestination(d: Destination): Form {
  return {
    name: d.name,
    description: d.description ?? "",
    host: d.host,
    port: d.port,
    remoteDir: d.remoteDir,
    username: d.username,
    serverAuth: d.serverAuth,
    hostKeySha256: d.hostKeySha256 ?? "",
    hostCaPublicKey: d.hostCaPublicKey ?? "",
    overwrite: d.overwrite,
    enabled: d.enabled,
    browsable: d.browsable,
  };
}

export function AdminDestinationsPage() {
  const [creating, setCreating] = useState(false);
  const list = useQuery({
    queryKey: ["admin-destinations"],
    queryFn: () => get<DestinationAdminView[]>("/api/v1/admin/forward-destinations"),
  });
  const rows = list.data ?? [];
  return (
    <>
      <div className={s.head}>
        <h1>Forward destinations</h1>
        <div className={s.actions}>
          <Button variant="primary" onClick={() => setCreating(true)}>
            New destination
          </Button>
        </div>
      </div>
      <p className="muted">
        A destination is an SFTP server and directory. Assign it to users and they can pick it from a drop-down when
        they create a forward folder. Every connection is mutually authenticated: fdrop checks the server's host key
        or certificate, and logs in with its own key for the destination. Passwords are never used.
      </p>
      <ProblemNote error={list.error} />
      {rows.length === 0 && !list.isLoading ? (
        <Empty title="No destinations yet" />
      ) : (
        <div className={s.listWrap}>
          <table className={s.table}>
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Server</th>
                <th scope="col">Directory</th>
                <th scope="col">Assigned to</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((v) => (
                <tr key={v.destination.id}>
                  <td>
                    <span className={s.nameCell}>
                      <Link to={`/admin/destinations/${v.destination.id}`}>{v.destination.name}</Link>
                    </span>
                  </td>
                  <td>
                    {v.destination.username}@{v.destination.host}:{v.destination.port}
                  </td>
                  <td>{v.destination.remoteDir}</td>
                  <td>{v.assignedUsers.length ? v.assignedUsers.join(", ") : "–"}</td>
                  <td>{v.destination.enabled ? <Badge kind="ok">Enabled</Badge> : <Badge kind="warn">Disabled</Badge>}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <ul className={s.cards}>
            {rows.map((v) => (
              <li key={v.destination.id} className={s.card}>
                <div className={s.cardBody}>
                  <div className={s.cardTitle}>
                    <Link to={`/admin/destinations/${v.destination.id}`}>{v.destination.name}</Link>
                  </div>
                  <div className={s.cardMeta}>
                    <span>{v.destination.host}</span>
                    {v.destination.enabled ? <Badge kind="ok">Enabled</Badge> : <Badge kind="warn">Disabled</Badge>}
                  </div>
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}
      {creating && <CreateDestinationDialog onClose={() => setCreating(false)} />}
    </>
  );
}

function DestinationFields({ form, setForm }: { form: Form; setForm: (f: Form) => void }) {
  const set = <K extends keyof Form>(k: K, v: Form[K]) => setForm({ ...form, [k]: v });
  return (
    <>
      <Field label="Name" htmlFor="d-name" hint="What users see in their drop-down.">
        <input id="d-name" type="text" value={form.name} onChange={(e) => set("name", e.target.value)} />
      </Field>
      <Field label="Description" htmlFor="d-desc">
        <input id="d-desc" type="text" value={form.description} onChange={(e) => set("description", e.target.value)} />
      </Field>
      <div className={s.formRow}>
        <Field label="Host" htmlFor="d-host">
          <input id="d-host" type="text" value={form.host} onChange={(e) => set("host", e.target.value)} />
        </Field>
        <Field label="Port" htmlFor="d-port">
          <input id="d-port" type="number" min={1} max={65535} value={form.port} onChange={(e) => set("port", Number(e.target.value))} />
        </Field>
      </div>
      <div className={s.formRow}>
        <Field label="Username" htmlFor="d-user" hint="The account fdrop logs in as.">
          <input id="d-user" type="text" value={form.username} onChange={(e) => set("username", e.target.value)} />
        </Field>
        <Field label="Remote directory" htmlFor="d-dir">
          <input id="d-dir" type="text" value={form.remoteDir} onChange={(e) => set("remoteDir", e.target.value)} />
        </Field>
      </div>
      <Field label="Server authentication" htmlFor="d-auth">
        <select id="d-auth" value={form.serverAuth} onChange={(e) => set("serverAuth", e.target.value as Form["serverAuth"])}>
          <option value="HOST_KEY">Pinned host key</option>
          <option value="HOST_CA">Host certificate from a trusted CA</option>
        </select>
      </Field>
      {form.serverAuth === "HOST_CA" && (
        <Field label="Host CA public key" htmlFor="d-ca" hint="Any host certificate this CA signs for the host is accepted.">
          <textarea id="d-ca" rows={3} value={form.hostCaPublicKey} onChange={(e) => set("hostCaPublicKey", e.target.value)} />
        </Field>
      )}
      <label className={s.checkbox}>
        <input type="checkbox" checked={form.overwrite} onChange={(e) => set("overwrite", e.target.checked)} /> Overwrite
        existing files on the destination
      </label>
      <label className={s.checkbox}>
        <input type="checkbox" checked={form.enabled} onChange={(e) => set("enabled", e.target.checked)} /> Enabled
      </label>
      <label className={s.checkbox}>
        <input type="checkbox" checked={form.browsable} onChange={(e) => set("browsable", e.target.checked)} /> Let
        forward folders browse this destination and retrieve its files
      </label>
      {form.browsable && (
        <p className="muted">
          Everyone in a forward folder that sends here will see every file in the remote directory, and can copy them
          into fdrop. Turn this on only where that is all right.
        </p>
      )}
    </>
  );
}

/** Connects to the host (trusting nothing) so the admin can confirm the fingerprint to pin. */
function ProbePanel({ form, setForm }: { form: Form; setForm: (f: Form) => void }) {
  const probe = useMutation({
    mutationFn: () => post<TestResult>("/api/v1/admin/forward-destinations/probe", { host: form.host, port: form.port }),
  });
  const r = probe.data;
  return (
    <div className={s.panel}>
      <p className="section-label">Host key</p>
      {form.hostKeySha256 ? (
        <p>
          Pinned: <code>{form.hostKeySha256}</code>{" "}
          <Button small variant="ghost" onClick={() => setForm({ ...form, hostKeySha256: "" })}>
            Clear
          </Button>
        </p>
      ) : (
        <p className="muted">Run a test connection, check the fingerprint with the server's owner, then pin it.</p>
      )}
      <Button small onClick={() => probe.mutate()} disabled={!form.host || probe.isPending}>
        {probe.isPending ? "Connecting…" : "Test connection"}
      </Button>
      <ErrorNote>{probe.error?.message ?? (r && !r.connected ? r.error : undefined)}</ErrorNote>
      {r?.connected && r.hostKeyFingerprint && (
        <Notice>
          The server presented a {r.hostKeyType} key{r.hostCertificate ? " (certificate)" : ""}:
          <br />
          <code>{r.hostKeyFingerprint}</code>
          <br />
          <Button small variant="primary" onClick={() => setForm({ ...form, hostKeySha256: r.hostKeyFingerprint! })}>
            Pin this key
          </Button>
        </Notice>
      )}
    </div>
  );
}

function CreateDestinationDialog({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate();
  const client = useQueryClient();
  const [form, setForm] = useState<Form>(EMPTY);
  const m = useMutation({
    mutationFn: () => post<DestinationAdminView>("/api/v1/admin/forward-destinations", form),
    onSuccess: (v) => {
      void client.invalidateQueries({ queryKey: ["admin-destinations"] });
      onClose();
      navigate(`/admin/destinations/${v.destination.id}`);
    },
  });
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title="New forward destination">
      <form
        onSubmit={(e) => {
          e.preventDefault();
          m.mutate();
        }}
      >
        <DestinationFields form={form} setForm={setForm} />
        {form.serverAuth === "HOST_KEY" && <ProbePanel form={form} setForm={setForm} />}
        <ErrorNote>{m.error?.message}</ErrorNote>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="primary" disabled={m.isPending}>
            Create destination
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

export function AdminDestinationPage() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const client = useQueryClient();
  const view = useQuery({
    queryKey: ["admin-destination", id],
    queryFn: () => get<DestinationAdminView>(`/api/v1/admin/forward-destinations/${q(id)}`),
  });
  const [form, setForm] = useState<Form>();
  const [assignee, setAssignee] = useState("");
  const [certFor, setCertFor] = useState<KeyView>();
  const refresh = () => {
    void client.invalidateQueries({ queryKey: ["admin-destination", id] });
    void client.invalidateQueries({ queryKey: ["admin-destinations"] });
  };
  const save = useMutation({
    mutationFn: (f: Form) => patch(`/api/v1/admin/forward-destinations/${q(id)}`, f),
    onSuccess: () => {
      setForm(undefined);
      refresh();
    },
  });
  const test = useMutation({ mutationFn: () => post<TestResult>(`/api/v1/admin/forward-destinations/${q(id)}/test`) });
  const newKey = useMutation({ mutationFn: () => post(`/api/v1/admin/forward-destinations/${q(id)}/keys`), onSuccess: refresh });
  const keyState = useMutation({
    mutationFn: ({ key, state }: { key: string; state: string }) =>
      put(`/api/v1/admin/forward-destinations/${q(id)}/keys/${q(key)}`, { state }),
    onSuccess: refresh,
  });
  const assign = useMutation({
    mutationFn: (u: string) => put(`/api/v1/admin/forward-destinations/${q(id)}/assignments/${q(u)}`),
    onSuccess: () => {
      setAssignee("");
      refresh();
    },
  });
  const unassign = useMutation({
    mutationFn: (u: string) => del(`/api/v1/admin/forward-destinations/${q(id)}/assignments/${q(u)}`),
    onSuccess: refresh,
  });
  const remove = useMutation({
    mutationFn: () => del(`/api/v1/admin/forward-destinations/${q(id)}`),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ["admin-destinations"] });
      navigate("/admin/destinations");
    },
  });

  if (view.isError) {
    return <ProblemNote error={view.error} />;
  }
  const v = view.data;
  if (!v) {
    return null;
  }
  const d = v.destination;
  const t = test.data;
  const mutationError =
    save.error ?? newKey.error ?? keyState.error ?? assign.error ?? unassign.error ?? remove.error ?? test.error;

  return (
    <>
      <p>
        <Link to="/admin/destinations">← All destinations</Link>
      </p>
      <div className={s.head}>
        <h1>{d.name}</h1>
        <div className={s.actions}>
          <Button onClick={() => test.mutate()} disabled={test.isPending}>
            {test.isPending ? "Testing…" : "Test connection"}
          </Button>
          <Button onClick={() => save.mutate({ ...fromDestination(d), enabled: !d.enabled })}>
            {d.enabled ? "Disable" : "Enable"}
          </Button>
          {!form && <Button onClick={() => setForm(fromDestination(d))}>Edit</Button>}
          <Button variant="danger" onClick={() => remove.mutate()} disabled={v.usedByFolders.length > 0}>
            Delete
          </Button>
        </div>
      </div>
      <ErrorNote>{mutationError?.message}</ErrorNote>
      {t && (
        <div className={s.panel}>
          <p className="section-label">Test result</p>
          <p>
            {t.connected ? "Connected" : "Could not connect"}
            {t.connected && (t.authenticated ? ", fdrop logged in with its key." : ", but login failed.")}
          </p>
          {t.hostKeyFingerprint && (
            <p className="muted">
              Host key: <code>{t.hostKeyFingerprint}</code>
              {t.hostCertificate && ` (certificate from CA ${t.hostCertificateCa})`}
            </p>
          )}
          {t.error && <ErrorNote>{t.error}</ErrorNote>}
        </div>
      )}
      {!d.enabled && <Notice>This destination is disabled: forward folders using it are paused.</Notice>}

      {form ? (
        <section className={s.section}>
          <h2>Edit</h2>
          <form
            className={s.form}
            onSubmit={(e) => {
              e.preventDefault();
              save.mutate(form);
            }}
          >
            <DestinationFields form={form} setForm={setForm} />
            {form.serverAuth === "HOST_KEY" && <ProbePanel form={form} setForm={setForm} />}
            <div className={s.actions}>
              <Button onClick={() => setForm(undefined)}>Cancel</Button>
              <Button type="submit" variant="primary" disabled={save.isPending}>
                Save
              </Button>
            </div>
          </form>
        </section>
      ) : (
        <dl className={s.kv}>
          <dt>Server</dt>
          <dd>
            {d.username}@{d.host}:{d.port}
          </dd>
          <dt>Directory</dt>
          <dd>{d.remoteDir}</dd>
          <dt>Server authentication</dt>
          <dd>
            {d.serverAuth === "HOST_KEY" ? (
              <>
                Pinned host key <code>{d.hostKeySha256}</code>
              </>
            ) : (
              "Host certificate from the configured CA"
            )}
          </dd>
          <dt>Existing files</dt>
          <dd>{d.overwrite ? "Overwritten" : "Never overwritten (the job fails with a conflict)"}</dd>
          <dt>Browsing</dt>
          <dd>{d.browsable ? "Forward folders can list it and retrieve its files" : "Off"}</dd>
          <dt>Updated</dt>
          <dd>{formatDate(d.updatedAt)}</dd>
        </dl>
      )}

      <section className={s.section}>
        <h2>Client keys</h2>
        <p className="muted">
          Install fdrop's active public key in the destination account's <code>authorized_keys</code>. To rotate,
          generate a new key, install it, test, activate it, then retire the old one.
        </p>
        <ul className={s.cards} style={{ display: "block" }}>
          {v.keys.map((k) => (
            <li key={k.id} className={s.card}>
              <div className={s.cardBody}>
                <div className={s.cardMeta}>
                  <Badge kind={k.state === "ACTIVE" ? "ok" : k.state === "PENDING" ? "neutral" : "warn"}>{k.state}</Badge>
                  <code>{k.fingerprint}</code>
                  {k.hasCertificate && <Badge kind="folder">User certificate</Badge>}
                  <span>Created {formatDate(k.createdAt)}</span>
                </div>
                <pre className={s.pre}>{k.publicKey}</pre>
              </div>
              <RowMenu
                label="Key actions"
                actions={[
                  { label: "Copy public key", onSelect: () => void navigator.clipboard?.writeText(k.publicKey) },
                  { label: "Set user certificate", onSelect: () => setCertFor(k) },
                  ...(k.state !== "ACTIVE" ? [{ label: "Activate", onSelect: () => keyState.mutate({ key: k.id, state: "ACTIVE" }) }] : []),
                  ...(k.state !== "RETIRED"
                    ? [{ label: "Retire", danger: true, onSelect: () => keyState.mutate({ key: k.id, state: "RETIRED" }) }]
                    : []),
                ]}
              />
            </li>
          ))}
        </ul>
        <Button onClick={() => newKey.mutate()} disabled={newKey.isPending}>
          Generate new key
        </Button>
      </section>

      <section className={s.section}>
        <h2>Assigned users</h2>
        <form
          className={s.inline}
          onSubmit={(e) => {
            e.preventDefault();
            assign.mutate(assignee.trim());
          }}
        >
          <label htmlFor="assignee" className="visually-hidden">
            Username
          </label>
          <input id="assignee" type="text" placeholder="Username" value={assignee} onChange={(e) => setAssignee(e.target.value)} />
          <Button type="submit" variant="primary" disabled={!assignee.trim() || assign.isPending}>
            Assign
          </Button>
        </form>
        {v.assignedUsers.length === 0 ? (
          <Empty title="Not assigned to anyone yet" />
        ) : (
          <ul className={s.cards} style={{ display: "block" }}>
            {v.assignedUsers.map((u) => (
              <li key={u} className={s.card}>
                <div className={s.cardBody}>
                  <div className={s.cardTitle}>{u}</div>
                </div>
                <Button small variant="ghost" onClick={() => unassign.mutate(u)}>
                  Remove
                </Button>
              </li>
            ))}
          </ul>
        )}
        <p className="muted">Removing an assignment pauses that user's forward folders using this destination.</p>
      </section>

      <section className={s.section}>
        <h2>Used by</h2>
        {v.usedByFolders.length === 0 ? (
          <p className="muted">No forward folders use this destination.</p>
        ) : (
          <ul>
            {v.usedByFolders.map((f) => (
              <li key={f}>{f}</li>
            ))}
          </ul>
        )}
      </section>

      {certFor && <CertificateDialog destinationId={id} keyView={certFor} onClose={() => setCertFor(undefined)} onDone={refresh} />}
    </>
  );
}

function CertificateDialog({
  destinationId,
  keyView,
  onClose,
  onDone,
}: {
  destinationId: string;
  keyView: KeyView;
  onClose: () => void;
  onDone: () => void;
}) {
  const [cert, setCert] = useState("");
  const m = useMutation({
    mutationFn: () =>
      put(`/api/v1/admin/forward-destinations/${q(destinationId)}/keys/${q(keyView.id)}`, { certificate: cert.trim() || null }),
    onSuccess: () => {
      onClose();
      onDone();
    },
  });
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title="User certificate"
      description="Paste the OpenSSH user certificate the destination's CA signed for this key. Leave empty to remove it."
    >
      <Field label="Certificate" htmlFor="cert">
        <textarea id="cert" rows={4} value={cert} onChange={(e) => setCert(e.target.value)} placeholder="ssh-ed25519-cert-v01@openssh.com AAAA…" />
      </Field>
      <ErrorNote>{m.error?.message}</ErrorNote>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="primary" onClick={() => m.mutate()} disabled={m.isPending}>
          Save
        </Button>
      </DialogActions>
    </Dialog>
  );
}
