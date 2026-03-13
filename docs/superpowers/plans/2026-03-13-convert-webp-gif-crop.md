# Convert Tool: WebP→GIF + Crop UI Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add WebP input decoding, GIF output encoding, and a draggable crop overlay to the Convert tool; fix animated WebP dimension reporting in Inspect.

**Architecture:** Extend the existing decode→settings→encode pipeline with a new WebP decoder (video-style canvas capture), GIF encoder (gifenc), and crop step inserted between decode and resize. CropOverlay is a new Svelte component with draggable handles over the source preview.

**Tech Stack:** Svelte 5, TypeScript, gifenc (mattdesl), OffscreenCanvas, pointer events

**Spec:** `docs/superpowers/specs/2026-03-13-convert-webp-gif-crop-design.md`

---

## File Structure

| Action | Path | Responsibility |
|--------|------|----------------|
| NEW | `site/src/lib/convert/webp-decoder.ts` | Decode animated/static WebP via `<img>` + canvas capture |
| NEW | `site/src/lib/convert/gif-encoder.ts` | Encode RGBA frames to animated GIF via gifenc |
| NEW | `site/src/components/tools/convert/CropOverlay.svelte` | Draggable crop rectangle with aspect ratio support |
| MODIFY | `site/src/lib/convert/types.ts` | Add `CropRect`, `outputFormat`, `dithering`, update `SourceInfo.format` |
| MODIFY | `site/src/lib/convert/encoder.ts` | Add `cropFrame()`, export `resizeFrame`+`subsampleFrames` for shared use |
| MODIFY | `site/src/components/tools/convert/ConvertSettings.svelte` | Output format chips, crop toggle + overlay, GIF-specific options |
| MODIFY | `site/src/components/tools/convert/ConvertTool.svelte` | WebP input routing, GIF encoder routing, crop in pipeline |
| MODIFY | `site/src/components/tools/convert/ConvertResult.svelte` | Dynamic output format label + filename extension |
| MODIFY | `app/src/main/kotlin/app/embeddy/inspect/MetadataEngine.kt` | Fix animated WebP dimension reading |

---

## Chunk 1: Types, Dependencies, and Shared Utilities

### Task 1: Add gifenc dependency

- [ ] **Step 1: Install gifenc**

```bash
cd site && bun add gifenc
```

- [ ] **Step 2: Verify installation**

```bash
ls site/node_modules/gifenc/index.js
```

- [ ] **Step 3: Commit**

```bash
git add site/package.json site/bun.lock
git commit -m "chore: add gifenc dependency for GIF output encoding"
```

### Task 2: Update types.ts with new interfaces and options

**Files:**
- Modify: `site/src/lib/convert/types.ts`

- [ ] **Step 1: Add CropRect interface and update ConvertOptions and SourceInfo**

Replace the full contents of `site/src/lib/convert/types.ts`:

```typescript
/** Shared types for the Convert tool pipeline. */

/** A single decoded frame ready for encoding */
export interface DecodedFrame {
  /** Raw RGBA pixel data (width * height * 4 bytes) */
  rgba: Uint8Array;
  /** Frame display duration in milliseconds */
  delay: number;
}

/** Normalized crop rectangle (all values 0-1 fractions of source dimensions) */
export interface CropRect {
  /** Left edge as fraction of source width */
  x: number;
  /** Top edge as fraction of source height */
  y: number;
  /** Crop width as fraction of source width */
  w: number;
  /** Crop height as fraction of source height */
  h: number;
}

/** Source file metadata extracted during decoding */
export interface SourceInfo {
  /** Canvas width in pixels */
  width: number;
  /** Canvas height in pixels */
  height: number;
  /** Number of frames */
  frameCount: number;
  /** Total animation duration in ms */
  totalDuration: number;
  /** Average frame rate */
  fps: number;
  /** Source format */
  format: "gif" | "video" | "webp";
}

/** Encoding options for the output */
export interface ConvertOptions {
  /** Quality 1-100 (lossy mode for WebP; palette quality for GIF) */
  quality: number;
  /** Lossless encoding — WebP only */
  lossless: boolean;
  /** Max dimension (0 = no resize) */
  maxDimension: number;
  /** Loop count (0 = infinite) */
  loops: number;
  /** Target FPS for video/WebP sources (0 = preserve original) */
  targetFps: number;
  /** Output format */
  outputFormat: "webp" | "gif";
  /** Crop region or null for no crop */
  crop: CropRect | null;
}

/** Progress callback for long-running operations */
export interface ConvertProgress {
  /** Current phase description */
  phase: "decoding" | "cropping" | "resizing" | "encoding";
  /** 0-100 percentage within current phase */
  percent: number;
  /** Current frame being processed */
  frame: number;
  /** Total frames */
  total: number;
}
```

- [ ] **Step 2: Run typecheck**

```bash
cd site && bunx astro check 2>&1 | tail -20
```

Expected: Errors in files that reference old types (ConvertTool, ConvertSettings, encoder) — that's fine, we'll fix those in subsequent tasks.

- [ ] **Step 3: Commit**

```bash
git add site/src/lib/convert/types.ts
git commit -m "feat(convert): add CropRect, outputFormat, and webp source format to types"
```

### Task 3: Add cropFrame() and export shared utilities from encoder.ts

**Files:**
- Modify: `site/src/lib/convert/encoder.ts`

- [ ] **Step 1: Add cropFrame function and export resizeFrame + subsampleFrames**

Add `cropFrame` before `resizeFrame`. Make `resizeFrame`, `subsampleFrames`, and `createCanvas` exported so `gif-encoder.ts` can reuse them. Add crop step to `encodeAnimatedWebP`.

At top of encoder.ts, update the import to include `CropRect`:

```typescript
import type { DecodedFrame, ConvertOptions, ConvertProgress, CropRect } from "./types";
```

Add this function before `resizeFrame` (after `getModule`):

