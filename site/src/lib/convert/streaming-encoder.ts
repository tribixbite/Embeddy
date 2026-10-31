/**
 * Main-thread API for streaming WebP encoding via Web Worker.
 *
 * Instead of buffering all frames into memory and calling encodeAnimation(),
 * this streams one frame at a time to the Worker which calls WebPAnimEncoderAdd
 * per frame. Memory usage is O(1 frame + compressed output) instead of O(N frames).
 *
 * Usage:
 *   const enc = new StreamingWebPEncoder();
 *   await enc.init({ loop: 0 });
 *   for (const frame of frames) {
 *     await enc.pushFrame(frame.rgba, width, height, { duration: frame.delay, quality: 75 });
 *   }
 *   const blob = await enc.finalize();
 */

import type { FromWorker, InitOptions, FrameOptions } from "./streaming-encoder.worker";
import type { ConvertProgress } from "./types";

/**
 * One idle encoder Worker kept warm between conversions (P3). Spawning a
 * Worker re-parses the ~660 KB bundle and recompiles the embedded WASM; the
 * Worker already caches its WASM module across init() calls, so reusing it
 * makes every encode after the first (including each adaptive target-size
 * attempt) skip that cost. Workers that errored or were aborted are
 * terminated instead of returned — their state is unknown.
 */
let idleWorker: Worker | null = null;
let idleTimer: ReturnType<typeof setTimeout> | undefined;

/**
 * An Emscripten heap never shrinks, so a Worker that just encoded a large
 * animation keeps that memory while idle. Drop it after this long unused.
 */
const IDLE_WORKER_TTL_MS = 60_000;

function acquireWorker(): Worker {
  clearTimeout(idleTimer);
  const reused = idleWorker;
  idleWorker = null;
  // Vite resolves this to the Worker bundle at build time
  return (
    reused ??
    new Worker(new URL("./streaming-encoder.worker.ts", import.meta.url), { type: "module" })
  );
}

function releaseWorker(worker: Worker) {
  if (idleWorker) {
    worker.terminate();
    return;
  }
  idleWorker = worker;
  clearTimeout(idleTimer);
  idleTimer = setTimeout(() => {
    idleWorker?.terminate();
    idleWorker = null;
  }, IDLE_WORKER_TTL_MS);
}

export class StreamingWebPEncoder {
  private worker: Worker | null = null;
  private onWorkerError = (e: ErrorEvent) => {
    this.failPending(
      new Error(e.message ? `Encoder worker error: ${e.message}` : "Encoder worker failed to load"),
    );
  };
  private onMessageError = () => {
    this.failPending(new Error("Encoder worker message could not be deserialized"));
  };
  private totalFrames = 0;
  private pushedFrames = 0;
  private onProgress?: (p: ConvertProgress) => void;
  /** Active reject handler — invoked on abort() to unblock pending Promises */
  private rejectActive?: (err: Error) => void;

  /** Spawn the Worker and load the WASM module */
  async init(options: InitOptions = {}, onProgress?: (p: ConvertProgress) => void): Promise<void> {
    this.onProgress = onProgress;
    this.pushedFrames = 0;

    this.worker = acquireWorker();

    // A Worker whose script (or vendored WASM import) fails to fetch fires
    // "error" and never posts a message — without these listeners init(),
    // pushFrame() and finalize() would hang the conversion forever. They are
    // removed again when the Worker goes back to the pool.
    this.worker.addEventListener("error", this.onWorkerError);
    this.worker.addEventListener("messageerror", this.onMessageError);

    return new Promise<void>((resolve, reject) => {
      this.rejectActive = reject;
      const handler = (e: MessageEvent<FromWorker>) => {
        if (e.data.type === "ready") {
          this.rejectActive = undefined;
          this.worker!.removeEventListener("message", handler);
          resolve();
        } else if (e.data.type === "error") {
          this.rejectActive = undefined;
          this.worker!.removeEventListener("message", handler);
          // A Worker that failed to initialise is unusable — don't leak it
          this.terminate();
          reject(new Error(e.data.message));
        }
      };
      this.worker!.addEventListener("message", handler);
      this.worker!.postMessage({ type: "init", options });
    });
  }

  /** Set the expected total frame count (for progress reporting) */
  setTotalFrames(total: number) {
    this.totalFrames = total;
  }

