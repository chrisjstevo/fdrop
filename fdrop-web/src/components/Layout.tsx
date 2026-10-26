import * as Menu from "@radix-ui/react-dropdown-menu";
import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import { NavLink, useLocation, useNavigate, useSearchParams } from "react-router";
import { signOut } from "../api/client";
import { useMe } from "../auth/useMe";
import { setTheme, useTheme } from "../theme/theme";
import { formatBytes } from "../util/format";
import { FolderActionsProvider, useFolderActions } from "./FolderActions";
import {
  AppIcon,
  CloseIcon,
  FolderIcon,
  ForwardIcon,
  HomeIcon,
  ListIcon,
  LockIcon,
  MenuIcon,
  MoonIcon,
  PlusIcon,
  SearchIcon,
  ServerIcon,
  SettingsIcon,
  SunIcon,
  UploadIcon,
  UserIcon,
} from "./icons";
import { UploadQueuePanel } from "./UploadQueuePanel";
import { Logo } from "./ui";
import s from "./Layout.module.css";

interface NavItem {
  to: string;
  label: string;
  icon: ReactNode;
  admin?: boolean;
  standard?: boolean;
}

const NAV: NavItem[] = [
  { to: "/files/home", label: "Home", icon: <HomeIcon />, standard: true },
  { to: "/files/shared", label: "Shared", icon: <FolderIcon />, standard: true },
  { to: "/files/forward", label: "Forward", icon: <ForwardIcon />, standard: true },
  { to: "/uploads", label: "My uploads", icon: <ListIcon />, standard: true },
  { to: "/folders", label: "Manage folders", icon: <SettingsIcon />, standard: true },
  { to: "/admin/users", label: "Users", icon: <UserIcon />, admin: true },
  { to: "/admin/applications", label: "Applications", icon: <AppIcon />, admin: true },
  { to: "/admin/destinations", label: "Destinations", icon: <ServerIcon />, admin: true },
  { to: "/admin/encryption", label: "Encryption", icon: <LockIcon />, admin: true },
];

export function Layout({ children }: { children: ReactNode }) {
  return (
    <FolderActionsProvider>
      <Shell>{children}</Shell>
    </FolderActionsProvider>
  );
}

/**
 * The workspace: a light sidebar (logo, Upload and New folder, navigation, storage) beside a white top bar with
 * search and the account menu. Under 1024 px the sidebar becomes a drawer behind the menu button.
 */
function Shell({ children }: { children: ReactNode }) {
  const me = useMe();
  const [drawer, setDrawer] = useState(false);
  const location = useLocation();
  useEffect(() => setDrawer(false), [location.pathname]);

  const roles = me.data?.roles;
  const people = NAV.filter((n) => n.standard && roles?.standard);
  const admin = NAV.filter((n) => n.admin && roles?.admin);

  const sidebar = (
    <div className={s.sidebarInner}>
      <NavLink to="/" className={s.brand}>
        <Logo size={32} />
        <span>fdrop</span>
      </NavLink>
      {roles?.standard && <SidebarActions />}
      <nav aria-label="Main" className={s.nav}>
        <ul>
          {people.map((n) => (
            <NavItemLink key={n.to} item={n} />
          ))}
        </ul>
        {admin.length > 0 && (
          <>
            <p className={s.navGroup}>Admin</p>
            <ul>
              {admin.map((n) => (
                <NavItemLink key={n.to} item={n} />
              ))}
            </ul>
          </>
        )}
      </nav>
      {me.data && roles?.standard && (
        <div className={s.storage}>
          <p className="section-label">Storage</p>
          <div className={s.meter} aria-hidden="true">
            <div style={{ width: `${Math.min(100, (100 * me.data.usedBytes) / Math.max(1, me.data.quotaBytes))}%` }} />
          </div>
          <p className={s.storageText}>
            {formatBytes(me.data.usedBytes)} of {formatBytes(me.data.quotaBytes)} used
          </p>
        </div>
      )}
    </div>
  );

  return (
    <div className={s.shell}>
      <a href="#main" className={s.skip}>
        Skip to content
      </a>
      <aside className={s.sidebar}>{sidebar}</aside>
      {drawer && (
        <div className={s.drawerBackdrop} onClick={() => setDrawer(false)}>
          <aside className={s.drawer} onClick={(e) => e.stopPropagation()} aria-label="Navigation">
            {sidebar}
          </aside>
        </div>
      )}
      <div className={s.column}>
        <header className={s.topbar}>
          <button
            type="button"
            className={s.menuButton}
            aria-label={drawer ? "Close navigation" : "Open navigation"}
            aria-expanded={drawer}
            onClick={() => setDrawer((d) => !d)}
          >
            {drawer ? <CloseIcon size={22} /> : <MenuIcon size={22} />}
          </button>
          <NavLink to="/" className={`${s.brand} ${s.topBrand}`} aria-label="fdrop home">
            <Logo size={28} />
          </NavLink>
          {roles?.standard && <SearchBox />}
          <div className={s.spacer} />
          <ThemeToggle />
          {me.data && (
            <Menu.Root>
              <Menu.Trigger asChild>
                <button type="button" className={s.user} aria-label={`Account menu for ${me.data.username}`}>
                  <span className={s.avatar} aria-hidden="true">
                    {me.data.username.slice(0, 2).toUpperCase()}
                  </span>
                </button>
              </Menu.Trigger>
              <Menu.Portal>
                <Menu.Content className={s.userMenu} align="end" sideOffset={6}>
                  <p className={s.userMenuName}>{me.data.username}</p>
                  <Menu.Item className={s.userMenuItem} asChild>
                    <NavLink to="/account">Account and SSH keys</NavLink>
                  </Menu.Item>
                  <ThemeMenuItem />
                  <Menu.Item className={s.userMenuItem} onSelect={() => void signOut()}>
                    Sign out
                  </Menu.Item>
                </Menu.Content>
              </Menu.Portal>
            </Menu.Root>
          )}
        </header>
        <main id="main" className={s.main}>
          {children}
        </main>
      </div>
      <UploadQueuePanel />
    </div>
  );
}