```typescript
/**
 * Crop a single RGBA frame using normalized CropRect (0-1 fractions).
 * Uses OffscreenCanvas for sub-rectangle extraction.
 */
export function cropFrame(
  rgba: Uint8Array,
  srcW: number,
  srcH: number,
  crop: CropRect,
): { data: Uint8Array; width: number; height: number } {
  // Convert fractional crop to pixel coordinates, clamped to source bounds
  const px = Math.max(0, Math.round(crop.x * srcW));
  const py = Math.max(0, Math.round(crop.y * srcH));
  const pw = Math.min(srcW - px, Math.round(crop.w * srcW));
  const ph = Math.min(srcH - py, Math.round(crop.h * srcH));

  if (pw <= 0 || ph <= 0) {
    return { data: rgba, width: srcW, height: srcH };
  }

  // Draw full frame, then extract crop region
  const srcCanvas =
    typeof OffscreenCanvas !== "undefined"
      ? new OffscreenCanvas(srcW, srcH)
      : createCanvas(srcW, srcH);
  const srcCtx = srcCanvas.getContext("2d") as CanvasRenderingContext2D;
  const imageData = srcCtx.createImageData(srcW, srcH);
  imageData.data.set(rgba);
  srcCtx.putImageData(imageData, 0, 0);

  const dstCanvas =
    typeof OffscreenCanvas !== "undefined"
      ? new OffscreenCanvas(pw, ph)
      : createCanvas(pw, ph);
  const dstCtx = dstCanvas.getContext("2d") as CanvasRenderingContext2D;
  dstCtx.drawImage(srcCanvas, px, py, pw, ph, 0, 0, pw, ph);

  const cropped = dstCtx.getImageData(0, 0, pw, ph);
  return { data: new Uint8Array(cropped.data.buffer), width: pw, height: ph };
}
```

Make `resizeFrame`, `subsampleFrames`, and `createCanvas` exported (add `export` keyword to each).

Add crop step inside `encodeAnimatedWebP`, after subsample but before resize:

```typescript
  // Crop all frames if crop is set
  let cropW = width;
  let cropH = height;
  if (options.crop) {
    cropW = Math.round(options.crop.w * width);
    cropH = Math.round(options.crop.h * height);
    workFrames = workFrames.map((frame, i) => {
      onProgress?.({
        phase: "cropping",
        percent: Math.round((i / workFrames.length) * 100),
        frame: i + 1,
        total: workFrames.length,
      });
      const cropped = cropFrame(frame.rgba, width, height, options.crop!);
      return { rgba: cropped.data, delay: frame.delay };
    });
  }
```

Then update the resize section to use `cropW`/`cropH` instead of `width`/`height`.

- [ ] **Step 2: Run typecheck**

```bash
cd site && bunx astro check 2>&1 | tail -20
```

- [ ] **Step 3: Commit**

```bash
git add site/src/lib/convert/encoder.ts
git commit -m "feat(convert): add cropFrame utility and crop step to WebP encoder"
```

---

## Chunk 2: WebP Decoder

### Task 4: Create webp-decoder.ts

**Files:**
- Create: `site/src/lib/convert/webp-decoder.ts`

- [ ] **Step 1: Write the WebP decoder**

```typescript
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

    // Try to detect if animated by capturing a second frame after a short delay
    const isAnimated = await detectAnimated(img, ctx, firstRgba, width, height);

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

    // Animated WebP — capture frames at targetFps using requestAnimationFrame timing
    const delayMs = Math.round(1000 / targetFps);
    const frames: DecodedFrame[] = [{ rgba: firstRgba, delay: delayMs }];
    let totalDuration = delayMs;
    let duplicateCount = 0;
    const maxDuplicates = targetFps * 2; // Stop after 2s of identical frames (animation ended)

    onProgress?.({ phase: "decoding", percent: 0, frame: 1, total: maxFrames });

    for (let i = 1; i < maxFrames; i++) {
      // Wait one frame interval
      await sleep(delayMs);

      // Capture current frame
      ctx.clearRect(0, 0, width, height);
      ctx.drawImage(img, 0, 0, width, height);
      const frameData = ctx.getImageData(0, 0, width, height);
      const rgba = new Uint8Array(frameData.data.buffer);

      // Check if frame is identical to previous (animation may have ended)
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
 * Detect if a WebP is animated by capturing two frames with a short gap
 * and comparing pixel data.
 */
async function detectAnimated(
  img: HTMLImageElement,
  ctx: CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D,
  firstRgba: Uint8Array,
  width: number,
  height: number,
): Promise<boolean> {
  // Wait ~150ms and recapture — if pixels differ, it's animated
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
```

- [ ] **Step 2: Run typecheck**

```bash
cd site && bunx astro check 2>&1 | tail -20
```

- [ ] **Step 3: Commit**

```bash
git add site/src/lib/convert/webp-decoder.ts
git commit -m "feat(convert): add WebP decoder — video-style canvas frame capture"
```

---

## Chunk 3: GIF Encoder

### Task 5: Create gif-encoder.ts

**Files:**
- Create: `site/src/lib/convert/gif-encoder.ts`

- [ ] **Step 1: Write the GIF encoder**

```typescript
/**
 * Encode RGBA frames into animated GIF using gifenc.
 * Handles color quantization (256 palette), frame optimization,
 * and shared crop/resize pipeline from encoder.ts.
 */

import type { DecodedFrame, ConvertOptions, ConvertProgress } from "./types";
import { cropFrame, resizeFrame, subsampleFrames } from "./encoder";

/** Lazy-loaded gifenc module */
let gifencModule: typeof import("gifenc") | null = null;

async function getGifenc() {
  if (!gifencModule) {
    gifencModule = await import("gifenc");
  }
  return gifencModule;
}

/**
 * Encode decoded frames into an animated GIF blob.
 * @param frames - RGBA frames with per-frame delays
 * @param width - Source canvas width
 * @param height - Source canvas height
 * @param options - Encoding options
 * @param sourceFps - Original source FPS (for subsampling)
 * @param onProgress - Progress callback
 */
export async function encodeAnimatedGif(
  frames: DecodedFrame[],
  width: number,
  height: number,
  options: ConvertOptions,
  sourceFps: number,
  onProgress?: (p: ConvertProgress) => void,
): Promise<Blob> {
  const { GIFEncoder, quantize, applyPalette } = await getGifenc();

  // Subsample if target FPS is lower than source
  let workFrames = subsampleFrames(frames, sourceFps, options.targetFps);

  // Crop all frames if crop is set
  let outW = width;
  let outH = height;
  if (options.crop) {
    outW = Math.max(1, Math.round(options.crop.w * width));
    outH = Math.max(1, Math.round(options.crop.h * height));
    workFrames = workFrames.map((frame, i) => {
      onProgress?.({
        phase: "cropping",
        percent: Math.round((i / workFrames.length) * 100),
        frame: i + 1,
        total: workFrames.length,
      });
      const cropped = cropFrame(frame.rgba, width, height, options.crop!);
      return { rgba: cropped.data, delay: frame.delay };
    });
  }

  // Resize all frames if maxDimension is set
  if (options.maxDimension > 0 && (outW > options.maxDimension || outH > options.maxDimension)) {
    const scale = Math.min(options.maxDimension / outW, options.maxDimension / outH);
    const newW = Math.round(outW * scale);
    const newH = Math.round(outH * scale);

    workFrames = workFrames.map((frame, i) => {
      onProgress?.({
        phase: "resizing",
        percent: Math.round((i / workFrames.length) * 100),
        frame: i + 1,
        total: workFrames.length,
      });
      const resized = resizeFrame(frame.rgba, outW, outH, options.maxDimension);
      return { rgba: resized.data, delay: frame.delay };
    });

    outW = newW;
    outH = newH;
  }

  // Encode to GIF
  const gif = GIFEncoder();

  // Palette quality: map 1-100 quality to 256-16 max colors
  // Higher quality = more colors = larger file
  const maxColors = Math.max(16, Math.round((options.quality / 100) * 256));

  for (let i = 0; i < workFrames.length; i++) {
    const frame = workFrames[i];

    onProgress?.({
      phase: "encoding",
      percent: Math.round((i / workFrames.length) * 100),
      frame: i + 1,
      total: workFrames.length,
    });

    // Quantize RGBA to 256-color palette
    const palette = quantize(frame.rgba, maxColors, { format: "rgb444" });
    const index = applyPalette(frame.rgba, palette, "rgb444");

    gif.writeFrame(index, outW, outH, {
      palette,
      delay: frame.delay,
      repeat: options.loops === 0 ? 0 : options.loops, // 0 = infinite in GIF spec
    });
  }

  gif.finish();

  onProgress?.({
    phase: "encoding",
    percent: 100,
    frame: workFrames.length,
    total: workFrames.length,
  });

  return new Blob([gif.bytesView()], { type: "image/gif" });
}
```

