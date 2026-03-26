/**
 * Extract frames from a video file using <video> + canvas.
 * Supports two modes:
 *  1. Batch decode: extract all frames into memory (small/medium inputs)
 *  2. Probe-only: extract metadata without decoding pixels (for streaming pipeline)
 */

import type { DecodedFrame, SourceInfo, ConvertProgress, CropRect } from "./types";
import { cropFrame, resizeFrame } from "./encoder";

/**
 * Decode a video file into RGBA frames by stepping through with seek.
 * @param file - Video file (MP4, WebM, etc.)
 * @param targetFps - Frames per second to extract (default 10)
 * @param maxFrames - Safety cap to prevent memory issues (default 1500, auto-reduced for high-res)
 */
export async function decodeVideo(
  file: File,
  targetFps = 10,
  maxFrames = 1500,
  onProgress?: (p: ConvertProgress) => void,
): Promise<{ frames: DecodedFrame[]; info: SourceInfo }> {
  const url = URL.createObjectURL(file);

  try {
    const video = document.createElement("video");
    video.muted = true;
    video.playsInline = true;
    video.preload = "auto";
    video.src = url;

    // Wait for metadata (duration, dimensions)
    await new Promise<void>((resolve, reject) => {
      video.onloadedmetadata = () => resolve();
      video.onerror = () => reject(new Error("Failed to load video"));
    });

    const { videoWidth: width, videoHeight: height, duration } = video;
    if (!width || !height) throw new Error("Could not determine video dimensions");
    if (!duration || !isFinite(duration)) throw new Error("Could not determine video duration");

    // Memory-aware frame cap: limit total RGBA data to ~512 MB
    // Each frame = width * height * 4 bytes (RGBA)
    const bytesPerFrame = width * height * 4;
    const memoryBudget = 512 * 1024 * 1024; // 512 MB
    const memoryMaxFrames = Math.max(30, Math.floor(memoryBudget / bytesPerFrame));

    const frameInterval = 1 / targetFps;
    const totalFrames = Math.min(Math.floor(duration * targetFps), maxFrames, memoryMaxFrames);
    const delayMs = Math.round(1000 / targetFps);

    // Canvas for frame capture
    const canvas =
      typeof OffscreenCanvas !== "undefined"
        ? new OffscreenCanvas(width, height)
        : createFallbackCanvas(width, height);
    const ctx = canvas.getContext("2d") as
      | OffscreenCanvasRenderingContext2D
      | CanvasRenderingContext2D;
    if (!ctx) throw new Error("Could not create canvas context");

    const frames: DecodedFrame[] = [];

    for (let i = 0; i < totalFrames; i++) {
      const seekTime = i * frameInterval;

      onProgress?.({
        phase: "decoding",
        percent: Math.round((i / totalFrames) * 100),
        frame: i + 1,
        total: totalFrames,
      });

      // Seek to target time and wait for frame
      await seekTo(video, seekTime);

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
      },
    };
  } finally {
    URL.revokeObjectURL(url);
  }
}

/** Seek the video to a specific time and wait for the frame to be available */
function seekTo(video: HTMLVideoElement, time: number): Promise<void> {
  return new Promise((resolve, reject) => {
    const onSeeked = () => {
      video.removeEventListener("seeked", onSeeked);
      video.removeEventListener("error", onError);
      resolve();
    };
    const onError = () => {
      video.removeEventListener("seeked", onSeeked);
      video.removeEventListener("error", onError);
      reject(new Error(`Seek to ${time}s failed`));
    };
    video.addEventListener("seeked", onSeeked);
    video.addEventListener("error", onError);
    video.currentTime = time;
  });
}

/** Create a DOM <canvas> fallback for environments without OffscreenCanvas */
function createFallbackCanvas(w: number, h: number): HTMLCanvasElement {
  const c = document.createElement("canvas");
  c.width = w;
  c.height = h;
  return c;
}

/**
 * Probe a video file for metadata without decoding any frames.
 * Returns SourceInfo with width, height, duration, estimated frame count.
 */
