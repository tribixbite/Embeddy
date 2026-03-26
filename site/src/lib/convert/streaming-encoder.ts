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

export class StreamingWebPEncoder {
  private worker: Worker | null = null;
  private totalFrames = 0;
  private pushedFrames = 0;
  private onProgress?: (p: ConvertProgress) => void;

  /** Spawn the Worker and load the WASM module */
  async init(options: InitOptions = {}, onProgress?: (p: ConvertProgress) => void): Promise<void> {
    this.onProgress = onProgress;
    this.pushedFrames = 0;

    // Vite resolves this to the Worker bundle at build time
    this.worker = new Worker(
      new URL("./streaming-encoder.worker.ts", import.meta.url),
      { type: "module" },
    );

    return new Promise<void>((resolve, reject) => {
      const handler = (e: MessageEvent<FromWorker>) => {
        if (e.data.type === "ready") {
          this.worker!.removeEventListener("message", handler);
          resolve();
        } else if (e.data.type === "error") {
          this.worker!.removeEventListener("message", handler);
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
   * The ArrayBuffer is transferred (zero-copy) to the Worker.
   * After this call, the rgba buffer is neutered and cannot be reused.
   */
  async pushFrame(
    rgba: Uint8Array,
    width: number,
    height: number,
    frameOptions: FrameOptions,
  ): Promise<void> {
    if (!this.worker) throw new Error("Encoder not initialized");

    return new Promise<void>((resolve, reject) => {
      const handler = (e: MessageEvent<FromWorker>) => {
        if (e.data.type === "pushed") {
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
          this.worker!.removeEventListener("message", handler);
          reject(new Error(e.data.message));
        }
      };
      this.worker!.addEventListener("message", handler);

      // Transfer the ArrayBuffer to avoid copying (main thread loses access)
      const buffer = rgba.buffer;
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
      const handler = (e: MessageEvent<FromWorker>) => {
        if (e.data.type === "encoded") {
          this.worker!.removeEventListener("message", handler);

          this.onProgress?.({
            phase: "encoding",
            percent: 100,
            frame: this.pushedFrames,
            total: this.totalFrames,
          });

          const blob = new Blob([e.data.data], { type: "image/webp" });
          this.terminate();
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

  /** Abort and clean up the Worker */
  abort() {
    if (this.worker) {
      this.worker.postMessage({ type: "abort" });
      this.terminate();
    }
  }

  private terminate() {
    if (this.worker) {
      this.worker.terminate();
      this.worker = null;
    }
  }
}
