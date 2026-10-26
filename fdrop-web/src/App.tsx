import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useEffect, useState, type ReactNode } from "react";
import { BrowserRouter, Navigate, Route, Routes } from "react-router";
import { ApiError } from "./api/client";
import { useMe } from "./auth/useMe";
import { Layout } from "./components/Layout";
import { Empty, ErrorNote, Logo } from "./components/ui";
import { AccountPage } from "./pages/AccountPage";
import { AdminApplicationPage, AdminApplicationsPage } from "./pages/AdminApplicationsPage";
import { AdminDestinationPage, AdminDestinationsPage } from "./pages/AdminDestinationsPage";
import { AdminEncryptionPage } from "./pages/AdminEncryptionPage";
import { AdminUsersPage } from "./pages/AdminUsersPage";
import { FilesPage } from "./pages/FilesPage";
import { FolderDetailPage, FoldersPage } from "./pages/FoldersPage";
import { MyUploadsPage } from "./pages/MyUploadsPage";
import { SignInProblemPage } from "./pages/SignInProblemPage";
import { UploadProvider } from "./upload/UploadContext";

export function makeQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: (n, e) => !(e instanceof ApiError && e.status < 500) && n < 2,
        refetchOnWindowFocus: true,
      },
    },
  });
}

/** Drops outside a drop target must never make the browser open the file in place. */
function useBlockStrayDrops() {
  useEffect(() => {
    const block = (e: DragEvent) => {
      if (Array.from(e.dataTransfer?.types ?? []).includes("Files")) {
        e.preventDefault();
        if (e.type === "dragover" && e.dataTransfer && !(e.target as HTMLElement)?.closest?.("[data-drop-target]")) {
          e.dataTransfer.dropEffect = "none";
        }
      }
    };
    window.addEventListener("dragover", block);
    window.addEventListener("drop", block);
    return () => {
      window.removeEventListener("dragover", block);
      window.removeEventListener("drop", block);
    };
  }, []);
}

function Gate({ children }: { children: ReactNode }) {
  const me = useMe();
  // A 401 has already sent the browser to sign in, so keep the splash up rather than flash an error.
  if (me.isLoading || (me.error instanceof ApiError && me.error.status === 401)) {
    return (
      <div style={{ display: "grid", placeItems: "center", minHeight: "60vh" }}>
        <Logo size={48} />
      </div>
    );
  }
  if (me.isError) {
    return (
      <main style={{ padding: 24 }}>
        <ErrorNote>{(me.error as Error).message}</ErrorNote>
      </main>
    );
  }
  return <>{children}</>;
}

function Home() {
  const me = useMe();
  if (me.data && !me.data.roles.standard) {
    return me.data.roles.admin ? <Navigate to="/admin/users" replace /> : <NoAccess />;
  }
  return <Navigate to="/files/home" replace />;
}

function NoAccess() {
  return (
    <Empty title="You don't have access to fdrop yet">
      Your account exists but has no fdrop role. Ask an admin to give you the standard role.
    </Empty>
  );
}

function Standard({ children }: { children: ReactNode }) {
  const me = useMe();
  return me.data?.roles.standard ? <>{children}</> : <NoAccess />;
}

function Admin({ children }: { children: ReactNode }) {
  const me = useMe();
  return me.data?.roles.admin ? <>{children}</> : <Empty title="Admins only" />;
}

export function AppRoutes() {
  useBlockStrayDrops();
  return (
    <Routes>
      <Route path="/sign-in-problem" element={<SignInProblemPage />} />
      <Route path="*" element={<SignedIn />} />
    </Routes>
  );
}

function SignedIn() {
  return (
    <Gate>
      <UploadProvider>
        <Layout>
          <Routes>
            <Route path="/" element={<Home />} />
            <Route path="/files/*" element={<Standard><FilesPage /></Standard>} />
            <Route path="/folders" element={<Standard><FoldersPage /></Standard>} />
            <Route path="/folders/:id" element={<Standard><FolderDetailPage /></Standard>} />
            <Route path="/uploads" element={<Standard><MyUploadsPage /></Standard>} />
            <Route path="/account" element={<AccountPage />} />
            <Route path="/admin/users" element={<Admin><AdminUsersPage /></Admin>} />
            <Route path="/admin/applications" element={<Admin><AdminApplicationsPage /></Admin>} />
            <Route path="/admin/applications/:id" element={<Admin><AdminApplicationPage /></Admin>} />
            <Route path="/admin/destinations" element={<Admin><AdminDestinationsPage /></Admin>} />
            <Route path="/admin/destinations/:id" element={<Admin><AdminDestinationPage /></Admin>} />
            <Route path="/admin/encryption" element={<Admin><AdminEncryptionPage /></Admin>} />
            <Route path="*" element={<Empty title="Page not found" />} />
          </Routes>
        </Layout>
      </UploadProvider>
    </Gate>
  );
}

export function App() {
  const [client] = useState(makeQueryClient);
  return (
    <QueryClientProvider client={client}>
      <BrowserRouter>
        <AppRoutes />
      </BrowserRouter>
    </QueryClientProvider>
  );
}
