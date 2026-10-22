/** A failed API call, carrying the problem JSON detail when the server sent one. */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    /** The problem JSON `type`, e.g. "two-factor-required". */
    public readonly type?: string,
  ) {
    super(message);
    this.name = "ApiError";
  }

  /** The server wants a sign-in with a second factor before it allows this. */
  get needsTwoFactor() {
    return this.type === TWO_FACTOR_REQUIRED;
  }
}

export const TWO_FACTOR_REQUIRED = "two-factor-required";

let csrfToken: string | undefined;

/** The session's CSRF token, from /api/v1/me; sent on every state-changing request. */
export function setCsrfToken(token: string | undefined) {
  csrfToken = token;
}

export function getCsrfToken() {
  return csrfToken;
}

export function signIn() {
  const returnTo = window.location.pathname + window.location.search;
  window.location.assign("/auth/login?returnTo=" + encodeURIComponent(returnTo));
}

/** Signs in again, asking Keycloak for the second factor, and comes back to this page. */
export function signInWithTwoFactor() {
  const returnTo = window.location.pathname + window.location.search;
  window.location.assign("/auth/login?twoFactor=1&returnTo=" + encodeURIComponent(returnTo));
}

/** Starts a Keycloak account action (setting up a factor) and returns to the Account page. */
export function startAccountAction(action: "configure-totp" | "register-security-key" | "recovery-codes") {
  window.location.assign("/auth/login?action=" + action + "&returnTo=%2Faccount");
}

async function problem(resp: Response): Promise<ApiError> {
  let detail = resp.statusText || `HTTP ${resp.status}`;
  let type: string | undefined;
  try {
    const body = await resp.json();
    detail = body.detail ?? body.title ?? detail;
    type = body.type;
  } catch {
    // not JSON
  }
  return new ApiError(resp.status, detail, type);
}

export async function api<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  if (method !== "GET" && csrfToken) {
    headers["X-Fdrop-CSRF"] = csrfToken;
  }
  const resp = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    credentials: "same-origin",
  });
  if (resp.status === 401) {
    signIn();
    throw new ApiError(401, "sign in required");
  }
  if (!resp.ok) {
    throw await problem(resp);
  }
  if (resp.status === 204 || resp.headers.get("Content-Length") === "0") {
    return undefined as T;
  }
  const text = await resp.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const get = <T>(path: string) => api<T>("GET", path);
export const post = <T>(path: string, body?: unknown) => api<T>("POST", path, body ?? {});
export const put = <T>(path: string, body?: unknown) => api<T>("PUT", path, body ?? {});
export const patch = <T>(path: string, body?: unknown) => api<T>("PATCH", path, body ?? {});
export const del = <T>(path: string) => api<T>("DELETE", path);

export const q = encodeURIComponent;

/** A download URL; the server sends Content-Disposition: attachment. */
export function downloadUrl(path: string) {
  return "/api/v1/files/content?path=" + q(path);
}

export async function signOut() {
  const r = await api<{ logoutUrl: string }>("POST", "/auth/logout", {});
  window.location.assign(r?.logoutUrl ?? "/");
}
