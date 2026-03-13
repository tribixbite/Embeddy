# Convert Tool Enhancement: WebP Input, GIF Output, Crop UI

**Date:** 2026-03-13
**Scope:** Browser Convert tool (`site/src/components/tools/convert/`), Inspect tab (web + Android)

## Summary

Extend the Convert tool to:
1. Accept animated WebP as input and decode frames via video-style canvas capture
2. Output animated GIF using the `gifenc` library
3. Add a draggable crop overlay on the source preview with aspect ratio support
4. Fix animated WebP dimension reporting in the Inspect tab (web worker + Android app)

## Current Architecture

Pipeline: **idle → decode → settings → encode → done**

- **Decoders:** `gif-decoder.ts` (gifuct-js), `video-decoder.ts` (<video> + canvas seek)
- **Encoder:** `encoder.ts` (wasm-webp animated WebP output)
- **Settings:** `ConvertSettings.svelte` — quality, lossless, maxDimension, loops, targetFps
- **Types:** `types.ts` — `DecodedFrame`, `ConvertOptions`, `ProgressInfo`

## Design

### 1. WebP Decoder (`site/src/lib/convert/webp-decoder.ts`)

**Approach:** Video-style frame extraction — load animated WebP into an `<img>` element, draw to canvas at timed intervals.

**Interface:** Returns `DecodedFrame[]` matching existing decoder contract:
```typescript
interface DecodedFrame {
  rgba: Uint8Array;   // RGBA pixel data
  delay: number;      // Frame delay in milliseconds
}
```

**Implementation:**
- Create `<img>` element, set `src` to object URL of input file
- Wait for `onload` event to get natural dimensions
- For **static WebP**: draw once to canvas, return single frame with delay 0
- For **animated WebP**: use a timed capture loop at `targetFps` intervals
  - Draw `<img>` to an OffscreenCanvas (or DOM canvas fallback)
  - Extract RGBA via `getImageData()`
  - Capture for a configurable duration or until frames stop changing (pixel comparison)
  - Apply same frame delay calculation as video decoder: `delayMs = 1000 / targetFps`
- Detection of animated vs static: attempt multi-frame capture; if first two frames are identical, treat as static
- Frame limit: same 300-frame safety cap as video decoder
- Export: `decodeWebP(file: File, targetFps: number, maxFrames: number, onProgress?): Promise<{ frames: DecodedFrame[], width: number, height: number }>`

**Limitations:** Frame timing is approximate (browser render-loop dependent), not bit-exact from WebP container. Acceptable for the GIF output use case.

### 2. GIF Encoder (`site/src/lib/convert/gif-encoder.ts`)

**Library:** `gifenc` — lightweight (~8KB), ESM, handles palette quantization and frame optimization.

**API design:**
```typescript
export async function encodeAnimatedGif(
  frames: DecodedFrame[],
  width: number,
  height: number,
  options: ConvertOptions,
  onProgress?: (info: ProgressInfo) => void,
): Promise<Blob>
```

**Implementation:**
1. Import `gifenc` (lazy, like wasm-webp pattern)
2. Create GIF encoder: `GIFEncoder()` from gifenc
3. For each frame:
   a. Apply crop (if set) via `cropFrame()`
   b. Apply resize via existing `resizeFrame()`
   c. Quantize RGBA → 256-color palette using gifenc's `quantize()`
   d. Map pixels to palette indices via `applyPalette()`
   e. Add frame with delay and optional transparency
   f. Report progress
4. Finish encoding, return `new Blob([bytes], { type: "image/gif" })`

**GIF-specific options (new):**
- `dithering: boolean` — Floyd-Steinberg dithering during quantization (default true)
- `transparentBackground: boolean` — use transparency for unchanged pixels between frames (default false)

### 3. Type Changes (`site/src/lib/convert/types.ts`)

```typescript
export interface CropRect {
  x: number;  // 0-1 fraction of source width (left edge)
  y: number;  // 0-1 fraction of source height (top edge)
  w: number;  // 0-1 fraction of source width (crop width)
  h: number;  // 0-1 fraction of source height (crop height)
}

export interface ConvertOptions {
  quality: number;
  lossless: boolean;
  maxDimension: number;
  loops: number;
  targetFps: number;
  outputFormat: "webp" | "gif";     // NEW
  crop: CropRect | null;            // NEW
  dithering: boolean;               // NEW — GIF only
}
```

Progress phases updated:
```typescript
export type ProgressPhase = "decoding" | "cropping" | "resizing" | "encoding";
```

### 4. Crop Overlay (`site/src/components/tools/convert/CropOverlay.svelte`)

**Visual design:**
- Semi-transparent dark overlay outside the crop region
- Bright/clear area inside the crop region
- 8 drag handles: 4 corners + 4 edge midpoints
- Dashed border on crop edges (white, 1px)
- Rule-of-thirds grid lines inside crop area (subtle white/20%)

**Interaction:**
- **Drag handles** to resize crop from edges/corners
- **Drag inside** crop area to reposition the whole rectangle
- **Touch-friendly:** handles are 24x24px visible, 44x44px hit area
- `touch-action: none` on container to prevent scroll interference
- `pointer-events` based (works for mouse + touch)

