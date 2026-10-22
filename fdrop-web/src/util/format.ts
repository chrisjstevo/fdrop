const UNITS = ["B", "KB", "MB", "GB", "TB"];

/** 1536 → "1.5 KB"; decimal units, as file managers show them. */
export function formatBytes(n: number): string {
  if (!Number.isFinite(n) || n < 0) {
    return "–";
  }
  let v = n;
  let u = 0;
  while (v >= 1000 && u < UNITS.length - 1) {
    v /= 1000;
    u++;
  }
  const digits = u === 0 ? 0 : v >= 100 ? 0 : v >= 10 ? 1 : 1;
  return `${v.toFixed(digits)} ${UNITS[u]}`;
}

export function formatCount(n: number): string {
  return new Intl.NumberFormat().format(n);
}

export function formatDate(iso?: string | number): string {
  if (iso === undefined || iso === null || iso === "") {
    return "–";
  }
  const d = new Date(iso);
  return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(d);
}

/** "3 min" / "45 s" / "2 h 5 min" */
export function formatDuration(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) {
    return "–";
  }
  const s = Math.round(seconds);
  if (s < 60) {
    return `${s} s`;
  }
  const m = Math.round(s / 60);
  if (m < 60) {
    return `${m} min`;
  }
  const h = Math.floor(m / 60);
  return `${h} h ${m % 60} min`;
}

export function formatSpeed(bytesPerSecond: number): string {
  return `${formatBytes(bytesPerSecond)}/s`;
}

/** Joins a folder path and a child name. */
export function join(dir: string, name: string): string {
  return dir.endsWith("/") ? dir + name : `${dir}/${name}`;
}

export function parentOf(path: string): string {
  const i = path.lastIndexOf("/");
  return i <= 0 ? "/" : path.slice(0, i);
}
