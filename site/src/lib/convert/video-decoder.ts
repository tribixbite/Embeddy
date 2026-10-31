/**
 * Extract frames from a video file using <video> + canvas.
 * Supports two modes:
 *  1. Batch decode: extract all frames into memory (small/medium inputs)
 *  2. Probe-only: extract metadata without decoding pixels (for streaming pipeline)
 */

import type { DecodedFrame, SourceInfo, ConvertProgress } from "./types";
import { abortError, isAbortError, throwIfAborted } from "./abort";
import { FramePreparer, createCanvas2d, getContext2d, type FrameTransform } from "./prepare-frame";

// Re-exported for existing callers/tests that import them from here
export { isAbortError, throwIfAborted };

/**
 * Number of frames extractable from a video of the given duration at a
 * target frame rate. Never less than 1 — a sub-frame-interval video
 * (e.g. 0.05 s at 10 fps) still yields its t=0 frame, which the seek
 * path already handles; flooring to 0 left a dead Convert button.
 * Exported for testing.
 */
export function availableFrameCount(durationSeconds: number, targetFps: number): number {
  return Math.max(1, Math.floor(durationSeconds * targetFps));
}

/**
 * Decode a video file into RGBA frames by stepping through with seek.
 * @param file - Video file (MP4, WebM, etc.)
 * @param targetFps - Frames per second to extract (default 10)
 * @param maxFrames - Safety cap to prevent memory issues (default 1500, auto-reduced for high-res)
 * @param onProgress - Progress callback
 * @param signal - Optional AbortSignal; aborting rejects promptly (even mid-seek)
 *   with an AbortError and releases the video element + object URL
 * @param times - Explicit source timestamps (seconds) to sample instead of
 *   every 1/targetFps from 0 — used for trim + speed (see timeline.ts). Each
 *   frame still gets a 1000/targetFps delay.
 */
export async function decodeVideo(
  file: File,
  targetFps = 10,
  maxFrames = 1500,
  onProgress?: (p: ConvertProgress) => void,
  signal?: AbortSignal,
  times?: number[],
): Promise<{ frames: DecodedFrame[]; info: SourceInfo }> {
  throwIfAborted(signal);
  const url = URL.createObjectURL(file);
  const video = document.createElement("video");

  try {
    video.muted = true;
    video.playsInline = true;
    video.preload = "auto";
    video.src = url;

    // Wait for metadata (duration, dimensions)
    await waitForMetadata(video, signal);

    const { videoWidth: width, videoHeight: height, duration } = video;
    if (!width || !height) throw new Error("Could not determine video dimensions");
    if (!duration || !isFinite(duration)) throw new Error("Could not determine video duration");

    // Memory-aware frame cap: limit total RGBA data to ~512 MB
    // Each frame = width * height * 4 bytes (RGBA)
    const bytesPerFrame = width * height * 4;
    const memoryBudget = 512 * 1024 * 1024; // 512 MB
    const memoryMaxFrames = Math.max(30, Math.floor(memoryBudget / bytesPerFrame));

    const frameInterval = 1 / targetFps;
    const availableFrames = times ? times.length : availableFrameCount(duration, targetFps);
    const totalFrames = Math.min(availableFrames, maxFrames, memoryMaxFrames);
    const delayMs = Math.round(1000 / targetFps);

    // Canvas for frame capture
    const ctx = getContext2d(createCanvas2d(width, height), true);

    // Frame 0 seeks to t=0, which never fires 'seeked' — make sure pixels exist first
    await waitForFrameData(video, signal);

    const frames: DecodedFrame[] = [];

    for (let i = 0; i < totalFrames; i++) {
      // Abort between frames too — the synchronous draw/getImageData stretch
      // below never observes the signal on its own
      throwIfAborted(signal);
      const seekTime = times ? Math.min(times[i]!, duration) : i * frameInterval;

      onProgress?.({
        phase: "decoding",
        percent: Math.round((i / totalFrames) * 100),
        frame: i + 1,
        total: totalFrames,
      });

      // Seek to target time and wait for frame
      await seekTo(video, seekTime, signal);

      // Draw video frame to canvas and extract RGBA
      ctx.drawImage(video, 0, 0, width, height);
      const imageData = ctx.getImageData(0, 0, width, height);
      frames.push({
        rgba: new Uint8Array(imageData.data.buffer),
        delay: delayMs,
      });
    }

    return {
      frames,
      info: {
        width,
        height,
        frameCount: frames.length,
        totalDuration: Math.round(duration * 1000),
        fps: targetFps,
        format: "video",
        loopCount: 0, // videos carry no loop metadata — default to looping forever
        // The memory budget can silently cut a long/high-res video short —
        // surface it so the settings panel can warn instead of quietly truncating.
        frameCapped: totalFrames < availableFrames,
      },
    };
  } finally {
    detachVideo(video);
    URL.revokeObjectURL(url);
  }
}