  /**
   * Push one RGBA frame to the encoder.
   * By default a copy of the pixel data is transferred, so the caller's
   * buffer stays valid — load-bearing for buffered source frames, which are
   * re-pushed when the user adjusts settings or the adaptive target-size
   * loop re-encodes. Pass `transfer: true` for a throwaway buffer (a freshly
   * transformed frame) to hand it over zero-copy; it is neutered afterwards.
   */
  async pushFrame(
    rgba: Uint8Array,
    width: number,
    height: number,
    frameOptions: FrameOptions,
    transfer = false,
  ): Promise<void> {
    if (!this.worker) throw new Error("Encoder not initialized");

    return new Promise<void>((resolve, reject) => {
      this.rejectActive = reject;
      const handler = (e: MessageEvent<FromWorker>) => {
        if (e.data.type === "pushed") {
          this.rejectActive = undefined;
          this.worker!.removeEventListener("message", handler);
          this.pushedFrames++;

          this.onProgress?.({
            phase: "encoding",
            percent: this.totalFrames > 0
              ? Math.round((this.pushedFrames / this.totalFrames) * 95) // reserve 5% for assemble
              : 0,
            frame: this.pushedFrames,
            total: this.totalFrames,
          });

          resolve();
        } else if (e.data.type === "error") {
          this.rejectActive = undefined;
          this.worker!.removeEventListener("message", handler);
          // The WASM encoder released its state on error — the Worker can't continue
          this.terminate();
          reject(new Error(e.data.message));
        }
      };
      this.worker!.addEventListener("message", handler);

      // Transfer neuters the ArrayBuffer, so shared frames are copied first.
      // A view that doesn't span its whole buffer must be copied either way.
      const owned = transfer && rgba.byteOffset === 0 && rgba.byteLength === rgba.buffer.byteLength;
      const buffer = (owned ? rgba : rgba.slice()).buffer as ArrayBuffer;

      this.worker!.postMessage(
        { type: "push", rgba: buffer, width, height, frameOptions },
        [buffer],
      );
    });
  }

  /** Finalize encoding and return the animated WebP as a Blob */
  async finalize(): Promise<Blob> {
    if (!this.worker) throw new Error("Encoder not initialized");

    return new Promise<Blob>((resolve, reject) => {
      this.rejectActive = reject;
      const handler = (e: MessageEvent<FromWorker>) => {
        if (e.data.type !== "encoded" && e.data.type !== "error") return;
        this.rejectActive = undefined;
        if (e.data.type === "encoded") {
          this.worker!.removeEventListener("message", handler);

          this.onProgress?.({
            phase: "encoding",
            percent: 100,
            frame: this.pushedFrames,
            total: this.totalFrames,
          });

          const blob = new Blob([e.data.data], { type: "image/webp" });
          // Clean finish: the Worker released its encoder — keep it warm
          this.release();
          resolve(blob);
        } else if (e.data.type === "error") {
          this.worker!.removeEventListener("message", handler);
          this.terminate();
          reject(new Error(e.data.message));
        }
      };
      this.worker!.addEventListener("message", handler);
      this.worker!.postMessage({ type: "encode" });
    });
  }

  /** Abort and clean up the Worker, rejecting any pending operation */
  abort() {
    if (this.worker) {
      this.worker.postMessage({ type: "abort" });
      this.terminate();
    }
    // Reject any pending Promise so the caller's for-await-of loop exits cleanly
    if (this.rejectActive) {
      this.rejectActive(new Error("Encoder aborted"));
      this.rejectActive = undefined;
    }
  }

  /** Reject the in-flight operation (if any) and tear the Worker down. */
  private failPending(err: Error) {
    const reject = this.rejectActive;
    this.rejectActive = undefined;
    this.terminate();
    reject?.(err);
  }

  /** Detach from the Worker, removing this encoder's listeners */
  private detach(): Worker | null {
    const worker = this.worker;
    if (worker) {
      worker.removeEventListener("error", this.onWorkerError);
      worker.removeEventListener("messageerror", this.onMessageError);
      this.worker = null;
    }
    return worker;
  }

  /** Return a cleanly finished Worker to the pool */
  private release() {
    const worker = this.detach();
    if (worker) releaseWorker(worker);
  }

  /** Kill a Worker whose state is unknown (error/abort) */
  private terminate() {
    this.detach()?.terminate();
  }
}