- [ ] **Step 2: Run typecheck**

```bash
cd site && bunx astro check 2>&1 | tail -20
```

Note: gifenc may not ship with TypeScript types. If typecheck fails on the import, create a minimal declaration file at `site/src/lib/convert/gifenc.d.ts`:

```typescript
declare module "gifenc" {
  export function GIFEncoder(opts?: { auto?: boolean; initialCapacity?: number }): {
    writeFrame(
      index: Uint8Array,
      width: number,
      height: number,
      opts?: {
        palette?: number[][];
        delay?: number;
        repeat?: number;
        transparent?: boolean;
        transparentIndex?: number;
        dispose?: number;
      },
    ): void;
    finish(): void;
    bytes(): Uint8Array;
    bytesView(): Uint8Array;
  };

  export function quantize(
    rgba: Uint8Array | Uint8ClampedArray,
    maxColors: number,
    opts?: { format?: string; oneBitAlpha?: boolean | number },
  ): number[][];

  export function applyPalette(
    rgba: Uint8Array | Uint8ClampedArray,
    palette: number[][],
    format?: string,
  ): Uint8Array;
}
```

- [ ] **Step 3: Commit**

```bash
git add site/src/lib/convert/gif-encoder.ts site/src/lib/convert/gifenc.d.ts
git commit -m "feat(convert): add GIF encoder using gifenc with quantization pipeline"
```

---

## Chunk 4: Crop Overlay Component

### Task 6: Create CropOverlay.svelte

**Files:**
- Create: `site/src/components/tools/convert/CropOverlay.svelte`

- [ ] **Step 1: Write the CropOverlay component**

This is a Svelte 5 component with:
- A positioned container that overlays the source preview image
- Semi-transparent dark mask outside crop region (4 overlay rects)
- Dashed border on crop edges
- 8 drag handles (4 corners + 4 edges) with 44px touch hit areas
- Aspect ratio chip row: Free | 1:1 | 16:9 | 4:3 | Custom
- Custom ratio shows W:H numeric inputs
- Pointer-event-based dragging (mouse + touch)
- Outputs normalized CropRect (0-1 fractions)

