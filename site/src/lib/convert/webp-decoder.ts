/**
 * Decode animated/static WebP files into RGBA frames.
 * Uses <img> element + canvas capture (video-style approach).
 * Animated WebP frames are captured at a target FPS since browsers
 * don't expose per-frame access for animated WebP.
 */

import type { DecodedFrame, SourceInfo, ConvertProgress } from "./types";

/** Maximum capture duration in seconds to prevent runaway loops */
const MAX_CAPTURE_DURATION_S = 30;

/**
 * Decode a WebP file into RGBA frames.
 * Static WebP returns a single frame. Animated WebP is captured at targetFps.
 * @param file - WebP file
 * @param targetFps - Frames per second to capture (default 10)
 * @param maxFrames - Safety cap to prevent memory issues (default 300)
 */
export async function decodeWebP(
  file: File,
  targetFps = 10,
  maxFrames = 300,
  onProgress?: (p: ConvertProgress) => void,
): Promise<{ frames: DecodedFrame[]; info: SourceInfo }> {
  const url = URL.createObjectURL(file);

  try {
    // Load image to get dimensions
    const img = await loadImage(url);
    const { naturalWidth: width, naturalHeight: height } = img;
    if (!width || !height) throw new Error("Could not determine WebP dimensions");

    // Canvas for frame capture
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

    // Detect animation via RIFF header (VP8X flags), pixel-comparison fallback
    const isAnimated = await detectAnimated(file, img, ctx, firstRgba, width, height);

    if (!isAnimated) {
      // Static WebP — single frame
      onProgress?.({ phase: "decoding", percent: 100, frame: 1, total: 1 });
      return {
        frames: [{ rgba: firstRgba, delay: 0 }],
        info: {
          width,
          height,
          frameCount: 1,
          totalDuration: 0,
          fps: 0,
          format: "webp",
        },
      };
    }

    // Animated WebP — capture frames at targetFps using timed intervals
    const delayMs = Math.round(1000 / targetFps);
    const frames: DecodedFrame[] = [{ rgba: firstRgba, delay: delayMs }];
    let totalDuration = delayMs;
    let duplicateCount = 0;
    // Stop after 2s of identical frames (animation loop completed)
    const maxDuplicates = targetFps * 2;

    onProgress?.({ phase: "decoding", percent: 0, frame: 1, total: maxFrames });

    for (let i = 1; i < maxFrames; i++) {
      // Wait one frame interval
      await sleep(delayMs);

      // Capture current frame
      ctx.clearRect(0, 0, width, height);
      ctx.drawImage(img, 0, 0, width, height);
      const frameData = ctx.getImageData(0, 0, width, height);
      const rgba = new Uint8Array(frameData.data.buffer);

      // Check if frame is identical to previous (animation may have ended/looped)
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

      // Safety timeout
      if (totalDuration >= MAX_CAPTURE_DURATION_S * 1000) break;
    }

    // Trim trailing duplicate frames (from the loop detection overshoot)
    while (
      frames.length > 1 &&
      framesEqual(frames[frames.length - 1].rgba, frames[frames.length - 2].rgba)
    ) {
      frames.pop();
      totalDuration -= delayMs;
    }

    return {
      frames,
      info: {
        width,
        height,
        frameCount: frames.length,
        totalDuration,
        fps: targetFps,
        format: "webp",
      },
    };
  } finally {
    URL.revokeObjectURL(url);
  }
}

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
 * Falls back to pixel-comparison heuristic if header parsing fails.
 */
async function detectAnimated(
  file: File,
  img: HTMLImageElement,
  ctx: CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D,
  firstRgba: Uint8Array,
  width: number,
  height: number,
): Promise<boolean> {
  // Primary: parse RIFF header for VP8X animation flag
  try {
    // VP8X chunk starts at byte 12 if present: "RIFF" (4) + size (4) + "WEBP" (4)
    // VP8X chunk: "VP8X" (4) + chunk_size (4) + flags (4) — animation is bit 1 of flags
    const header = new Uint8Array(await file.slice(0, 21).arrayBuffer());
    // Verify RIFF + WEBP signature
    const riff = String.fromCharCode(header[0], header[1], header[2], header[3]);
    const webp = String.fromCharCode(header[8], header[9], header[10], header[11]);
    if (riff === "RIFF" && webp === "WEBP") {
      const chunkId = String.fromCharCode(header[12], header[13], header[14], header[15]);
      if (chunkId === "VP8X") {
        // Flags byte is at offset 20 (after VP8X + chunk_size)
        const flags = header[20];
        // Bit 1 (0x02) = animation flag
        return (flags & 0x02) !== 0;
      }
      // No VP8X chunk means simple lossy/lossless WebP — not animated
      return false;
    }
  } catch (_) {
    // Header parsing failed, fall through to pixel heuristic
  }

  // Fallback: pixel-comparison heuristic (less reliable, esp. in headless browsers)
  await sleep(150);
  ctx.clearRect(0, 0, width, height);
  ctx.drawImage(img, 0, 0, width, height);
  const secondFrame = ctx.getImageData(0, 0, width, height);
  return !framesEqual(firstRgba, new Uint8Array(secondFrame.data.buffer));
}

/** Fast pixel-level equality check (samples every 100th pixel for speed) */
function framesEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  // Sample every 100th pixel (400 bytes apart in RGBA)
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
