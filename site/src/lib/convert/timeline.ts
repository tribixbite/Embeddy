/**
 * Time-domain edits shared by every Convert path: trim range and playback
 * speed. Pure functions over frame delays / timestamps so the batch,
 * streaming and video-export pipelines agree on what "trim 2s–5s at 2x"
 * means. Times are milliseconds unless a name says otherwise.
 */

import type { DecodedFrame } from "./types";

/** Shortest trim span the UI allows (one 10 fps frame) */
export const MIN_TRIM_MS = 100;
/** Speed bounds offered by the UI */
export const MIN_SPEED = 0.25;
export const MAX_SPEED = 4;
/** Browsers clamp GIF delays under 20 ms to 100 ms — never emit less */
export const MIN_FRAME_DELAY_MS = 20;

/** A resolved trim window */
export interface TrimRange {
  startMs: number;
  endMs: number;
}

/**
 * Resolve a (possibly unset/out-of-range) trim against the source length.
 * `endMs <= 0` means "to the end". Keeps at least {@link MIN_TRIM_MS}.
 */
export function clampTrim(totalMs: number, startMs: number, endMs: number): TrimRange {
  if (totalMs <= 0) return { startMs: 0, endMs: 0 };
  const minSpan = Math.min(MIN_TRIM_MS, totalMs);
  const end = endMs > 0 ? Math.min(endMs, totalMs) : totalMs;
  const start = Math.max(0, Math.min(startMs, end - minSpan));
  return { startMs: start, endMs: Math.max(end, start + minSpan) };
}

/** True when the trim window covers the whole source */
export function isFullRange(totalMs: number, trim: TrimRange): boolean {
  return trim.startMs <= 0 && trim.endMs >= totalMs;
}

/**
 * Keep only frames that are visible within [startMs, endMs), clipping the
 * first and last frames' delays to the window. Frame pixel buffers are
 * shared, not copied. Always returns at least one frame for non-empty input.
 */
export function sliceFramesByTime(
  frames: DecodedFrame[],
  startMs: number,
  endMs: number,
): DecodedFrame[] {
  const out: DecodedFrame[] = [];
  let t = 0;
  for (const frame of frames) {
    const frameStart = t;
    const frameEnd = t + frame.delay;
    t = frameEnd;
    if (frameEnd <= startMs) continue;
    if (frameStart >= endMs) break;
    const visible = Math.min(frameEnd, endMs) - Math.max(frameStart, startMs);
    out.push({ rgba: frame.rgba, delay: Math.max(1, Math.round(visible)) });
  }
  if (out.length === 0 && frames.length > 0) {
    // Window fell past the end (rounding) — keep the last frame
    const last = frames[frames.length - 1]!;
    out.push({ rgba: last.rgba, delay: Math.max(1, Math.round(endMs - startMs)) });
  }
  return out;
}

/** Clamp a speed multiplier to the supported range (non-finite → 1). */
export function clampSpeed(speed: number): number {
  if (!Number.isFinite(speed) || speed <= 0) return 1;
  return Math.min(MAX_SPEED, Math.max(MIN_SPEED, speed));
}

/**
 * Play frames `speed` times faster by dividing every delay. Pixel buffers are
 * shared. Callers subsample afterwards against `sourceFps * speed`, so a 4x
 * speed-up drops frames instead of emitting sub-20 ms delays.
 */
export function retimeFrames(frames: DecodedFrame[], speed: number): DecodedFrame[] {
  const s = clampSpeed(speed);
  if (s === 1) return frames;
  return frames.map((f) => ({ rgba: f.rgba, delay: Math.max(1, Math.round(f.delay / s)) }));
}

/**
 * Source timestamps (seconds) to sample for a lazily decoded video: one per
 * output frame at `targetFps`, stepping `speed / targetFps` seconds of source
 * per output frame, within the trim window.
 */
export function sampleTimes(trim: TrimRange, targetFps: number, speed: number, maxFrames: number): number[] {
  const step = clampSpeed(speed) / targetFps;
  const startS = trim.startMs / 1000;
  const endS = trim.endMs / 1000;
  const times: number[] = [];
  // Always at least the first frame, even for a sub-step window
  for (let t = startS; times.length < maxFrames && (times.length === 0 || t < endS - 1e-6); t += step) {
    times.push(Math.round(t * 1e6) / 1e6);
  }
  return times;
}

/** Output delay for a frame, floored to what every viewer can display. */
export function outputDelay(delayMs: number): number {
  return Math.max(MIN_FRAME_DELAY_MS, Math.round(delayMs));
}

/**
 * Apply trim then speed to buffered frames. Returns the frames plus the
 * effective source frame rate to subsample against (speed multiplies it, so
 * the encoders' subsampling to the target FPS drops frames on speed-up).
 */
export function applyTimeline(
  frames: DecodedFrame[],
  sourceFps: number,
  totalMs: number,
  opts: { trimStartMs: number; trimEndMs: number; speed: number },
): { frames: DecodedFrame[]; sourceFps: number } {
  const trim = clampTrim(totalMs, opts.trimStartMs, opts.trimEndMs);
  const trimmed = totalMs > 0 && !isFullRange(totalMs, trim)
    ? sliceFramesByTime(frames, trim.startMs, trim.endMs)
    : frames;
  const speed = clampSpeed(opts.speed);
  return { frames: retimeFrames(trimmed, speed), sourceFps: sourceFps * speed };
}
