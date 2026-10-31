/**
 * Single-pass per-frame geometry: crop → resize → rotate/flip.
 *
 * Replaces three copies of a crop-then-resize pipeline (ConvertTool batch
 * WebP, gif-encoder, streamDecodeVideo) that each allocated 2–4 canvases per
 * frame and materialised every intermediate frame array (H2/D1/P2). Here the
 * whole transform is ONE `drawImage` onto a reused canvas, and callers run it
 * one frame at a time inside their encode loop, so peak memory is the source
 * frames plus a single output frame.
 */

import type { ConvertOptions, CropRect } from "./types";
import { cropRectToPixels } from "./encoder";

/** Clockwise rotation in degrees */
export type Rotation = 0 | 90 | 180 | 270;

/** Geometric edits applied to every frame */
export interface FrameTransform {
  /** Normalised crop rect, or null for the full frame */
  crop: CropRect | null;
  /** Fit the cropped region within this many pixels per side (0 = off) */
  maxDimension: number;
  /** Clockwise rotation, applied after crop + resize */
  rotate: Rotation;
  /** Mirror left↔right (applied before rotation, in source orientation) */
  flipH: boolean;
  /** Mirror top↔bottom (applied before rotation, in source orientation) */
  flipV: boolean;
}

/** A transform that leaves frames untouched */
export const IDENTITY_TRANSFORM: Readonly<FrameTransform> = Object.freeze({
  crop: null,
  maxDimension: 0,
  rotate: 0,
  flipH: false,
  flipV: false,
});

/** Extract the geometric part of the Convert options */
export function transformFromOptions(
  opts: Pick<ConvertOptions, "crop" | "maxDimension" | "rotate" | "flipH" | "flipV">,
): FrameTransform {
  return {
    crop: opts.crop,
    maxDimension: opts.maxDimension,
    rotate: opts.rotate,
    flipH: opts.flipH,
    flipV: opts.flipV,
  };
}

/** Resolved pixel geometry for one source size + transform */
export interface FramePlan {
  /** Source rectangle (clamped crop) */
  sx: number;
  sy: number;
  sw: number;
  sh: number;
  /** Scaled size of the source rect before rotation */
  drawW: number;
  drawH: number;
  /** Output frame size (drawW/drawH swapped for 90/270) */
  outW: number;
  outH: number;
  /** True when the output is byte-identical to the input — skip the canvas */
  identity: boolean;
}

/** True when `t` can never change a frame (crop/resize may still no-op per size). */
export function isIdentityTransform(t: FrameTransform): boolean {
  return t.crop === null && t.maxDimension <= 0 && t.rotate === 0 && !t.flipH && !t.flipV;
}

/**
 * Resolve a transform against a source size. Crop pixels come from
 * {@link cropRectToPixels} so declared output dims always match the buffer
 * (the M4 size-mismatch class of bug).
 */
export function planFrame(srcW: number, srcH: number, t: FrameTransform): FramePlan {
  const { px, py, pw, ph } = t.crop
    ? cropRectToPixels(srcW, srcH, t.crop)
    : { px: 0, py: 0, pw: srcW, ph: srcH };

  let drawW = pw;
  let drawH = ph;
  if (t.maxDimension > 0 && (pw > t.maxDimension || ph > t.maxDimension)) {
    const scale = Math.min(t.maxDimension / pw, t.maxDimension / ph);
    drawW = Math.max(1, Math.round(pw * scale));
    drawH = Math.max(1, Math.round(ph * scale));
  }

  const quarterTurn = t.rotate === 90 || t.rotate === 270;
  const identity =
    px === 0 && py === 0 && pw === srcW && ph === srcH &&
    drawW === pw && drawH === ph &&
    t.rotate === 0 && !t.flipH && !t.flipV;

  return {
    sx: px,
    sy: py,
    sw: pw,
    sh: ph,
    drawW,
    drawH,
    outW: quarterTurn ? drawH : drawW,
    outH: quarterTurn ? drawW : drawH,
    identity,
  };
}

/** Any 2D canvas this module can draw into */
export type AnyCanvas = OffscreenCanvas | HTMLCanvasElement;
/** Its 2D context */
export type AnyContext2D = OffscreenCanvasRenderingContext2D | CanvasRenderingContext2D;

