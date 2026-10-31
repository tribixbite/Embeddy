/**
 * AbortSignal helpers shared by every decoder/encoder in the Convert
 * pipeline, so cancellation behaves identically on every path (H1).
 */

/**
 * The error thrown/rejected when an AbortSignal cancels work: the signal's
 * own reason when one was set (matching `AbortSignal.throwIfAborted`),
 * otherwise a DOMException named "AbortError".
 */
export function abortError(signal: AbortSignal): unknown {
  return signal.reason ?? new DOMException("The operation was aborted", "AbortError");
}

/** Throw the signal's abort reason if it has been aborted; no-op otherwise. */
export function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) throw abortError(signal);
}

/**
 * True when a caught value is an abort-triggered rejection (a DOMException or
 * Error named "AbortError"). Callers swallow these instead of surfacing them.
 */
export function isAbortError(e: unknown): boolean {
  return typeof e === "object" && e !== null && (e as { name?: unknown }).name === "AbortError";
}
