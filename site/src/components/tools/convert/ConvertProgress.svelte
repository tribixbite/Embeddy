<script lang="ts">
  /**
   * Progress indicator showing phase, frame count, and percentage bar.
   */
  import type { ConvertProgress } from "../../../lib/convert/types";

  let { progress }: { progress: ConvertProgress } = $props();

  const phaseLabels: Record<ConvertProgress["phase"], string> = {
    decoding: "Decoding frames",
    resizing: "Resizing frames",
    encoding: "Encoding WebP",
  };
</script>

<div class="rounded-2xl border border-white/8 bg-white/[0.03] p-6">
  <div class="mb-3 flex items-center justify-between text-sm">
    <span class="text-white/60">{phaseLabels[progress.phase]}</span>
    <span class="font-mono text-white/40">
      {progress.frame} / {progress.total}
    </span>
  </div>

  <!-- Progress bar -->
  <div class="h-2 overflow-hidden rounded-full bg-white/10">
    <div
      class="h-full rounded-full bg-brand-500 transition-all duration-150"
      style="width: {progress.percent}%"
    ></div>
  </div>

  <p class="mt-2 text-center text-xs text-white/30">
    {progress.percent}%
  </p>
</div>
