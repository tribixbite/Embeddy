/**
 * Decode animated/static WebP files into RGBA frames.
 * Primary: WebCodecs ImageDecoder API (reliable per-frame access).
 * Fallback: split the RIFF container and decode each frame as a still image —
 * used on Firefox, which ships no ImageDecoder.
 */

import type { DecodedFrame, SourceInfo, ConvertProgress } from "./types";
import { splitAnimatedWebP, type WebPFrame } from "./webp-frames";

/**
 * Decode a WebP file into RGBA frames.
 * Static WebP returns a single frame. Animated WebP extracts all frames.
 * @param file - WebP file
 * @param targetFps - Frames per second to capture (used only by canvas fallback)
 * @param maxFrames - Safety cap to prevent memory issues (default 1500, auto-reduced for high-res)
 */
export async function decodeWebP(
  file: File,
  maxFrames = 1500,
  onProgress?: (p: ConvertProgress) => void,
): Promise<{ frames: DecodedFrame[]; info: SourceInfo }> {
  // Prefer ImageDecoder API — gives direct per-frame access with proper timing
  if (typeof ImageDecoder !== "undefined") {
    try {
      return await decodeWithImageDecoder(file, maxFrames, onProgress);
    } catch (e) {
      console.warn("ImageDecoder failed, falling back to container split:", e);
    }
  }

  // Fallback (Firefox): split the container and decode each frame as a still.
  const buffer = await file.arrayBuffer();
  const frameSet = splitAnimatedWebP(buffer);
  if (frameSet) {
    return decodeFromContainer(frameSet, maxFrames, onProgress);
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
): Promise<{ frames: DecodedFrame[]; info: SourceInfo }> {
  const decoder = new ImageDecoder({
    // file.stream() typing mismatch between DOM lib and WebCodecs — safe cast
    data: file.stream() as unknown as ReadableStream<Uint8Array>,
    type: "image/webp",
  });

  // Wait for header to be parsed so we know frame count
  await decoder.tracks.ready;
  const track = decoder.tracks.selectedTrack;
  if (!track) throw new Error("No image track found in WebP");

  // Memory-aware cap applied after first frame dimensions are known
  const sourceFrames = track.frameCount;
  let totalFrames = Math.min(sourceFrames, maxFrames);
  onProgress?.({ phase: "decoding", percent: 0, frame: 0, total: totalFrames });

  // Decode all frames to get dimensions from first frame
  await decoder.completed.catch(() => {
    // Some decoders signal completion early for static images — ignore
  });

  const frames: DecodedFrame[] = [];
  let width = 0;
  let height = 0;
  let totalDuration = 0;

  for (let i = 0; i < totalFrames; i++) {
    const result = await decoder.decode({ frameIndex: i });
    const videoFrame = result.image;

    if (i === 0) {
      width = videoFrame.displayWidth;
      height = videoFrame.displayHeight;
      // Tighten frame cap based on actual dimensions (512 MB memory budget)
      const bytesPerFrame = width * height * 4;
      const memoryMax = Math.max(30, Math.floor((512 * 1024 * 1024) / bytesPerFrame));
      totalFrames = Math.min(totalFrames, memoryMax);
    }

    // Draw VideoFrame to canvas to extract RGBA pixels
    const canvas =
      typeof OffscreenCanvas !== "undefined"
        ? new OffscreenCanvas(width, height)
        : createFallbackCanvas(width, height);
    const ctx = canvas.getContext("2d") as
      | OffscreenCanvasRenderingContext2D
      | CanvasRenderingContext2D;
    if (!ctx) throw new Error("Could not create canvas context");

    ctx.drawImage(videoFrame, 0, 0, width, height);
    const imageData = ctx.getImageData(0, 0, width, height);
    const rgba = new Uint8Array(imageData.data.buffer);

    // Frame duration in microseconds → milliseconds
    // VideoFrame.duration is in microseconds; use 100ms default if missing
    const delayMs = videoFrame.duration
      ? Math.round(videoFrame.duration / 1000)
      : 100;

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

  decoder.close();

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
      frameCapped: frames.length < sourceFrames,
    },
  };
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
): Promise<{ frames: DecodedFrame[]; info: SourceInfo }> {
  const { width, height } = frameSet;

  // Same 512 MB RGBA budget the other decoders use
  const bytesPerFrame = width * height * 4;
  const memoryMax = Math.max(30, Math.floor((512 * 1024 * 1024) / bytesPerFrame));
  const limit = Math.min(frameSet.frames.length, maxFrames, memoryMax);

  const canvas =
    typeof OffscreenCanvas !== "undefined"
      ? new OffscreenCanvas(width, height)
      : createFallbackCanvas(width, height);
  const ctx = canvas.getContext("2d") as
    | OffscreenCanvasRenderingContext2D
    | CanvasRenderingContext2D;
  if (!ctx) throw new Error("Could not create canvas context");

  const frames: DecodedFrame[] = [];
  let totalDuration = 0;

  for (let i = 0; i < limit; i++) {
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

  const canvas =
    typeof OffscreenCanvas !== "undefined"
      ? new OffscreenCanvas(width, height)
      : createFallbackCanvas(width, height);
  const ctx = canvas.getContext("2d") as
    | OffscreenCanvasRenderingContext2D
    | CanvasRenderingContext2D;
  if (!ctx) throw new Error("Could not create canvas context");

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
      frameCapped: false,
    },
  };
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function createFallbackCanvas(w: number, h: number): HTMLCanvasElement {
  const c = document.createElement("canvas");
  c.width = w;
  c.height = h;
  return c;
}
