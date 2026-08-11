import { describe, expect, test } from "bun:test";
import { splitAnimatedWebP } from "./webp-frames";

// ── Fixture helpers ─────────────────────────────────────────────────────────

const cc = (id: string) => [...id].map((c) => c.charCodeAt(0));
const u32 = (n: number) => [n & 0xff, (n >> 8) & 0xff, (n >> 16) & 0xff, (n >>> 24) & 0xff];
const u24 = (n: number) => [n & 0xff, (n >> 8) & 0xff, (n >> 16) & 0xff];

function chunk(id: string, payload: number[]): number[] {
  const out = [...cc(id), ...u32(payload.length), ...payload];
  if (payload.length % 2 === 1) out.push(0);
  return out;
}

function riff(body: number[]): ArrayBuffer {
  const payload = [...cc("WEBP"), ...body];
  return new Uint8Array([...cc("RIFF"), ...u32(payload.length), ...payload]).buffer;
}

function vp8x(flags: number, w: number, h: number): number[] {
  return chunk("VP8X", [flags, 0, 0, 0, ...u24(w - 1), ...u24(h - 1)]);
}

/**
 * Build an ANMF frame.
 * @param flags bit 1 = blending (1 = do not blend), bit 0 = disposal (1 = background)
 */
function anmf(
  opts: {
    x?: number; y?: number; w: number; h: number; duration: number; flags?: number;
    sub?: Array<{ id: string; payload: number[] }>;
  },
): number[] {
  const sub = opts.sub ?? [{ id: "VP8 ", payload: [1, 2, 3, 4] }];
  const body = [
    ...u24((opts.x ?? 0) / 2),
    ...u24((opts.y ?? 0) / 2),
    ...u24(opts.w - 1),
    ...u24(opts.h - 1),
    ...u24(opts.duration),
    opts.flags ?? 0,
    ...sub.flatMap((s) => chunk(s.id, s.payload)),
  ];
  return chunk("ANMF", body);
}

/** Read a FourCC out of a produced still-image buffer. */
function fourCCAt(bytes: Uint8Array, offset: number): string {
  return String.fromCharCode(...bytes.subarray(offset, offset + 4));
}

// ── Tests ───────────────────────────────────────────────────────────────────

describe("splitAnimatedWebP — rejection cases", () => {
  test("returns null for a non-RIFF buffer", () => {
    expect(splitAnimatedWebP(new Uint8Array([1, 2, 3, 4]).buffer)).toBeNull();
  });

  test("returns null for a still WebP (no animation flag)", () => {
    const still = riff([...vp8x(0x10, 32, 32), ...chunk("VP8 ", [9, 9, 9, 9])]);
    expect(splitAnimatedWebP(still)).toBeNull();
  });

  test("returns null when the animation flag is set but no frames follow", () => {
    expect(splitAnimatedWebP(riff(vp8x(0x02, 32, 32)))).toBeNull();
  });
});

describe("splitAnimatedWebP — frame geometry and timing", () => {
  const buffer = riff([
    ...vp8x(0x02, 100, 80),
    ...chunk("ANIM", [0, 0, 0, 0, 5, 0]), // loop count 5
    ...anmf({ w: 100, h: 80, duration: 40 }),
    ...anmf({ x: 10, y: 20, w: 30, h: 40, duration: 60, flags: 0b10 }), // replace
    ...anmf({ x: 4, y: 6, w: 20, h: 20, duration: 0, flags: 0b01 }), // dispose bg
  ]);

  test("reads canvas size and loop count", () => {
    const set = splitAnimatedWebP(buffer)!;
    expect(set.width).toBe(100);
    expect(set.height).toBe(80);
    expect(set.loopCount).toBe(5);
    expect(set.frames).toHaveLength(3);
  });

  test("decodes per-frame offsets, which are stored halved", () => {
    const [, second, third] = splitAnimatedWebP(buffer)!.frames;
    expect(second!.x).toBe(10);
    expect(second!.y).toBe(20);
    expect(second!.width).toBe(30);
    expect(second!.height).toBe(40);
    expect(third!.x).toBe(4);
    expect(third!.y).toBe(6);
  });

  test("decodes durations verbatim, including zero", () => {
    const frames = splitAnimatedWebP(buffer)!.frames;
    expect(frames.map((f) => f.duration)).toEqual([40, 60, 0]);
  });

  test("decodes blend and dispose flags independently", () => {
    const frames = splitAnimatedWebP(buffer)!.frames;
    expect(frames[0]!.blend).toBe("blend");
    expect(frames[0]!.dispose).toBe("none");
    expect(frames[1]!.blend).toBe("replace");
    expect(frames[1]!.dispose).toBe("none");
    expect(frames[2]!.blend).toBe("blend");
    expect(frames[2]!.dispose).toBe("background");
  });
});

