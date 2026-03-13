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

  const HANDLE_SIZE = 12;
  const HANDLE_HIT = 22;

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
    if (disabled) return;
    e.preventDefault();
    e.stopPropagation();
    activeHandle = handle;
    containerRef?.setPointerCapture(e.pointerId);
  }

  /** Clamp a value between min and max */
  function clamp(val: number, min: number, max: number): number {
    return Math.max(min, Math.min(max, val));
  }

  /** Get cursor style for a given handle */
  function cursorFor(id: HandleId): string {
    if (id === "tl" || id === "br") return "nwse-resize";
    if (id === "tr" || id === "bl") return "nesw-resize";
    if (id === "t" || id === "b") return "ns-resize";
    if (id === "l" || id === "r") return "ew-resize";
    return "move";
  }

  /** Handle pointer move — update crop rect based on active handle */
  function onPointerMove(e: PointerEvent) {
    if (!activeHandle || !containerRef) return;
    e.preventDefault();

    const rect = containerRef.getBoundingClientRect();
    const nx = clamp((e.clientX - rect.left) / rect.width, 0, 1);
    const ny = clamp((e.clientY - rect.top) / rect.height, 0, 1);

    let { x, y, w, h } = crop;
    const MIN = 0.02; // Minimum crop size (2% of source)

    if (activeHandle === "move") {
      // Reposition entire crop
      const halfW = w / 2;
      const halfH = h / 2;
      x = clamp(nx - halfW, 0, 1 - w);
      y = clamp(ny - halfH, 0, 1 - h);
    } else {
      // Edge/corner resize
      const right = x + w;
      const bottom = y + h;

      // Horizontal adjustments
      if (activeHandle === "l" || activeHandle === "tl" || activeHandle === "bl") {
        const newX = clamp(nx, 0, right - MIN);
        w = right - newX;
        x = newX;
      }
      if (activeHandle === "r" || activeHandle === "tr" || activeHandle === "br") {
        w = clamp(nx - x, MIN, 1 - x);
      }

      // Vertical adjustments
      if (activeHandle === "t" || activeHandle === "tl" || activeHandle === "tr") {
        const newY = clamp(ny, 0, bottom - MIN);
        h = bottom - newY;
        y = newY;
      }
      if (activeHandle === "b" || activeHandle === "bl" || activeHandle === "br") {
        h = clamp(ny - y, MIN, 1 - y);
      }

      // Enforce aspect ratio when set
      if (aspectRatio !== null) {
        const sourceAspect = sourceWidth / sourceHeight;
        const targetH = (w * sourceAspect) / aspectRatio;

        if (activeHandle.includes("t")) {
          // Anchor bottom edge
          const newH = clamp(targetH, MIN, 1);
          y = clamp(y + h - newH, 0, 1 - MIN);
          h = newH;
        } else {
          h = clamp(targetH, MIN, 1 - y);
        }
      }
    }

    // Final clamp
    crop = {
      x: clamp(x, 0, 1),
      y: clamp(y, 0, 1),
      w: clamp(w, MIN, 1 - x),
      h: clamp(h, MIN, 1 - y),
    };
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

    const ratio = mode === "1:1" ? 1
      : mode === "16:9" ? 16 / 9
      : mode === "4:3" ? 4 / 3
      : customW / (customH || 1);

    const sourceAspect = sourceWidth / sourceHeight;
    let newW = crop.w;
    let newH = (newW * sourceAspect) / ratio;

    // If too tall, constrain by height instead
    if (crop.y + newH > 1) {
      newH = Math.min(crop.h, 1 - crop.y);
      newW = (newH * ratio) / sourceAspect;
    }
    // If too wide, constrain
    if (crop.x + newW > 1) {
      newW = Math.min(1 - crop.x, newW);
      newH = (newW * sourceAspect) / ratio;
    }

    const cx = crop.x + crop.w / 2;
    const cy = crop.y + crop.h / 2;
    crop = {
      x: clamp(cx - newW / 2, 0, 1 - newW),
      y: clamp(cy - newH / 2, 0, 1 - newH),
      w: clamp(newW, 0.02, 1),
      h: clamp(newH, 0.02, 1),
    };
  }

  /** Pixel dimensions of the crop region */
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