```svelte
<script lang="ts">
  /**
   * Draggable crop overlay for the Convert tool.
   * Renders on top of the source preview image.
   * Outputs normalized CropRect (0-1 fractions of source dimensions).
   */
  import type { CropRect } from "../../../lib/convert/types";

  type AspectMode = "free" | "1:1" | "16:9" | "4:3" | "custom";
  type HandleId = "tl" | "t" | "tr" | "r" | "br" | "b" | "bl" | "l" | "move";

  let {
    crop = $bindable({ x: 0.1, y: 0.1, w: 0.8, h: 0.8 }),
    sourceWidth,
    sourceHeight,
    disabled = false,
  }: {
    crop: CropRect;
    sourceWidth: number;
    sourceHeight: number;
    disabled?: boolean;
  } = $props();

  let containerRef: HTMLDivElement | undefined = $state(undefined);
  let activeHandle: HandleId | null = $state(null);
  let aspectMode: AspectMode = $state("free");
  let customW = $state(16);
  let customH = $state(9);

  /** Aspect ratio value or null for free mode */
  let aspectRatio = $derived.by(() => {
    switch (aspectMode) {
      case "1:1": return 1;
      case "16:9": return 16 / 9;
      case "4:3": return 4 / 3;
      case "custom": return customW / (customH || 1);
      default: return null;
    }
  });

  /** Convert crop fractions to pixel positions within the container */
  let containerRect = $derived.by(() => {
    if (!containerRef) return { width: 0, height: 0 };
    return { width: containerRef.clientWidth, height: containerRef.clientHeight };
  });

  const HANDLE_SIZE = 12; // Visual size in px
  const HANDLE_HIT = 22; // Touch hit area in px

  /** Handle positions for rendering */
  let handles = $derived.by(() => {
    const { x, y, w, h } = crop;
    const cx = x + w / 2;
    const cy = y + h / 2;
    return [
      { id: "tl" as HandleId, fx: x, fy: y },
      { id: "t" as HandleId, fx: cx, fy: y },
      { id: "tr" as HandleId, fx: x + w, fy: y },
      { id: "r" as HandleId, fx: x + w, fy: cy },
      { id: "br" as HandleId, fx: x + w, fy: y + h },
      { id: "b" as HandleId, fx: cx, fy: y + h },
      { id: "bl" as HandleId, fx: x, fy: y + h },
      { id: "l" as HandleId, fx: x, fy: cy },
    ];
  });

  /** Start dragging a handle or the crop area */
  function onPointerDown(e: PointerEvent, handle: HandleId) {
    if (disabled) return;
    e.preventDefault();
    e.stopPropagation();
    activeHandle = handle;
    containerRef?.setPointerCapture(e.pointerId);
  }

  /** Handle pointer move — update crop based on active handle */
  function onPointerMove(e: PointerEvent) {
    if (!activeHandle || !containerRef) return;
    e.preventDefault();

    const rect = containerRef.getBoundingClientRect();
    // Normalized position (0-1) of pointer within container
    const nx = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
    const ny = Math.max(0, Math.min(1, (e.clientY - rect.top) / rect.height));

    let { x, y, w, h } = crop;

    if (activeHandle === "move") {
      // Move entire crop rectangle
      const dx = nx - (x + w / 2);
      const dy = ny - (y + h / 2);
      x = Math.max(0, Math.min(1 - w, x + dx));
      y = Math.max(0, Math.min(1 - h, y + dy));
    } else {
      // Resize from handle
      const right = x + w;
      const bottom = y + h;

      if (activeHandle.includes("l")) x = Math.min(nx, right - 0.02);
      if (activeHandle.includes("r")) w = Math.max(0.02, nx - x);
      if (activeHandle === "l") w = right - x;

      if (activeHandle.includes("t") && activeHandle !== "tr" && activeHandle !== "tl") {
        y = Math.min(ny, bottom - 0.02);
        h = bottom - y;
      }
      if (activeHandle === "tl" || activeHandle === "t") {
        y = Math.min(ny, bottom - 0.02);
        h = bottom - y;
      }
      if (activeHandle === "tl" || activeHandle === "tr") {
        y = Math.min(ny, bottom - 0.02);
        h = bottom - y;
      }
      if (activeHandle.includes("b")) h = Math.max(0.02, ny - y);

      // Recalculate after edge drags
      if (activeHandle === "l" || activeHandle === "bl" || activeHandle === "tl") {
        w = right - x;
      }
      if (activeHandle === "r" || activeHandle === "br" || activeHandle === "tr") {
        w = nx - x;
      }
      if (activeHandle === "t" || activeHandle === "tl" || activeHandle === "tr") {
        y = Math.min(ny, bottom - 0.02);
        h = bottom - y;
      }
      if (activeHandle === "b" || activeHandle === "bl" || activeHandle === "br") {
        h = Math.max(0.02, ny - y);
      }

      // Enforce aspect ratio if set
      if (aspectRatio !== null) {
        // Adjust height to match aspect ratio based on width
        const sourceAspect = sourceWidth / sourceHeight;
        const desiredH = (w * sourceAspect) / aspectRatio;
        if (activeHandle.includes("t")) {
          y = y + h - desiredH;
        }
        h = desiredH;
      }
    }

    // Clamp all values to 0-1
    x = Math.max(0, Math.min(1, x));
    y = Math.max(0, Math.min(1, y));
    w = Math.max(0.02, Math.min(1 - x, w));
    h = Math.max(0.02, Math.min(1 - y, h));

    crop = { x, y, w, h };
  }

  function onPointerUp(e: PointerEvent) {
    if (activeHandle) {
      containerRef?.releasePointerCapture(e.pointerId);
      activeHandle = null;
    }
  }

  /** Apply aspect ratio when mode changes */
  function applyAspect(mode: AspectMode) {
    aspectMode = mode;
    if (aspectMode === "free") return;

    const ratio = mode === "1:1" ? 1
      : mode === "16:9" ? 16 / 9
      : mode === "4:3" ? 4 / 3
      : customW / (customH || 1);

    // Adjust current crop to match ratio, centered
    const sourceAspect = sourceWidth / sourceHeight;
    let newW = crop.w;
    let newH = (newW * sourceAspect) / ratio;
    if (newH > 1) {
      newH = crop.h;
      newW = (newH * ratio) / sourceAspect;
    }
    const cx = crop.x + crop.w / 2;
    const cy = crop.y + crop.h / 2;
    crop = {
      x: Math.max(0, Math.min(1 - newW, cx - newW / 2)),
      y: Math.max(0, Math.min(1 - newH, cy - newH / 2)),
      w: Math.min(1, newW),
      h: Math.min(1, newH),
    };
  }

  /** Pixel dimensions of the crop region for display */
  let cropPixelW = $derived(Math.round(crop.w * sourceWidth));
  let cropPixelH = $derived(Math.round(crop.h * sourceHeight));
</script>

<!-- Aspect ratio chips -->
<div class="mb-3 flex flex-wrap items-center gap-2">
  <span class="text-xs font-medium text-white/40 uppercase tracking-wider">Aspect</span>
  {#each ["free", "1:1", "16:9", "4:3", "custom"] as mode}
    <button
      class="rounded-lg px-3 py-1 text-xs font-medium transition-colors
        {aspectMode === mode
          ? 'bg-brand-500 text-white'
          : 'bg-white/5 text-white/50 hover:bg-white/10'}"
      onclick={() => applyAspect(mode as AspectMode)}
      {disabled}
    >
      {mode === "free" ? "Free" : mode === "custom" ? "Custom" : mode}
    </button>
  {/each}
  {#if aspectMode === "custom"}
    <div class="flex items-center gap-1">
      <input
        type="number"
        min="1"
        max="99"
        bind:value={customW}
        onchange={() => applyAspect("custom")}
        class="w-12 rounded border border-white/10 bg-white/5 px-1.5 py-0.5 text-center text-xs text-white/70"
        {disabled}
      />
      <span class="text-xs text-white/30">:</span>
      <input
        type="number"
        min="1"
        max="99"
        bind:value={customH}
        onchange={() => applyAspect("custom")}
        class="w-12 rounded border border-white/10 bg-white/5 px-1.5 py-0.5 text-center text-xs text-white/70"
        {disabled}
      />
    </div>
  {/if}
</div>

<!-- Crop overlay container -->
<!-- svelte-ignore a11y_no_static_element_interactions -->
<div
  bind:this={containerRef}
  class="relative select-none overflow-hidden rounded-lg"
  style="touch-action: none;"
  onpointermove={onPointerMove}
  onpointerup={onPointerUp}
  onpointercancel={onPointerUp}
>
  <!-- Slot for the source image -->
  <slot />

  {#if !disabled}
    <!-- Dark overlay masks (4 rects around the crop) -->
    <!-- Top -->
    <div
      class="pointer-events-none absolute left-0 top-0 w-full bg-black/60"
      style="height: {crop.y * 100}%;"
    ></div>
    <!-- Bottom -->
    <div
      class="pointer-events-none absolute bottom-0 left-0 w-full bg-black/60"
      style="height: {(1 - crop.y - crop.h) * 100}%;"
    ></div>
    <!-- Left -->
    <div
      class="pointer-events-none absolute bg-black/60"
      style="top: {crop.y * 100}%; left: 0; width: {crop.x * 100}%; height: {crop.h * 100}%;"
    ></div>
    <!-- Right -->
    <div
      class="pointer-events-none absolute bg-black/60"
      style="top: {crop.y * 100}%; right: 0; width: {(1 - crop.x - crop.w) * 100}%; height: {crop.h * 100}%;"
    ></div>

    <!-- Crop border (dashed) -->
    <div
      class="pointer-events-none absolute border border-dashed border-white/60"
      style="left: {crop.x * 100}%; top: {crop.y * 100}%; width: {crop.w * 100}%; height: {crop.h * 100}%;"
    >
      <!-- Rule-of-thirds grid -->
      <div class="absolute left-1/3 top-0 h-full w-px bg-white/15"></div>
      <div class="absolute left-2/3 top-0 h-full w-px bg-white/15"></div>
      <div class="absolute left-0 top-1/3 h-px w-full bg-white/15"></div>
      <div class="absolute left-0 top-2/3 h-px w-full bg-white/15"></div>
    </div>

    <!-- Move handle (invisible, covers crop area) -->
    <!-- svelte-ignore a11y_no_static_element_interactions -->
    <div
      class="absolute cursor-move"
      style="left: {crop.x * 100}%; top: {crop.y * 100}%; width: {crop.w * 100}%; height: {crop.h * 100}%;"
      onpointerdown={(e) => onPointerDown(e, "move")}
    ></div>

    <!-- Drag handles -->
    {#each handles as { id, fx, fy }}
      <!-- svelte-ignore a11y_no_static_element_interactions -->
      <div
        class="absolute -translate-x-1/2 -translate-y-1/2"
        style="left: {fx * 100}%; top: {fy * 100}%;
          width: {HANDLE_HIT}px; height: {HANDLE_HIT}px;
          cursor: {id === 'tl' || id === 'br' ? 'nwse-resize'
            : id === 'tr' || id === 'bl' ? 'nesw-resize'
            : id === 't' || id === 'b' ? 'ns-resize' : 'ew-resize'};"
        onpointerdown={(e) => onPointerDown(e, id)}
      >
        <div
          class="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 rounded-sm border border-white/80 bg-brand-500"
          style="width: {HANDLE_SIZE}px; height: {HANDLE_SIZE}px;"
        ></div>
      </div>
    {/each}
  {/if}
</div>

<!-- Crop dimensions display -->
<div class="mt-2 text-center text-xs text-white/40">
  {cropPixelW} x {cropPixelH} px
</div>
```