describe("splitAnimatedWebP — still-image rewrapping", () => {
  test("wraps a bare VP8 frame in a minimal RIFF/WEBP container", () => {
    const set = splitAnimatedWebP(riff([
      ...vp8x(0x02, 16, 16),
      ...anmf({ w: 16, h: 16, duration: 100, sub: [{ id: "VP8 ", payload: [7, 7, 7, 7] }] }),
    ]))!;
    const bytes = set.frames[0]!.bytes;

    expect(fourCCAt(bytes, 0)).toBe("RIFF");
    expect(fourCCAt(bytes, 8)).toBe("WEBP");
    // No alpha chunk, so no synthesised VP8X — the image chunk follows directly
    expect(fourCCAt(bytes, 12)).toBe("VP8 ");
    // Declared RIFF size must match the actual payload
    const declared = new DataView(bytes.buffer, bytes.byteOffset).getUint32(4, true);
    expect(declared).toBe(bytes.length - 8);
  });

  test("synthesises a VP8X header when the frame carries a separate alpha chunk", () => {
    const set = splitAnimatedWebP(riff([
      ...vp8x(0x02, 64, 48),
      ...anmf({
        w: 64, h: 48, duration: 100,
        sub: [
          { id: "ALPH", payload: [0, 1, 2, 3] },
          { id: "VP8 ", payload: [4, 5, 6, 7] },
        ],
      }),
    ]))!;
    const bytes = set.frames[0]!.bytes;
    const view = new DataView(bytes.buffer, bytes.byteOffset);

    expect(fourCCAt(bytes, 12)).toBe("VP8X");
    expect(view.getUint8(20) & 0x10).toBe(0x10); // alpha flag set
    // Canvas dims in the synthesised header are the frame's own size, minus one
    expect(view.getUint8(24) | (view.getUint8(25) << 8) | (view.getUint8(26) << 16)).toBe(63);
    expect(view.getUint8(27) | (view.getUint8(28) << 8) | (view.getUint8(29) << 16)).toBe(47);
    // Both sub-chunks are carried over
    expect(fourCCAt(bytes, 30)).toBe("ALPH");
    expect(view.getUint32(4, true)).toBe(bytes.length - 8);
  });

  test("carries a lossless VP8L frame through unchanged", () => {
    const set = splitAnimatedWebP(riff([
      ...vp8x(0x02, 8, 8),
      ...anmf({ w: 8, h: 8, duration: 50, sub: [{ id: "VP8L", payload: [0x2f, 1, 2, 3] }] }),
    ]))!;
    const bytes = set.frames[0]!.bytes;
    expect(fourCCAt(bytes, 12)).toBe("VP8L");
    expect(bytes[20]).toBe(0x2f); // VP8L signature survives
  });
});

describe("splitAnimatedWebP — malformed input", () => {
  test("does not throw when a frame declares more bytes than exist", () => {
    const truncated = new Uint8Array([
      ...cc("RIFF"), ...u32(1000), ...cc("WEBP"),
      ...vp8x(0x02, 16, 16),
      ...cc("ANMF"), ...u32(9999), 0, 0, 0,
    ]).buffer;
    expect(() => splitAnimatedWebP(truncated)).not.toThrow();
  });

  test("terminates on a chunk size that fails to advance the cursor", () => {
    const buffer = riff([...vp8x(0x02, 16, 16), ...chunk("XMP ", []), ...anmf({ w: 16, h: 16, duration: 10 })]);
    const set = splitAnimatedWebP(buffer);
    expect(set!.frames).toHaveLength(1);
  });
});
