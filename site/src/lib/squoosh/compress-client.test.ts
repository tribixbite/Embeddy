import { afterEach, describe, expect, test } from "bun:test";
import { GIF_UNSUPPORTED_MESSAGE } from "./codecs";
import type { CompressOptions } from "./compress";
import {
  compressOffThread,
  resetCompressWorkerState,
  type FromCompressWorker,
  type ToCompressWorker,
} from "./compress-client";

const OPTS: CompressOptions = { format: "webp", quality: 75, maxDimension: 0, lossless: false };
const png = new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], "a.png", { type: "image/png" });
/** A GIF makes the in-thread fallback fail fast with a known message */
const gif = new File([new TextEncoder().encode("GIF89a....")], "a.gif", { type: "image/gif" });

/** Scriptable stand-in for a module Worker */
class FakeWorker extends EventTarget {
  sent: ToCompressWorker[] = [];
  terminated = false;
  constructor(private readonly reply: (msg: ToCompressWorker, w: FakeWorker) => void) {
    super();
  }
  postMessage(msg: ToCompressWorker) {
    this.sent.push(msg);
    queueMicrotask(() => this.reply(msg, this));
  }
  terminate() {
    this.terminated = true;
  }
  emit(data: FromCompressWorker) {
    this.dispatchEvent(new MessageEvent("message", { data }));
  }
}

function factoryOf(reply: (msg: ToCompressWorker, w: FakeWorker) => void) {
  const made: FakeWorker[] = [];
  const factory = () => {
    const w = new FakeWorker(reply);
    made.push(w);
    return w as unknown as Worker;
  };
  return { factory, made };
}

afterEach(resetCompressWorkerState);

describe("compressOffThread", () => {
  test("returns the Worker's blob and keeps the Worker warm", async () => {
    const out = new Blob(["x"], { type: "image/webp" });
    const { factory, made } = factoryOf((m, w) => w.emit({ id: m.id, type: "done", blob: out }));
    expect(await compressOffThread(png, OPTS, undefined, factory)).toBe(out);
    expect(await compressOffThread(png, OPTS, undefined, factory)).toBe(out);
    expect(made.length).toBe(1);
    expect(made[0]!.sent[0]!.options).toEqual(OPTS);
  });

  test("ignores replies for other jobs", async () => {
    const out = new Blob(["ok"]);
    const { factory } = factoryOf((m, w) => {
      w.emit({ id: m.id + 100, type: "error", message: "not mine" });
      w.emit({ id: m.id, type: "done", blob: out });
    });
    expect(await compressOffThread(png, OPTS, undefined, factory)).toBe(out);
  });

  test("a compress error from the Worker rejects with its message", async () => {
    const { factory } = factoryOf((m, w) => w.emit({ id: m.id, type: "error", message: "bad image" }));
    await expect(compressOffThread(png, OPTS, undefined, factory)).rejects.toThrow("bad image");
  });

  test("abort terminates the Worker and the next run starts a fresh one", async () => {
    const { factory, made } = factoryOf(() => {}); // never replies
    const ctrl = new AbortController();
    const run = compressOffThread(png, OPTS, ctrl.signal, factory);
    ctrl.abort();
    await expect(run).rejects.toMatchObject({ name: "AbortError" });
    expect(made[0]!.terminated).toBe(true);

    const second = new AbortController();
    const run2 = compressOffThread(png, OPTS, second.signal, factory);
    second.abort();
    await expect(run2).rejects.toMatchObject({ name: "AbortError" });
    expect(made.length).toBe(2);
  });

  test("an already-aborted signal rejects without starting a Worker", async () => {
    const { factory, made } = factoryOf(() => {});
    const ctrl = new AbortController();
    ctrl.abort();
    await expect(compressOffThread(png, OPTS, ctrl.signal, factory)).rejects.toMatchObject({ name: "AbortError" });
    expect(made.length).toBe(0);
  });

  test("a Worker that fails to start falls back to the main thread, and stays off", async () => {
    const { factory, made } = factoryOf((_m, w) => w.dispatchEvent(new Event("error", { cancelable: true })));
    await expect(compressOffThread(gif, OPTS, undefined, factory)).rejects.toThrow(GIF_UNSUPPORTED_MESSAGE);
    expect(made[0]!.terminated).toBe(true);
    await expect(compressOffThread(gif, OPTS, undefined, factory)).rejects.toThrow(GIF_UNSUPPORTED_MESSAGE);
    expect(made.length).toBe(1);
  });

  test("a throwing Worker constructor falls back to the main thread", async () => {
    const factory = () => {
      throw new Error("module workers unsupported");
    };
    await expect(compressOffThread(gif, OPTS, undefined, factory)).rejects.toThrow(GIF_UNSUPPORTED_MESSAGE);
  });
});
