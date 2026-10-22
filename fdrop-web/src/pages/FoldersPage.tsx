import * as Select from "@radix-ui/react-select";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router";
import { del, get, patch, post, put, q } from "../api/client";
import type { DestinationOption, FolderView, ForwardJob, MemberView } from "../api/types";
import { useMe } from "../auth/useMe";
import { Badge, Button, Dialog, DialogActions, Empty, ErrorNote, Field, LinkButton, RowMenu } from "../components/ui";
import { formatBytes, formatDate } from "../util/format";
import { filesLink } from "./FilesPage";
import { JobBadge } from "./status";
import s from "./pages.module.css";
import sel from "./select.module.css";

/** The destination drop-down: only destinations an admin has assigned to this user. */
export function DestinationSelect({
  id,
  value,
  onChange,
  options,
}: {
  id: string;
  value: string;
  onChange: (v: string) => void;
  options: DestinationOption[];
}) {
  return (
    <Select.Root value={value} onValueChange={onChange}>
      <Select.Trigger id={id} className={sel.trigger} aria-label="Destination">
        <Select.Value placeholder="Choose a destination" />
        <Select.Icon aria-hidden="true">▾</Select.Icon>
      </Select.Trigger>
      <Select.Portal>
        <Select.Content className={sel.content} position="popper" sideOffset={4}>
          <Select.Viewport>
            {options.map((o) => (
              <Select.Item key={o.id} value={o.id} className={sel.item}>
                <Select.ItemText>{o.name}</Select.ItemText>
                {o.description && <span className={sel.desc}>{o.description}</span>}
              </Select.Item>
            ))}
          </Select.Viewport>
        </Select.Content>
      </Select.Portal>
    </Select.Root>
  );
}

