import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { del, get, patch, post, q } from "../api/client";
import type { AppAccount, AppCreated, AppMethod } from "../api/types";
import { useMe } from "../auth/useMe";
import { ProblemNote } from "../components/TwoFactor";
import { Badge, Button, Dialog, DialogActions, Empty, ErrorNote, Field, Notice, RowMenu } from "../components/ui";
import { formatBytes, formatDate } from "../util/format";
import s from "./pages.module.css";

const METHODS: { value: AppMethod; label: string; hint: string }[] = [
  { value: "MTLS", label: "Client certificate (mTLS)", hint: "HTTPS API on the mTLS port" },
  { value: "PASSWORD", label: "Password", hint: "HTTP Basic over TLS, and SFTP" },
  { value: "PUBLICKEY", label: "SSH key", hint: "SFTP" },
];

const methodLabel = (m: AppMethod) => METHODS.find((x) => x.value === m)?.label ?? m;

/** Expiry within 30 days shows as a warning, past expiry as a failure. */
function expiryBadge(at?: number) {
  if (!at) {
    return <span className="muted">–</span>;
  }
  const days = (at - Date.now()) / 86_400_000;
  const kind = days < 0 ? "fail" : days < 30 ? "warn" : "neutral";
  return <Badge kind={kind}>{days < 0 ? "Expired " : ""}{formatDate(at)}</Badge>;
}

function PasswordNotice({ created, onHide }: { created: AppCreated; onHide: () => void }) {
  return (
    <Notice>
      The password for <strong>{created.account.username}</strong> is <code>{created.password}</code>. It is shown
      only once: store it in the other system's secret store now.{" "}
      <button type="button" className="muted" onClick={onHide} style={{ border: "none", background: "none", cursor: "pointer", textDecoration: "underline" }}>
        Hide
      </button>
    </Notice>
  );
}

