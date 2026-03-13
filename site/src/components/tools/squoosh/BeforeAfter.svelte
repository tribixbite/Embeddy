<script lang="ts">
  /**
   * Horizontal drag-divider comparison slider for before/after images.
   * Uses clip-path so both images are always the same size.
   * Touch-friendly via PointerEvent + capture on the container.
   */

  let {
    sourceUrl,
    resultUrl,
    sourceLabel = "Original",
    resultLabel = "Compressed",
  }: {
    sourceUrl: string;
    resultUrl: string;
    sourceLabel?: string;
    resultLabel?: string;
  } = $props();

  let containerRef: HTMLDivElement | undefined = $state();
  let dividerPos = $state(50); // percentage 0-100
  let dragging = $state(false);

  function updatePosition(clientX: number) {
    if (!containerRef) return;
    const rect = containerRef.getBoundingClientRect();
    const x = clientX - rect.left;
    dividerPos = Math.max(2, Math.min(98, (x / rect.width) * 100));
  }

  function onPointerDown(e: PointerEvent) {
    dragging = true;
    // Capture on the container so drag works even when pointer leaves bounds
    containerRef?.setPointerCapture(e.pointerId);
    updatePosition(e.clientX);
  }

  function onPointerMove(e: PointerEvent) {
    if (!dragging) return;
    e.preventDefault();
    updatePosition(e.clientX);
  }

  function onPointerUp() {
    dragging = false;
  }

  function onKeyDown(e: KeyboardEvent) {
    if (e.key === "ArrowLeft") {
      dividerPos = Math.max(2, dividerPos - 2);
    } else if (e.key === "ArrowRight") {
      dividerPos = Math.min(98, dividerPos + 2);
    }
  }
</script>

<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
<div
  bind:this={containerRef}
  class="relative select-none overflow-hidden rounded-2xl border border-white/8 touch-none"
  role="slider"
  aria-label="Before/after comparison — drag left or right"
  aria-valuenow={Math.round(dividerPos)}
  aria-valuemin={0}
  aria-valuemax={100}
  tabindex="0"
  onpointerdown={onPointerDown}
  onpointermove={onPointerMove}
  onpointerup={onPointerUp}
  onpointercancel={onPointerUp}
  onkeydown={onKeyDown}
>
  <!-- Result (full, unclipped — visible on the right side) -->
  <img
    src={resultUrl}
    alt={resultLabel}
    class="block w-full h-auto object-contain"
    draggable="false"
  />

  <!-- Source (clipped from the right via clip-path — visible on the left side) -->
  <img
    src={sourceUrl}
    alt={sourceLabel}
    class="absolute inset-0 block w-full h-full object-contain"
    style="clip-path: inset(0 {100 - dividerPos}% 0 0)"
    draggable="false"
  />

  <!-- Divider line -->
  <div
    class="absolute top-0 bottom-0 w-0.5 -translate-x-1/2 bg-white/80 shadow-lg pointer-events-none"
    style="left: {dividerPos}%"
  >
    <!-- Handle — left/right arrows -->
    <div class="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 flex h-10 w-10 items-center justify-center rounded-full border-2 border-white/80 bg-dark-900/80 backdrop-blur-sm pointer-events-none">
      <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="2" stroke="currentColor" class="h-5 w-5 text-white/80">
        <path stroke-linecap="round" stroke-linejoin="round" d="M15.75 8.25L19.5 12m0 0l-3.75 3.75M19.5 12H4.5m3.75-3.75L4.5 12m0 0l3.75 3.75" />
      </svg>
    </div>
  </div>

  <!-- Labels -->
  <div class="absolute top-3 left-3 rounded-md bg-dark-900/70 px-2 py-1 text-xs font-medium text-white/70 backdrop-blur-sm pointer-events-none">
    {sourceLabel}
  </div>
  <div class="absolute top-3 right-3 rounded-md bg-dark-900/70 px-2 py-1 text-xs font-medium text-white/70 backdrop-blur-sm pointer-events-none">
    {resultLabel}
  </div>
</div>
