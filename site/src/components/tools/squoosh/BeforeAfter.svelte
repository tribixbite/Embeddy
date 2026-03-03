<script lang="ts">
  /**
   * Drag-divider comparison slider for before/after image comparison.
   * Touch-friendly with pointer events.
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
    dividerPos = Math.max(0, Math.min(100, (x / rect.width) * 100));
  }

  function onPointerDown(e: PointerEvent) {
    dragging = true;
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
    updatePosition(e.clientX);
  }

  function onPointerMove(e: PointerEvent) {
    if (!dragging) return;
    updatePosition(e.clientX);
  }

  function onPointerUp() {
    dragging = false;
  }
</script>

<div
  bind:this={containerRef}
  class="relative select-none overflow-hidden rounded-2xl border border-white/8"
  role="slider"
  aria-label="Before/after comparison"
  aria-valuenow={Math.round(dividerPos)}
  tabindex="0"
  onpointerdown={onPointerDown}
  onpointermove={onPointerMove}
  onpointerup={onPointerUp}
  onpointercancel={onPointerUp}
>
  <!-- Result (full background) -->
  <img
    src={resultUrl}
    alt={resultLabel}
    class="block w-full object-contain"
    draggable="false"
  />

  <!-- Source (clipped to left side) -->
  <div
    class="absolute inset-0 overflow-hidden"
    style="width: {dividerPos}%"
  >
    <img
      src={sourceUrl}
      alt={sourceLabel}
      class="block w-full object-contain"
      style="width: {containerRef ? containerRef.offsetWidth + 'px' : '100%'}"
      draggable="false"
    />
  </div>

  <!-- Divider line -->
  <div
    class="absolute top-0 bottom-0 w-0.5 bg-white/80 shadow-lg"
    style="left: {dividerPos}%"
  >
    <!-- Handle -->
    <div class="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 flex h-10 w-10 items-center justify-center rounded-full border-2 border-white/80 bg-dark-900/80 backdrop-blur-sm">
      <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="2" stroke="currentColor" class="h-5 w-5 text-white/80">
        <path stroke-linecap="round" stroke-linejoin="round" d="M8.25 15L12 18.75 15.75 15m-7.5-6L12 5.25 15.75 9" />
      </svg>
    </div>
  </div>

  <!-- Labels -->
  <div class="absolute top-3 left-3 rounded-md bg-dark-900/70 px-2 py-1 text-xs font-medium text-white/70 backdrop-blur-sm">
    {sourceLabel}
  </div>
  <div class="absolute top-3 right-3 rounded-md bg-dark-900/70 px-2 py-1 text-xs font-medium text-white/70 backdrop-blur-sm">
    {resultLabel}
  </div>
</div>