- [ ] **Step 2: Run typecheck**

```bash
cd site && bunx astro check 2>&1 | tail -20
```

- [ ] **Step 3: Commit**

```bash
git add site/src/components/tools/convert/CropOverlay.svelte
git commit -m "feat(convert): add CropOverlay component with draggable handles and aspect ratios"
```

---

## Chunk 5: Integrate into Settings, Orchestrator, and Result

### Task 7: Update ConvertSettings.svelte

**Files:**
- Modify: `site/src/components/tools/convert/ConvertSettings.svelte`

- [ ] **Step 1: Add output format chips, crop toggle, and conditional GIF options**

Key changes:
- Add `outputFormat` chip row (WebP | GIF) before quality slider
- Add crop section: checkbox toggle → when enabled, renders `CropOverlay` around a `<slot>` for the preview image
- Move lossless toggle to be WebP-only
- Add `sourcePreviewUrl` prop for rendering preview inside crop overlay
- Update button text to reflect output format

Full replacement of `ConvertSettings.svelte`:

```svelte
<script lang="ts">
  /**
   * Settings panel for the Convert tool.
   * Output format, quality, crop, max dimension, FPS, lossless, loop count.
   */
  import type { ConvertOptions, SourceInfo, CropRect } from "../../../lib/convert/types";
  import CropOverlay from "./CropOverlay.svelte";

  let {
    options = $bindable(),
    info,
    disabled = false,
    sourcePreviewUrl = "",
    onconvert,
  }: {
    options: ConvertOptions;
    info: SourceInfo;
    disabled?: boolean;
    sourcePreviewUrl?: string;
    onconvert: () => void;
  } = $props();

  /** Show quality slider only in lossy mode (WebP) or always for GIF */
  let showQuality = $derived(options.outputFormat === "gif" || !options.lossless);

  /** Show target FPS control for video and WebP sources (GIFs have intrinsic timing) */
  let showFps = $derived(info.format === "video" || info.format === "webp");

  /** Crop enabled toggle */
  let cropEnabled = $state(false);

  /** Initialize crop when toggled on */
  function toggleCrop() {
    cropEnabled = !cropEnabled;
    if (cropEnabled && !options.crop) {
      options.crop = { x: 0.1, y: 0.1, w: 0.8, h: 0.8 };
    } else if (!cropEnabled) {
      options.crop = null;
    }
  }
</script>

<div class="space-y-5 rounded-2xl border border-white/8 bg-white/[0.03] p-5">
  <!-- Output format -->
  <div>
    <label class="mb-2 block text-xs font-medium text-white/40 uppercase tracking-wider">
      Output Format
    </label>
    <div class="flex gap-2">
      {#each ["webp", "gif"] as fmt}
        <button
          class="rounded-lg px-4 py-2 text-sm font-medium transition-colors
            {options.outputFormat === fmt
              ? 'bg-brand-500 text-white'
              : 'bg-white/5 text-white/50 hover:bg-white/10'}"
          onclick={() => { options.outputFormat = fmt as "webp" | "gif"; }}
          {disabled}
        >
          {fmt.toUpperCase()}
        </button>
      {/each}
    </div>
  </div>

  <!-- Quality slider -->
  {#if showQuality}
    <div>
      <div class="mb-2 flex items-center justify-between">
        <label class="text-xs font-medium text-white/40 uppercase tracking-wider">
          {options.outputFormat === "gif" ? "Color quality" : "Quality"}
        </label>
        <span class="text-sm font-mono text-white/60">{options.quality}</span>
      </div>
      <input
        type="range"
        min="1"
        max="100"
        step="1"
        bind:value={options.quality}
        {disabled}
        class="w-full accent-brand-500"
      />
      <div class="mt-1 flex justify-between text-xs text-white/30">
        <span>{options.outputFormat === "gif" ? "Fewer colors" : "Smallest"}</span>
        <span>{options.outputFormat === "gif" ? "More colors" : "Best quality"}</span>
      </div>
    </div>
  {/if}

  <!-- Crop section -->
  <div>
    <label class="flex cursor-pointer items-center gap-3">
      <input
        type="checkbox"
        checked={cropEnabled}
        onchange={toggleCrop}
        {disabled}
        class="h-4 w-4 rounded border-white/20 bg-white/5 text-brand-500 accent-brand-500"
      />
      <span class="text-sm text-white/60">Crop</span>
    </label>

    {#if cropEnabled && options.crop && sourcePreviewUrl}
      <div class="mt-3">
        <CropOverlay
          bind:crop={options.crop}
          sourceWidth={info.width}
          sourceHeight={info.height}
          {disabled}
        >
          <img
            src={sourcePreviewUrl}
            alt="Source preview"
            class="block w-full"
            draggable="false"
          />
        </CropOverlay>
      </div>
    {/if}
  </div>

  <!-- Max dimension -->
  <div>
    <label class="mb-2 block text-xs font-medium text-white/40 uppercase tracking-wider">
      Max dimension <span class="text-white/25">(0 = no resize)</span>
    </label>
    <input
      type="number"
      min="0"
      max="4096"
      step="1"
      bind:value={options.maxDimension}
      {disabled}
      placeholder="0"
      class="w-full rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-sm text-white/80 placeholder:text-white/25 focus:border-brand-500/50 focus:outline-none"
    />
  </div>

  <!-- Target FPS (video/WebP only) -->
  {#if showFps}
    <div>
      <div class="mb-2 flex items-center justify-between">
        <label class="text-xs font-medium text-white/40 uppercase tracking-wider">
          Target FPS
        </label>
        <span class="text-sm font-mono text-white/60">{options.targetFps}</span>
      </div>
      <input
        type="range"
        min="1"
        max="30"
        step="1"
        bind:value={options.targetFps}
        {disabled}
        class="w-full accent-brand-500"
      />
      <div class="mt-1 flex justify-between text-xs text-white/30">
        <span>1 fps</span>
        <span>30 fps</span>
      </div>
    </div>
  {/if}

  <!-- Loop count -->
  <div>
    <label class="mb-2 block text-xs font-medium text-white/40 uppercase tracking-wider">
      Loop count <span class="text-white/25">(0 = infinite)</span>
    </label>
    <input
      type="number"
      min="0"
      max="65535"
      step="1"
      bind:value={options.loops}
      {disabled}
      placeholder="0"
      class="w-full rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-sm text-white/80 placeholder:text-white/25 focus:border-brand-500/50 focus:outline-none"
    />
  </div>

  <!-- Lossless toggle (WebP only) -->
  {#if options.outputFormat === "webp"}
    <label class="flex cursor-pointer items-center gap-3">
      <input
        type="checkbox"
        bind:checked={options.lossless}
        {disabled}
        class="h-4 w-4 rounded border-white/20 bg-white/5 text-brand-500 accent-brand-500"
      />
      <span class="text-sm text-white/60">Lossless</span>
    </label>
  {/if}

  <!-- Source info summary -->
  <div class="flex flex-wrap gap-3 text-xs text-white/40">
    <span>{info.width} x {info.height}</span>
    <span>{info.frameCount} frames</span>
    <span>{info.fps} fps</span>
    <span>{(info.totalDuration / 1000).toFixed(1)}s</span>
  </div>

  <!-- Convert button -->
  <button
    onclick={onconvert}
    {disabled}
    class="w-full rounded-xl bg-brand-500 px-6 py-3 text-sm font-semibold text-white transition-all hover:bg-brand-400 hover:shadow-lg hover:shadow-brand-500/25 disabled:opacity-40 disabled:cursor-not-allowed"
  >
    {#if disabled}
      Converting...
    {:else}
      Convert to {options.outputFormat.toUpperCase()}
    {/if}
  </button>
</div>
```

