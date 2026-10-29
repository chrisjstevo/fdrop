import * as RadixDialog from "@radix-ui/react-dialog";
import * as Menu from "@radix-ui/react-dropdown-menu";
import { type ButtonHTMLAttributes, type ReactNode } from "react";
import { Link } from "react-router";
import logoUrl from "../assets/logo/fdrop-logo.svg";
import s from "./ui.module.css";

export function Logo({ size = 32 }: { size?: number }) {
  return <img src={logoUrl} width={size} height={size} alt="" aria-hidden="true" className={s.logo} />;
}

type Variant = "primary" | "secondary" | "danger" | "ghost" | "onDark" | "ready";

export function Button({
  variant = "secondary",
  small,
  className,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; small?: boolean }) {
  return <button type="button" className={[s.button, s[variant], small ? s.small : "", className ?? ""].join(" ")} {...rest} />;
}

export function LinkButton({ to, variant = "secondary", children }: { to: string; variant?: Variant; children: ReactNode }) {
  return (
    <Link to={to} className={[s.button, s[variant]].join(" ")}>
      {children}
    </Link>
  );
}

type BadgeKind = "folder" | "forward" | "ok" | "neutral" | "warn" | "fail";

export function Badge({ kind, children }: { kind: BadgeKind; children: ReactNode }) {
  return <span className={[s.badge, s[`badge_${kind}`]].join(" ")}>{children}</span>;
}

/** A progress bar announced to assistive technology by its percentage. */
export function ProgressBar({ percent, label }: { percent: number; label: string }) {
  return (
    <div
      className={s.progress}
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={percent}
    >
      <div className={s.progressFill} style={{ width: `${percent}%` }} />
    </div>
  );
}

export function ErrorNote({ children }: { children: ReactNode }) {
  if (!children) {
    return null;
  }
  return (
    <div className={s.error} role="alert">
      <span className={s.errorIcon} aria-hidden="true">!</span>
      <span>{children}</span>
    </div>
  );
}

export function Notice({ children }: { children: ReactNode }) {
  return <div className={s.notice}>{children}</div>;
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className={s.empty}>
      <p className={s.emptyTitle}>{title}</p>
      {children && <div className="muted">{children}</div>}
    </div>
  );
}

export function Dialog({
  open,
  onOpenChange,
  title,
  description,
  children,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description?: string;
  children: ReactNode;
}) {
  return (
    <RadixDialog.Root open={open} onOpenChange={onOpenChange}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className={s.overlay} />
        <RadixDialog.Content className={s.dialog} aria-describedby={description ? undefined : undefined}>
          <RadixDialog.Title className={s.dialogTitle}>{title}</RadixDialog.Title>
          {description ? (
            <RadixDialog.Description className="muted">{description}</RadixDialog.Description>
          ) : (
            <RadixDialog.Description className="visually-hidden">{title}</RadixDialog.Description>
          )}
          <div className={s.dialogBody}>{children}</div>
          <RadixDialog.Close asChild>
            <button type="button" className={s.dialogClose} aria-label="Close">
              ×
            </button>
          </RadixDialog.Close>
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  );
}

export function DialogActions({ children }: { children: ReactNode }) {
  return <div className={s.dialogActions}>{children}</div>;
}

export interface MenuAction {
  label: string;
  onSelect: () => void;
  danger?: boolean;
  disabled?: boolean;
}

/** The overflow menu used for row actions, on every screen size. */
export function RowMenu({ label, actions }: { label: string; actions: MenuAction[] }) {
  return (
    <Menu.Root>
      <Menu.Trigger asChild>
        <button type="button" className={s.menuTrigger} aria-label={label}>
          <span aria-hidden="true">⋯</span>
        </button>
      </Menu.Trigger>
      <Menu.Portal>
        <Menu.Content className={s.menu} align="end" sideOffset={4}>
          {actions.map((a) => (
            <Menu.Item
              key={a.label}
              className={[s.menuItem, a.danger ? s.menuDanger : ""].join(" ")}
              onSelect={a.onSelect}
              disabled={a.disabled}
            >
              {a.label}
            </Menu.Item>
          ))}
        </Menu.Content>
      </Menu.Portal>
    </Menu.Root>
  );
}

export function Field({ label, htmlFor, hint, children }: { label: string; htmlFor: string; hint?: string; children: ReactNode }) {
  return (
    <div className={s.field}>
      <label htmlFor={htmlFor}>{label}</label>
      {children}
      {hint && <p className={s.hint}>{hint}</p>}
    </div>
  );
}

export function Breadcrumbs({ path, linkFor }: { path: string; linkFor: (p: string) => string }) {
  const parts = path.split("/").filter(Boolean);
  const labels: Record<string, string> = { home: "Home", shared: "Shared", forward: "Forward" };
  return (
    <nav aria-label="Folder path" className={s.crumbs}>
      <ol>
        {parts.map((p, i) => {
          const sub = "/" + parts.slice(0, i + 1).join("/");
          const name = i === 0 ? (labels[p] ?? p) : p;
          return (
            <li key={sub}>
              {i < parts.length - 1 ? <Link to={linkFor(sub)}>{name}</Link> : <span aria-current="page">{name}</span>}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
