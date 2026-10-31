import { describe, expect, test } from "bun:test";
import { IDENTITY_TRANSFORM } from "./prepare-frame";
import {
  availableFrameCount,
  decodeVideo,
  isAbortError,
  probeVideo,
  streamDecodeVideo,
  throwIfAborted,
} from "./video-decoder";

describe("availableFrameCount (M47)", () => {
  test("a sub-frame-interval video still yields one frame", () => {
    // 0.05 s at 10 fps used to floor to 0 frames — "0 frames" in the UI
    // and a Convert button that silently no-ops.
    expect(availableFrameCount(0.05, 10)).toBe(1);
  });

  test("normal durations floor to whole frames", () => {
    expect(availableFrameCount(1, 10)).toBe(10);
    expect(availableFrameCount(2.55, 10)).toBe(25);
  });
});

describe("throwIfAborted", () => {
  test("no-op without a signal or with a live signal", () => {
    expect(() => throwIfAborted()).not.toThrow();
    expect(() => throwIfAborted(new AbortController().signal)).not.toThrow();
  });

  test("throws an AbortError once the signal is aborted", () => {
    const controller = new AbortController();
    controller.abort();
    let caught: unknown;
    try {
      throwIfAborted(controller.signal);
    } catch (e) {
      caught = e;
    }
    expect(isAbortError(caught)).toBe(true);
  });

  test("propagates a custom abort reason verbatim", () => {
    const controller = new AbortController();
    controller.abort(new Error("stop right now"));
    expect(() => throwIfAborted(controller.signal)).toThrow("stop right now");
  });
});

describe("isAbortError", () => {
  test("recognizes a DOMException named AbortError", () => {
    expect(isAbortError(new DOMException("The operation was aborted", "AbortError"))).toBe(true);
  });

  test("recognizes any error-shaped value named AbortError", () => {
    const err = new Error("cancelled");
    err.name = "AbortError";
    expect(isAbortError(err)).toBe(true);
  });

  test("rejects everything else", () => {
    expect(isAbortError(new Error("boom"))).toBe(false);
    expect(isAbortError(undefined)).toBe(false);
    expect(isAbortError(null)).toBe(false);
    expect(isAbortError("AbortError")).toBe(false);
  });
});

// The mid-decode abort paths (seek waits, metadata waits, per-frame checks) need a
// real <video> element and are browser-only. What bun CAN verify is the contract
// that an already-aborted signal rejects before any DOM API is touched — these
// would throw ReferenceError on `document` otherwise, since bun has no DOM.
describe("already-aborted fast paths (pre-DOM)", () => {
  const abortedSignal = () => {
    const controller = new AbortController();
    controller.abort();
    return controller.signal;
  };
  const videoFile = () => new File([new Uint8Array(8)], "clip.mp4", { type: "video/mp4" });

  test("decodeVideo rejects with an AbortError", async () => {
    let caught: unknown;
    try {
      await decodeVideo(videoFile(), 10, 10, undefined, abortedSignal());
    } catch (e) {
      caught = e;
    }
    expect(isAbortError(caught)).toBe(true);
  });

  test("probeVideo rejects with an AbortError", async () => {
    let caught: unknown;
    try {
      await probeVideo(videoFile(), 10, abortedSignal());
    } catch (e) {
      caught = e;
    }
    expect(isAbortError(caught)).toBe(true);
  });

  test("streamDecodeVideo rejects on the first next()", async () => {
    const gen = streamDecodeVideo(
      videoFile(),
      { targetFps: 10, maxFrames: 10, transform: IDENTITY_TRANSFORM },
      undefined,
      abortedSignal(),
    );
    let caught: unknown;
    try {
      await gen.next();
    } catch (e) {
      caught = e;
    }
    expect(isAbortError(caught)).toBe(true);
  });
});
