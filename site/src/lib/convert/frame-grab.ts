/**
 * Save the current preview frame as a still image (PNG/WebP), with the same
 * crop/rotate/flip the conversion would apply, at full resolution.
 */

import { FramePreparer, type FrameTransform } from "./prepare-frame";

/** Still formats a frame can be saved as */
export type StillFormat = "image/png" | "image/webp";

/**
 * File name for a grabbed frame: `<base>-frame-<mm>m<ss.ss>s.<ext>`.
 * The timestamp makes several grabs from one clip distinct and sortable.
 */
export function grabFileName(sourceName: string, timeSeconds: number, format: StillFormat): string {
  const base = sourceName.replace(/\.[^.]+$/, "") || "frame";
  const t = Math.max(0, timeSeconds);
  const minutes = Math.floor(t / 60);
  const seconds = (t - minutes * 60).toFixed(2).padStart(5, "0");
  const ext = format === "image/png" ? "png" : "webp";
  return `${base}-frame-${String(minutes).padStart(2, "0")}m${seconds}s.${ext}`;
}

/**
 * Draw `source` (a <video> at its current time, an ImageBitmap, ...) through
 * the transform — ignoring maxDimension, a still is saved at full size — and
 * encode it.
 */
export async function grabFrame(
  source: CanvasImageSource,
  srcW: number,
  srcH: number,
  transform: FrameTransform,
  format: StillFormat,
  quality = 0.92,
): Promise<Blob> {
  const preparer = new FramePreparer(srcW, srcH, { ...transform, maxDimension: 0 });
  const { rgba, width, height } = preparer.fromImage(source);
  const canvas =
    typeof OffscreenCanvas !== "undefined"
      ? new OffscreenCanvas(width, height)
      : Object.assign(document.createElement("canvas"), { width, height });
  const ctx = canvas.getContext("2d") as OffscreenCanvasRenderingContext2D | CanvasRenderingContext2D | null;
  if (!ctx) throw new Error("Could not create canvas context");
  ctx.putImageData(new ImageData(new Uint8ClampedArray(rgba.buffer as ArrayBuffer, rgba.byteOffset, rgba.byteLength), width, height), 0, 0);
  if ("convertToBlob" in canvas) return canvas.convertToBlob({ type: format, quality });
  return new Promise((resolve, reject) =>
    (canvas as HTMLCanvasElement).toBlob(
      (blob) => (blob ? resolve(blob) : reject(new Error("Could not encode frame"))),
      format,
      quality,
    ),
  );
}