export async function probeVideo(
  file: File,
  targetFps = 10,
): Promise<{ info: SourceInfo; videoUrl: string }> {
  const url = URL.createObjectURL(file);

  const video = document.createElement("video");
  video.muted = true;
  video.playsInline = true;
  video.preload = "metadata";
  video.src = url;

  await new Promise<void>((resolve, reject) => {
    video.onloadedmetadata = () => resolve();
    video.onerror = () => reject(new Error("Failed to load video"));
  });

  const { videoWidth: width, videoHeight: height, duration } = video;
  if (!width || !height) throw new Error("Could not determine video dimensions");
  if (!duration || !isFinite(duration)) throw new Error("Could not determine video duration");

  const frameCount = Math.floor(duration * targetFps);

  return {
    info: {
      width,
      height,
      frameCount,
      totalDuration: Math.round(duration * 1000),
      fps: targetFps,
      format: "video",
    },
    // Caller must call URL.revokeObjectURL(videoUrl) when done
    videoUrl: url,
  };
}

/** Options for the streaming decode pipeline */
export interface StreamDecodeOptions {
  targetFps: number;
  maxFrames: number;
  crop: CropRect | null;
  maxDimension: number;
}

/**
 * Async generator that decodes video frames one at a time.
 * Each yielded frame is crop+resize processed and ready for encoding.
 * Only one frame of RGBA data exists at a time — previous frames are GC'd.
 *
 * @param file - Video file
 * @param options - Decode/process options
 * @param onProgress - Progress callback
 * @yields Processed RGBA frames with dimensions and delay
 */
export async function* streamDecodeVideo(
  file: File,
  options: StreamDecodeOptions,
  onProgress?: (p: ConvertProgress) => void,
): AsyncGenerator<{
  rgba: Uint8Array;
  width: number;
  height: number;
  delay: number;
}> {
  const url = URL.createObjectURL(file);

  try {
    const video = document.createElement("video");
    video.muted = true;
    video.playsInline = true;
    video.preload = "auto";
    video.src = url;

    await new Promise<void>((resolve, reject) => {
      video.onloadedmetadata = () => resolve();
      video.onerror = () => reject(new Error("Failed to load video"));
    });

    const { videoWidth: srcW, videoHeight: srcH, duration } = video;
    if (!srcW || !srcH) throw new Error("Could not determine video dimensions");
    if (!duration || !isFinite(duration)) throw new Error("Could not determine video duration");

    const frameInterval = 1 / options.targetFps;
    const totalFrames = Math.min(
      Math.floor(duration * options.targetFps),
      options.maxFrames,
    );
    const delayMs = Math.round(1000 / options.targetFps);

    // Canvas for frame capture (reused across all frames)
    const canvas =
      typeof OffscreenCanvas !== "undefined"
        ? new OffscreenCanvas(srcW, srcH)
        : createFallbackCanvas(srcW, srcH);
    const ctx = canvas.getContext("2d") as
      | OffscreenCanvasRenderingContext2D
      | CanvasRenderingContext2D;
    if (!ctx) throw new Error("Could not create canvas context");

    // Pre-calculate output dimensions after crop + resize
    let outW = srcW;
    let outH = srcH;
    if (options.crop) {
      outW = Math.max(1, Math.round(options.crop.w * srcW));
      outH = Math.max(1, Math.round(options.crop.h * srcH));
    }
    if (options.maxDimension > 0 && (outW > options.maxDimension || outH > options.maxDimension)) {
      const scale = Math.min(options.maxDimension / outW, options.maxDimension / outH);
      outW = Math.round(outW * scale);
      outH = Math.round(outH * scale);
    }

    for (let i = 0; i < totalFrames; i++) {
      const seekTime = i * frameInterval;

      onProgress?.({
        phase: "decoding",
        percent: Math.round((i / totalFrames) * 100),
        frame: i + 1,
        total: totalFrames,
      });

      await seekTo(video, seekTime);

      // Capture raw RGBA from video frame
      ctx.drawImage(video, 0, 0, srcW, srcH);
      const imageData = ctx.getImageData(0, 0, srcW, srcH);
      let rgba = new Uint8Array(imageData.data.buffer);
      let w = srcW;
      let h = srcH;

      // Crop if needed
      if (options.crop) {
        const cropped = cropFrame(rgba, w, h, options.crop);
        rgba = new Uint8Array(cropped.data);
        w = cropped.width;
        h = cropped.height;
      }

      // Resize if needed
      if (options.maxDimension > 0 && (w > options.maxDimension || h > options.maxDimension)) {
        const resized = resizeFrame(rgba, w, h, options.maxDimension);
        rgba = new Uint8Array(resized.data);
        w = resized.width;
        h = resized.height;
      }

      yield { rgba, width: w, height: h, delay: delayMs };
    }
  } finally {
    URL.revokeObjectURL(url);
  }
}