/**
 * Create a canvas — OffscreenCanvas where available (also usable in Workers),
 * else a detached DOM <canvas>. Shared by every decoder/encoder.
 */
export function createCanvas2d(w: number, h: number): AnyCanvas {
  if (typeof OffscreenCanvas !== "undefined") return new OffscreenCanvas(w, h);
  const c = document.createElement("canvas");
  c.width = w;
  c.height = h;
  return c;
}

/** Get a 2D context, optionally tuned for repeated getImageData() */
export function getContext2d(canvas: AnyCanvas, willReadFrequently = false): AnyContext2D {
  const ctx = canvas.getContext("2d", { willReadFrequently }) as AnyContext2D | null;
  if (!ctx) throw new Error("Could not create canvas context");
  return ctx;
}

/** A processed frame */
export interface PreparedFrame {
  rgba: Uint8Array;
  width: number;
  height: number;
  /**
   * True when `rgba` is a new buffer owned by the caller (safe to transfer
   * to a Worker without copying); false when it is the caller's input.
   */
  fresh: boolean;
}

/**
 * Applies one {@link FramePlan} to a stream of frames, reusing its canvases.
 * Construct one per source size + transform and call per frame.
 */
export class FramePreparer {
  readonly plan: FramePlan;
  private srcCanvas: AnyCanvas | null = null;
  private srcCtx: AnyContext2D | null = null;
  private dstCanvas: AnyCanvas | null = null;
  private dstCtx: AnyContext2D | null = null;
  private readonly transform: FrameTransform;

  constructor(
    private readonly srcW: number,
    private readonly srcH: number,
    transform: FrameTransform,
  ) {
    this.plan = planFrame(srcW, srcH, transform);
    this.transform = transform;
  }

  /**
   * Transform a buffered RGBA frame. Identity plans return the input as-is
   * without touching a canvas (keeps the no-edit path canvas-free).
   */
  fromRgba(rgba: Uint8Array): PreparedFrame {
    const { plan } = this;
    if (plan.identity) return { rgba, width: plan.outW, height: plan.outH, fresh: false };

    if (!this.srcCanvas) {
      this.srcCanvas = createCanvas2d(this.srcW, this.srcH);
      this.srcCtx = getContext2d(this.srcCanvas);
    }
    const imageData = this.srcCtx!.createImageData(this.srcW, this.srcH);
    imageData.data.set(rgba);
    this.srcCtx!.putImageData(imageData, 0, 0);
    return this.fromImage(this.srcCanvas);
  }

  /**
   * Transform any drawable (a <video>, VideoFrame, ImageBitmap, canvas) of
   * the planned source size with a single drawImage. Always reads back.
   */
  fromImage(source: CanvasImageSource): PreparedFrame {
    const { plan, transform } = this;
    if (!this.dstCanvas) {
      this.dstCanvas = createCanvas2d(plan.outW, plan.outH);
      this.dstCtx = getContext2d(this.dstCanvas, true);
    }
    const ctx = this.dstCtx!;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    // Transparent sources composite over the previous frame without this
    ctx.clearRect(0, 0, plan.outW, plan.outH);
    ctx.imageSmoothingEnabled = true;
    ctx.imageSmoothingQuality = "high";
    // Rotate/flip about the output centre, then draw the (scaled) source
    // rect centred on the origin
    ctx.translate(plan.outW / 2, plan.outH / 2);
    if (transform.rotate) ctx.rotate((transform.rotate * Math.PI) / 180);
    if (transform.flipH || transform.flipV) {
      ctx.scale(transform.flipH ? -1 : 1, transform.flipV ? -1 : 1);
    }
    ctx.drawImage(
      source,
      plan.sx, plan.sy, plan.sw, plan.sh,
      -plan.drawW / 2, -plan.drawH / 2, plan.drawW, plan.drawH,
    );
    const out = ctx.getImageData(0, 0, plan.outW, plan.outH);
    return {
      rgba: new Uint8Array(out.data.buffer),
      width: plan.outW,
      height: plan.outH,
      fresh: true,
    };
  }
}
