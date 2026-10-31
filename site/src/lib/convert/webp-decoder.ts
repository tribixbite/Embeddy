/**
 * Decode animated/static WebP files into RGBA frames.
 * Primary: WebCodecs ImageDecoder API (reliable per-frame access).
 * Fallback: split the RIFF container and decode each frame as a still image —
 * used on Firefox, which ships no ImageDecoder.
 */

import type { DecodedFrame, SourceInfo, ConvertProgress } from "./types";
import { splitAnimatedWebP, type WebPFrame } from "./webp-frames";
import { isAbortError, throwIfAborted } from "./abort";
import { createCanvas2d, getContext2d } from "./prepare-frame";

/** Same RGBA budget the other decoders use (512 MB). */
const MEMORY_BUDGET_BYTES = 512 * 1024 * 1024;

/**
 * Map a WebCodecs ImageTrack.repetitionCount to our loop-count convention
 * (0 = loop forever, N = play N times). WebCodecs reports Infinity for
 * loop-forever and 0 for play-once. Exported for testing.
 */
export function repetitionCountToLoops(repetitionCount: number): number {
  if (!Number.isFinite(repetitionCount)) return 0;
  if (repetitionCount <= 0) return 1;
  return Math.round(repetitionCount);
}

/**
 * Decode a WebP file into RGBA frames.
 * Static WebP returns a single frame. Animated WebP extracts all frames.
 * @param file - WebP file
 * @param maxFrames - Safety cap to prevent memory issues (default 1500, auto-reduced for high-res)
 * @param onProgress - Per-frame progress callback
 * @param signal - Aborting rejects with an AbortError between frames
 */
export async function decodeWebP(
  file: File,
  maxFrames = 1500,
  onProgress?: (p: ConvertProgress) => void,
  signal?: AbortSignal,
): Promise<{ frames: DecodedFrame[]; info: SourceInfo }> {
  throwIfAborted(signal);
  // Prefer ImageDecoder API — gives direct per-frame access with proper timing
  if (typeof ImageDecoder !== "undefined") {
    try {
      return await decodeWithImageDecoder(file, maxFrames, onProgress, signal);
    } catch (e) {
      // A cancel is not a decoder failure — don't fall back and decode again
      if (isAbortError(e)) throw e;
      console.warn("ImageDecoder failed, falling back to container split:", e);
    }
  }

  // Fallback (Firefox): split the container and decode each frame as a still.
  const buffer = await file.arrayBuffer();
  const frameSet = splitAnimatedWebP(buffer);
  if (frameSet) {
    return decodeFromContainer(frameSet, maxFrames, onProgress, signal);
  }

  // Not animated (or unparseable) — decode as a single still image
  return decodeStill(file, onProgress);
}

// ---------------------------------------------------------------------------
// ImageDecoder path — reliable per-frame extraction
// ---------------------------------------------------------------------------

/**
 * Decode WebP using the WebCodecs ImageDecoder API.
 * Each frame is decoded individually with its native delay preserved.
 */
async function decodeWithImageDecoder(
  file: File,
  maxFrames: number,
  onProgress?: (p: ConvertProgress) => void,
  signal?: AbortSignal,
): Promise<{ frames: DecodedFrame[]; info: SourceInfo }> {
  const decoder = new ImageDecoder({
    // file.stream() typing mismatch between DOM lib and WebCodecs — safe cast
    data: file.stream() as unknown as ReadableStream<Uint8Array>,
    type: "image/webp",
  });

  try {
    await decoder.tracks.ready;
    const track = decoder.tracks.selectedTrack;
    if (!track) throw new Error("No image track found in WebP");

    // With streamed input, `frameCount` only counts frames parsed SO FAR and
    // grows as data arrives — reading it before `completed` could truncate
    // the animation (M1). Wait for the whole file, then read the count.
    await decoder.completed.catch(() => {
      // Some decoders signal completion early for static images — ignore
    });
    throwIfAborted(signal);
    const sourceFrames = track.frameCount;
    let totalFrames = Math.min(sourceFrames, maxFrames);
    onProgress?.({ phase: "decoding", percent: 0, frame: 0, total: totalFrames });

    const frames: DecodedFrame[] = [];
    let width = 0;
    let height = 0;
    let totalDuration = 0;
    // One canvas for the whole decode, created once the frame size is known
    let ctx: ReturnType<typeof getContext2d> | null = null;

    for (let i = 0; i < totalFrames; i++) {
      throwIfAborted(signal);
      const { image: videoFrame } = await decoder.decode({ frameIndex: i });

      if (i === 0) {
        width = videoFrame.displayWidth;
        height = videoFrame.displayHeight;
        // Tighten the frame cap now the real frame size is known
        const memoryMax = Math.max(30, Math.floor(MEMORY_BUDGET_BYTES / (width * height * 4)));
        totalFrames = Math.min(totalFrames, memoryMax);
        ctx = getContext2d(createCanvas2d(width, height), true);
      }

      // Each frame is a full composited canvas — clear so alpha doesn't stack
      ctx!.clearRect(0, 0, width, height);
      ctx!.drawImage(videoFrame, 0, 0, width, height);
      const rgba = new Uint8Array(ctx!.getImageData(0, 0, width, height).data.buffer);

      // VideoFrame.duration is in microseconds; 100 ms default when missing
      const delayMs = videoFrame.duration ? Math.round(videoFrame.duration / 1000) : 100;
      videoFrame.close();

      frames.push({ rgba, delay: delayMs });
      totalDuration += delayMs;

      onProgress?.({
        phase: "decoding",
        percent: Math.round(((i + 1) / totalFrames) * 100),
        frame: i + 1,
        total: totalFrames,
      });
    }

    const fps =
      frames.length > 1 && totalDuration > 0
        ? Math.round((frames.length / totalDuration) * 1000)
        : 0;

    return {
      frames,
      info: {
        width,
        height,
        frameCount: frames.length,
        totalDuration,
        fps,
        format: "webp",
        loopCount: repetitionCountToLoops(track.repetitionCount),
        frameCapped: frames.length < sourceFrames,
      },
    };
  } finally {
    // Released on success, failure and abort alike — previously a mid-loop
    // throw leaked the decoder
    decoder.close();
  }
}

