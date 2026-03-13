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