/** Light or dark: a switch in the top bar; the choice is kept in this browser. */
function ThemeToggle() {
  const { theme } = useTheme();
  const dark = theme === "dark";
  return (
    <button
      type="button"
      role="switch"
      aria-checked={dark}
      aria-label="Dark mode"
      title={dark ? "Switch to light mode" : "Switch to dark mode"}
      className={s.themeToggle}
      onClick={() => setTheme(dark ? "light" : "dark")}
    >
      <span className={s.themeTrack}>
        <span className={s.themeKnob}>{dark ? <MoonIcon size={14} /> : <SunIcon size={14} />}</span>
      </span>
    </button>
  );
}

/** Once a theme is chosen here, the account menu offers to follow the system setting again. */
function ThemeMenuItem() {
  const { chosen } = useTheme();
  if (!chosen) {
    return null;
  }
  return (
    <Menu.Item className={s.userMenuItem} onSelect={() => setTheme(null)}>
      Use the system's light or dark setting
    </Menu.Item>
  );
}

function NavItemLink({ item }: { item: NavItem }) {
  return (
    <li>
      <NavLink to={item.to} className={({ isActive }) => (isActive ? `${s.navLink} ${s.active}` : s.navLink)}>
        <span className={s.navIcon}>{item.icon}</span>
        <span>{item.label}</span>
      </NavLink>
    </li>
  );
}

/** Upload and New folder act on the folder being shown; elsewhere they take you to Home first. */
function SidebarActions() {
  const actions = useFolderActions();
  const navigate = useNavigate();
  return (
    <div className={s.sideActions}>
      <button
        type="button"
        className={s.uploadButton}
        onClick={() => (actions.upload ? actions.upload() : navigate("/files/home"))}
      >
        <UploadIcon size={16} />
        Upload
      </button>
      <button
        type="button"
        className={s.newButton}
        aria-label="New folder"
        title="New folder"
        onClick={() => (actions.newFolder ? actions.newFolder() : navigate("/files/home"))}
      >
        <PlusIcon size={18} />
      </button>
    </div>
  );
}

/** Filters the folder being shown by name; from other pages it searches Home. */
function SearchBox() {
  const location = useLocation();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const inFiles = location.pathname.startsWith("/files/");
  const [value, setValue] = useState(inFiles ? (params.get("q") ?? "") : "");
  useEffect(() => setValue(inFiles ? (params.get("q") ?? "") : ""), [inFiles, params, location.pathname]);

  const apply = (q: string) => {
    const base = inFiles ? location.pathname : "/files/home";
    navigate(q.trim() ? `${base}?q=${encodeURIComponent(q.trim())}` : base, { replace: inFiles });
  };
  const submit = (e: FormEvent) => {
    e.preventDefault();
    apply(value);
  };
  return (
    <form role="search" className={s.search} onSubmit={submit}>
      <label htmlFor="file-search" className="visually-hidden">
        Search files in this folder
      </label>
      <span className={s.searchIcon}>
        <SearchIcon size={16} />
      </span>
      <input
        id="file-search"
        type="search"
        placeholder={inFiles ? "Search this folder" : "Search Home"}
        value={value}
        onChange={(e) => {
          setValue(e.target.value);
          if (inFiles) {
            apply(e.target.value);
          }
        }}
      />
    </form>
  );
}
