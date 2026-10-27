import { useInfiniteQuery, useMutation, useQuery } from "@tanstack/react-query";
import { useMemo, useState, type FormEvent } from "react";
import { get, post } from "../api/client";
import type {
  AuditChainCheck,
  AuditEventDetail,
  AuditEventName,
  AuditEventRow,
  AuditIndexStatus,
  AuditPage,
} from "../api/types";
import { ProblemNote } from "../components/TwoFactor";
import { Badge, Button, Dialog, Empty, ErrorNote, Notice } from "../components/ui";
import { formatCount, formatDate } from "../util/format";
import u from "../components/ui.module.css";
import a from "./AdminAuditPage.module.css";
import s from "./pages.module.css";

const GROUPS = [
  "Sign-in",
  "Files",
  "Folders and sharing",
  "Forwarding",
  "Expiry",
  "Accounts",
  "Encryption",
  "Search",
  "Audit",
  "System",
];
const OUTCOMES = ["success", "failure", "refused"] as const;
const OUTCOME_LABEL: Record<string, string> = { success: "Success", failure: "Failure", refused: "Refused" };

type Range = "1h" | "24h" | "7d" | "30d" | "all" | "custom";
const RANGE_MS: Record<string, number> = { "1h": 3600e3, "24h": 86400e3, "7d": 7 * 86400e3, "30d": 30 * 86400e3 };

export interface AuditFilters {
  q: string;
  groups: string[];
  outcomes: string[];
  event: string;
  range: Range;
  from: string;
  to: string;
}

const EMPTY: AuditFilters = { q: "", groups: [], outcomes: [], event: "", range: "7d", from: "", to: "" };

/** The query string for the list and the export; times are resolved when the filters are applied. */
export function auditParams(f: AuditFilters, now = Date.now()): URLSearchParams {
  const p = new URLSearchParams();
  if (f.q.trim()) p.set("q", f.q.trim());
  if (f.groups.length) p.set("group", f.groups.join(","));
  if (f.outcomes.length) p.set("outcome", f.outcomes.join(","));
  if (f.event) p.set("event", f.event);
  if (f.range === "custom") {
    if (f.from) p.set("from", new Date(f.from).toISOString());
    if (f.to) p.set("to", new Date(f.to).toISOString());
  } else if (f.range !== "all") {
    p.set("from", new Date(now - RANGE_MS[f.range]).toISOString());
  }
  return p;
}

/** Anything beyond the date range is answered by the audit index. */
const searching = (f: AuditFilters) => !!(f.q.trim() || f.groups.length || f.outcomes.length || f.event);

function OutcomeCell({ outcome }: { outcome: string }) {
  if (outcome === "success") {
    return <span className="muted">Success</span>;
  }
  return (
    <span className={a.outcome}>
      <span className={a.errorIcon} aria-hidden="true">
        !
      </span>
      {OUTCOME_LABEL[outcome] ?? outcome}
    </span>
  );
}

function Time({ ts }: { ts: string }) {
  return (
    <time dateTime={ts} title={`${ts} (UTC)`}>
      {new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "medium" }).format(new Date(ts))}
    </time>
  );
}

function toggle(list: string[], v: string) {
  return list.includes(v) ? list.filter((x) => x !== v) : [...list, v];
}