/**
 * Wait for the video's metadata (duration, dimensions) to load.
 * Racing against the signal means an abort rejects immediately instead of
 * waiting on a load that may never finish.
 */
function waitForMetadata(video: HTMLVideoElement, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(abortError(signal));
      return;
    }
    const onAbort = () => reject(abortError(signal!));
    const settle = () => signal?.removeEventListener("abort", onAbort);
    video.onloadedmetadata = () => {
      settle();
      resolve();
    };
    video.onerror = () => {
      settle();
      reject(new Error("Failed to load video"));
    };
    signal?.addEventListener("abort", onAbort, { once: true });
  });
}

/**
 * Wait until the video has decoded pixel data available for the current position.
 *
 * `loadedmetadata` only guarantees readyState >= HAVE_METADATA (1). Calling
 * drawImage() at that point is a silent no-op, which produced a blank first frame
 * because seekTo(0) short-circuits (currentTime is already 0, so no 'seeked' fires).
 */
function waitForFrameData(video: HTMLVideoElement, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(abortError(signal));
      return;
    }
    if (video.readyState >= 2 /* HAVE_CURRENT_DATA */) {
      resolve();
      return;
    }
    const cleanup = () => {
      video.removeEventListener("loadeddata", onLoaded);
      video.removeEventListener("error", onError);
      signal?.removeEventListener("abort", onAbort);
    };
    const onLoaded = () => {
      cleanup();
      resolve();
    };
    const onError = () => {
      cleanup();
      reject(new Error("Failed to decode video data"));
    };
    const onAbort = () => {
      cleanup();
      reject(abortError(signal!));
    };
    video.addEventListener("loadeddata", onLoaded);
    video.addEventListener("error", onError);
    signal?.addEventListener("abort", onAbort, { once: true });
  });
}

/**
 * Seek the video to a specific time and wait for the frame to be available.
 * Short-circuits if the video is already at the target time (HTML5 spec
 * won't fire 'seeked' event if currentTime matches the requested time).
 * An abort rejects immediately — it does not wait for the next 'seeked' event.
 */
function seekTo(video: HTMLVideoElement, time: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(abortError(signal));
      return;
    }
    // If already at target time, browser won't fire 'seeked' — resolve immediately
    if (Math.abs(video.currentTime - time) < 0.001) {
      resolve();
      return;
    }

    const cleanup = () => {
      video.removeEventListener("seeked", onSeeked);
      video.removeEventListener("error", onError);
      signal?.removeEventListener("abort", onAbort);
    };
    const onSeeked = () => {
      cleanup();
      resolve();
    };
    const onError = () => {
      cleanup();
      reject(new Error(`Seek to ${time}s failed`));
    };
    const onAbort = () => {
      cleanup();
      reject(abortError(signal!));
    };
    video.addEventListener("seeked", onSeeked);
    video.addEventListener("error", onError);
    signal?.addEventListener("abort", onAbort, { once: true });
    video.currentTime = time;
  });
}

/** Detach video source and halt background decoding before URL revocation */
function detachVideo(video: HTMLVideoElement) {
  video.removeAttribute("src");
  video.load();
}


/**
 * Probe a video file for metadata without decoding any frames.
 * Returns SourceInfo with width, height, duration, estimated frame count.
 */
