/**
 * Extract frames from a video file using <video> + canvas.
 * Seeks through the video at the target FPS and captures each frame.
 */

import type { DecodedFrame, SourceInfo, ConvertProgress } from "./types";

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
