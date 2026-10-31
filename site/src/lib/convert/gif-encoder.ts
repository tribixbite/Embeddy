/**
 * Encode RGBA frames into animated GIF using gifenc.
 * Frame geometry comes from prepare-frame.ts; palette quantisation and
 * writing live in gif-writer.ts and run in a Worker when available.
 */

import type { DecodedFrame, ConvertOptions, ConvertProgress } from "./types";
import { subsampleFrames, YIELD_EVERY, yieldToEventLoop } from "./encoder";
import { FramePreparer, transformFromOptions } from "./prepare-frame";
import { throwIfAborted } from "./abort";
import { outputDelay } from "./timeline";
import {
  createGifWriter,
  qualityToMaxColors,
  type GifWriterOptions,
} from "./gif-writer";
import type { FromGifWorker, ToGifWorker } from "./gif-encoder.worker";

/** Lazy-loaded gifenc module (in-thread path only) */
let gifencModule: typeof import("gifenc") | null = null;

async function getGifenc() {
  if (!gifencModule) {
    gifencModule = await import("gifenc");
  }
  return gifencModule;
}

/** Frames the main thread may queue ahead of the Worker (bounds memory) */
const MAX_FRAMES_IN_FLIGHT = 2;

/** Where prepared frames go: a Worker, or gifenc in-thread */
interface GifSink {
  write(rgba: Uint8Array, width: number, height: number, delay: number, transfer: boolean): Promise<void>;
  finish(): Promise<Uint8Array>;
  /** Release resources after an error/abort */
  dispose(): void;
}

/** In-thread sink — used where Workers don't exist (tests, SSR) */
async function createInlineSink(opts: GifWriterOptions): Promise<GifSink> {
  const writer = createGifWriter(await getGifenc(), opts);
  let written = 0;
  return {
    async write(rgba, width, height, delay) {
      writer.write(rgba, width, height, delay);
      // Quantization is pure CPU work — yield so the progress bar can paint
      if (++written % YIELD_EVERY === 0) await yieldToEventLoop();
    },
    async finish() {
      return writer.finish();
    },
    dispose() {},
  };
}

/**
 * Worker sink: quantisation runs off the main thread (P5). Up to
 * {@link MAX_FRAMES_IN_FLIGHT} frames are queued so the main thread prepares
 * the next frame while the Worker quantises the current one.
 */
async function createWorkerSink(opts: GifWriterOptions): Promise<GifSink> {
  const worker = new Worker(new URL("./gif-encoder.worker.ts", import.meta.url), { type: "module" });
  /** Resolvers for outstanding replies, in message order */
  const waiting: Array<{ resolve: (msg: FromGifWorker) => void; reject: (e: Error) => void }> = [];
  let failure: Error | null = null;

  const fail = (err: Error) => {
    failure ??= err;
    while (waiting.length) waiting.shift()!.reject(err);
  };
  worker.onmessage = (e: MessageEvent<FromGifWorker>) => {
    if (e.data.type === "error") fail(new Error(e.data.message));
    else waiting.shift()?.resolve(e.data);
  };
  worker.onerror = (e) => fail(new Error(e.message ? `GIF worker error: ${e.message}` : "Encoder worker failed to load"));

  const request = (msg: ToGifWorker, transfer: Transferable[] = []) =>
    new Promise<FromGifWorker>((resolve, reject) => {
      if (failure) return reject(failure);
      waiting.push({ resolve, reject });
      worker.postMessage(msg, transfer);
    });

  const pending: Array<Promise<FromGifWorker>> = [];
  await request({ type: "init", maxColors: opts.maxColors, loops: opts.loops });

  return {
    async write(rgba, width, height, delay, transfer) {
      const owned = transfer && rgba.byteOffset === 0 && rgba.byteLength === rgba.buffer.byteLength;
      const buffer = (owned ? rgba : rgba.slice()).buffer as ArrayBuffer;
      pending.push(request({ type: "frame", rgba: buffer, width, height, delay }, [buffer]));
      if (pending.length >= MAX_FRAMES_IN_FLIGHT) await pending.shift();
    },
    async finish() {
      await Promise.all(pending.splice(0));
      const reply = await request({ type: "finish" });
      worker.terminate();
      if (reply.type !== "done") throw new Error("GIF worker returned no data");
      return new Uint8Array(reply.bytes);
    },
    dispose() {
      worker.terminate();
      // Unblock anything still awaiting a reply
      fail(new DOMException("The operation was aborted", "AbortError") as unknown as Error);
    },
  };
}

/**
 * Encode decoded frames into an animated GIF blob.
 * Pipeline per frame: subsample → crop/resize/rotate (one drawImage on the
 * main thread) → quantize + write (in a Worker). Frames are transformed one
 * at a time, so no transformed copy of the whole animation is held (H2).
 * @param frames - RGBA frames with per-frame delays
 * @param width - Source canvas width
 * @param height - Source canvas height
 * @param options - Encoding options
 * @param sourceFps - Original source FPS (for subsampling)
 * @param onProgress - Progress callback
 * @param signal - Aborting rejects with an AbortError at the next frame (M7)
 */
export async function encodeAnimatedGif(
  frames: DecodedFrame[],
  width: number,
  height: number,
  options: ConvertOptions,
  sourceFps: number,
  onProgress?: (p: ConvertProgress) => void,
  signal?: AbortSignal,
): Promise<Blob> {
  throwIfAborted(signal);

  // Subsample if target FPS is lower than source (keeps references, no copy)
  const workFrames = subsampleFrames(frames, sourceFps, options.targetFps);
  const preparer = new FramePreparer(width, height, transformFromOptions(options));
  const { outW, outH } = preparer.plan;
  const writerOptions: GifWriterOptions = {
    maxColors: qualityToMaxColors(options.quality),
    loops: options.loops,
  };

  const useWorker = typeof Worker !== "undefined" && typeof window !== "undefined";
  const sink = useWorker ? await createWorkerSink(writerOptions) : await createInlineSink(writerOptions);

  try {
    for (let i = 0; i < workFrames.length; i++) {
      throwIfAborted(signal);
      const source = workFrames[i]!;
      onProgress?.({
        phase: "encoding",
        percent: Math.round((i / workFrames.length) * 100),
        frame: i + 1,
        total: workFrames.length,
      });
      const prepared = preparer.fromRgba(source.rgba);
      await sink.write(prepared.rgba, outW, outH, outputDelay(source.delay), prepared.fresh);
    }
    throwIfAborted(signal);
    const bytes = await sink.finish();

    onProgress?.({
      phase: "encoding",
      percent: 100,
      frame: workFrames.length,
      total: workFrames.length,
    });
    return new Blob([bytes as BlobPart], { type: "image/gif" });
  } catch (e) {
    sink.dispose();
    throw e;
  }
}
