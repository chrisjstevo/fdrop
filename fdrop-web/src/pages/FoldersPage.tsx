import * as Menu from "@radix-ui/react-dropdown-menu";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router";
import { del, get, patch, post, put, q } from "../api/client";
import type { DestinationOption, FolderView, ForwardJob, MemberView, UserMatch } from "../api/types";
import { useMe } from "../auth/useMe";
import { Badge, Button, Dialog, DialogActions, Empty, ErrorNote, Field, LinkButton, RowMenu } from "../components/ui";
import { expiryProblem, longerThan, parseExpiry } from "../util/expiry";
import { formatBytes, formatDate } from "../util/format";
import { filesLink } from "./FilesPage";
import { FileForwardBadge, groupJobs, JobBadge, type FileJobs } from "./status";
import s from "./pages.module.css";
import sel from "./select.module.css";

/**
 * The destination multi-select: a drop-down of the destinations an admin has assigned to this user, where each
 * one ticked receives its own copy of every file. Stays open while ticking, so several can be chosen at once.
 */
export function DestinationPicker({
  id,
  value,
  onChange,
  options,
  max,
}: {
  id: string;
  value: string[];
  onChange: (v: string[]) => void;
  options: DestinationOption[];
  max: number;
}) {
  const names = value.map((v) => options.find((o) => o.id === v)?.name ?? v);
  const toggle = (destId: string, on: boolean) =>
    onChange(on ? [...value.filter((v) => v !== destId), destId] : value.filter((v) => v !== destId));
  return (
    <Menu.Root modal={false}>
      <Menu.Trigger id={id} className={sel.trigger} aria-label={`Destinations: ${names.join(", ") || "none chosen"}`}>
        <span>{names.length ? names.join(", ") : "Choose destinations"}</span>
        <span aria-hidden="true">▾</span>
      </Menu.Trigger>
      <Menu.Portal>
        <Menu.Content className={sel.content} align="start" sideOffset={4}>
          {options.map((o) => {
            const checked = value.includes(o.id);
            return (
              <Menu.CheckboxItem
                key={o.id}
                className={sel.item}
                checked={checked}
                disabled={!checked && value.length >= max}
                onCheckedChange={(on) => toggle(o.id, on === true)}
                onSelect={(e) => e.preventDefault()}
              >
                <span className={sel.check}>
                  <span aria-hidden="true">{checked ? "☑" : "☐"}</span> {o.name}
                </span>
                {o.description && <span className={sel.desc}>{o.description}</span>}
              </Menu.CheckboxItem>
            );
          })}
        </Menu.Content>
      </Menu.Portal>
    </Menu.Root>
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
                <th scope="col">Destinations</th>
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
  const [chosen, setChosen] = useState<string[]>([]);
  const [keepDays, setKeepDays] = useState(0);
  const max = me.data?.settings.maxDestinationsPerFolder ?? 5;
  const dests = useQuery({
    queryKey: ["destinations-mine"],
    enabled: type === "forward",
    queryFn: () => get<DestinationOption[]>("/api/v1/forward-destinations"),
  });
  useEffect(() => {
    setName("");
    setChosen([]);
    setKeepDays(0);
  }, [type]);
  const m = useMutation({
    mutationFn: () =>
      post<FolderView>("/api/v1/folders", {
        type,
        name: name.trim(),
        destinationIds: type === "forward" ? chosen : undefined,
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
          ? "Every file dropped in this folder is sent to each destination you choose."
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
              <Field
                label="Destinations"
                htmlFor="folder-destination"
                hint={`Each one gets its own copy of every file. Choose up to ${max}.`}
              >
                <DestinationPicker id="folder-destination" value={chosen} onChange={setChosen} options={options} max={max} />
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
              disabled={!name.trim() || (type === "forward" && chosen.length === 0) || m.isPending}
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
            <dt>{(f.destinations ?? []).length > 1 ? "Destinations" : "Destination"}</dt>
            <dd>
              {(f.destinations ?? []).length === 0
                ? "–"
                : f.destinations!.map((d, i) => (
                    <span key={d.id}>
                      {i > 0 && ", "}
                      {d.name}
                      {d.paused && (
                        <>
                          {" "}
                          <Badge kind="warn">Paused</Badge>
                        </>
                      )}
                    </span>
                  ))}
            </dd>
            <dt>After sending</dt>
            <dd>{f.keepDays ? `Kept for ${f.keepDays} days` : "Deleted from fdrop"}</dd>
          </>
        )}
        {f.type === "SHARED" && (
          <>
            <dt>Default expiry</dt>
            <dd>{f.defaultExpiry ?? (f.maxExpiry ? `The maximum, ${f.maxExpiry}` : "Files never expire")}</dd>
            <dt>Maximum expiry</dt>
            <dd>{f.maxExpiry ?? "None"}</dd>
          </>
        )}
      </dl>

      {isOwner && <SettingsPanel folder={f} onSaved={refresh} />}
      {isOwner && <MembersPanel folder={f} onChanged={refresh} onTransfer={setTransfer} />}

      {f.type === "FORWARD" && (
        <ForwardStatus folderId={id} jobs={jobs.data ?? []} isOwner={isOwner} onChanged={() => void jobs.refetch()} />
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
  const me = useMe();
  const [name, setName] = useState(folder.name);
  const current = (folder.destinations ?? []).map((d) => d.id);
  const [chosen, setChosen] = useState<string[]>(current);
  const [sendExisting, setSendExisting] = useState(false);
  const max = me.data?.settings.maxDestinationsPerFolder ?? 5;
  const [keepDays, setKeepDays] = useState(folder.keepDays ?? 0);
  const [defaultExpiry, setDefaultExpiry] = useState(folder.defaultExpiry ?? "");
  const [maxExpiry, setMaxExpiry] = useState(folder.maxExpiry ?? "");
  const [applyToExisting, setApplyToExisting] = useState(false);
  const dests = useQuery({
    queryKey: ["destinations-mine"],
    enabled: folder.type === "FORWARD",
    queryFn: () => get<DestinationOption[]>("/api/v1/forward-destinations"),
  });
  const shared = folder.type === "SHARED";
  const defaultProblem = defaultExpiry.trim() ? expiryProblem(defaultExpiry) : undefined;
  const maxProblem = maxExpiry.trim() ? expiryProblem(maxExpiry) : undefined;
  const orderProblem =
    !defaultProblem && !maxProblem && defaultExpiry.trim() && maxExpiry.trim()
      && longerThan(parseExpiry(defaultExpiry), parseExpiry(maxExpiry))
      ? "The default can't be longer than the maximum"
      : undefined;
  const maxChanged = shared && maxExpiry.trim() !== (folder.maxExpiry ?? "") && !!maxExpiry.trim();
  // the owner's assigned destinations, plus any the folder has that are no longer assigned, so they can be removed
  const options = [
    ...(dests.data ?? []),
    ...(folder.destinations ?? [])
      .filter((d) => !(dests.data ?? []).some((o) => o.id === d.id))
      .map((d) => ({ id: d.id, name: `${d.name} (no longer assigned)` })),
  ];
  const added = chosen.filter((c) => !current.includes(c));
  const destinationsChanged = added.length > 0 || current.some((c) => !chosen.includes(c));
  const m = useMutation({
    mutationFn: () =>
      patch<{ expiryFilesChanged?: number }>(`/api/v1/folders/${q(folder.id)}`, {
        name: name.trim(),
        destinationIds: folder.type === "FORWARD" && destinationsChanged ? chosen : undefined,
        sendExisting: folder.type === "FORWARD" && added.length > 0 && sendExisting ? true : undefined,
        keepDays: folder.type === "FORWARD" ? keepDays : undefined,
        defaultExpiry: shared && defaultExpiry.trim() !== (folder.defaultExpiry ?? "") ? defaultExpiry.trim() : undefined,
        maxExpiry: shared && maxExpiry.trim() !== (folder.maxExpiry ?? "") ? maxExpiry.trim() : undefined,
        applyToExisting: maxChanged && applyToExisting ? true : undefined,
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
            <Field
              label="Destinations"
              htmlFor="settings-destination"
              hint={`Up to ${max}. Added ones get new files; files already queued to a removed one still go to it.`}
            >
              <DestinationPicker id="settings-destination" value={chosen} onChange={setChosen} options={options} max={max} />
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
        {folder.type === "FORWARD" && added.length > 0 && (
          <label className={s.checkbox}>
            <input type="checkbox" checked={sendExisting} onChange={(e) => setSendExisting(e.target.checked)} /> Also send
            the files still in this folder to {added.length === 1 ? "the added destination" : "the added destinations"}
          </label>
        )}
        {shared && (
          <div className={s.formRow}>
            <Field
              label="Default expiry"
              htmlFor="settings-default-expiry"
              hint={defaultProblem ?? "Files uploaded without an expiry are deleted this long after they arrive, e.g. 7 days. Empty: never."}
            >
              <input
                id="settings-default-expiry"
                type="text"
                placeholder="never"
                value={defaultExpiry}
                aria-invalid={!!defaultProblem}
                onChange={(e) => setDefaultExpiry(e.target.value)}
              />
            </Field>
            <Field
              label="Maximum expiry"
              htmlFor="settings-max-expiry"
              hint={maxProblem ?? orderProblem ?? "When set, every file in this folder is deleted within this time. Empty: no maximum."}
            >
              <input
                id="settings-max-expiry"
                type="text"
                placeholder="none"
                value={maxExpiry}
                aria-invalid={!!maxProblem || !!orderProblem}
                onChange={(e) => setMaxExpiry(e.target.value)}
              />
            </Field>
          </div>
        )}
        {maxChanged && (
          <label className={s.checkbox}>
            <input type="checkbox" checked={applyToExisting} onChange={(e) => setApplyToExisting(e.target.checked)} />
            Also shorten the expiry of files already in this folder that would outlive the new maximum
          </label>
        )}
        <ErrorNote>{m.error?.message}</ErrorNote>
        {m.isSuccess && (
          <p className="muted">
            Saved.
            {m.data?.expiryFilesChanged ? ` ${m.data.expiryFilesChanged} files now expire sooner.` : ""}
          </p>
        )}
        <Button
          type="submit"
          variant="primary"
          disabled={
            m.isPending || !!defaultProblem || !!maxProblem || !!orderProblem
            || (folder.type === "FORWARD" && chosen.length === 0)
          }
        >
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
    queryFn: () => get<UserMatch[]>("/api/v1/users?search=" + q(username.trim())),
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
            <option key={u.username} value={u.username} label={u.kind === "application" ? `${u.username} (App)` : u.username} />
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
                <div className={s.cardTitle}>
                  {m.username} {m.kind === "APPLICATION" && <Badge kind="neutral">App</Badge>}
                </div>
                <div className={s.cardMeta}>{m.permission === "WRITE" ? "Read-write" : "Read-only"}</div>
              </div>
              <RowMenu
                label={`Actions for ${m.username}`}
                actions={[
                  {
                    label: m.permission === "WRITE" ? "Make read-only" : "Make read-write",
                    onSelect: () => add.mutate({ username: m.username, permission: m.permission === "WRITE" ? "READ" : "WRITE" }),
                  },
                  // application accounts are members only and never own folders
                  ...(m.kind === "APPLICATION" ? [] : [{ label: "Make owner", onSelect: () => onTransfer(m.username) }]),
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

function jobDetail(j: ForwardJob) {
  if (j.state === "SENT") {
    return `sent ${formatDate(j.sentAt)}`;
  }
  if (j.state === "CANCELLED") {
    return j.cancelReason ? `stopped: ${j.cancelReason}` : "stopped";
  }
  const retryAt = j.state === "RETRY_WAIT" && j.nextAttemptAt ? ` Next try ${formatDate(j.nextAttemptAt)}.` : "";
  return (j.lastError ?? "") + retryAt;
}

/** The folder's forward status: each file's overall state, and where it stands at every destination. */
function ForwardStatus({
  folderId,
  jobs,
  isOwner,
  onChanged,
}: {
  folderId: string;
  jobs: ForwardJob[];
  isOwner: boolean;
  onChanged: () => void;
}) {
  const [stopping, setStopping] = useState<ForwardJob>();
  const retry = useMutation({
    mutationFn: (jobId: string) => post(`/api/v1/folders/${q(folderId)}/forward-jobs/${q(jobId)}/retry`),
    onSuccess: onChanged,
  });
  const files = groupJobs(jobs);
  const perDestination = (g: FileJobs) => (
    <ul className={s.destList}>
      {g.jobs.map((j) => (
        <li key={j.id}>
          <span className={s.destName}>{j.destinationName}</span> <JobBadge state={j.state} />{" "}
          <span className="muted">{jobDetail(j)}</span>
          {isOwner && j.state === "FAILED" && (
            <span className={s.destActions}>
              <Button small onClick={() => retry.mutate(j.id)}>
                Retry
              </Button>
              <Button small variant="ghost" onClick={() => setStopping(j)}>
                Stop sending
              </Button>
            </span>
          )}
        </li>
      ))}
    </ul>
  );
  return (
    <section className={s.section}>
      <h2>Forward status</h2>
      <ErrorNote>{retry.error?.message}</ErrorNote>
      {files.length === 0 ? (
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
                <th scope="col">Destinations</th>
              </tr>
            </thead>
            <tbody>
              {files.map((g) => (
                <tr key={g.key}>
                  <td>{g.fileName}</td>
                  <td className={s.num}>{formatBytes(g.size)}</td>
                  <td>
                    <FileForwardBadge states={g.jobs.map((j) => j.state)} />
                  </td>
                  <td>{formatDate(g.createdAt)}</td>
                  <td>{perDestination(g)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <ul className={s.cards}>
            {files.map((g) => (
              <li key={g.key} className={s.card}>
                <div className={s.cardBody}>
                  <div className={s.cardTitle}>{g.fileName}</div>
                  <div className={s.cardMeta}>
                    <FileForwardBadge states={g.jobs.map((j) => j.state)} />
                    <span>{formatBytes(g.size)}</span>
                  </div>
                  {perDestination(g)}
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}
      {stopping && (
        <StopSendingDialog
          folderId={folderId}
          job={stopping}
          onClose={() => setStopping(undefined)}
          onDone={() => {
            setStopping(undefined);
            onChanged();
          }}
        />
      )}
    </section>
  );
}

/** Gives up on one destination for one file, with a reason for the audit log. */
export function StopSendingDialog({
  folderId,
  job,
  onClose,
  onDone,
}: {
  folderId: string;
  job: { id: string; fileName: string; destinationName: string };
  onClose: () => void;
  onDone: () => void;
}) {
  const [reason, setReason] = useState("");
  const m = useMutation({
    mutationFn: () =>
      post(`/api/v1/folders/${q(folderId)}/forward-jobs/${q(job.id)}/cancel`, { reason: reason.trim() }),
    onSuccess: onDone,
  });
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={`Stop sending ${job.fileName} to ${job.destinationName}?`}
      description="fdrop stops trying this destination for this file. Once the other destinations have it, the file is removed or kept as the folder's settings say."
    >
      <form
        onSubmit={(e) => {
          e.preventDefault();
          m.mutate();
        }}
      >
        <Field label="Why" htmlFor="stop-reason" hint="Recorded in the audit log, e.g. the partner retired this server.">
          <input id="stop-reason" type="text" value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} />
        </Field>
        <ErrorNote>{m.error?.message}</ErrorNote>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="danger" disabled={!reason.trim() || m.isPending}>
            Stop sending
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
