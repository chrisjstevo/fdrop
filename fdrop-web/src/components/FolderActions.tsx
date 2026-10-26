import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";

/** What the sidebar's Upload and New folder buttons do on the page currently shown. */
export interface FolderActions {
  /** Opens the file picker for the folder being shown. */
  upload?: () => void;
  newFolder?: () => void;
}

const Ctx = createContext<{ actions: FolderActions; set: (a: FolderActions) => void }>({
  actions: {},
  set: () => {},
});

export function FolderActionsProvider({ children }: { children: ReactNode }) {
  const [actions, set] = useState<FolderActions>({});
  const value = useMemo(() => ({ actions, set }), [actions]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useFolderActions(): FolderActions {
  return useContext(Ctx).actions;
}

/** A page offers its folder's actions to the sidebar while it is shown. */
export function useProvideFolderActions(upload?: () => void, newFolder?: () => void) {
  const { set } = useContext(Ctx);
  useEffect(() => {
    set({ upload, newFolder });
    return () => set({});
  }, [set, upload, newFolder]);
}
