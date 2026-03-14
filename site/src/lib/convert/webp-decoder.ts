/**
 * Decode animated/static WebP files into RGBA frames.
 * Primary: WebCodecs ImageDecoder API (reliable per-frame access).
 * Fallback: <img> + canvas timed capture for older browsers.
 */

import type { DecodedFrame, SourceInfo, ConvertProgress } from "./types";

/** Maximum capture duration in seconds to prevent runaway loops (canvas fallback) */
const MAX_CAPTURE_DURATION_S = 30;

/**
 * Decode a WebP file into RGBA frames.
 * Static WebP returns a single frame. Animated WebP extracts all frames.
 * @param file - WebP file
 * @param targetFps - Frames per second to capture (used only by canvas fallback)
 * @param maxFrames - Safety cap to prevent memory issues (default 300)
 */
export async function decodeWebP(
  file: File,
  targetFps = 10,
  maxFrames = 300,
  onProgress?: (p: ConvertProgress) => void,
): Promise<{ frames: DecodedFrame[]; info: SourceInfo }> {
  // Detect animation from RIFF header before choosing decode path
  const isAnimated = await detectAnimatedFromHeader(file);

  // Prefer ImageDecoder API — gives direct per-frame access with proper timing
  if (typeof ImageDecoder !== "undefined") {
    try {
      return await decodeWithImageDecoder(file, isAnimated, maxFrames, onProgress);
    } catch (e) {
      console.warn("ImageDecoder failed, falling back to canvas capture:", e);
    }
  }

  // Fallback: canvas capture (unreliable for animated WebP in some contexts)
  return decodeWithCanvas(file, isAnimated, targetFps, maxFrames, onProgress);
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
  isAnimated: boolean,
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

  const totalFrames = Math.min(track.frameCount, maxFrames);
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
    },
  };
}

// ---------------------------------------------------------------------------
// Canvas capture fallback — for browsers without ImageDecoder
// ---------------------------------------------------------------------------

/**
 * Decode WebP using <img> element drawn to canvas at timed intervals.
 * Unreliable for animated WebP when the browser isn't actively rendering
 * the image animation (e.g. background tabs, automated testing).
 */
async function decodeWithCanvas(
  file: File,
  isAnimated: boolean,
  targetFps: number,
  maxFrames: number,
  onProgress?: (p: ConvertProgress) => void,
): Promise<{ frames: DecodedFrame[]; info: SourceInfo }> {
  const url = URL.createObjectURL(file);

  try {
    const img = await loadImage(url);
    const { naturalWidth: width, naturalHeight: height } = img;
    if (!width || !height) throw new Error("Could not determine WebP dimensions");

    const canvas =
      typeof OffscreenCanvas !== "undefined"
        ? new OffscreenCanvas(width, height)
        : createFallbackCanvas(width, height);
    const ctx = canvas.getContext("2d") as
      | OffscreenCanvasRenderingContext2D
      | CanvasRenderingContext2D;
    if (!ctx) throw new Error("Could not create canvas context");

    // Capture first frame
    ctx.drawImage(img, 0, 0, width, height);
    const firstFrameData = ctx.getImageData(0, 0, width, height);
    const firstRgba = new Uint8Array(firstFrameData.data.buffer);

    if (!isAnimated) {
      onProgress?.({ phase: "decoding", percent: 100, frame: 1, total: 1 });
      return {
        frames: [{ rgba: firstRgba, delay: 0 }],
        info: { width, height, frameCount: 1, totalDuration: 0, fps: 0, format: "webp" },
      };
    }

    // Animated: capture frames at targetFps via timed intervals
    const delayMs = Math.round(1000 / targetFps);
    const frames: DecodedFrame[] = [{ rgba: firstRgba, delay: delayMs }];
    let totalDuration = delayMs;
    let duplicateCount = 0;
    const maxDuplicates = targetFps * 2; // 2s of identical frames → stop

    onProgress?.({ phase: "decoding", percent: 0, frame: 1, total: maxFrames });

    for (let i = 1; i < maxFrames; i++) {
      await sleep(delayMs);
      ctx.clearRect(0, 0, width, height);
      ctx.drawImage(img, 0, 0, width, height);
      const frameData = ctx.getImageData(0, 0, width, height);
      const rgba = new Uint8Array(frameData.data.buffer);

      if (framesEqual(rgba, frames[frames.length - 1].rgba)) {
        duplicateCount++;
        if (duplicateCount >= maxDuplicates) break;
      } else {
        duplicateCount = 0;
      }

      frames.push({ rgba, delay: delayMs });
      totalDuration += delayMs;

      onProgress?.({
        phase: "decoding",
        percent: Math.round((i / maxFrames) * 100),
        frame: i + 1,
        total: maxFrames,
      });

      if (totalDuration >= MAX_CAPTURE_DURATION_S * 1000) break;
    }

    // Trim trailing duplicate frames
    while (
      frames.length > 1 &&
      framesEqual(frames[frames.length - 1].rgba, frames[frames.length - 2].rgba)
    ) {
      frames.pop();
      totalDuration -= delayMs;
    }

    return {
      frames,
      info: { width, height, frameCount: frames.length, totalDuration, fps: targetFps, format: "webp" },
    };
  } finally {
    URL.revokeObjectURL(url);
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Load an image element and wait for it to be fully decoded */
function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error("Failed to load WebP image"));
    img.src = src;
  });
}

/**
 * Detect if a WebP is animated by parsing the RIFF container header.
 * Animated WebPs have a VP8X chunk where bit 1 of the flags byte is set.
 */
async function detectAnimatedFromHeader(file: File): Promise<boolean> {
  try {
    // VP8X chunk starts at byte 12: "RIFF" (4) + size (4) + "WEBP" (4)
    // VP8X chunk: "VP8X" (4) + chunk_size (4) + flags (4)
    // Animation flag is bit 1 (0x02) of the flags byte at offset 20
    const header = new Uint8Array(await file.slice(0, 21).arrayBuffer());
    const riff = String.fromCharCode(header[0], header[1], header[2], header[3]);
    const webp = String.fromCharCode(header[8], header[9], header[10], header[11]);
    if (riff === "RIFF" && webp === "WEBP") {
      const chunkId = String.fromCharCode(header[12], header[13], header[14], header[15]);
      if (chunkId === "VP8X") {
        return (header[20] & 0x02) !== 0;
      }
      // No VP8X chunk → simple lossy/lossless WebP, not animated
      return false;
    }
  } catch (_) {
    // Header parsing failed — assume not animated
  }
  return false;
}

/** Fast pixel-level equality check (samples every 100th pixel for speed) */
function framesEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i += 400) {
    if (a[i] !== b[i] || a[i + 1] !== b[i + 1] || a[i + 2] !== b[i + 2]) {
      return false;
    }
  }
  return true;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function createFallbackCanvas(w: number, h: number): HTMLCanvasElement {
  const c = document.createElement("canvas");
  c.width = w;
  c.height = h;
  return c;
}
