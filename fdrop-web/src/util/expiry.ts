/**
 * File expiry as people type it ("6 days", "2weeks", "never"): a copy of the server's parser
 * (io.fdrop.core.expiry.Expiry) for instant feedback in forms, with the same messages. Both pass
 * fdrop-core/src/test/resources/expiry-syntax-cases.json; the server checks again, including folder limits.
 */

const UNITS = "sec, min, hour, day, week, month, year";
const MAX_COUNT = 999_999;

const NAMES: Record<string, string> = {
  s: "second", sec: "second", secs: "second", second: "second", seconds: "second",
  min: "minute", mins: "minute", minute: "minute", minutes: "minute",
  h: "hour", hr: "hour", hrs: "hour", hour: "hour", hours: "hour",
  d: "day", day: "day", days: "day",
  w: "week", wk: "week", wks: "week", week: "week", weeks: "week",
  month: "month", months: "month",
  y: "year", yr: "year", yrs: "year", year: "year", years: "year",
};

const isSpace = (c: string) => c === " " || c === "\t";
const isDigit = (c: string) => c >= "0" && c <= "9";

const BAD_NUMBER = `The number must be a whole number from 1 to ${MAX_COUNT} without leading zeros. To keep a file forever use 'never'`;

export class ExpiryError extends Error {}

/** The canonical form ("6 days", "never"), or an ExpiryError with the reason. */
export function parseExpiry(text: string): string {
  const s = (text ?? "").trim().toLowerCase();
  if (!s) {
    throw new ExpiryError("Expiry is empty. Use a number and a unit, such as '6 days', or 'never'");
  }
  if (s === "never") {
    return "never";
  }
  let i = 0;
  while (i < s.length && isDigit(s[i])) {
    i++;
  }
  if (i === 0) {
    if (s[0] === "-" || s[0] === "+") {
      throw new ExpiryError(BAD_NUMBER);
    }
    throw new ExpiryError("An expiry starts with a number, such as '6 days', or is 'never'");
  }
  const digits = s.slice(0, i);
  if (i < s.length && (s[i] === "." || s[i] === ",")) {
    throw new ExpiryError("Fractions are not supported. Use a smaller unit, such as '36 hours'");
  }
  if (digits.length > 6) {
    throw new ExpiryError(`The number must be a whole number from 1 to ${MAX_COUNT}`);
  }
  if (digits[0] === "0") {
    throw new ExpiryError(BAD_NUMBER);
  }
  while (i < s.length && isSpace(s[i])) {
    i++;
  }
  const start = i;
  while (i < s.length && !isSpace(s[i])) {
    i++;
  }
  const unitText = s.slice(start, i);
  if (!unitText) {
    throw new ExpiryError(`'${digits}' has no unit. Add one of ${UNITS}`);
  }
  while (i < s.length && isSpace(s[i])) {
    i++;
  }
  if (i < s.length) {
    throw new ExpiryError("Only one number and one unit are allowed, such as '30 hours'");
  }
  const unit = NAMES[unitText];
  if (!unit) {
    const letters = unitText.replace(/[0-9].*$/, "");
    if (letters !== unitText && NAMES[letters]) {
      throw new ExpiryError("Only one number and one unit are allowed, such as '30 hours'");
    }
    if (unitText === "m") {
      throw new ExpiryError(`'m' is ambiguous. Write '${digits} min' or '${digits} months'`);
    }
    throw new ExpiryError(`Unknown unit '${unitText}'. Use one of ${UNITS}`);
  }
  return `${digits} ${unit}${digits === "1" ? "" : "s"}`;
}

/** The message for an invalid value, or undefined when it parses. */
export function expiryProblem(text: string): string | undefined {
  try {
    parseExpiry(text);
    return undefined;
  } catch (e) {
    return (e as Error).message;
  }
}

const SECONDS: Record<string, number> = { second: 1, minute: 60, hour: 3600, day: 86_400, week: 604_800 };

/** When a file starting at `from` would expire; months and years are calendar units in UTC. Null for never. */
export function expiryFrom(canonical: string, from: Date): Date | null {
  if (canonical === "never") {
    return null;
  }
  const [n, word] = canonical.split(" ");
  const count = Number(n);
  const unit = word.replace(/s$/, "");
  if (unit === "month" || unit === "year") {
    const d = new Date(from.getTime());
    const day = d.getUTCDate();
    d.setUTCDate(1);
    d.setUTCMonth(d.getUTCMonth() + (unit === "year" ? 12 * count : count));
    const last = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 0)).getUTCDate();
    d.setUTCDate(Math.min(day, last));
    return d;
  }
  return new Date(from.getTime() + count * SECONDS[unit] * 1000);
}

/** True when `a` lasts longer than `b` for a file starting now; never is longest. */
export function longerThan(a: string, b: string, now = new Date()): boolean {
  const ta = expiryFrom(a, now);
  const tb = expiryFrom(b, now);
  if (tb === null) {
    return false;
  }
  return ta === null || ta.getTime() > tb.getTime();
}

/** "in 5 days", "in 40 min", "in 12 s"; "expired" once past. */
export function timeLeft(iso: string, now = Date.now()): string {
  const s = Math.round((new Date(iso).getTime() - now) / 1000);
  if (s <= 0) {
    return "expired";
  }
  if (s < 60) {
    return `in ${s} s`;
  }
  const m = Math.round(s / 60);
  if (m < 60) {
    return `in ${m} min`;
  }
  const h = Math.round(m / 60);
  if (h < 48) {
    return `in ${h} h`;
  }
  const d = Math.round(h / 24);
  return d < 60 ? `in ${d} days` : `in ${Math.round(d / 30)} months`;
}

/** Choices offered in upload and change dialogs; the folder default and custom are added by the caller. */
export const EXPIRY_PRESETS = ["1 hour", "1 day", "7 days", "30 days"];
