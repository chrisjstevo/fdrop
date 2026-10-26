import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { get, post } from "../api/client";
import type { SearchIndexStatus } from "../api/types";
import { ProblemNote } from "../components/TwoFactor";
import { Badge, Button, ErrorNote, Notice } from "../components/ui";
import { formatBytes, formatCount, formatDuration } from "../util/format";
import s from "./pages.module.css";

const STATES: Record<string, string> = {
  ok: "Text read",
  unsupported: "No text (images, archives…)",
  too_large: "Too large",
  protected: "Password-protected",
  failed: "Could not be read",
  pending: "Being read",
};

/** Admin: this node's search index, how far behind it is, and a rebuild. */
export function AdminSearchPage() {
  const client = useQueryClient();
  const status = useQuery({
    queryKey: ["search-index"],
    queryFn: () => get<SearchIndexStatus>("/api/v1/admin/search"),
    refetchInterval: (q) => (q.state.data?.rebuilding ? 3000 : 15_000),
  });
  const rebuild = useMutation({
    mutationFn: () => post<SearchIndexStatus>("/api/v1/admin/search/rebuild"),
    onSuccess: (d) => client.setQueryData(["search-index"], d),
  });
  if (status.isError) {
    return (
      <>
        <h1>Search index</h1>
        <ProblemNote error={status.error} />
      </>
    );
  }
  const st = status.data;
  return (
    <>
      <div className={s.head}>
        <h1>Search index</h1>
        <div className={s.actions}>
          {st?.available && (
            <Button onClick={() => rebuild.mutate()} disabled={rebuild.isPending || st.rebuilding}>
              {st.rebuilding ? "Rebuilding…" : "Rebuild this node's index"}
            </Button>
          )}
        </div>
      </div>
      <ErrorNote>{rebuild.error?.message}</ErrorNote>
      {st && !st.available && <Notice>Search is turned off on this server.</Notice>}
      {st?.available && (
        <>
          <p className="muted">
            Each node keeps its own index, built from the same change feed. A rebuild reads every file's cached text
            again and keeps answering searches from the old index until the new one is ready.
          </p>
          <dl className={s.kv}>
            <dt>Files indexed</dt>
            <dd>{formatCount(st.documents)}</dd>
            <dt>Index size</dt>
            <dd>{formatBytes(st.sizeBytes)}</dd>
            <dt>Changes applied</dt>
            <dd>
              {formatCount(st.appliedSeq)} of {formatCount(st.latestSeq)}{" "}
              {st.lagSeconds > 0 ? <Badge kind="warn">{formatDuration(st.lagSeconds)} behind</Badge> : <Badge kind="ok">Up to date</Badge>}
            </dd>
          </dl>
          <section className={s.section}>
            <h2>File contents</h2>
            <dl className={s.kv}>
              {Object.entries(st.extract).map(([k, v]) => (
                <span key={k} style={{ display: "contents" }}>
                  <dt>{STATES[k] ?? k}</dt>
                  <dd>{formatCount(v)}</dd>
                </span>
              ))}
            </dl>
          </section>
        </>
      )}
    </>
  );
}
