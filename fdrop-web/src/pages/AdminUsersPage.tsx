import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { del, get, post } from "../api/client";
import type { AdminUser, CreatedUser } from "../api/types";
import { useMe } from "../auth/useMe";
import { ProblemNote } from "../components/TwoFactor";
import { Badge, Button, Dialog, DialogActions, Empty, ErrorNote, Field, Notice, RowMenu, type MenuAction } from "../components/ui";
import { formatBytes, formatDate } from "../util/format";
import s from "./pages.module.css";

export function AdminUsersPage() {
  const me = useMe();
  const client = useQueryClient();
  const [search, setSearch] = useState("");
  const [creating, setCreating] = useState(false);
  const [deleting, setDeleting] = useState<AdminUser>();
  const [resetting, setResetting] = useState<AdminUser>();
  const [created, setCreated] = useState<CreatedUser>();
  const users = useQuery({
    queryKey: ["admin-users", search],
    queryFn: () => get<AdminUser[]>("/api/v1/admin/users?search=" + encodeURIComponent(search.trim())),
  });
  const managed = me.data?.settings.userManagement !== false;
  const actions = (u: AdminUser): MenuAction[] => [
    ...(u.twoFactor ? [{ label: "Reset two-factor", onSelect: () => setResetting(u) }] : []),
    ...(managed ? [{ label: "Delete user", danger: true, onSelect: () => setDeleting(u) }] : []),
  ];
  const rows = users.data ?? [];

  const roles = (u: AdminUser) => (
    <span className={s.inline}>
      {u.standard && <Badge kind="folder">Standard</Badge>}
      {u.admin && <Badge kind="forward">Admin</Badge>}
      {!u.enabled && <Badge kind="warn">Disabled</Badge>}
    </span>
  );

  return (
    <>
      <div className={s.head}>
        <h1>Users</h1>
        <div className={s.actions}>
          {managed && (
            <Button variant="primary" onClick={() => setCreating(true)}>
              New user
            </Button>
          )}
        </div>
      </div>
      {!managed && <Notice>User management is turned off on this server; manage users in Keycloak.</Notice>}
      {created && (
        <Notice>
          <strong>{created.user.username}</strong> was created.{" "}
          {created.emailSent ? (
            "Keycloak emailed them a link to set their password."
          ) : (
            <>
              Their temporary password is <code>{created.temporaryPassword}</code>. It is shown only once; they must
              change it at first sign-in.
            </>
          )}{" "}
          <button type="button" className="muted" onClick={() => setCreated(undefined)} style={{ border: "none", background: "none", cursor: "pointer", textDecoration: "underline" }}>
            Hide
          </button>
        </Notice>
      )}
      <div className={s.filters}>
        <div>
          <label htmlFor="user-search">Search</label>
          <input id="user-search" type="search" value={search} onChange={(e) => setSearch(e.target.value)} />
        </div>
      </div>
      <ProblemNote error={users.error} />
      {rows.length === 0 && !users.isLoading ? (
        <Empty title="No users found" />
      ) : (
        <div className={s.listWrap}>
          <table className={s.table}>
            <thead>
              <tr>
                <th scope="col">Username</th>
                <th scope="col">Name</th>
                <th scope="col">Roles</th>
                <th scope="col" className={s.num}>
                  Storage used
                </th>
                <th scope="col">Last sign-in</th>
                <th scope="col">Two-factor</th>
                <th scope="col">
                  <span className="visually-hidden">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((u) => (
                <tr key={u.id}>
                  <td>
                    <strong>{u.username}</strong>
                    {u.email && <div className="muted">{u.email}</div>}
                  </td>
                  <td>{[u.firstName, u.lastName].filter(Boolean).join(" ")}</td>
                  <td>{roles(u)}</td>
                  <td className={s.num}>{u.usedBytes !== undefined ? formatBytes(u.usedBytes) : "–"}</td>
                  <td>{u.lastLoginAt ? formatDate(u.lastLoginAt) : "Never"}</td>
                  <td>{twoFactor(u)}</td>
                  <td className={s.num}>
                    {u.id !== me.data?.sub && actions(u).length > 0 && (
                      <RowMenu label={`Actions for ${u.username}`} actions={actions(u)} />
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <ul className={s.cards}>
            {rows.map((u) => (
              <li key={u.id} className={s.card}>
                <div className={s.cardBody}>
                  <div className={s.cardTitle}>{u.username}</div>
                  <div className={s.cardMeta}>
                    {roles(u)}
                    {twoFactor(u)}
                    {u.email && <span>{u.email}</span>}
                  </div>
                </div>
                {u.id !== me.data?.sub && actions(u).length > 0 && (
                  <RowMenu label={`Actions for ${u.username}`} actions={actions(u)} />
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
      {resetting && (
        <ResetTwoFactorDialog
          user={resetting}
          onClose={() => setResetting(undefined)}
          onDone={() => {
            setResetting(undefined);
            void client.invalidateQueries({ queryKey: ["admin-users"] });
          }}
        />
      )}
      <CreateUserDialog
        open={creating}
        onClose={() => setCreating(false)}
        onCreated={(c) => {
          setCreated(c);
          setCreating(false);
          void client.invalidateQueries({ queryKey: ["admin-users"] });
        }}
      />
      {deleting && (
        <DeleteUserDialog
          user={deleting}
          onClose={() => setDeleting(undefined)}
          onDone={() => {
            setDeleting(undefined);
            void client.invalidateQueries({ queryKey: ["admin-users"] });
          }}
        />
      )}
    </>
  );
}

function CreateUserDialog({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: (c: CreatedUser) => void;
}) {
  const [form, setForm] = useState({ username: "", email: "", firstName: "", lastName: "", admin: false });
  const m = useMutation({
    mutationFn: () =>
      post<CreatedUser>("/api/v1/admin/users", {
        username: form.username.trim(),
        email: form.email.trim() || undefined,
        firstName: form.firstName.trim() || undefined,
        lastName: form.lastName.trim() || undefined,
        admin: form.admin,
      }),
    onSuccess: (c) => {
      setForm({ username: "", email: "", firstName: "", lastName: "", admin: false });
      onCreated(c);
    },
  });
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [k]: k === "admin" ? e.target.checked : e.target.value }));
  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()} title="New user">
      <form
        onSubmit={(e) => {
          e.preventDefault();
          m.mutate();
        }}
      >
        <Field label="Username" htmlFor="nu-username">
          <input id="nu-username" type="text" value={form.username} onChange={set("username")} autoFocus />
        </Field>
        <Field label="Email" htmlFor="nu-email" hint="If the realm can send mail, the user gets a link to set a password.">
          <input id="nu-email" type="email" value={form.email} onChange={set("email")} />
        </Field>
        <div className={s.formRow}>
          <Field label="First name" htmlFor="nu-first">
            <input id="nu-first" type="text" value={form.firstName} onChange={set("firstName")} />
          </Field>
          <Field label="Last name" htmlFor="nu-last">
            <input id="nu-last" type="text" value={form.lastName} onChange={set("lastName")} />
          </Field>
        </div>
        <label className={s.checkbox}>
          <input type="checkbox" checked={form.admin} onChange={set("admin")} /> Also make them an admin
        </label>
        <ErrorNote>{m.error?.message}</ErrorNote>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="primary" disabled={!form.username.trim() || m.isPending}>
            Create user
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

function twoFactor(u: AdminUser) {
  if (u.twoFactor === undefined || u.twoFactor === null) {
    return <span className="muted">–</span>;
  }
  return u.twoFactor ? <Badge kind="ok">On</Badge> : <Badge kind="neutral">Off</Badge>;
}

function ResetTwoFactorDialog({ user, onClose, onDone }: { user: AdminUser; onClose: () => void; onDone: () => void }) {
  const m = useMutation({
    mutationFn: () => post(`/api/v1/admin/users/${encodeURIComponent(user.id)}/two-factor/reset`),
    onSuccess: onDone,
  });
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={`Reset two-factor for ${user.username}?`}
      description="Their authenticator apps, security keys and recovery codes are removed and they are signed out everywhere. If two-factor is required for them, they set it up again at their next sign-in. Only do this once you are sure who is asking."
    >
      <ProblemNote error={m.error} />
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="danger" disabled={m.isPending} onClick={() => m.mutate()}>
          Reset two-factor
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function DeleteUserDialog({ user, onClose, onDone }: { user: AdminUser; onClose: () => void; onDone: () => void }) {
  const [typed, setTyped] = useState("");
  const m = useMutation({ mutationFn: () => del(`/api/v1/admin/users/${encodeURIComponent(user.id)}`), onSuccess: onDone });
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={`Delete ${user.username}?`}
      description="They are signed out everywhere and their account, SSH keys and private files are deleted. Folders they own pass to you."
    >
      <Field label={`Type ${user.username} to confirm`} htmlFor="confirm-user">
        <input id="confirm-user" type="text" value={typed} onChange={(e) => setTyped(e.target.value)} />
      </Field>
      <ErrorNote>{m.error?.message}</ErrorNote>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="danger" disabled={typed !== user.username || m.isPending} onClick={() => m.mutate()}>
          Delete user
        </Button>
      </DialogActions>
    </Dialog>
  );
}