export function AdminApplicationsPage() {
  const me = useMe();
  const client = useQueryClient();
  const [creating, setCreating] = useState(false);
  const [created, setCreated] = useState<AppCreated>();
  const list = useQuery({ queryKey: ["admin-apps"], queryFn: () => get<AppAccount[]>("/api/v1/admin/app-accounts") });
  const managed = me.data?.settings.userManagement !== false;
  const rows = list.data ?? [];
  return (
    <>
      <div className={s.head}>
        <h1>Application accounts</h1>
        <div className={s.actions}>
          {managed && (
            <Button variant="primary" onClick={() => setCreating(true)}>
              New application account
            </Button>
          )}
        </div>
      </div>
      <p className="muted">
        Application accounts let other systems send and collect files without anyone signing in. They use a client
        certificate, a generated password or an SSH key, never a second factor, and folder owners add them to shared
        and forward folders as members.
      </p>
      {!managed && (
        <Notice>
          User management is turned off on this server: create accounts in Keycloak by adding a user to the
          applications group. They appear here after their first sign-in.
        </Notice>
      )}
      {created && <PasswordNotice created={created} onHide={() => setCreated(undefined)} />}
      <ProblemNote error={list.error} />
      {rows.length === 0 && !list.isLoading ? (
        <Empty title="No application accounts yet" />
      ) : (
        <div className={s.listWrap}>
          <table className={s.table}>
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Responsible</th>
                <th scope="col">Sign-in methods</th>
                <th scope="col">Last sign-in</th>
                <th scope="col">Next expiry</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((a) => (
                <tr key={a.id}>
                  <td>
                    <span className={s.nameCell}>
                      <Link to={`/admin/applications/${q(a.id)}`}>{a.username}</Link>
                    </span>
                    {a.description && <div className="muted">{a.description}</div>}
                  </td>
                  <td>{a.responsible ?? "–"}</td>
                  <td>{a.allowedMethods.map(methodLabel).join(", ")}</td>
                  <td>{a.lastLoginAt ? formatDate(a.lastLoginAt) : "Never"}</td>
                  <td>{expiryBadge(a.nearestExpiry)}</td>
                  <td>{a.enabled ? <Badge kind="ok">Enabled</Badge> : <Badge kind="warn">Disabled</Badge>}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <ul className={s.cards}>
            {rows.map((a) => (
              <li key={a.id} className={s.card}>
                <div className={s.cardBody}>
                  <div className={s.cardTitle}>
                    <Link to={`/admin/applications/${q(a.id)}`}>{a.username}</Link>
                  </div>
                  <div className={s.cardMeta}>
                    {a.enabled ? <Badge kind="ok">Enabled</Badge> : <Badge kind="warn">Disabled</Badge>}
                    {expiryBadge(a.nearestExpiry)}
                    {a.responsible && <span>{a.responsible}</span>}
                  </div>
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}
      {creating && (
        <CreateAppDialog
          onClose={() => setCreating(false)}
          onCreated={(c) => {
            setCreating(false);
            setCreated(c.password ? c : undefined);
            void client.invalidateQueries({ queryKey: ["admin-apps"] });
          }}
        />
      )}
    </>
  );
}

interface AppForm {
  description: string;
  responsible: string;
  methods: AppMethod[];
  sources: string;
}

function MethodAndSourceFields({ form, setForm }: { form: AppForm; setForm: (f: AppForm) => void }) {
  const toggle = (m: AppMethod) =>
    setForm({ ...form, methods: form.methods.includes(m) ? form.methods.filter((x) => x !== m) : [...form.methods, m] });
  return (
    <>
      <fieldset style={{ border: "none", padding: 0, margin: "0 0 16px" }}>
        <legend className="section-label">Allowed sign-in methods</legend>
        {METHODS.map((m) => (
          <label key={m.value} className={s.checkbox}>
            <input type="checkbox" checked={form.methods.includes(m.value)} onChange={() => toggle(m.value)} />{" "}
            {m.label} <span className="muted">({m.hint})</span>
          </label>
        ))}
      </fieldset>
      <Field label="Allowed source addresses" htmlFor="app-sources" hint="Optional. Addresses or CIDR ranges, one per line; empty allows any address.">
        <textarea id="app-sources" rows={3} value={form.sources} onChange={(e) => setForm({ ...form, sources: e.target.value })} />
      </Field>
    </>
  );
}

const sourcesOf = (text: string) =>
  text
    .split(/[\s,]+/)
    .map((x) => x.trim())
    .filter(Boolean);

function CreateAppDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (c: AppCreated) => void }) {
  const [username, setUsername] = useState("");
  const [form, setForm] = useState<AppForm>({ description: "", responsible: "", methods: ["MTLS"], sources: "" });
  const m = useMutation({
    mutationFn: () =>
      post<AppCreated>("/api/v1/admin/app-accounts", {
        username: username.trim(),
        description: form.description.trim() || undefined,
        responsible: form.responsible.trim(),
        allowedMethods: form.methods,
        allowedSources: sourcesOf(form.sources),
      }),
    onSuccess: onCreated,
  });
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title="New application account">
      <form
        onSubmit={(e) => {
          e.preventDefault();
          m.mutate();
        }}
      >
        <Field label="Name" htmlFor="app-name" hint="The username the system signs in with, e.g. erp-export.">
          <input id="app-name" type="text" value={username} onChange={(e) => setUsername(e.target.value)} autoFocus />
        </Field>
        <Field label="Description" htmlFor="app-desc">
          <input id="app-desc" type="text" value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
        </Field>
        <Field label="Responsible person" htmlFor="app-owner" hint="A standard user who is told before credentials expire.">
          <input id="app-owner" type="text" value={form.responsible} onChange={(e) => setForm({ ...form, responsible: e.target.value })} />
        </Field>
        <MethodAndSourceFields form={form} setForm={setForm} />
        {form.methods.includes("PASSWORD") && (
          <p className="muted">fdrop generates the password and shows it once after you create the account.</p>
        )}
        <ErrorNote>{m.error?.message}</ErrorNote>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            type="submit"
            variant="primary"
            disabled={!username.trim() || !form.responsible.trim() || form.methods.length === 0 || m.isPending}
          >
            Create account
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

export function AdminApplicationPage() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const client = useQueryClient();
  const me = useMe();
  const managed = me.data?.settings.userManagement !== false;
  const base = `/api/v1/admin/app-accounts/${q(id)}`;
  const view = useQuery({ queryKey: ["admin-app", id], queryFn: () => get<AppAccount>(base) });
  const [form, setForm] = useState<AppForm>();
  const [created, setCreated] = useState<AppCreated>();
  const [addingCert, setAddingCert] = useState(false);
  const [keyLine, setKeyLine] = useState("");
  const [deleting, setDeleting] = useState(false);
  const refresh = () => {
    void client.invalidateQueries({ queryKey: ["admin-app", id] });
    void client.invalidateQueries({ queryKey: ["admin-apps"] });
  };
  const save = useMutation({
    mutationFn: (body: object) => patch<AppAccount>(base, body),
    onSuccess: () => {
      setForm(undefined);
      refresh();
    },
  });
  const password = useMutation({
    mutationFn: () => post<AppCreated>(base + "/password"),
    onSuccess: (c) => {
      setCreated(c);
      refresh();
    },
  });
  const retire = useMutation({ mutationFn: (certId: string) => del(`${base}/certificates/${q(certId)}`), onSuccess: refresh });
  const addKey = useMutation({
    mutationFn: () => post(base + "/ssh-keys", { publicKey: keyLine.trim() }),
    onSuccess: () => {
      setKeyLine("");
      refresh();
    },
  });
  const removeKey = useMutation({ mutationFn: (keyId: string) => del(`${base}/ssh-keys/${q(keyId)}`), onSuccess: refresh });

  if (view.isError) {
    return <ProblemNote error={view.error} />;
  }
  const a = view.data;
  if (!a) {
    return null;
  }
  const error = save.error ?? password.error ?? retire.error ?? addKey.error ?? removeKey.error;
  return (
    <>
      <p>
        <Link to="/admin/applications">← All application accounts</Link>
      </p>
      <div className={s.head}>
        <h1>{a.username}</h1>
        <div className={s.actions}>
          {managed && (
            <Button onClick={() => save.mutate({ enabled: !a.enabled })}>{a.enabled ? "Disable" : "Enable"}</Button>
          )}
          {!form && (
            <Button
              onClick={() =>
                setForm({
                  description: a.description ?? "",
                  responsible: a.responsible ?? "",
                  methods: a.allowedMethods,
                  sources: a.allowedSources.join("\n"),
                })
              }
            >
              Edit
            </Button>
          )}
          {managed && (
            <Button variant="danger" onClick={() => setDeleting(true)}>
              Delete
            </Button>
          )}
        </div>
      </div>
      <ErrorNote>{error?.message}</ErrorNote>
      {created && <PasswordNotice created={created} onHide={() => setCreated(undefined)} />}
      {!a.enabled && <Notice>This account is disabled: every sign-in is refused. Its files and memberships are kept.</Notice>}

      {form ? (
        <section className={s.section}>
          <h2>Edit</h2>
          <form
            className={s.form}
            onSubmit={(e) => {
              e.preventDefault();
              save.mutate({
                description: form.description.trim(),
                responsible: form.responsible.trim(),
                allowedMethods: form.methods,
                allowedSources: sourcesOf(form.sources),
              });
            }}
          >
            <Field label="Description" htmlFor="edit-desc">
              <input id="edit-desc" type="text" value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
            </Field>
            <Field label="Responsible person" htmlFor="edit-owner">
              <input id="edit-owner" type="text" value={form.responsible} onChange={(e) => setForm({ ...form, responsible: e.target.value })} />
            </Field>
            <MethodAndSourceFields form={form} setForm={setForm} />
            <div className={s.actions}>
              <Button onClick={() => setForm(undefined)}>Cancel</Button>
              <Button type="submit" variant="primary" disabled={save.isPending || form.methods.length === 0}>
                Save
              </Button>
            </div>
          </form>
        </section>
      ) : (
        <dl className={s.kv}>
          <dt>Description</dt>
          <dd>{a.description ?? "–"}</dd>
          <dt>Responsible person</dt>
          <dd>{a.responsible ?? "–"}</dd>
          <dt>Sign-in methods</dt>
          <dd>{a.allowedMethods.map(methodLabel).join(", ")}</dd>
          <dt>Source addresses</dt>
          <dd>{a.allowedSources.length ? a.allowedSources.join(", ") : "Any"}</dd>
          <dt>Storage used</dt>
          <dd>
            {formatBytes(a.usedBytes)} of {formatBytes(a.quotaBytes)}
          </dd>
          <dt>Last sign-in</dt>
          <dd>{a.lastLoginAt ? formatDate(a.lastLoginAt) : "Never"}</dd>
        </dl>
      )}

      {a.allowedMethods.includes("PASSWORD") && (
        <section className={s.section}>
          <h2>Password</h2>
          <p className="muted">
            {a.passwordSetAt ? <>Set {formatDate(a.passwordSetAt)}. </> : "Not set by fdrop. "}
            {a.passwordExpiresAt && <>Expires {formatDate(a.passwordExpiresAt)}. </>}A new password replaces the old one
            at once.
          </p>
          {managed && (
            <Button onClick={() => password.mutate()} disabled={password.isPending}>
              Generate new password
            </Button>
          )}
        </section>
      )}

      <section className={s.section}>
        <div className={s.head}>
          <h2>Client certificates</h2>
          <div className={s.actions}>
            <Button onClick={() => setAddingCert(true)}>Add certificate</Button>
          </div>
        </div>
        <p className="muted">
          Pinned certificates match by fingerprint and may be self-signed. By-subject certificates must come from a
          trusted CA and match the subject or a SAN, so the CA can reissue them without a change here. Add the new
          certificate before the old one expires, then retire the old one.
        </p>
        {a.certificates.length === 0 ? (
          <Empty title="No certificates" />
        ) : (
          <ul className={s.cards} style={{ display: "block" }}>
            {a.certificates.map((c) => (
              <li key={c.id} className={s.card}>
                <div className={s.cardBody}>
                  <div className={s.cardTitle}>
                    {c.match === "PINNED" ? (
                      <>
                        Pinned <code>{c.sha256?.slice(0, 16)}…</code>
                      </>
                    ) : (
                      <>
                        By subject <code>{c.san ?? c.subject}</code>
                      </>
                    )}
                  </div>
                  <div className={s.cardMeta}>
                    {c.state === "ACTIVE" ? <Badge kind="ok">Active</Badge> : <Badge kind="neutral">Retired</Badge>}
                    {c.match === "PINNED" && c.subject && <span>{c.subject}</span>}
                    {c.notAfter && <span>Expires {expiryBadge(c.notAfter)}</span>}
                  </div>
                </div>
                {c.state === "ACTIVE" && (
                  <RowMenu label={`Actions for certificate ${c.id}`} actions={[{ label: "Retire", danger: true, onSelect: () => retire.mutate(c.id) }]} />
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className={s.section}>
        <h2>SSH keys</h2>
        <p className="muted">Keys the system uses to sign in over SFTP.</p>
        <form
          className={s.inline}
          onSubmit={(e) => {
            e.preventDefault();
            addKey.mutate();
          }}
        >
          <label htmlFor="app-key" className="visually-hidden">
            Public key
          </label>
          <input id="app-key" type="text" placeholder="ssh-ed25519 AAAA… comment" value={keyLine} onChange={(e) => setKeyLine(e.target.value)} />
          <Button type="submit" disabled={!keyLine.trim() || addKey.isPending}>
            Add key
          </Button>
        </form>
        {a.sshKeys.length === 0 ? (
          <Empty title="No SSH keys" />
        ) : (
          <ul className={s.cards} style={{ display: "block" }}>
            {a.sshKeys.map((k) => (
              <li key={k.id} className={s.card}>
                <div className={s.cardBody}>
                  <div className={s.cardTitle}>{k.name}</div>
                  <div className={s.cardMeta}>
                    <code>{k.fingerprint}</code>
                    <span>{k.lastUsedAt ? `Last used ${formatDate(k.lastUsedAt)}` : "Never used"}</span>
                  </div>
                </div>
                <RowMenu label={`Actions for key ${k.name}`} actions={[{ label: "Remove", danger: true, onSelect: () => removeKey.mutate(k.id) }]} />
              </li>
            ))}
          </ul>
        )}
      </section>

      {addingCert && (
        <AddCertificateDialog
          base={base}
          onClose={() => setAddingCert(false)}
          onDone={() => {
            setAddingCert(false);
            refresh();
          }}
        />
      )}
      {deleting && (
        <DeleteAppDialog
          account={a}
          onClose={() => setDeleting(false)}
          onDone={() => {
            void client.invalidateQueries({ queryKey: ["admin-apps"] });
            navigate("/admin/applications");
          }}
        />
      )}
    </>
  );
}

function AddCertificateDialog({ base, onClose, onDone }: { base: string; onClose: () => void; onDone: () => void }) {
  const [mode, setMode] = useState<"pem" | "subject" | "san">("pem");
  const [value, setValue] = useState("");
  const m = useMutation({ mutationFn: () => post(base + "/certificates", { [mode]: value.trim() }), onSuccess: onDone });
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title="Add a client certificate">
      <form
        onSubmit={(e) => {
          e.preventDefault();
          m.mutate();
        }}
      >
        <Field label="Match by" htmlFor="cert-mode">
          <select id="cert-mode" value={mode} onChange={(e) => setMode(e.target.value as typeof mode)}>
            <option value="pem">Pinned certificate (paste the PEM)</option>
            <option value="subject">Subject DN, from a trusted CA</option>
            <option value="san">SAN, from a trusted CA</option>
          </select>
        </Field>
        <Field
          label={mode === "pem" ? "Certificate (PEM)" : mode === "subject" ? "Subject" : "SAN"}
          htmlFor="cert-value"
          hint={mode === "pem" ? undefined : mode === "subject" ? "e.g. CN=erp-export,O=Example" : "DNS:host, URI:spiffe://… or EMAIL:a@b"}
        >
          {mode === "pem" ? (
            <textarea id="cert-value" rows={8} value={value} onChange={(e) => setValue(e.target.value)} placeholder="-----BEGIN CERTIFICATE-----" />
          ) : (
            <input id="cert-value" type="text" value={value} onChange={(e) => setValue(e.target.value)} />
          )}
        </Field>
        <ErrorNote>{m.error?.message}</ErrorNote>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="primary" disabled={!value.trim() || m.isPending}>
            Add certificate
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

function DeleteAppDialog({ account, onClose, onDone }: { account: AppAccount; onClose: () => void; onDone: () => void }) {
  const [typed, setTyped] = useState("");
  const m = useMutation({ mutationFn: () => del(`/api/v1/admin/app-accounts/${q(account.id)}`), onSuccess: onDone });
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={`Delete ${account.username}?`}
      description="Its credentials, folder memberships and private files are deleted, and the system using it can no longer sign in."
    >
      <Field label={`Type ${account.username} to confirm`} htmlFor="confirm-app">
        <input id="confirm-app" type="text" value={typed} onChange={(e) => setTyped(e.target.value)} />
      </Field>
      <ErrorNote>{m.error?.message}</ErrorNote>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="danger" disabled={typed !== account.username || m.isPending} onClick={() => m.mutate()}>
          Delete account
        </Button>
      </DialogActions>
    </Dialog>
  );
}
