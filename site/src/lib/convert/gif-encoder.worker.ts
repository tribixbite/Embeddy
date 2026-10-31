/**
 * Web Worker running GIF quantisation + LZW encoding off the main thread
 * (P5). Frames arrive already transformed; the main thread only does the
 * single drawImage per frame. Messages are processed strictly in order.
 */

import { createGifWriter, type GifWriter } from "./gif-writer";

/** Messages the main thread sends */
export type ToGifWorker =
  | { type: "init"; maxColors: number; loops: number }
  | { type: "frame"; rgba: ArrayBuffer; width: number; height: number; delay: number }
  | { type: "finish" };

/** Messages this Worker sends back */
export type FromGifWorker =
  | { type: "ready" }
  | { type: "written" }
  | { type: "done"; bytes: ArrayBuffer }
  | { type: "error"; message: string };

let writer: GifWriter | null = null;

function post(msg: FromGifWorker, transfer: Transferable[] = []) {
  self.postMessage(msg, { transfer });
}

self.onmessage = async (e: MessageEvent<ToGifWorker>) => {
  const msg = e.data;
  try {
    switch (msg.type) {
      case "init": {
        const gifenc = await import("gifenc");
        writer = createGifWriter(gifenc, { maxColors: msg.maxColors, loops: msg.loops });
        post({ type: "ready" });
        break;
      }
      case "frame": {
        if (!writer) throw new Error("GIF writer not initialized");
        writer.write(new Uint8Array(msg.rgba), msg.width, msg.height, msg.delay);
        post({ type: "written" });
        break;
      }
      case "finish": {
        if (!writer) throw new Error("GIF writer not initialized");
        const bytes = writer.finish();
        writer = null;
        post({ type: "done", bytes: bytes.buffer as ArrayBuffer }, [bytes.buffer]);
        break;
      }
    }
  } catch (err) {
    writer = null;
    post({ type: "error", message: err instanceof Error ? err.message : String(err) });
  }
};