export async function probeVideo(
  file: File,
  targetFps = 10,
  signal?: AbortSignal,
): Promise<{ info: SourceInfo; videoUrl: string }> {
  throwIfAborted(signal);
  const url = URL.createObjectURL(file);

  const video = document.createElement("video");
  let frameCount: number;
  let width: number;
  let height: number;
  let duration: number;
  try {
    video.muted = true;
    video.playsInline = true;
    video.preload = "metadata";
    video.src = url;

    await waitForMetadata(video, signal);

    ({ videoWidth: width, videoHeight: height, duration } = video);
    if (!width || !height) throw new Error("Could not determine video dimensions");
    if (!duration || !isFinite(duration)) throw new Error("Could not determine video duration");

    frameCount = availableFrameCount(duration, targetFps);
  } catch (e) {
    // On abort (or any probe failure) nothing is handed to the caller, so the
    // object URL and decoder must be released here
    detachVideo(video);
    URL.revokeObjectURL(url);
    throw e;
  }

  // Release the decoder now that metadata is read. The object URL stays valid —
  // the caller reuses it for the crop preview and owns revoking it.
  detachVideo(video);

  return {
    info: {
      width,
      height,
      frameCount,
      totalDuration: Math.round(duration * 1000),
      fps: targetFps,
      format: "video",
      loopCount: 0, // videos carry no loop metadata — default to looping forever
      frameCapped: false,
    },
    // Caller must call URL.revokeObjectURL(videoUrl) when done
    videoUrl: url,
  };
}

/** Options for the streaming decode pipeline */
export interface StreamDecodeOptions {
  targetFps: number;
  maxFrames: number;
  /** Crop/resize/rotate/flip applied to every frame in one drawImage */
  transform: FrameTransform;
  /**
   * Explicit source timestamps (seconds) to sample — trim + speed (see
   * timeline.ts). Defaults to every 1/targetFps from 0.
   */
  times?: number[];
}

/**
 * Async generator that decodes video frames one at a time.
 * Each yielded frame is already transformed (crop/resize/rotate/flip) by a
 * single drawImage straight from the <video> element — no full-resolution
 * readback followed by JS-side crop/resize (P2). Only one frame of RGBA data
 * exists at a time.
 *
 * @param file - Video file
 * @param options - Decode/process options
 * @param onProgress - Progress callback
 * @param signal - Optional AbortSignal, honored between yielded frames and
 *   inside seek/metadata waits; aborting throws an AbortError out of the
 *   generator, whose finally block releases the video element + object URL
 * @yields Processed RGBA frames with dimensions and delay
 */
export async function* streamDecodeVideo(
  file: File,
  options: StreamDecodeOptions,
  onProgress?: (p: ConvertProgress) => void,
  signal?: AbortSignal,
): AsyncGenerator<{
  rgba: Uint8Array;
  width: number;
  height: number;
  delay: number;
}> {
  throwIfAborted(signal);
  const url = URL.createObjectURL(file);
  const video = document.createElement("video");

  try {
    video.muted = true;
    video.playsInline = true;
    video.preload = "auto";
    video.src = url;

    await waitForMetadata(video, signal);

    const { videoWidth: srcW, videoHeight: srcH, duration } = video;
    if (!srcW || !srcH) throw new Error("Could not determine video dimensions");
    if (!duration || !isFinite(duration)) throw new Error("Could not determine video duration");

    const frameInterval = 1 / options.targetFps;
    const totalFrames = Math.min(
      options.times ? options.times.length : availableFrameCount(duration, options.targetFps),
      options.maxFrames,
    );
    const delayMs = Math.round(1000 / options.targetFps);
    // Identity transforms still need a readback canvas, so always draw
    const preparer = new FramePreparer(srcW, srcH, options.transform);

    // Frame 0 seeks to t=0, which never fires 'seeked' — make sure pixels exist first
    await waitForFrameData(video, signal);

    for (let i = 0; i < totalFrames; i++) {
      // Checked on every resume from yield — an abort while the consumer was
      // encoding stops the decode before the next seek
      throwIfAborted(signal);

      onProgress?.({
        phase: "decoding",
        percent: Math.round((i / totalFrames) * 100),
        frame: i + 1,
        total: totalFrames,
      });

      const seekTime = options.times ? Math.min(options.times[i]!, duration) : i * frameInterval;
      await seekTo(video, seekTime, signal);

      const { rgba, width, height } = preparer.fromImage(video);
      yield { rgba, width, height, delay: delayMs };
    }
  } finally {
    detachVideo(video);
    URL.revokeObjectURL(url);
  }
}
