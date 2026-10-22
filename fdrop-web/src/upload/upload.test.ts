import { createHash } from "node:crypto";
import { describe, expect, it, vi } from "vitest";
import { nextPercent, overallPercent, SpeedMeter } from "./progress";
import { matchPending } from "./persist";
import { UploadQueue } from "./queue";
import { Sha256 } from "./sha256";

describe("Sha256", () => {
  it("matches Node's SHA-256 for known vectors", () => {
    expect(new Sha256().update(new Uint8Array()).digestHex()).toBe(
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    );
    expect(new Sha256().update(new TextEncoder().encode("abc")).digestHex()).toBe(
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
    );
  });

  it("gives the same digest however the data is chunked", () => {
    const data = new Uint8Array(1_000_003);
    for (let i = 0; i < data.length; i++) {
      data[i] = (i * 31 + 7) & 0xff;
    }
    const expected = createHash("sha256").update(data).digest("hex");
    const h = new Sha256();
    for (let i = 0; i < data.length; i += 65_537) {
      h.update(data.subarray(i, i + 65_537));
    }
    expect(h.digestHex()).toBe(expected);
    const odd = new Sha256();
    for (let i = 0; i < 200; i++) {
      odd.update(data.subarray(i * 7, i * 7 + 7));
    }
    expect(odd.digestHex()).toBe(createHash("sha256").update(data.subarray(0, 1400)).digest("hex"));
  });
});

describe("progress rules", () => {
  it("never goes down and stops at 99 until complete", () => {
    let p = nextPercent(0, 500, 1000, false);
    expect(p).toBe(50);
    p = nextPercent(p, 300, 1000, false); // a chunk failed and its bytes were dropped
    expect(p).toBe(50);
    p = nextPercent(p, 1000, 1000, false);
    expect(p).toBe(99);
    expect(nextPercent(p, 1000, 1000, true)).toBe(100);
  });

  it("weights the overall percentage by bytes", () => {
    expect(
      overallPercent([
        { size: 900, sent: 0, done: false },
        { size: 100, sent: 100, done: true },
      ]),
    ).toBe(10);
  });

  it("measures speed over a window", () => {
    const m = new SpeedMeter(5000);
    m.add(0, 0);
    m.add(1000, 1_000_000);
    expect(m.bytesPerSecond()).toBe(1_000_000);
    expect(m.secondsRemaining(3_000_000)).toBe(3);
  });
});

describe("UploadQueue", () => {
  function queue() {
    const posted: unknown[] = [];
    const worker = { postMessage: (m: unknown) => posted.push(m), onmessage: null } as unknown as Worker;
    return { q: new UploadQueue(() => worker), posted };
  }

  it("keeps the percentage monotonic and reaches 100 only on done", () => {
    const { q, posted } = queue();
    const file = new File([new Uint8Array(1000)], "a.bin", { lastModified: 1 });
    const key = q.add(file, "/home/a.bin");
    expect(posted[0]).toMatchObject({ type: "start", path: "/home/a.bin" });
    q.handle({ key, type: "progress", sent: 600 });
    expect(q.getSnapshot()[0].percent).toBe(60);
    q.handle({ key, type: "progress", sent: 400 });
    expect(q.getSnapshot()[0].percent).toBe(60);
    q.handle({ key, type: "progress", sent: 1000 });
    expect(q.getSnapshot()[0].percent).toBe(99);
    const done = vi.fn();
    q.onDone = done;
    q.handle({ key, type: "done" });
    expect(q.getSnapshot()[0].percent).toBe(100);
    expect(done).toHaveBeenCalledOnce();
  });

  it("marks forward-folder uploads so the row can show forward status", () => {
    const { q } = queue();
    q.add(new File(["x"], "x.pdf"), "/forward/Partner/x.pdf");
    expect(q.getSnapshot()[0].forward).toBe(true);
  });

  it("matches a re-selected file to a pending upload by name, size and last-modified", () => {
    const pending = [
      { key: "k", uploadId: "u", name: "big.iso", size: 3, lastModified: 42, chunkSize: 1, path: "/home/big.iso", createdAt: 0 },
    ];
    expect(matchPending(pending, new File(["abc"], "big.iso", { lastModified: 42 }))?.uploadId).toBe("u");
    expect(matchPending(pending, new File(["abc"], "big.iso", { lastModified: 43 }))).toBeUndefined();
  });
});
