/**
 * The upload percentage rules from the spec:
 *  - percentage = (acknowledged bytes + bytes in flight) / size, as a whole number
 *  - never goes down, even when a chunk fails and is resent
 *  - stops at 99 until the server has assembled the file; 100 only when complete succeeds
 */
export function nextPercent(previous: number, sentBytes: number, size: number, completed: boolean): number {
  if (completed) {
    return 100;
  }
  if (size <= 0) {
    return Math.max(previous, 0);
  }
  const raw = Math.floor((sentBytes / size) * 100);
  return Math.max(previous, Math.min(99, Math.max(0, raw)));
}

/** Overall percentage across several uploads, by bytes. */
export function overallPercent(items: { size: number; sent: number; done: boolean }[]): number {
  const active = items.filter((i) => i.size > 0);
  const total = active.reduce((a, i) => a + i.size, 0);
  if (total === 0) {
    return 0;
  }
  const sent = active.reduce((a, i) => a + (i.done ? i.size : Math.min(i.sent, i.size)), 0);
  return Math.min(100, Math.floor((sent / total) * 100));
}

/** Speed over a sliding window of (time, bytes) samples. */
export class SpeedMeter {
  private samples: { t: number; bytes: number }[] = [];

  constructor(private readonly windowMs = 5000) {}

  add(t: number, totalBytes: number) {
    this.samples.push({ t, bytes: totalBytes });
    while (this.samples.length > 2 && t - this.samples[0].t > this.windowMs) {
      this.samples.shift();
    }
  }

  bytesPerSecond(): number {
    if (this.samples.length < 2) {
      return 0;
    }
    const first = this.samples[0];
    const last = this.samples[this.samples.length - 1];
    const dt = (last.t - first.t) / 1000;
    return dt > 0 ? Math.max(0, (last.bytes - first.bytes) / dt) : 0;
  }

  secondsRemaining(remainingBytes: number): number {
    const s = this.bytesPerSecond();
    return s > 0 ? remainingBytes / s : Number.POSITIVE_INFINITY;
  }
}
