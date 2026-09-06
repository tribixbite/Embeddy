/**
 * Pure geometry for the CropOverlay component.
 *
 * All rects are normalized CropRect fractions (0-1 of the source dimensions).
 * Aspect ratios are expressed in PIXELS (w/h of the cropped output); because
 * the normalized space is stretched by the source's own aspect, converting a
 * pixel ratio into normalized units uses `arNorm = aspectRatio / sourceAspect`
 * so that `w = h * arNorm` holds in fraction space.
 *
 * Kept free of DOM/Svelte so the drag/clamp math is unit-testable (M20, M51,
 * M52, M53).
 */
import type { CropRect } from "../../../lib/convert/types";

export type AspectMode = "free" | "1:1" | "16:9" | "4:3" | "custom";
export type CropHandle = "tl" | "t" | "tr" | "r" | "br" | "b" | "bl" | "l" | "move";

/** Minimum crop size — 2% of the source on either axis. */
export const MIN_CROP = 0.02;

/** Clamp a value between min and max. */
export function clamp(val: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, val));
}

/** A custom-ratio side that is missing/NaN/≤0 falls back to 1 (M53). */
function safeSide(side: number | undefined): number {
  return Number.isFinite(side) && (side as number) > 0 ? (side as number) : 1;
}

/**
 * Aspect ratio (pixel w/h) for a chip mode, or null in free mode.
 * Custom values are guarded so clearing an input mid-edit never produces a
 * 0/Infinity ratio that collapses the crop (M53).
 */
export function aspectRatioFor(
  mode: AspectMode,
  customW?: number,
  customH?: number,
): number | null {
  switch (mode) {
    case "1:1": return 1;
    case "16:9": return 16 / 9;
    case "4:3": return 4 / 3;
    case "custom": return safeSide(customW) / safeSide(customH);
    default: return null;
  }
}

export interface CropDragArgs {
  /** Crop rect at the start of this move event */
  crop: CropRect;
  /** Which handle is being dragged */
  handle: CropHandle;
  /** Pointer position, normalized to the container (0-1) */
  nx: number;
  /** Pointer position, normalized to the container (0-1) */
  ny: number;
  /**
   * Pointer offset from the crop origin recorded at pointerdown — used by the
   * move handle so the rect doesn't teleport its center under the finger (M52).
   */
  grabOffset?: { dx: number; dy: number };
  /** Locked pixel aspect ratio (w/h) or null for free resize */
  aspectRatio: number | null;
  /** Source pixel aspect ratio (width/height) */
  sourceAspect: number;
}

/**
 * Resolve a pointer drag into a new crop rect.
 *
 * Free mode: each axis follows the pointer independently, anchored on the
 * opposite edge. Aspect-locked mode: the driven axis follows the pointer and
 * the other is derived; when either axis hits a bound, BOTH are shrunk so the
 * ratio always holds exactly (M20), and vertical handles drive the width too
 * instead of springing back (M51).
 */
export function resolveCropDrag(args: CropDragArgs): CropRect {
  const { crop, handle, nx, ny, grabOffset, aspectRatio, sourceAspect } = args;
  let { x, y, w, h } = crop;

  if (handle === "move") {
    const dx = grabOffset?.dx ?? w / 2;
    const dy = grabOffset?.dy ?? h / 2;
    return {
      x: clamp(nx - dx, 0, 1 - w),
      y: clamp(ny - dy, 0, 1 - h),
      w,
      h,
    };
  }

  const right = x + w;
  const bottom = y + h;
  const hasLeft = handle === "l" || handle === "tl" || handle === "bl";
  const hasRight = handle === "r" || handle === "tr" || handle === "br";
  const hasTop = handle === "t" || handle === "tl" || handle === "tr";
  const hasBottom = handle === "b" || handle === "bl" || handle === "br";

  if (aspectRatio === null) {
    // Free resize — axes are independent, anchored on the opposite edge.
    if (hasLeft) {
      const newX = clamp(nx, 0, right - MIN_CROP);
      w = right - newX;
      x = newX;
    }
    if (hasRight) w = clamp(nx - x, MIN_CROP, 1 - x);
    if (hasTop) {
      const newY = clamp(ny, 0, bottom - MIN_CROP);
      h = bottom - newY;
      y = newY;
    }
    if (hasBottom) h = clamp(ny - y, MIN_CROP, 1 - y);
    return {
      x: clamp(x, 0, 1),
      y: clamp(y, 0, 1),
      w: clamp(w, MIN_CROP, 1 - x),
      h: clamp(h, MIN_CROP, 1 - y),
    };
  }

  // Aspect-locked resize: solve for a width that satisfies BOTH axes' bounds,
  // then derive the height — the ratio can never be silently violated.
  const arNorm = aspectRatio / sourceAspect; // w = h * arNorm in fraction space

  // Candidate width from the pointer along the driven axis.
  let candW: number;
  if (hasLeft) {
    candW = right - nx;
  } else if (hasRight) {
    candW = nx - x;
  } else {
    // Pure vertical handle (t/b): height drives, width follows (M51).
    const candH = hasTop ? bottom - ny : ny - y;
    candW = candH * arNorm;
  }

  // Horizontal room: left-anchored handles grow toward 0, right-anchored
  // toward 1; pure-vertical handles grow symmetrically around the center.
  const cx = x + w / 2;
  const horizontalMax = hasLeft ? right
    : hasRight ? 1 - x
    : Math.min(1, 2 * Math.min(cx, 1 - cx));
  // Vertical room, expressed as a width limit via the ratio: top-dragged
  // handles anchor the bottom edge, everything else anchors the top edge.
  const verticalMax = (hasTop ? bottom : 1 - y) * arNorm;
  const maxW = Math.min(horizontalMax, verticalMax, 1);
  const minW = Math.max(MIN_CROP, MIN_CROP * arNorm);
  const newW = clamp(candW, Math.min(minW, maxW), maxW);
  const newH = newW / arNorm;

  const newX = hasLeft ? right - newW
    : hasRight ? x
    : clamp(cx - newW / 2, 0, 1 - newW);
  const newY = hasTop ? bottom - newH : y;

  return { x: newX, y: newY, w: newW, h: newH };
}

/**
 * Reshape an existing crop to a locked ratio (chip click / custom edit).
 * Keeps the current width where possible, derives the height, shrinks to fit
 * the bounds while preserving the EXACT ratio, and re-centers on the previous
 * crop's center (clamped into the container).
 */
export function applyAspectToCrop(
  crop: CropRect,
  aspectRatio: number,
  sourceAspect: number,
): CropRect {
  const arNorm = aspectRatio / sourceAspect;
  let w = crop.w;
  let h = w / arNorm;
  if (h > 1) {
    h = 1;
    w = h * arNorm;
  }
  if (w > 1) {
    w = 1;
    h = w / arNorm;
  }
  const minW = Math.max(MIN_CROP, MIN_CROP * arNorm);
  if (w < minW) {
    w = Math.min(minW, 1);
    h = w / arNorm;
  }
  const cx = crop.x + crop.w / 2;
  const cy = crop.y + crop.h / 2;
  return {
    x: clamp(cx - w / 2, 0, 1 - w),
    y: clamp(cy - h / 2, 0, 1 - h),
    w,
    h,
  };
}