/** Admin: every node's audit events, newest first, with search, filters, export and the chain check. */
export function AdminAuditPage() {
  const [draft, setDraft] = useState<AuditFilters>(EMPTY);
  const [applied, setApplied] = useState<{ f: AuditFilters; at: number }>({ f: EMPTY, at: Date.now() });
  const [live, setLive] = useState(false);
  const [open, setOpen] = useState<string>();
  const [checks, setChecks] = useState<AuditChainCheck[]>();

  const f = applied.f;
  const params = useMemo(() => auditParams(f, applied.at), [f, applied.at]);
  const isSearch = searching(f);
  const following = live && !isSearch && !open;

  const pages = useInfiniteQuery({
    queryKey: ["audit", params.toString()],
    initialPageParam: "",
    queryFn: ({ pageParam }) => {
      const p = new URLSearchParams(params);
      if (pageParam) p.set("cursor", pageParam);
      return get<AuditPage>(`/api/v1/admin/audit?${p}`);
    },
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    refetchInterval: following ? 5000 : false,
  });
  const events = useQuery({
    queryKey: ["audit-events"],
    queryFn: () => get<{ events: AuditEventName[] }>("/api/v1/admin/audit/events"),
  });
  const index = useQuery({
    queryKey: ["audit-index"],
    queryFn: () => get<AuditIndexStatus>("/api/v1/admin/audit/index"),
    enabled: isSearch,
    refetchInterval: isSearch ? 10_000 : false,
  });
  const verify = useMutation({
    mutationFn: () => post<{ nodes: AuditChainCheck[] }>("/api/v1/admin/audit/verify"),
    onSuccess: (d) => setChecks(d.nodes),
  });

  const apply = (next: AuditFilters) => {
    setDraft(next);
    setApplied({ f: next, at: Date.now() });
  };
  const submit = (e: FormEvent) => {
    e.preventDefault();
    apply(draft);
  };

  if (pages.isError && !pages.data) {
    return (
      <>
        <h1>Audit log</h1>
        <ProblemNote error={pages.error} />
      </>
    );
  }

  const first = pages.data?.pages[0];
  const rows = pages.data?.pages.flatMap((p) => p.events) ?? [];
  const facets = first?.facets ?? {};
  const lagging = isSearch && (index.data?.lagSeconds ?? 0) > 5 && first?.indexedUpTo;
  const exportHref = (format: string) => {
    const p = new URLSearchParams(params);
    p.set("format", format);
    return `/api/v1/admin/audit/export?${p}`;
  };

  return (
    <>
      <div className={s.head}>
        <h1>Audit log</h1>
        <div className={s.actions}>
          <a className={`${u.button} ${u.secondary}`} href={exportHref("jsonl")} download>
            Export JSON Lines
          </a>
          <a className={`${u.button} ${u.secondary}`} href={exportHref("csv")} download>
            Export CSV
          </a>
          <Button onClick={() => verify.mutate()} disabled={verify.isPending}>
            {verify.isPending ? "Checking…" : "Verify chain"}
          </Button>
        </div>
      </div>

      <form className={a.search} role="search" onSubmit={submit}>
        <label htmlFor="audit-q" className="visually-hidden">
          Search the audit log
        </label>
        <input
          id="audit-q"
          type="search"
          placeholder='user:erp-export outcome:refused, ip:203.0.113.0/24, path:/shared/Finance, "phrase"'
          value={draft.q}
          onChange={(e) => setDraft({ ...draft, q: e.target.value })}
        />
        <Button type="submit" variant="primary">
          Search
        </Button>
      </form>

      <div className={s.filters}>
        <div>
          <label htmlFor="audit-range">When</label>
          <select
            id="audit-range"
            value={draft.range}
            onChange={(e) => apply({ ...draft, range: e.target.value as Range })}
          >
            <option value="1h">Last hour</option>
            <option value="24h">Last 24 hours</option>
            <option value="7d">Last 7 days</option>
            <option value="30d">Last 30 days</option>
            <option value="all">All time</option>
            <option value="custom">Custom…</option>
          </select>
        </div>
        {draft.range === "custom" && (
          <>
            <div>
              <label htmlFor="audit-from">From</label>
              <input
                id="audit-from"
                type="datetime-local"
                value={draft.from}
                onChange={(e) => apply({ ...draft, from: e.target.value })}
              />
            </div>
            <div>
              <label htmlFor="audit-to">To</label>
              <input
                id="audit-to"
                type="datetime-local"
                value={draft.to}
                onChange={(e) => apply({ ...draft, to: e.target.value })}
              />
            </div>
          </>
        )}
        <div>
          <label htmlFor="audit-event">Event</label>
          <select id="audit-event" value={draft.event} onChange={(e) => apply({ ...draft, event: e.target.value })}>
            <option value="">Any event</option>
            {events.data?.events.map((e) => (
              <option key={e.event} value={e.event}>
                {e.event} ({formatCount(e.count)})
              </option>
            ))}
          </select>
        </div>
        <label className={s.checkbox}>
          <input
            type="checkbox"
            checked={live}
            disabled={isSearch}
            onChange={(e) => setLive(e.target.checked)}
          />
          Follow live
        </label>
      </div>

      <div className={a.chips} role="group" aria-label="Groups">
        {GROUPS.map((g) => (
          <button
            key={g}
            type="button"
            className={a.chip}
            aria-pressed={f.groups.includes(g)}
            onClick={() => apply({ ...draft, groups: toggle(draft.groups, g) })}
          >
            {g}
            {facets.group && <span className={a.count}>{formatCount(facets.group[g] ?? 0)}</span>}
          </button>
        ))}
      </div>
      <div className={a.chips} role="group" aria-label="Outcomes">
        {OUTCOMES.map((o) => (
          <button
            key={o}
            type="button"
            className={a.chip}
            aria-pressed={f.outcomes.includes(o)}
            onClick={() => apply({ ...draft, outcomes: toggle(draft.outcomes, o) })}
          >
            {OUTCOME_LABEL[o]}
            {facets.outcome && <span className={a.count}>{formatCount(facets.outcome[o] ?? 0)}</span>}
          </button>
        ))}
        {(isSearch || f.range !== EMPTY.range) && (
          <button type="button" className={a.clear} onClick={() => apply(EMPTY)}>
            Clear
          </button>
        )}
      </div>

      <ErrorNote>{(pages.isError ? pages.error?.message : undefined) ?? verify.error?.message}</ErrorNote>
      {lagging && (
        <Notice>
          Searching events up to <Time ts={first!.indexedUpTo!} />; newer events appear shortly.
        </Notice>
      )}

      {checks && (
        <section className={s.section} aria-live="polite">
          <h2>Chain check</h2>
          {checks.length === 0 && <p className="muted">There are no events to check yet.</p>}
          <ul className={a.checks}>
            {checks.map((c) => (
              <li key={c.node}>
                <strong>{c.node}</strong>: {formatCount(c.checked)} events
                {c.first && c.last && (
                  <>
                    {" "}
                    from {formatDate(c.first)} to {formatDate(c.last)}
                  </>
                )}{" "}
                {c.intact ? (
                  <Badge kind="ok">Intact</Badge>
                ) : (
                  <Badge kind="fail">
                    Broken at event {c.brokenAt}: {c.reason}
                  </Badge>
                )}
              </li>
            ))}
          </ul>
        </section>
      )}

      {first && (
        <p className={s.summary}>
          {formatCount(first.total)} event{first.total === 1 ? "" : "s"}
        </p>
      )}
      {rows.length === 0 && !pages.isLoading ? (
        <Empty title="No events match">Try a longer time range or fewer filters.</Empty>
      ) : (
        <div className={s.listWrap}>
          <table className={s.table}>
            <thead>
              <tr>
                <th scope="col">Time</th>
                <th scope="col">Event</th>
                <th scope="col">User</th>
                <th scope="col">IP</th>
                <th scope="col">Outcome</th>
                <th scope="col">Summary</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td className={a.nowrap}>
                    <Time ts={r.ts} />
                  </td>
                  <td>
                    <button type="button" className={a.rowLink} onClick={() => setOpen(r.id)}>
                      {r.event}
                    </button>{" "}
                    <Badge kind="neutral">{r.group}</Badge>
                  </td>
                  <td>{r.user ?? ""}</td>
                  <td>{r.ip ?? ""}</td>
                  <td>
                    <OutcomeCell outcome={r.outcome} />
                  </td>
                  <td className={a.summaryCell}>{r.summary}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <ul className={s.cards}>
            {rows.map((r: AuditEventRow) => (
              <li key={r.id} className={s.card}>
                <button type="button" className={`${s.cardBody} ${a.cardButton}`} onClick={() => setOpen(r.id)}>
                  <div className={s.cardTitle}>{r.event}</div>
                  <div className={s.cardMeta}>
                    <Time ts={r.ts} />
                    {r.user && <span>{r.user}</span>}
                    <OutcomeCell outcome={r.outcome} />
                  </div>
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
      {pages.hasNextPage && (
        <p>
          <Button onClick={() => void pages.fetchNextPage()} disabled={pages.isFetchingNextPage}>
            Load more
          </Button>
        </p>
      )}
      {open && (
        <EventPanel
          id={open}
          onClose={() => setOpen(undefined)}
          onFilter={(q) => {
            setOpen(undefined);
            apply({ ...EMPTY, range: draft.range, from: draft.from, to: draft.to, q });
          }}
        />
      )}
    </>
  );
}

/** Quotes a value for the search box when it holds spaces or quotes. */
const term = (v: string) => (/[\s"]/.test(v) ? `"${v.replace(/"/g, "")}"` : v);

function EventPanel({ id, onClose, onFilter }: { id: string; onClose: () => void; onFilter: (q: string) => void }) {
  const ev = useQuery({
    queryKey: ["audit-event", id],
    queryFn: () => get<AuditEventDetail>(`/api/v1/admin/audit/${encodeURIComponent(id)}`),
  });
  const [copied, setCopied] = useState(false);
  const d = ev.data;
  const path = d && typeof d.data.path === "string" ? d.data.path : undefined;
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={d?.event ?? "Audit event"}>
      <ErrorNote>{ev.error?.message}</ErrorNote>
      {d && (
        <>
          <dl className={s.kv}>
            <dt>Time</dt>
            <dd>
              <Time ts={d.ts} /> <span className="muted">({d.ts})</span>
            </dd>
            <dt>Group</dt>
            <dd>{d.group}</dd>
            <dt>Outcome</dt>
            <dd>
              <OutcomeCell outcome={d.outcome} />
            </dd>
            <dt>Summary</dt>
            <dd>{d.summary}</dd>
            <dt>Node</dt>
            <dd>{d.node}</dd>
            <dt>Chain position</dt>
            <dd>
              Event {formatCount(d.chain.nodeSeq)} on {d.chain.node}
              <div className={a.hash} title="SHA-256 of this event and the one before it">
                {d.chain.hash}
              </div>
            </dd>
            <dt>Event ID</dt>
            <dd className={a.hash}>{d.id}</dd>
          </dl>
          <h3 className={a.panelHeading}>Every field</h3>
          <pre className={s.pre}>{JSON.stringify(d.data, null, 2)}</pre>
          <div className={a.panelActions}>
            <Button
              onClick={() =>
                void navigator.clipboard?.writeText(JSON.stringify(d.data)).then(() => setCopied(true))
              }
            >
              {copied ? "Copied" : "Copy as JSON"}
            </Button>
            {d.user && <Button onClick={() => onFilter(`user:${term(d.user!)}`)}>Events by this user</Button>}
            {d.ip && <Button onClick={() => onFilter(`ip:${d.ip}`)}>Events from this IP</Button>}
            {path && <Button onClick={() => onFilter(`path:${term(path)}`)}>Other events for this path</Button>}
          </div>
        </>
      )}
    </Dialog>
  );
}
