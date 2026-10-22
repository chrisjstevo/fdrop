import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { del, get, post, signOut } from "../api/client";
import type { SshKey } from "../api/types";
import { useMe } from "../auth/useMe";
import { ProblemNote, TwoFactorSection } from "../components/TwoFactor";
import { Button, Empty, Field, ProgressBar, RowMenu } from "../components/ui";
import { formatBytes, formatDate } from "../util/format";
import s from "./pages.module.css";

export function AccountPage() {
  const me = useMe();
  const client = useQueryClient();
  const keys = useQuery({ queryKey: ["ssh-keys"], queryFn: () => get<SshKey[]>("/api/v1/me/ssh-keys") });
  const [name, setName] = useState("");
  const [publicKey, setPublicKey] = useState("");
  const add = useMutation({
    mutationFn: () => post("/api/v1/me/ssh-keys", { name: name.trim() || undefined, publicKey: publicKey.trim() }),
    onSuccess: () => {
      setName("");
      setPublicKey("");
      void client.invalidateQueries({ queryKey: ["ssh-keys"] });
    },
  });
  const remove = useMutation({
    mutationFn: (id: string) => del(`/api/v1/me/ssh-keys/${encodeURIComponent(id)}`),
    onSuccess: () => void client.invalidateQueries({ queryKey: ["ssh-keys"] }),
  });

  const m = me.data;
  if (!m) {
    return null;
  }
  const used = m.usedBytes + m.reservedBytes;
  const percent = m.quotaBytes > 0 ? Math.min(100, Math.floor((used / m.quotaBytes) * 100)) : 0;
  const host = window.location.hostname;

  return (
    <>
      <div className={s.head}>
        <h1>Account</h1>
        <div className={s.actions}>
          <Button onClick={() => void signOut()}>Sign out</Button>
        </div>
      </div>
      <div className={s.panel}>
        <dl className={s.kv}>
          <dt>Username</dt>
          <dd>{m.username}</dd>
          {m.email && (
            <>
              <dt>Email</dt>
              <dd>{m.email}</dd>
            </>
          )}
          <dt>Roles</dt>
          <dd>{[m.roles.standard && "Standard", m.roles.admin && "Admin"].filter(Boolean).join(", ") || "None"}</dd>
        </dl>
      </div>
      {m.roles.standard && (
        <div className={`${s.panel} ${s.quota}`}>
          <p className="section-label">Storage</p>
          <ProgressBar percent={percent} label="Quota used" />
          <p>
            {formatBytes(used)} of {formatBytes(m.quotaBytes)} used
            {m.reservedBytes > 0 && ` (${formatBytes(m.reservedBytes)} held by uploads in progress)`}
          </p>
        </div>
      )}
      <TwoFactorSection me={m} />
      {m.roles.standard && m.settings.sftpPort && (
        <section className={s.section}>
          <h2>SFTP access</h2>
          <p>
            Connect any SFTP client to <code>{host}</code> port <code>{m.settings.sftpPort}</code> as{" "}
            <code>{m.username}</code>, with your password or an SSH key added below
            {m.twoFactor.factors?.some((f) => f.kind !== "recovery-codes") || m.twoFactor.required
              ? " (with two-factor sign-in, a password login also asks for a code from your authenticator app)"
              : ""}
            . You start in{" "}
            <code>/home</code>; your shared and forward folders are under <code>/shared</code> and{" "}
            <code>/forward</code>.
          </p>
          <pre className={s.pre}>{`sftp -P ${m.settings.sftpPort} ${m.username}@${host}`}</pre>
        </section>
      )}
      {m.roles.standard && (
        <section className={s.section}>
          <h2>SSH keys</h2>
          {(keys.data ?? []).length === 0 && !keys.isLoading ? (
            <Empty title="No SSH keys yet" />
          ) : (
            <ul className={s.cards} style={{ display: "block" }}>
              {(keys.data ?? []).map((k) => (
                <li key={k.id} className={s.card}>
                  <div className={s.cardBody}>
                    <div className={s.cardTitle}>{k.name}</div>
                    <div className={s.cardMeta}>
                      <code>{k.fingerprint}</code>
                      <span>{k.type}</span>
                      <span>Added {formatDate(k.createdAt)}</span>
                      <span>{k.lastUsedAt ? `Last used ${formatDate(k.lastUsedAt)}` : "Never used"}</span>
                    </div>
                  </div>
                  <RowMenu
                    label={`Actions for key ${k.name}`}
                    actions={[{ label: "Remove", danger: true, onSelect: () => remove.mutate(k.id) }]}
                  />
                </li>
              ))}
            </ul>
          )}
          <form
            className={s.form}
            onSubmit={(e) => {
              e.preventDefault();
              add.mutate();
            }}
          >
            <h3>Add a key</h3>
            <Field label="Name" htmlFor="key-name" hint="Optional, e.g. the laptop it is on.">
              <input id="key-name" type="text" value={name} onChange={(e) => setName(e.target.value)} />
            </Field>
            <Field label="Public key" htmlFor="key-value" hint="The contents of a .pub file, e.g. ~/.ssh/id_ed25519.pub">
              <textarea
                id="key-value"
                rows={3}
                value={publicKey}
                onChange={(e) => setPublicKey(e.target.value)}
                placeholder="ssh-ed25519 AAAA… you@laptop"
              />
            </Field>
            <ProblemNote error={add.error ?? remove.error} />
            <Button type="submit" variant="primary" disabled={!publicKey.trim() || add.isPending}>
              Add key
            </Button>
          </form>
        </section>
      )}
    </>
  );
}
