import { createContext, useContext, useEffect, useMemo, useSyncExternalStore, type ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { createQueue, type QueueItem, type UploadQueue } from "./queue";
import { useMe } from "../auth/useMe";

const Ctx = createContext<UploadQueue | null>(null);

export function UploadProvider({ children, queue }: { children: ReactNode; queue?: UploadQueue }) {
  const q = useMemo(() => queue ?? createQueue(), [queue]);
  const me = useMe();
  const client = useQueryClient();
  useEffect(() => {
    if (me.data) {
      q.configure({
        maxParallelChunks: me.data.settings.maxParallelChunks,
        clientHashMax: me.data.settings.clientHashMax,
      });
    }
  }, [me.data, q]);
  useEffect(() => {
    q.onDone = () => {
      void client.invalidateQueries({ queryKey: ["files"] });
      void client.invalidateQueries({ queryKey: ["me"] });
      void client.invalidateQueries({ queryKey: ["uploads"] });
      void client.invalidateQueries({ queryKey: ["jobs"] });
    };
  }, [q, client]);
  return <Ctx.Provider value={q}>{children}</Ctx.Provider>;
}

export function useUploadQueue(): { queue: UploadQueue; items: QueueItem[] } {
  const queue = useContext(Ctx);
  if (!queue) {
    throw new Error("useUploadQueue outside UploadProvider");
  }
  const items = useSyncExternalStore(
    (l) => queue.subscribe(l),
    queue.getSnapshot,
    queue.getSnapshot,
  );
  return { queue, items };
}
