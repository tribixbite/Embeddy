<script lang="ts">
  /**
   * Draggable crop overlay for the Convert tool.
   * Renders on top of a source preview image (via <slot>).
   * Outputs normalized CropRect (0-1 fractions of source dimensions).
   *
   * Features:
   * - 8 drag handles (4 corners + 4 edge midpoints)
   * - Drag inside crop area to reposition
   * - Aspect ratio chips: Free / 1:1 / 16:9 / 4:3 / Custom
   * - Dark mask outside crop, dashed border, rule-of-thirds grid
   * - Touch-friendly: pointer events, 44px hit areas
   *
   * All drag/aspect geometry lives in crop-math.ts (pure, unit-tested).
   */
  import type { CropRect } from "../../../lib/convert/types";
  import {
    applyAspectToCrop,
    aspectRatioFor,
    clamp,
    resolveCropDrag,
    type AspectMode,
    type CropHandle,
  } from "./crop-math";

  type HandleId = CropHandle;

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
  /** Pointer offset from the crop origin, recorded at pointerdown (move drag) */
  let grabOffset = { dx: 0, dy: 0 };
  /** Chip elements for focus-follows-selection keyboard navigation */
  let chipRefs: HTMLButtonElement[] = [];

  /** Aspect ratio value or null for free mode — degenerate custom values guarded (M53) */
  let aspectRatio = $derived(aspectRatioFor(aspectMode, customW, customH));

  const HANDLE_SIZE = 12;
  const HANDLE_HIT = 22;

  const ASPECT_MODES: AspectMode[] = ["free", "1:1", "16:9", "4:3", "custom"];

  /** Handle positions for rendering (normalized 0-1) */
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
    if (disabled || !containerRef) return;
    e.preventDefault();
    e.stopPropagation();
    activeHandle = handle;
    if (handle === "move") {
      // Record where inside the rect the user grabbed so the crop keeps its
      // offset under the pointer instead of teleporting its center (M52).
      const rect = containerRef.getBoundingClientRect();
      grabOffset = {
        dx: (e.clientX - rect.left) / rect.width - crop.x,
        dy: (e.clientY - rect.top) / rect.height - crop.y,
      };
    }
    containerRef.setPointerCapture(e.pointerId);
  }

  /** Get cursor style for a given handle */
  function cursorFor(id: HandleId): string {
    if (id === "tl" || id === "br") return "nwse-resize";
    if (id === "tr" || id === "bl") return "nesw-resize";
    if (id === "t" || id === "b") return "ns-resize";
    if (id === "l" || id === "r") return "ew-resize";
    return "move";
  }

  /** Handle pointer move — geometry resolved by the pure crop-math solver */
  function onPointerMove(e: PointerEvent) {
    if (!activeHandle || !containerRef) return;
    e.preventDefault();

    const rect = containerRef.getBoundingClientRect();
    const nx = clamp((e.clientX - rect.left) / rect.width, 0, 1);
    const ny = clamp((e.clientY - rect.top) / rect.height, 0, 1);

    crop = resolveCropDrag({
      crop,
      handle: activeHandle,
      nx,
      ny,
      grabOffset,
      aspectRatio,
      sourceAspect: sourceWidth / sourceHeight,
    });
  }

  function onPointerUp(e: PointerEvent) {
    if (activeHandle) {
      containerRef?.releasePointerCapture(e.pointerId);
      activeHandle = null;
    }
  }

  /** Apply aspect ratio when mode changes — adjust crop to match */
  function applyAspect(mode: AspectMode) {
    aspectMode = mode;
    if (mode === "free") return;
    const ratio = aspectRatioFor(mode, customW, customH);
    if (ratio === null) return;
    crop = applyAspectToCrop(crop, ratio, sourceWidth / sourceHeight);
  }

  /**
   * Arrow-key navigation for the aspect chips (M54) — the roving tabindex
   * alone left every unselected chip keyboard-unreachable. Focus follows
   * selection, per the WAI-ARIA radiogroup pattern.
   */
  function onChipKeydown(event: KeyboardEvent) {
    const delta =
      event.key === "ArrowRight" || event.key === "ArrowDown" ? 1
      : event.key === "ArrowLeft" || event.key === "ArrowUp" ? -1
      : 0;
    if (delta === 0 || disabled) return;
    event.preventDefault();
    const index = ASPECT_MODES.indexOf(aspectMode);
    const next = (index + delta + ASPECT_MODES.length) % ASPECT_MODES.length;
    applyAspect(ASPECT_MODES[next]!);
    chipRefs[next]?.focus();
  }

  /** Pixel dimensions of the crop region */
  let cropPixelW = $derived(Math.round(crop.w * sourceWidth));
  let cropPixelH = $derived(Math.round(crop.h * sourceHeight));
