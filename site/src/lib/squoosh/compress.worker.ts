/**
 * Squoosh compression Worker: decode → resize → WASM encode run here so a
 * multi-second AVIF/WebP encode never freezes the page, and Cancel can stop
 * it outright by terminating the Worker. Protocol types live in
 * compress-client.ts.
 */
import { compress } from "./compress";
import type { FromCompressWorker, ToCompressWorker } from "./compress-client";

self.onmessage = async (e: MessageEvent<ToCompressWorker>) => {
  const { id, file, options } = e.data;
  let reply: FromCompressWorker;
  try {
    reply = { id, type: "done", blob: await compress(file, options) };
  } catch (err) {
    reply = { id, type: "error", message: err instanceof Error ? err.message : String(err) };
  }
  self.postMessage(reply);
};
