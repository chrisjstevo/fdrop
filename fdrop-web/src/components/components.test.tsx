import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { api, ApiError, setCsrfToken } from "../api/client";
import type { Me } from "../api/types";
import { formatBytes, formatDuration } from "../util/format";
import { check } from "./DropZone";
import { ExpiresCell, ExpiryPicker } from "./Expiry";
import { ProblemNote, TwoFactorSection } from "./TwoFactor";
import { ProgressBar } from "./ui";

const me = (over: Partial<Me> = {}): Me => ({
  sub: "s",
  username: "alice",
  kind: "user",
  roles: { standard: true, admin: false },
  quotaBytes: 1000,
  usedBytes: 600,
  reservedBytes: 100,
  twoFactor: { policy: "admins", required: false, thisSession: false, sftpCodes: true, factors: [] },
  expiry: { enabled: true, serverMax: "10 years" },
  settings: {
    simpleUploadMax: 1,
    maxFileSize: 500,
    maxParallelChunks: 3,
    clientHashMax: 1,
    userManagement: true,
    forwarding: true,
  },
  ...over,
});

describe("drop checks", () => {
  it("refuses files over the size limit or the remaining quota before sending", () => {
    const small = new File([new Uint8Array(200)], "small");
    const tooBig = new File([new Uint8Array(600)], "huge");
    const overQuota = new File([new Uint8Array(250)], "late");
    const r = check([small, tooBig, overQuota], me(), "/home");
    expect(r.ok.map((f) => f.name)).toEqual(["small"]);
    expect(r.rejected.map((x) => x.name)).toEqual(["huge", "late"]);
    expect(r.rejected[0].reason).toContain("limit");
    expect(r.rejected[1].reason).toContain("quota");
  });

  it("does not apply the user's own quota to shared folders", () => {
    const f = new File([new Uint8Array(400)], "f");
    expect(check([f], me(), "/shared/Team").ok).toHaveLength(1);
  });
});

describe("ProgressBar", () => {
  it("exposes the percentage to assistive technology", () => {
    render(<ProgressBar percent={42} label="Uploading a.bin" />);
    const bar = screen.getByRole("progressbar", { name: "Uploading a.bin" });
    expect(bar).toHaveAttribute("aria-valuenow", "42");
  });
});

describe("api client", () => {
  afterEach(() => vi.restoreAllMocks());

  it("sends the CSRF token on state-changing requests only", async () => {
    const fetchMock = vi.fn().mockImplementation(async () => new Response("{}", { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    setCsrfToken("tok");
    await api("POST", "/api/v1/files/mkdir", { path: "/home/x" });
    await api("GET", "/api/v1/me");
    expect(fetchMock.mock.calls[0][1].headers["X-Fdrop-CSRF"]).toBe("tok");
    expect(fetchMock.mock.calls[1][1].headers["X-Fdrop-CSRF"]).toBeUndefined();
  });

  it("raises the problem detail as the error message", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(JSON.stringify({ status: 409, detail: "file exists: /home/a" }), { status: 409 })),
    );
    await expect(api("POST", "/x", {})).rejects.toThrow("file exists: /home/a");
  });
});

describe("format", () => {
  it("formats sizes and durations", () => {
    expect(formatBytes(0)).toBe("0 B");
    expect(formatBytes(1_700_000_000)).toBe("1.7 GB");
    expect(formatBytes(37_200_000_000)).toBe("37.2 GB");
    expect(formatDuration(45)).toBe("45 s");
    expect(formatDuration(3700)).toBe("1 h 2 min");
  });
});

describe("two-factor", () => {
  const withClient = (ui: React.ReactNode) =>
    render(<QueryClientProvider client={new QueryClient()}>{ui}</QueryClientProvider>);

  it("offers a two-factor sign-in when the server asks for one, and shows other errors as they are", () => {
    const { unmount } = render(<ProblemNote error={new ApiError(403, "admin pages need a two-factor sign-in", "two-factor-required")} />);
    expect(screen.getByRole("button", { name: "Sign in with your second factor" })).toBeInTheDocument();
    unmount();
    render(<ProblemNote error={new ApiError(403, "admins only")} />);
    expect(screen.getByRole("alert")).toHaveTextContent("admins only");
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("tells a covered user without factors to set one up", () => {
    withClient(<TwoFactorSection me={me({ twoFactor: { policy: "admins", required: true, thisSession: false, sftpCodes: true, factors: [] } })} />);
    expect(screen.getByText(/must use two-factor sign-in/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Set up authenticator app" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /recovery codes/ })).not.toBeInTheDocument();
  });

  it("lists factors, recovery codes left, and the SFTP code login", () => {
    withClient(
      <TwoFactorSection
        me={me({
          twoFactor: {
            policy: "admins",
            required: false,
            thisSession: true,
            sftpCodes: true,
            factors: [
              { id: "o1", kind: "otp", label: "Phone", createdAt: "2026-10-01T10:00:00Z" },
              { id: "r1", kind: "recovery-codes", remaining: 2 },
            ],
          },
        })}
      />,
    );
    expect(screen.getByText(/Two-factor sign-in is on/)).toBeInTheDocument();
    expect(screen.getByText("Authenticator app")).toBeInTheDocument();
    expect(screen.getByText("2 left")).toBeInTheDocument();
    expect(screen.getByText(/password and a code from your authenticator app/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Set up authenticator app" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "New recovery codes" })).toBeInTheDocument();
  });
});

describe("expiry controls", () => {
  it("offers the folder default, disables presets over the maximum and hides Never", () => {
    const onChange = vi.fn();
    render(<ExpiryPicker folder={{ enabled: true, allowed: true, default: "1 hour", max: "1 day", serverMax: "10 years" }} onChange={onChange} />);
    const select = screen.getByLabelText("Expires") as HTMLSelectElement;
    expect(select.options[0].textContent).toBe("folder default, 1 hour");
    const byValue = (v: string) => Array.from(select.options).find((o) => o.value === v);
    expect(byValue("1 day")?.disabled).toBe(false);
    expect(byValue("7 days")?.disabled).toBe(true);
    expect(byValue("never")).toBeUndefined();
    expect(onChange).toHaveBeenLastCalledWith({});
  });

  it("checks a custom value as it is typed, with the server's message", () => {
    const onChange = vi.fn();
    render(<ExpiryPicker folder={{ enabled: true, allowed: true, serverMax: "10 years" }} onChange={onChange} />);
    fireEvent.change(screen.getByLabelText("Expires"), { target: { value: "custom" } });
    fireEvent.change(screen.getByLabelText("Custom expiry"), { target: { value: "6 dys" } });
    expect(screen.getByRole("alert").textContent).toBe("Unknown unit 'dys'. Use one of sec, min, hour, day, week, month, year");
    fireEvent.change(screen.getByLabelText("Custom expiry"), { target: { value: "6days" } });
    expect(onChange).toHaveBeenLastCalledWith({ value: "6 days", error: undefined });
  });

  it("shows the time left", () => {
    const at = new Date(Date.now() + 2 * 3600_000 + 1000).toISOString();
    render(<ExpiresCell entry={{ name: "a", path: "/home/a", type: "FILE", size: 1, writable: true, expiresAt: at }} />);
    expect(screen.getByText("in 2 h")).toBeTruthy();
  });
});