- [ ] **Step 2: Commit**

```bash
git add site/src/components/tools/convert/ConvertSettings.svelte
git commit -m "feat(convert): add output format selector, crop toggle, and GIF-specific options"
```

### Task 8: Update ConvertTool.svelte

**Files:**
- Modify: `site/src/components/tools/convert/ConvertTool.svelte`

- [ ] **Step 1: Add WebP input routing, GIF encoder routing, source preview URL**

Key changes:
- Add `image/webp` to accepted types
- Import `decodeWebP` and `encodeAnimatedGif`
- Add `isWebP()` detection function
- Route decoder based on input type
- Route encoder based on `options.outputFormat`
- Set default output format based on input type (WebP→GIF, GIF→WebP, video→WebP)
- Create `sourcePreviewUrl` for the crop overlay preview
- Add new `outputFormat` and `crop` defaults to options
- Pass `sourcePreviewUrl` to ConvertSettings

Full replacement of `ConvertTool.svelte`:

```svelte
<script lang="ts">
  /**
   * Convert tool — GIF/video/WebP to animated WebP or GIF.
   * States: idle → decoding → settings → converting → done → error
   */
  import FileDropZone from "../shared/FileDropZone.svelte";
  import ConvertSettings from "./ConvertSettings.svelte";
  import ConvertResult from "./ConvertResult.svelte";
  import ConvertProgressBar from "./ConvertProgress.svelte";
  import { decodeGif } from "../../../lib/convert/gif-decoder";
  import { decodeVideo } from "../../../lib/convert/video-decoder";
  import { decodeWebP } from "../../../lib/convert/webp-decoder";
  import { encodeAnimatedWebP } from "../../../lib/convert/encoder";
  import { encodeAnimatedGif } from "../../../lib/convert/gif-encoder";
  import type {
    ConvertOptions,
    ConvertProgress,
    DecodedFrame,
    SourceInfo,
  } from "../../../lib/convert/types";

  type State = "idle" | "decoding" | "settings" | "converting" | "done" | "error";

  let state: State = $state("idle");
  let error = $state("");
  let sourceFile: File | null = $state(null);
  let sourceInfo: SourceInfo | null = $state(null);
  let frames: DecodedFrame[] = $state([]);
  let resultBlob: Blob | null = $state(null);
  let resultUrl = $state("");
  let sourcePreviewUrl = $state("");
  let progress: ConvertProgress = $state({
    phase: "decoding",
    percent: 0,
    frame: 0,
    total: 0,
  });

  let options: ConvertOptions = $state({
    quality: 75,
    lossless: false,
    maxDimension: 0,
    loops: 0,
    targetFps: 10,
    outputFormat: "webp",
    crop: null,
  });

  /** Accepted file types: GIF, WebP, and common video formats */
  const acceptTypes = "image/gif,image/webp,video/mp4,video/webm,video/quicktime,video/x-matroska";

  function isGif(file: File): boolean {
    return file.type === "image/gif" || file.name.toLowerCase().endsWith(".gif");
  }

  function isWebP(file: File): boolean {
    return file.type === "image/webp" || file.name.toLowerCase().endsWith(".webp");
  }

  async function handleFile(files: File[]) {
    const file = files[0];
    if (!file) return;

    sourceFile = file;
    state = "decoding";
    error = "";

    // Create source preview URL for crop overlay
    if (sourcePreviewUrl) URL.revokeObjectURL(sourcePreviewUrl);
    sourcePreviewUrl = URL.createObjectURL(file);

    try {
      let decoded: { frames: DecodedFrame[]; info: SourceInfo };

      if (isGif(file)) {
        const buffer = await file.arrayBuffer();
        decoded = await decodeGif(buffer, (p) => { progress = p; });
        // GIF input → default output WebP
        options.outputFormat = "webp";
      } else if (isWebP(file)) {
        decoded = await decodeWebP(file, options.targetFps, 300, (p) => { progress = p; });
        // WebP input → default output GIF
        options.outputFormat = "gif";
      } else if (file.type.startsWith("video/")) {
        decoded = await decodeVideo(file, options.targetFps, 300, (p) => { progress = p; });
        // Video input → default output WebP
        options.outputFormat = "webp";
      } else {
        throw new Error(`Unsupported format: ${file.type || file.name}`);
      }

      frames = decoded.frames;
      sourceInfo = decoded.info;

      // Set initial target FPS to match source for GIFs
      if (decoded.info.format === "gif") {
        options.targetFps = Math.round(decoded.info.fps);
      }

      state = "settings";
    } catch (e) {
      error = e instanceof Error ? e.message : "Failed to decode file";
      state = "error";
    }
  }

  async function handleConvert() {
    if (!sourceInfo || !frames.length) return;
    state = "converting";
    error = "";

    try {
      let blob: Blob;

      if (options.outputFormat === "gif") {
        blob = await encodeAnimatedGif(
          frames,
          sourceInfo.width,
          sourceInfo.height,
          options,
          sourceInfo.fps,
          (p) => { progress = p; },
        );
      } else {
        blob = await encodeAnimatedWebP(
          frames,
          sourceInfo.width,
          sourceInfo.height,
          options,
          sourceInfo.fps,
          (p) => { progress = p; },
        );
      }

      // Clean up previous result
      if (resultUrl) URL.revokeObjectURL(resultUrl);
      resultBlob = blob;
      resultUrl = URL.createObjectURL(blob);
      state = "done";
    } catch (e) {
      error = e instanceof Error ? e.message : "Encoding failed";
      state = "error";
    }
  }

  function reset() {
    if (resultUrl) URL.revokeObjectURL(resultUrl);
    if (sourcePreviewUrl) URL.revokeObjectURL(sourcePreviewUrl);
    sourceFile = null;
    sourceInfo = null;
    frames = [];
    resultBlob = null;
    resultUrl = "";
    sourcePreviewUrl = "";
    state = "idle";
    error = "";
  }
</script>

<div class="space-y-6">
  {#if state === "idle"}
    <FileDropZone
      accept={acceptTypes}
      label="Drop a GIF, WebP, or video here to convert"
      onfile={handleFile}
    />
    <p class="text-center text-xs text-white/30">
      Supports GIF, WebP, MP4, WebM, MOV, MKV
    </p>

  {:else if state === "decoding"}
    <ConvertProgressBar {progress} />

  {:else if (state === "settings" || state === "converting") && sourceInfo}
    <!-- Source file info -->
    <div class="rounded-2xl border border-white/8 bg-white/[0.03] p-4">
      <div class="flex items-center justify-between">
        <div class="text-sm text-white/50">
          <span class="font-medium text-white/80">{sourceFile?.name}</span>
          <span class="ml-2">{sourceInfo.width} x {sourceInfo.height}</span>
          <span class="ml-2">{sourceInfo.frameCount} frames</span>
        </div>
        <button
          onclick={reset}
          class="text-xs text-white/40 hover:text-white/60 transition-colors"
        >
          Change file
        </button>
      </div>
    </div>

    <ConvertSettings
      bind:options
      info={sourceInfo}
      disabled={state === "converting"}
      {sourcePreviewUrl}
      onconvert={handleConvert}
    />

    {#if state === "converting"}
      <ConvertProgressBar {progress} />
    {/if}

  {:else if state === "done" && resultBlob && sourceFile}
    <ConvertResult
      originalSize={sourceFile.size}
      resultSize={resultBlob.size}
      {resultUrl}
      filename={sourceFile.name}
      frameCount={frames.length}
      outputFormat={options.outputFormat}
    />

    <div class="flex gap-3">
      <button
        onclick={() => { state = "settings"; }}
        class="flex-1 rounded-xl border border-white/10 bg-white/5 px-4 py-2.5 text-sm font-medium text-white/60 transition-colors hover:bg-white/10"
      >
        Adjust settings
      </button>
      <button
        onclick={reset}
        class="flex-1 rounded-xl border border-white/10 bg-white/5 px-4 py-2.5 text-sm font-medium text-white/60 transition-colors hover:bg-white/10"
      >
        New file
      </button>
    </div>

  {:else if state === "error"}
    <div class="rounded-2xl border border-red-500/20 bg-red-500/5 p-6 text-center">
      <p class="text-sm text-red-400">{error}</p>
      <button
        onclick={reset}
        class="mt-4 rounded-lg border border-white/10 bg-white/5 px-4 py-2 text-sm text-white/60 transition-colors hover:bg-white/10"
      >
        Try again
      </button>
    </div>
  {/if}
</div>
```