**Aspect ratio support:**
- Chips row: `Free` | `1:1` | `16:9` | `4:3` | `Custom`
- Custom shows two small numeric inputs for W:H ratio
- When ratio locked, dragging a corner/edge maintains the ratio
- Free mode: no constraints

**State:**
- Props: `sourceWidth`, `sourceHeight`, `crop` (bindable CropRect)
- Internal: tracks which handle is being dragged, active aspect ratio
- Emits normalized `CropRect` (0-1 fractions) on every drag move

**Integration point:** Rendered inside ConvertSettings when crop is enabled, positioned over the source preview image.

### 5. Crop Function (`site/src/lib/convert/encoder.ts`)

New function added before `resizeFrame`:
```typescript
function cropFrame(
  rgba: Uint8Array,
  srcW: number,
  srcH: number,
  crop: CropRect,
): { data: Uint8Array; width: number; height: number }
```

**Implementation:**
- Convert fractional crop to pixel coordinates
- Use OffscreenCanvas: putImageData full frame, then drawImage with source rect to extract crop region
- Return cropped RGBA + new dimensions

**Pipeline order in `encodeAnimatedWebP` / `encodeAnimatedGif`:**
1. Subsample frames (FPS)
2. **Crop frames** (new step)
3. Resize frames (maxDimension)
4. Encode

### 6. ConvertSettings.svelte Changes

**New sections:**
- **Output Format** — chip row: `WebP` | `GIF` (after the existing format display)
- **Crop** — toggle checkbox "Crop" → when enabled, shows CropOverlay on preview + aspect ratio chips
- **Dithering** — checkbox, only visible when output format is GIF

**Conditional visibility:**
- Lossless checkbox: WebP only
- Dithering checkbox: GIF only
- Quality slider: both formats (maps to gifenc quantization quality for GIF)

**Layout update:** Source preview image needs to be in a positioned container to support the CropOverlay absolutely positioned on top.

### 7. ConvertTool.svelte Changes

**Input format detection (updated):**
```typescript
const acceptTypes = "image/gif,image/webp,video/mp4,video/webm,video/quicktime,video/x-matroska";

function isWebP(file: File): boolean {
  return file.type === "image/webp" || file.name.toLowerCase().endsWith(".webp");
}
```

**Decoder routing:**
- GIF → `decodeGif()`
- WebP → `decodeWebP()` (new)
- Video → `decodeVideo()`

**Encoder routing:**
- `options.outputFormat === "gif"` → `encodeAnimatedGif()`
- `options.outputFormat === "webp"` → `encodeAnimatedWebP()` (existing)

**Default output format heuristic:**
- GIF input → default output WebP (current behavior, conversion)
- WebP input → default output GIF (reverse conversion)
- Video input → default output WebP (current behavior)

### 8. Inspect Tab Fix — Animated WebP Dimensions

**Web (worker/):**
- Investigate the metadata extraction endpoint for WebP files
- Animated WebP uses VP8X chunk with canvas dimensions — ensure parser reads this chunk
- The RIFF container header contains width/height in the VP8X extended header

**Android (app/):**
- Check how the Inspect screen reads image metadata
- Android's `BitmapFactory.decodeStream()` with `inJustDecodeBounds = true` should report dimensions even for animated WebP
- If using ExifInterface or a custom parser, ensure animated WebP RIFF header is handled

## File Inventory

| Action | File |
|--------|------|
| NEW | `site/src/lib/convert/webp-decoder.ts` |
| NEW | `site/src/lib/convert/gif-encoder.ts` |
| NEW | `site/src/components/tools/convert/CropOverlay.svelte` |
| MODIFY | `site/src/lib/convert/types.ts` |
| MODIFY | `site/src/lib/convert/encoder.ts` |
| MODIFY | `site/src/components/tools/convert/ConvertTool.svelte` |
| MODIFY | `site/src/components/tools/convert/ConvertSettings.svelte` |
| MODIFY | `site/src/components/tools/convert/ConvertResult.svelte` |
| ADD DEP | `gifenc` via `bun add gifenc` in site/ |
| INVESTIGATE | `worker/src/` — inspect endpoint WebP metadata |
| INVESTIGATE | `app/.../InspectScreen.kt` — animated WebP dimensions |

## Error Handling

- WebP decode failure: fall back to single-frame static decode, show warning
- GIF encode failure: surface error in UI error state with message
- Crop out of bounds: clamp to valid region (0-1 range)
- gifenc import failure: disable GIF output option, show tooltip "GIF encoding unavailable"

## Testing Plan

- Browser: upload animated WebP → verify frame extraction → encode as GIF → verify animation plays
- Browser: upload GIF → enable crop → drag overlay → compress → verify cropped output
- Browser: test all aspect ratio modes (free, 1:1, 16:9, 4:3, custom)
- Browser: test on mobile viewport (touch drag crop overlay)
- Inspect: upload animated WebP → verify dimensions shown (web + Android)
- Edge cases: very large WebP, single-frame WebP, tiny crop regions
