<script lang="ts">
  /**
   * Progress indicator showing phase, frame count, and percentage bar.
   * Uses an indeterminate shimmer for the encoding phase since
   * wasm-webp encodeAnimation() is a single blocking call with no
   * per-frame callbacks.
   */
  import type { ConvertProgress } from "../../../lib/convert/types";

  let { progress }: { progress: ConvertProgress } = $props();

  const phaseLabels: Record<ConvertProgress["phase"], string> = {
    decoding: "Decoding frames",
    cropping: "Cropping frames",
    resizing: "Resizing frames",
    encoding: "Encoding",
  };

  /** Encoding phase has no granular progress — show indeterminate bar */
  let indeterminate = $derived(
    progress.phase === "encoding" && progress.percent < 100,
  );
</script>

<div class="rounded-2xl border border-white/8 bg-white/[0.03] p-6">
  <div class="mb-3 flex items-center justify-between text-sm">
    <span class="text-white/60">{phaseLabels[progress.phase] ?? progress.phase}</span>
    {#if !indeterminate}
      <span class="font-mono text-white/40">
        {progress.frame} / {progress.total}
      </span>
    {/if}
  </div>

  <!-- Progress bar -->
  <div class="h-2 overflow-hidden rounded-full bg-white/10">
    {#if indeterminate}
      <div class="h-full w-1/3 rounded-full bg-brand-500 animate-shimmer"></div>
    {:else}
      <div
        class="h-full rounded-full bg-brand-500 transition-all duration-150"
        style="width: {progress.percent}%"
      ></div>
    {/if}
  </div>

  {#if !indeterminate}
    <p class="mt-2 text-center text-xs text-white/30">
      {progress.percent}%
    </p>
  {:else}
    <p class="mt-2 text-center text-xs text-white/30">
      Encoding {progress.total} frames...
    </p>
  {/if}
</div>

<style>
  @keyframes shimmer {
    0% { transform: translateX(-100%); }
    100% { transform: translateX(400%); }
  }
  .animate-shimmer {
    animation: shimmer 1.5s ease-in-out infinite;
  }
</style>
