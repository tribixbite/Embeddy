/**
 * Runs Squoosh compression in a Web Worker (compress.worker.ts), keeping the
 * page responsive during WASM encodes and making Cancel immediate: aborting
 * terminates the Worker, which stops the encode mid-run instead of letting it
 * finish in the background.
 *
 * One Worker is kept warm between runs so codec WASM is compiled once. Where
 * module Workers are unavailable (old browsers, tests) or the Worker fails to
 * start, compression runs on the main thread instead — same result, just
 * blocking.
 */
import { abortError, throwIfAborted } from "../convert/abort";
import { compress, type CompressOptions } from "./compress";

/** Main thread → Worker */
export interface ToCompressWorker {
  id: number;
  file: File;
  options: CompressOptions;
}

/** Worker → main thread */
export type FromCompressWorker =
  | { id: number; type: "done"; blob: Blob }
  | { id: number; type: "error"; message: string };

/** Creates the Worker; injectable for tests */
export type WorkerFactory = () => Worker;

const defaultFactory: WorkerFactory = () =>
  new Worker(new URL("./compress.worker.ts", import.meta.url), { type: "module" });

let warm: Worker | null = null;
let nextId = 1;
/** Set once a Worker failed to start; later runs go straight to the main thread */
let workerUnavailable = false;

/** Drop the warm Worker (after abort/failure, or between tests) */
export function disposeCompressWorker(): void {
  warm?.terminate();
  warm = null;
}

/** Test hook: forget a previous Worker start failure */
export function resetCompressWorkerState(): void {
  disposeCompressWorker();
  workerUnavailable = false;
}

/**
 * Compress `file` off the main thread when possible.
 *
 * @param signal - Aborting terminates the encode and rejects with AbortError
 * @param factory - Worker constructor override (tests)
 */
export async function compressOffThread(
  file: File,
  options: CompressOptions,
  signal?: AbortSignal,
  factory: WorkerFactory = defaultFactory,
): Promise<Blob> {
  throwIfAborted(signal);
  if (typeof Worker === "undefined" || workerUnavailable) return compress(file, options);

  let worker: Worker;
  try {
    worker = warm ??= factory();
  } catch {
    workerUnavailable = true;
    return compress(file, options);
  }

  const id = nextId++;
  // `$state` proxies aren't structured-cloneable — send a plain copy
  const plain: CompressOptions = { ...options };
  const outcome = await new Promise<{ blob: Blob } | { failed: Error } | { startError: true }>((resolve, reject) => {
    const cleanup = () => {
      worker.removeEventListener("message", onMessage);
      worker.removeEventListener("error", onError);
      signal?.removeEventListener("abort", onAbort);
    };
    const onMessage = (e: MessageEvent<FromCompressWorker>) => {
      if (e.data.id !== id) return;
      cleanup();
      resolve(e.data.type === "done" ? { blob: e.data.blob } : { failed: new Error(e.data.message) });
    };
    // An `error` event here means the Worker script itself failed (load or
    // uncaught throw) — compress() errors arrive as messages instead
    const onError = (e: Event) => {
      e.preventDefault();
      cleanup();
      resolve({ startError: true });
    };
    const onAbort = () => {
      cleanup();
      disposeCompressWorker();
      reject(abortError(signal!));
    };
    worker.addEventListener("message", onMessage);
    worker.addEventListener("error", onError);
    signal?.addEventListener("abort", onAbort, { once: true });
    worker.postMessage({ id, file, options: plain } satisfies ToCompressWorker);
  });

  if ("blob" in outcome) return outcome.blob;
  if ("failed" in outcome) throw outcome.failed;
  // Worker unusable here — remember that and do the work in-thread
  disposeCompressWorker();
  workerUnavailable = true;
  throwIfAborted(signal);
  return compress(file, options);
}