- [ ] **Step 2: Commit**

```bash
git add site/src/components/tools/convert/ConvertTool.svelte
git commit -m "feat(convert): add WebP input decoding, GIF output routing, crop support"
```

### Task 9: Update ConvertResult.svelte for dynamic output format

**Files:**
- Modify: `site/src/components/tools/convert/ConvertResult.svelte`

- [ ] **Step 1: Add outputFormat prop, update labels and filename**

Add `outputFormat` prop (default `"webp"`). Update:
- "WebP" label → dynamic format label
- Output filename extension → dynamic based on format
- Alt text → dynamic

```svelte
<script lang="ts">
  /**
   * Result preview, size stats, and download button.
   * Supports WebP and GIF output formats.
   */
  import { formatFileSize, formatSavings } from "../shared/format-size";

  let {
    originalSize,
    resultSize,
    resultUrl,
    filename,
    frameCount,
    outputFormat = "webp",
  }: {
    originalSize: number;
    resultSize: number;
    resultUrl: string;
    filename: string;
    frameCount: number;
    outputFormat?: "webp" | "gif";
  } = $props();

  /** Generate output filename with correct extension */
  let outputName = $derived(() => {
    const base = filename.replace(/\.[^.]+$/, "");
    return `${base}.${outputFormat}`;
  });

  let formatLabel = $derived(outputFormat.toUpperCase());
  let savings = $derived(formatSavings(originalSize, resultSize));
  let savedBytes = $derived(originalSize - resultSize);
  let isSmaller = $derived(resultSize < originalSize);
</script>

<div class="space-y-4">
  <!-- Animated preview -->
  <div class="rounded-2xl border border-white/8 bg-white/[0.03] p-4">
    <img
      src={resultUrl}
      alt="Animated {formatLabel} preview"
      class="mx-auto max-h-80 rounded-lg object-contain"
    />
  </div>

  <!-- Stats grid -->
  <div class="rounded-2xl border border-white/8 bg-white/[0.03] p-5">
    <div class="mb-4 grid grid-cols-3 gap-4 text-center">
      <div>
        <p class="text-xs text-white/40 uppercase tracking-wider">Original</p>
        <p class="mt-1 text-sm font-mono text-white/70">{formatFileSize(originalSize)}</p>
      </div>
      <div>
        <p class="text-xs text-white/40 uppercase tracking-wider">{formatLabel}</p>
        <p class="mt-1 text-sm font-mono text-white/70">{formatFileSize(resultSize)}</p>
      </div>
      <div>
        <p class="text-xs text-white/40 uppercase tracking-wider">Savings</p>
        <p class="mt-1 text-sm font-mono {isSmaller ? 'text-green-400' : 'text-red-400'}">
          {savings}
        </p>
      </div>
    </div>

    <div class="mb-4 flex justify-center gap-4 text-xs text-white/40">
      <span>{frameCount} frames</span>
      {#if isSmaller}
        <span>Saved {formatFileSize(savedBytes)}</span>
      {/if}
    </div>

    <!-- Download button -->
    <a
      href={resultUrl}
      download={outputName()}
      class="flex w-full items-center justify-center gap-2 rounded-xl bg-brand-500 px-6 py-3 text-sm font-semibold text-white transition-all hover:bg-brand-400 hover:shadow-lg hover:shadow-brand-500/25"
    >
      <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="2" stroke="currentColor" class="h-4 w-4">
        <path stroke-linecap="round" stroke-linejoin="round" d="M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5M16.5 12L12 16.5m0 0L7.5 12m4.5 4.5V3" />
      </svg>
      Download {outputName()}
    </a>
  </div>
</div>
```