// ---------------------------------------------------------------------------
// Container-split fallback — for browsers without ImageDecoder (Firefox)
// ---------------------------------------------------------------------------

/**
 * Composite frames extracted from the RIFF container.
 *
 * Each ANMF frame is decoded independently as a still image, then drawn onto a
 * persistent canvas honouring its offset, blend method and disposal method —
 * the same model GIF uses. Frame timing comes from the container, so nothing
 * depends on the browser actually animating an <img>.
 */
async function decodeFromContainer(
  frameSet: NonNullable<ReturnType<typeof splitAnimatedWebP>>,
  maxFrames: number,
  onProgress?: (p: ConvertProgress) => void,
  signal?: AbortSignal,
): Promise<{ frames: DecodedFrame[]; info: SourceInfo }> {
  const { width, height } = frameSet;

  // Same 512 MB RGBA budget the other decoders use
  const bytesPerFrame = width * height * 4;
  const memoryMax = Math.max(30, Math.floor(MEMORY_BUDGET_BYTES / bytesPerFrame));
  const limit = Math.min(frameSet.frames.length, maxFrames, memoryMax);

  const ctx = getContext2d(createCanvas2d(width, height), true);

  const frames: DecodedFrame[] = [];
  let totalDuration = 0;

  for (let i = 0; i < limit; i++) {
    throwIfAborted(signal);
    const frame: WebPFrame = frameSet.frames[i]!;

    onProgress?.({
      phase: "decoding",
      percent: Math.round((i / limit) * 100),
      frame: i + 1,
      total: limit,
    });

    const bitmap = await createImageBitmap(
      new Blob([frame.bytes as unknown as BlobPart], { type: "image/webp" }),
    );

    // "replace" overwrites the region outright; "blend" composites with alpha
    if (frame.blend === "replace") {
      ctx.clearRect(frame.x, frame.y, frame.width, frame.height);
    }
    ctx.drawImage(bitmap, frame.x, frame.y);
    bitmap.close();

    const composited = ctx.getImageData(0, 0, width, height);
    // WebP frame durations of 0 render as fast as possible; match the 20ms
    // floor the GIF decoder applies so downstream FPS math stays sane.
    const delay = Math.max(frame.duration, 20);
    frames.push({ rgba: new Uint8Array(composited.data.buffer), delay });
    totalDuration += delay;

    if (frame.dispose === "background") {
      ctx.clearRect(frame.x, frame.y, frame.width, frame.height);
    }
  }

  const fps =
    frames.length > 1 && totalDuration > 0
      ? Math.round((frames.length / totalDuration) * 1000)
      : 0;

  return {
    frames,
    info: {
      width,
      height,
      frameCount: frames.length,
      totalDuration,
      fps,
      format: "webp",
      // ANIM chunk semantics match ours directly: 0 = forever, N = N times
      loopCount: frameSet.loopCount,
      frameCapped: limit < frameSet.frames.length,
    },
  };
}

/** Decode a non-animated WebP as a single frame. */
async function decodeStill(
  file: File,
  onProgress?: (p: ConvertProgress) => void,
): Promise<{ frames: DecodedFrame[]; info: SourceInfo }> {
  const bitmap = await createImageBitmap(file);
  const { width, height } = bitmap;

  const ctx = getContext2d(createCanvas2d(width, height), true);

  ctx.drawImage(bitmap, 0, 0);
  bitmap.close();

  const imageData = ctx.getImageData(0, 0, width, height);
  onProgress?.({ phase: "decoding", percent: 100, frame: 1, total: 1 });

  return {
    frames: [{ rgba: new Uint8Array(imageData.data.buffer), delay: 0 }],
    info: {
      width,
      height,
      frameCount: 1,
      totalDuration: 0,
      fps: 0,
      format: "webp",
      loopCount: 0, // a still image has no animation — loop value is moot
      frameCapped: false,
    },
  };
}