</script>

<!-- Aspect ratio chips -->
<div class="mb-3 flex flex-wrap items-center gap-2">
  <span id="crop-aspect-label" class="text-xs font-medium text-white/40 uppercase tracking-wider">
    Aspect
  </span>
  <div
    role="radiogroup"
    aria-labelledby="crop-aspect-label"
    class="flex flex-wrap gap-2"
    onkeydown={onChipKeydown}
  >
    {#each ASPECT_MODES as mode, i}
      <button
        bind:this={chipRefs[i]}
        type="button"
        role="radio"
        aria-checked={aspectMode === mode}
        tabindex={aspectMode === mode ? 0 : -1}
        class="rounded-lg px-3 py-1 text-xs font-medium transition-colors
          {aspectMode === mode
            ? 'bg-brand-500 text-white'
            : 'bg-white/5 text-white/50 hover:bg-white/10'}"
        onclick={() => applyAspect(mode)}
        {disabled}
      >
        {mode === "free" ? "Free" : mode === "custom" ? "Custom" : mode}
      </button>
    {/each}
  </div>
  {#if aspectMode === "custom"}
    <div class="flex items-center gap-1">
      <label class="sr-only" for="crop-aspect-w">Custom aspect width</label>
      <input
        id="crop-aspect-w"
        type="number"
        min="1"
        max="99"
        bind:value={customW}
        onchange={() => applyAspect("custom")}
        class="w-12 rounded border border-white/10 bg-white/5 px-1.5 py-0.5 text-center text-xs text-white/70"
        {disabled}
      />
      <span class="text-xs text-white/30" aria-hidden="true">:</span>
      <label class="sr-only" for="crop-aspect-h">Custom aspect height</label>
      <input
        id="crop-aspect-h"
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
  <!-- Source image via slot -->
  <slot />

  {#if !disabled}
    <!-- Dark overlay masks (4 rects around the crop) -->
    <div
      class="pointer-events-none absolute left-0 top-0 w-full bg-black/60"
      style="height: {crop.y * 100}%;"
    ></div>
    <div
      class="pointer-events-none absolute bottom-0 left-0 w-full bg-black/60"
      style="height: {(1 - crop.y - crop.h) * 100}%;"
    ></div>
    <div
      class="pointer-events-none absolute bg-black/60"
      style="top: {crop.y * 100}%; left: 0; width: {crop.x * 100}%; height: {crop.h * 100}%;"
    ></div>
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

    <!-- Move handle (invisible, covers entire crop area) -->
    <!-- svelte-ignore a11y_no_static_element_interactions -->
    <div
      class="absolute cursor-move"
      style="left: {crop.x * 100}%; top: {crop.y * 100}%; width: {crop.w * 100}%; height: {crop.h * 100}%;"
      onpointerdown={(e) => onPointerDown(e, "move")}
    ></div>

    <!-- 8 drag handles -->
    {#each handles as { id, fx, fy }}
      <!-- svelte-ignore a11y_no_static_element_interactions -->
      <div
        class="absolute -translate-x-1/2 -translate-y-1/2"
        style="left: {fx * 100}%; top: {fy * 100}%;
          width: {HANDLE_HIT}px; height: {HANDLE_HIT}px;
          cursor: {cursorFor(id)};"
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
  {cropPixelW} &times; {cropPixelH} px
</div>