- [ ] **Step 2: Commit**

```bash
git add site/src/components/tools/convert/ConvertResult.svelte
git commit -m "feat(convert): update result component for dynamic WebP/GIF output format"
```

---

## Chunk 6: Build, Typecheck, and Browser Test

### Task 10: Run full typecheck and build

- [ ] **Step 1: Run typecheck**

```bash
cd site && bunx astro check 2>&1
```

Fix any type errors that surface.

- [ ] **Step 2: Run build**

```bash
cd site && bun run build 2>&1
```

Fix any build errors.

- [ ] **Step 3: Commit any fixes**

```bash
git add -A site/src/
git commit -m "fix(convert): resolve type and build errors for WebP/GIF/crop integration"
```

### Task 11: Push, verify CI, test live site

- [ ] **Step 1: Push to origin**

```bash
git push origin main
```

- [ ] **Step 2: Verify CI passes**

```bash
gh run list --limit 3
gh run watch  # wait for completion
```

- [ ] **Step 3: Test on live site using Playwright**

Test the following flows:
1. Upload animated GIF → select GIF output → convert → verify GIF plays
2. Upload animated WebP → verify it auto-selects GIF output → convert → verify
3. Upload GIF → enable crop → drag handles → convert → verify cropped output
4. Upload video → convert with crop → verify

For each test: navigate to `https://embeddy.link/tools/convert`, upload file, check settings UI, run conversion, verify result preview and download.

- [ ] **Step 4: Test on mobile viewport (390x844)**

Verify crop overlay touch interactions work on mobile viewport.

---

## Chunk 7: Inspect Tab — Animated WebP Dimensions Fix

### Task 12: Fix Android MetadataEngine for animated WebP dimensions

**Files:**
- Modify: `app/src/main/kotlin/app/embeddy/inspect/MetadataEngine.kt`

- [ ] **Step 1: Investigate current behavior**

The current code uses `ExifInterface` for image dimensions, which may not read animated WebP correctly. Add a `BitmapFactory.decodeStream()` fallback with `inJustDecodeBounds = true` to reliably get width/height for all image formats including animated WebP.

- [ ] **Step 2: Add BitmapFactory fallback for image dimensions**

After the EXIF block, check if width/height were found. If not, use BitmapFactory:

```kotlin
// Fallback: use BitmapFactory for dimensions if EXIF didn't provide them
if (!metadata.containsKey("Image Width") || metadata["Image Width"] == "0") {
    try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                metadata["Image Width"] = opts.outWidth.toString()
                metadata["Image Height"] = opts.outHeight.toString()
            }
        }
    } catch (_: Exception) { /* ignore */ }
}
```

- [ ] **Step 3: Build and test on device**

```bash
cd app && ./gradlew assembleDebug 2>&1 | tail -5
adb -s 10.0.0.244:40243 install -r app/build/outputs/apk/debug/app-debug.apk
```

Test: open Inspect tab, load an animated WebP, verify dimensions display.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/app/embeddy/inspect/MetadataEngine.kt
git commit -m "fix(app): add BitmapFactory fallback for animated WebP dimensions in Inspect"
```

### Task 13: Investigate web Inspect for WebP dimensions

**Files:**
- Investigate: `worker/src/index.ts`

- [ ] **Step 1: Check if web Inspect shows image dimensions at all**

The worker Inspect endpoint (`/api/inspect`) only extracts OG/meta tags from HTML pages — it doesn't fetch and parse image files directly. Image dimensions are not part of the current Inspect feature for URLs.

If the user expects image dimensions when inspecting a URL that contains a WebP, this would require fetching the image and parsing RIFF headers — a separate feature. Document as a TODO if needed, otherwise this task is N/A for the web inspect tool.

- [ ] **Step 2: If applicable, add VP8X header parsing for WebP**

Only if the web Inspect tool is expected to show image file dimensions (not just OG metadata).

---

## Summary

| Task | Description | Files |
|------|-------------|-------|
| 1 | Add gifenc dependency | package.json, bun.lock |
| 2 | Update types.ts | types.ts |
| 3 | Add cropFrame + export shared utils | encoder.ts |
| 4 | Create WebP decoder | webp-decoder.ts (NEW) |
| 5 | Create GIF encoder | gif-encoder.ts (NEW) |
| 6 | Create CropOverlay component | CropOverlay.svelte (NEW) |
| 7 | Update ConvertSettings | ConvertSettings.svelte |
| 8 | Update ConvertTool orchestrator | ConvertTool.svelte |
| 9 | Update ConvertResult | ConvertResult.svelte |
| 10 | Typecheck + build | Fix any errors |
| 11 | Push, CI, live test | Playwright browser tests |
| 12 | Fix Android Inspect WebP dimensions | MetadataEngine.kt |
| 13 | Investigate web Inspect dimensions | worker/src/index.ts |