export function FoldersPage() {
  const [params, setParams] = useSearchParams();
  const create = params.get("create");
  const folders = useQuery({ queryKey: ["folders"], queryFn: () => get<FolderView[]>("/api/v1/folders") });
  const rows = folders.data ?? [];

  return (
    <>
      <div className={s.head}>
        <h1>Manage folders</h1>
        <div className={s.actions}>
          <Button onClick={() => setParams({ create: "shared" })}>New shared folder</Button>
          <Button variant="primary" onClick={() => setParams({ create: "forward" })}>
            New forward folder
          </Button>
        </div>
      </div>
      <p className="muted">
        Shared folders let the members you choose exchange files. Forward folders send every file that lands in them
        to a partner's SFTP server, chosen from the destinations an admin has assigned to you.
      </p>
      <ErrorNote>{folders.error?.message}</ErrorNote>
      {rows.length === 0 && !folders.isLoading ? (
        <Empty title="You are not in any shared or forward folders yet" />
      ) : (
        <div className={s.listWrap}>
          <table className={s.table}>
            <thead>
              <tr>
                <th scope="col">Folder</th>
                <th scope="col">Type</th>
                <th scope="col">Owner</th>
                <th scope="col">Your access</th>
                <th scope="col">Destination</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((f) => (
                <tr key={f.id}>
                  <td>
                    <span className={s.nameCell}>
                      <Link to={`/folders/${f.id}`}>{f.name}</Link>
                    </span>
                  </td>
                  <td>{f.type === "SHARED" ? <Badge kind="folder">Shared</Badge> : <Badge kind="forward">Forward</Badge>}</td>
                  <td>{f.owner}</td>
                  <td>{f.role === "OWNER" ? "Owner" : f.role === "WRITE" ? "Read-write" : "Read-only"}</td>
                  <td>
                    {f.destinationName ?? ""} {f.paused && <Badge kind="warn">Paused</Badge>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <ul className={s.cards}>
            {rows.map((f) => (
              <li key={f.id} className={s.card}>
                <div className={s.cardBody}>
                  <div className={s.cardTitle}>
                    <Link to={`/folders/${f.id}`}>{f.name}</Link>
                  </div>
                  <div className={s.cardMeta}>
                    {f.type === "SHARED" ? <Badge kind="folder">Shared</Badge> : <Badge kind="forward">Forward</Badge>}
                    <span>Owner: {f.owner}</span>
                    {f.destinationName && <span>To {f.destinationName}</span>}
                    {f.paused && <Badge kind="warn">Paused</Badge>}
                  </div>
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}
      <CreateFolderDialog
        type={create === "forward" ? "forward" : create === "shared" ? "shared" : null}
        onClose={() => setParams({})}
      />
    </>
  );
}

function CreateFolderDialog({ type, onClose }: { type: "shared" | "forward" | null; onClose: () => void }) {
  const navigate = useNavigate();
  const client = useQueryClient();
  const me = useMe();
  const [name, setName] = useState("");
  const [destination, setDestination] = useState("");
  const [keepDays, setKeepDays] = useState(0);
  const dests = useQuery({
    queryKey: ["destinations-mine"],
    enabled: type === "forward",
    queryFn: () => get<DestinationOption[]>("/api/v1/forward-destinations"),
  });
  useEffect(() => {
    setName("");
    setDestination("");
    setKeepDays(0);
  }, [type]);
  const m = useMutation({
    mutationFn: () =>
      post<FolderView>("/api/v1/folders", {
        type,
        name: name.trim(),
        destinationId: type === "forward" ? destination : undefined,
        keepDays: type === "forward" ? keepDays : undefined,
      }),
    onSuccess: (f) => {
      void client.invalidateQueries({ queryKey: ["folders"] });
      void client.invalidateQueries({ queryKey: ["files"] });
      onClose();
      navigate(filesLink(f.path));
    },
  });
  const options = dests.data ?? [];
  const noDestinations = type === "forward" && dests.isSuccess && options.length === 0;
  return (
    <Dialog
      open={type !== null}
      onOpenChange={(o) => !o && onClose()}
      title={type === "forward" ? "New forward folder" : "New shared folder"}
      description={
        type === "forward"
          ? "Every file dropped in this folder is sent to the destination you choose."
          : "You will own the folder and choose who can see it."
      }
    >
      {noDestinations ? (
        <>
          <ErrorNote>
            No forward destinations are assigned to you. Ask an admin to assign one
            {me.data?.settings.forwarding === false ? " (forwarding is not enabled on this server)" : ""}.
          </ErrorNote>
          <DialogActions>
            <Button onClick={onClose}>Close</Button>
          </DialogActions>
        </>
      ) : (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            m.mutate();
          }}
        >
          <Field label="Folder name" htmlFor="folder-name" hint="1 to 100 characters; must be unique across fdrop.">
            <input id="folder-name" type="text" value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
          </Field>
          {type === "forward" && (
            <>
              <Field label="Destination" htmlFor="folder-destination">
                <DestinationSelect id="folder-destination" value={destination} onChange={setDestination} options={options} />
              </Field>
              <Field label="After sending" htmlFor="keep-days">
                <select id="keep-days" value={keepDays} onChange={(e) => setKeepDays(Number(e.target.value))}>
                  <option value={0}>Delete from fdrop</option>
                  <option value={1}>Keep for 1 day</option>
                  <option value={7}>Keep for 7 days</option>
                  <option value={30}>Keep for 30 days</option>
                </select>
              </Field>
            </>
          )}
          <ErrorNote>{m.error?.message}</ErrorNote>
          <DialogActions>
            <Button onClick={onClose}>Cancel</Button>
            <Button
              type="submit"
              variant="primary"
              disabled={!name.trim() || (type === "forward" && !destination) || m.isPending}
            >
              Create folder
            </Button>
          </DialogActions>
        </form>
      )}
    </Dialog>
  );
}

export function FolderDetailPage() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const client = useQueryClient();
  const me = useMe();
  const folder = useQuery({ queryKey: ["folder", id], queryFn: () => get<FolderView>(`/api/v1/folders/${q(id)}`) });
  const f = folder.data;
  const isOwner = f?.role === "OWNER";
  const jobs = useQuery({
    queryKey: ["jobs", id],
    enabled: f?.type === "FORWARD",
    queryFn: () => get<ForwardJob[]>(`/api/v1/folders/${q(id)}/forward-jobs`),
    refetchInterval: 5000,
  });
  const refresh = () => {
    void client.invalidateQueries({ queryKey: ["folder", id] });
    void client.invalidateQueries({ queryKey: ["folders"] });
    void client.invalidateQueries({ queryKey: ["files"] });
  };
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [transfer, setTransfer] = useState<string>();

  const leave = useMutation({
    mutationFn: () => del(`/api/v1/folders/${q(id)}/members/${q(me.data?.username ?? "")}`),
    onSuccess: () => {
      refresh();
      navigate("/folders");
    },
  });
  const retry = useMutation({
    mutationFn: (jobId: string) => post(`/api/v1/folders/${q(id)}/forward-jobs/${q(jobId)}/retry`),
    onSuccess: () => void client.invalidateQueries({ queryKey: ["jobs", id] }),
  });

  if (folder.isError) {
    return (
      <>
        <h1>Folder</h1>
        <ErrorNote>{folder.error.message}</ErrorNote>
      </>
    );
  }
  if (!f) {
    return null;
  }
  return (
    <>
      <div className={s.head}>
        <h1>{f.name}</h1>
        <div className={s.actions}>
          <LinkButton to={filesLink(f.path)} variant="primary">
            Open folder
          </LinkButton>
          {!isOwner && (
            <Button variant="danger" onClick={() => leave.mutate()}>
              Leave folder
            </Button>
          )}
          {isOwner && (
            <Button variant="danger" onClick={() => setConfirmDelete(true)}>
              Delete folder
            </Button>
          )}
        </div>
      </div>
      <ErrorNote>{leave.error?.message}</ErrorNote>
      <dl className={s.kv}>
        <dt>Type</dt>
        <dd>{f.type === "SHARED" ? "Shared folder" : "Forward folder"}</dd>
        <dt>Owner</dt>
        <dd>{f.owner}</dd>
        <dt>Your access</dt>
        <dd>{f.role === "OWNER" ? "Owner" : f.role === "WRITE" ? "Read-write" : "Read-only"}</dd>
        {f.type === "FORWARD" && (
          <>
            <dt>Destination</dt>
            <dd>
              {f.destinationName ?? "–"} {f.paused && <Badge kind="warn">Paused</Badge>}
            </dd>
            <dt>After sending</dt>
            <dd>{f.keepDays ? `Kept for ${f.keepDays} days` : "Deleted from fdrop"}</dd>
          </>
        )}
      </dl>

      {isOwner && <SettingsPanel folder={f} onSaved={refresh} />}
      {isOwner && <MembersPanel folder={f} onChanged={refresh} onTransfer={setTransfer} />}

      {f.type === "FORWARD" && (
        <section className={s.section}>
          <h2>Forward status</h2>
          <ErrorNote>{retry.error?.message}</ErrorNote>
          {(jobs.data ?? []).length === 0 ? (
            <Empty title="Nothing forwarded yet" />
          ) : (
            <div className={s.listWrap}>
              <table className={s.table}>
                <thead>
                  <tr>
                    <th scope="col">File</th>
                    <th scope="col" className={s.num}>
                      Size
                    </th>
                    <th scope="col">Status</th>
                    <th scope="col">Arrived</th>
                    <th scope="col">Details</th>
                    <th scope="col">
                      <span className="visually-hidden">Actions</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {jobs.data!.map((j) => (
                    <tr key={j.id}>
                      <td>{j.fileName}</td>
                      <td className={s.num}>{formatBytes(j.size)}</td>
                      <td>
                        <JobBadge state={j.state} />
                      </td>
                      <td>{formatDate(j.createdAt)}</td>
                      <td className="muted">
                        {j.state === "SENT" ? `Sent ${formatDate(j.sentAt)}` : j.lastError ?? ""}
                        {j.state === "RETRY_WAIT" && j.nextAttemptAt ? ` Next try ${formatDate(j.nextAttemptAt)}.` : ""}
                      </td>
                      <td>
                        {isOwner && j.state === "FAILED" && (
                          <Button small onClick={() => retry.mutate(j.id)}>
                            Retry
                          </Button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <ul className={s.cards}>
                {jobs.data!.map((j) => (
                  <li key={j.id} className={s.card}>
                    <div className={s.cardBody}>
                      <div className={s.cardTitle}>{j.fileName}</div>
                      <div className={s.cardMeta}>
                        <JobBadge state={j.state} />
                        <span>{formatBytes(j.size)}</span>
                        {j.lastError && <span>{j.lastError}</span>}
                      </div>
                    </div>
                    {isOwner && j.state === "FAILED" && (
                      <Button small onClick={() => retry.mutate(j.id)}>
                        Retry
                      </Button>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </section>
      )}

      <DeleteFolderDialog
        open={confirmDelete}
        folder={f}
        onClose={() => setConfirmDelete(false)}
        onDone={() => {
          refresh();
          navigate("/folders");
        }}
      />
      {transfer && (
        <TransferDialog folder={f} username={transfer} onClose={() => setTransfer(undefined)} onDone={refresh} />
      )}
    </>
  );
}

function SettingsPanel({ folder, onSaved }: { folder: FolderView; onSaved: () => void }) {
  const [name, setName] = useState(folder.name);
  const [destination, setDestination] = useState(folder.destinationId ?? "");
  const [keepDays, setKeepDays] = useState(folder.keepDays ?? 0);
  const dests = useQuery({
    queryKey: ["destinations-mine"],
    enabled: folder.type === "FORWARD",
    queryFn: () => get<DestinationOption[]>("/api/v1/forward-destinations"),
  });
  const m = useMutation({
    mutationFn: () =>
      patch(`/api/v1/folders/${q(folder.id)}`, {
        name: name.trim(),
        destinationId: folder.type === "FORWARD" ? destination : undefined,
        keepDays: folder.type === "FORWARD" ? keepDays : undefined,
      }),
    onSuccess: onSaved,
  });
  return (
    <section className={s.section}>
      <h2>Settings</h2>
      <form
        className={s.form}
        onSubmit={(e) => {
          e.preventDefault();
          m.mutate();
        }}
      >
        <Field label="Name" htmlFor="settings-name">
          <input id="settings-name" type="text" value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
        </Field>
        {folder.type === "FORWARD" && (
          <div className={s.formRow}>
            <Field label="Destination" htmlFor="settings-destination" hint="Pending files keep their original destination.">
              <DestinationSelect
                id="settings-destination"
                value={destination}
                onChange={setDestination}
                options={dests.data ?? []}
              />
            </Field>
            <Field label="After sending" htmlFor="settings-keep">
              <select id="settings-keep" value={keepDays} onChange={(e) => setKeepDays(Number(e.target.value))}>
                <option value={0}>Delete from fdrop</option>
                <option value={1}>Keep for 1 day</option>
                <option value={7}>Keep for 7 days</option>
                <option value={30}>Keep for 30 days</option>
              </select>
            </Field>
          </div>
        )}
        <ErrorNote>{m.error?.message}</ErrorNote>
        {m.isSuccess && <p className="muted">Saved.</p>}
        <Button type="submit" variant="primary" disabled={m.isPending}>
          Save settings
        </Button>
      </form>
    </section>
  );
}

function MembersPanel({
  folder,
  onChanged,
  onTransfer,
}: {
  folder: FolderView;
  onChanged: () => void;
  onTransfer: (username: string) => void;
}) {
  const [username, setUsername] = useState("");
  const [permission, setPermission] = useState<"READ" | "WRITE">("WRITE");
  const suggestions = useQuery({
    queryKey: ["user-search", username],
    enabled: username.trim().length >= 1,
    queryFn: () => get<{ username: string }[]>("/api/v1/users?search=" + q(username.trim())),
  });
  const add = useMutation({
    mutationFn: (m: MemberView) => put(`/api/v1/folders/${q(folder.id)}/members/${q(m.username)}`, { permission: m.permission }),
    onSuccess: () => {
      setUsername("");
      onChanged();
    },
  });
  const remove = useMutation({
    mutationFn: (u: string) => del(`/api/v1/folders/${q(folder.id)}/members/${q(u)}`),
    onSuccess: onChanged,
  });
  return (
    <section className={s.section}>
      <h2>Members</h2>
      <p className="muted">Only you and the members below can see this folder.</p>
      <form
        className={s.inline}
        onSubmit={(e) => {
          e.preventDefault();
          add.mutate({ username: username.trim(), permission });
        }}
      >
        <label htmlFor="member-name" className="visually-hidden">
          Username
        </label>
        <input
          id="member-name"
          type="text"
          placeholder="Username"
          list="member-suggestions"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
        />
        <datalist id="member-suggestions">
          {(suggestions.data ?? []).map((u) => (
            <option key={u.username} value={u.username} />
          ))}
        </datalist>
        <label htmlFor="member-permission" className="visually-hidden">
          Permission
        </label>
        <select id="member-permission" value={permission} onChange={(e) => setPermission(e.target.value as "READ" | "WRITE")}>
          <option value="WRITE">Read-write</option>
          <option value="READ">Read-only</option>
        </select>
        <Button type="submit" variant="primary" disabled={!username.trim() || add.isPending}>
          Add member
        </Button>
      </form>
      <ErrorNote>{add.error?.message ?? remove.error?.message}</ErrorNote>
      {folder.members.length === 0 ? (
        <Empty title="No members yet" />
      ) : (
        <ul className={s.cards} style={{ display: "block" }}>
          {folder.members.map((m) => (
            <li key={m.username} className={s.card}>
              <div className={s.cardBody}>
                <div className={s.cardTitle}>{m.username}</div>
                <div className={s.cardMeta}>{m.permission === "WRITE" ? "Read-write" : "Read-only"}</div>
              </div>
              <RowMenu
                label={`Actions for ${m.username}`}
                actions={[
                  {
                    label: m.permission === "WRITE" ? "Make read-only" : "Make read-write",
                    onSelect: () => add.mutate({ username: m.username, permission: m.permission === "WRITE" ? "READ" : "WRITE" }),
                  },
                  { label: "Make owner", onSelect: () => onTransfer(m.username) },
                  { label: "Remove", onSelect: () => remove.mutate(m.username), danger: true },
                ]}
              />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function TransferDialog({
  folder,
  username,
  onClose,
  onDone,
}: {
  folder: FolderView;
  username: string;
  onClose: () => void;
  onDone: () => void;
}) {
  const m = useMutation({
    mutationFn: () => post(`/api/v1/folders/${q(folder.id)}/owner`, { username }),
    onSuccess: () => {
      onClose();
      onDone();
    },
  });
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={`Make ${username} the owner?`}
      description="You will stay in the folder as a read-write member. The folder's files will count against their quota."
    >
      <ErrorNote>{m.error?.message}</ErrorNote>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="primary" onClick={() => m.mutate()} disabled={m.isPending}>
          Transfer ownership
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function DeleteFolderDialog({
  open,
  folder,
  onClose,
  onDone,
}: {
  open: boolean;
  folder: FolderView;
  onClose: () => void;
  onDone: () => void;
}) {
  const [typed, setTyped] = useState("");
  const m = useMutation({ mutationFn: () => del(`/api/v1/folders/${q(folder.id)}`), onSuccess: onDone });
  return (
    <Dialog
      open={open}
      onOpenChange={(o) => !o && onClose()}
      title={`Delete ${folder.name}?`}
      description="The folder and every file in it are deleted for all members. This cannot be undone."
    >
      <Field label={`Type ${folder.name} to confirm`} htmlFor="confirm-folder">
        <input id="confirm-folder" type="text" value={typed} onChange={(e) => setTyped(e.target.value)} />
      </Field>
      <ErrorNote>{m.error?.message}</ErrorNote>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="danger" disabled={typed !== folder.name || m.isPending} onClick={() => m.mutate()}>
          Delete folder
        </Button>
      </DialogActions>
    </Dialog>
  );
}
