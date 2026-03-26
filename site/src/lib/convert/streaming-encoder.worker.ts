/**
 * Web Worker for streaming WebP encoding using xiaozhuai/webp_encoder.
 *
 * Uses Emscripten's incremental API: init() → push() × N → encode() → release()
 * Each push() encodes one frame via WebPAnimEncoderAdd, then yields via
 * emscripten_sleep(0) (Asyncify). Only one frame of RGBA data is held at a time
 * in the WASM heap, keeping memory O(compressed_output) instead of O(frames × pixels).
 *
 * @license MIT — vendored from https://github.com/xiaozhuai/webp_encoder
 */

import WebpEncoderWasm from "./vendor/webp-encoder-wasm.js";

/** Messages the main thread can send to this Worker */
export type ToWorker =
  | { type: "init"; options: InitOptions }
  | { type: "push"; rgba: ArrayBuffer; width: number; height: number; frameOptions: FrameOptions }
  | { type: "encode" }
  | { type: "abort" };

/** Messages this Worker sends back to the main thread */
export type FromWorker =
  | { type: "ready" }
  | { type: "pushed"; frameIndex: number }
  | { type: "encoded"; data: ArrayBuffer; byteLength: number }
  | { type: "error"; message: string };

export interface InitOptions {
  /** Minimize output size (slow). Default: true */
  minimize?: boolean;
  /** Loop count. 0 = infinite loop. Default: 0 */
  loop?: number;
  /** Allow mixed lossy/lossless frames. Default: true */
  mixed?: boolean;
}

export interface FrameOptions {
  /** Frame duration in milliseconds */
  duration: number;
  /** Lossless encoding for this frame */
  lossless?: boolean;
  /** Quality 0-100 */
  quality?: number;
  /** Speed/quality tradeoff 0=fast 6=slow-better. Default: 0 for streaming perf */
  method?: number;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
let wasmModule: any = null;
// eslint-disable-next-line @typescript-eslint/no-explicit-any
let encoder: any = null;
let frameIndex = 0;
let aborted = false;

function post(msg: FromWorker, transfer?: Transferable[]) {
  self.postMessage(msg, { transfer: transfer ?? [] });
}

self.onmessage = async (e: MessageEvent<ToWorker>) => {
  const msg = e.data;

  try {
    switch (msg.type) {
      case "init": {
        aborted = false;
        frameIndex = 0;

        // Lazy-load the Emscripten WASM module on first use
        if (!wasmModule) {
          wasmModule = await WebpEncoderWasm();
        }

        // Create fresh encoder instance
        encoder = new wasmModule.WebpEncoder();
        const ok = encoder.init({
          minimize: msg.options.minimize ?? true,
          loop: msg.options.loop ?? 0,
          mixed: msg.options.mixed ?? true,
        });

        if (!ok) throw new Error("WebpEncoder.init() failed");
        post({ type: "ready" });
        break;
      }

      case "push": {
        if (aborted) return;
        if (!encoder) throw new Error("Encoder not initialized — call init first");

        const rgba = new Uint8Array(msg.rgba);

        // push() calls WebPAnimEncoderAdd then emscripten_sleep(0) via Asyncify.
        // Must await — Asyncify functions return Promises; calling without await
        // would trigger "Unwinding while already unwinding" WASM crash on next push.
        const ok = await encoder.push(rgba, msg.width, msg.height, {
          duration: msg.frameOptions.duration,
          lossless: msg.frameOptions.lossless ?? false,
          quality: msg.frameOptions.quality ?? 75,
          method: msg.frameOptions.method ?? 0,
        });

        if (!ok) throw new Error(`WebpEncoder.push() failed on frame ${frameIndex}`);

        post({ type: "pushed", frameIndex: frameIndex++ });
        break;
      }

      case "encode": {
        if (!encoder) throw new Error("Encoder not initialized");
        if (aborted) return;

        // encode() calls WebPAnimEncoderAssemble → returns typed_memory_view into WASM heap
        const wasmView: Uint8Array = encoder.encode();
        if (!wasmView || wasmView.byteLength === 0) {
          throw new Error("WebpEncoder.encode() returned empty result");
        }

        // Copy out of WASM memory before release() invalidates the view
        const copy = wasmView.slice().buffer;
        const byteLength = wasmView.byteLength;

        encoder.release();
        encoder = null;

        // Transfer ownership of the ArrayBuffer (zero-copy to main thread)
        post({ type: "encoded", data: copy, byteLength }, [copy]);
        break;
      }

      case "abort": {
        aborted = true;
        if (encoder) {
          try {
            encoder.release();
          } catch {
            // Best-effort cleanup
          }
          encoder = null;
        }
        break;
      }
    }
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    post({ type: "error", message });

    // Clean up encoder on error
    if (encoder) {
      try {
        encoder.release();
      } catch {
        // Ignore cleanup errors
      }
      encoder = null;
    }
  }
};
