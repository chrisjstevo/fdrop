import * as Menu from "@radix-ui/react-dropdown-menu";
import { useEffect, useState, type ReactNode } from "react";
import { NavLink, useLocation } from "react-router";
import { signOut } from "../api/client";
import { useMe } from "../auth/useMe";
import { UploadQueuePanel } from "./UploadQueuePanel";
import { Logo } from "./ui";
import s from "./Layout.module.css";

interface NavItem {
  to: string;
  label: string;
  admin?: boolean;
  standard?: boolean;
}

const NAV: NavItem[] = [
  { to: "/files/home", label: "Home", standard: true },
  { to: "/files/shared", label: "Shared", standard: true },
  { to: "/files/forward", label: "Forward", standard: true },
  { to: "/uploads", label: "My uploads", standard: true },
  { to: "/folders", label: "Manage folders", standard: true },
  { to: "/admin/users", label: "Admin: users", admin: true },
  { to: "/admin/applications", label: "Admin: applications", admin: true },
  { to: "/admin/destinations", label: "Admin: destinations", admin: true },
];

export function Layout({ children }: { children: ReactNode }) {
  const me = useMe();
  const [drawer, setDrawer] = useState(false);
  const location = useLocation();
  useEffect(() => setDrawer(false), [location.pathname]);

  const roles = me.data?.roles;
  const items = NAV.filter((n) => (n.admin ? roles?.admin : n.standard ? roles?.standard : true));

  const nav = (
    <nav aria-label="Main" className={s.nav}>
      <ul>
        {items.map((n) => (
          <li key={n.to}>
            <NavLink to={n.to} className={({ isActive }) => (isActive ? `${s.navLink} ${s.active}` : s.navLink)}>
              {n.label}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  );

  return (
    <div className={s.shell}>
      <a href="#main" className={s.skip}>
        Skip to content
      </a>
      <header className={s.topbar}>
        <button
          type="button"
          className={s.menuButton}
          aria-label={drawer ? "Close navigation" : "Open navigation"}
          aria-expanded={drawer}
          onClick={() => setDrawer((d) => !d)}
        >
          <span aria-hidden="true">{drawer ? "×" : "☰"}</span>
        </button>
        <NavLink to="/" className={s.brand}>
          <Logo size={32} />
          <span>fdrop</span>
        </NavLink>
        <div className={s.spacer} />
        {me.data && (
          <Menu.Root>
            <Menu.Trigger asChild>
              <button type="button" className={s.user} aria-label={`Account menu for ${me.data.username}`}>
                <span className={s.avatar} aria-hidden="true">
                  {me.data.username.slice(0, 1).toUpperCase()}
                </span>
                <span className={s.username}>{me.data.username}</span>
              </button>
            </Menu.Trigger>
            <Menu.Portal>
              <Menu.Content className={s.userMenu} align="end" sideOffset={6}>
                <Menu.Item className={s.userMenuItem} asChild>
                  <NavLink to="/account">Account and SSH keys</NavLink>
                </Menu.Item>
                <Menu.Item className={s.userMenuItem} onSelect={() => void signOut()}>
                  Sign out
                </Menu.Item>
              </Menu.Content>
            </Menu.Portal>
          </Menu.Root>
        )}
      </header>
      <div className={s.body}>
        <aside className={s.sidebar}>{nav}</aside>
        {drawer && (
          <div className={s.drawerBackdrop} onClick={() => setDrawer(false)}>
            <aside className={s.drawer} onClick={(e) => e.stopPropagation()}>
              {nav}
            </aside>
          </div>
        )}
        <main id="main" className={s.main}>
          {children}
        </main>
      </div>
      <UploadQueuePanel />
    </div>
  );
}
