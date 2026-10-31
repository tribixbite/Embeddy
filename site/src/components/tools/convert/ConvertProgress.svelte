<script lang="ts">
  /**
   * Progress indicator showing phase, frame count, percentage bar, and a
   * time-remaining estimate extrapolated from the caller's per-phase elapsed
   * clock. Every pipeline phase (decode, crop, resize, encode) now reports
   * real per-frame percent — the batch WebP path streams frames through the
   * Worker encoder — so the indeterminate shimmer remains only for the brief
   * callback-less window before the first frame is pushed (Worker spawn +
   * WASM module init).
   */
  import type { ConvertProgress } from "../../../lib/convert/types";
  import { estimateRemainingMs, formatEta } from "../../../lib/convert/eta";

  let {
    progress,
    elapsedMs = 0,
    oncancel,
  }: {
    progress: ConvertProgress;
    elapsedMs?: number;
    /** When set, a Cancel button is shown — decode/encode can run for minutes (H1) */
    oncancel?: () => void;
  } = $props();

  const phaseLabels: Record<ConvertProgress["phase"], string> = {
    decoding: "Decoding frames",
    cropping: "Cropping frames",
    resizing: "Resizing frames",
    encoding: "Encoding",
  };

  /** Shimmer only while encoding has produced no frame progress yet
   *  (Worker/WASM startup) — all steady-state phases report real percent. */
  let indeterminate = $derived(
    progress.phase === "encoding" && progress.percent === 0 && progress.frame === 0,
  );

  /** Remaining-time estimate; null until enough progress/time has accumulated */
  let etaMs = $derived(estimateRemainingMs(progress.percent / 100, elapsedMs));
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

  <!-- Progress bar — exposed as a real progressbar for assistive tech (A2);
       aria-valuenow is omitted while indeterminate, per ARIA -->
  <div
    class="h-2 overflow-hidden rounded-full bg-white/10"
    role="progressbar"
    aria-label={phaseLabels[progress.phase] ?? progress.phase}
    aria-valuemin={0}
    aria-valuemax={100}
    aria-valuenow={indeterminate ? undefined : progress.percent}
  >
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
    <p class="mt-2 text-center text-xs text-white/30" aria-live="polite">
      {progress.percent}%{#if etaMs !== null}
        <span class="ml-2 text-white/40">{formatEta(etaMs)}</span>
      {/if}
    </p>
  {:else}
    <p class="mt-2 text-center text-xs text-white/30">
      Encoding {progress.total} frames...
    </p>
  {/if}

  {#if oncancel}
    <button
      type="button"
      onclick={oncancel}
      class="mt-4 w-full min-h-11 rounded-xl border border-white/10 bg-white/5 px-4 py-2.5 text-sm font-medium text-white/60 transition-colors hover:bg-white/10"
    >
      Cancel
    </button>
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
