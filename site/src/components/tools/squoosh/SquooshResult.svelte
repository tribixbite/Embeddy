<script lang="ts">
  /**
   * Download button + compression stats (original vs compressed size).
   */
  import { formatFileSize, formatSavings } from "../shared/format-size";

  let {
    originalSize,
    compressedSize,
    resultUrl,
    format,
    filename,
  }: {
    originalSize: number;
    compressedSize: number;
    resultUrl: string;
    format: string;
    filename: string;
  } = $props();

  /** Generate output filename: source.jpg → source.webp */
  let outputName = $derived(() => {
    const base = filename.replace(/\.[^.]+$/, "");
    const ext = format === "jpeg" ? "jpg" : format;
    return `${base}.${ext}`;
  });

  let savings = $derived(formatSavings(originalSize, compressedSize));
  let savedBytes = $derived(originalSize - compressedSize);
  let isSmaller = $derived(compressedSize < originalSize);
</script>

<div class="rounded-2xl border border-white/8 bg-white/[0.03] p-5">
  <!-- Stats grid -->
  <div class="mb-4 grid grid-cols-3 gap-4 text-center">
    <div>
      <p class="text-xs text-white/40 uppercase tracking-wider">Original</p>
      <p class="mt-1 text-sm font-mono text-white/70">{formatFileSize(originalSize)}</p>
    </div>
    <div>
      <p class="text-xs text-white/40 uppercase tracking-wider">Compressed</p>
      <p class="mt-1 text-sm font-mono text-white/70">{formatFileSize(compressedSize)}</p>
    </div>
    <div>
      <p class="text-xs text-white/40 uppercase tracking-wider">Savings</p>
      <p class="mt-1 text-sm font-mono {isSmaller ? 'text-green-400' : 'text-red-400'}">
        {savings}
      </p>
    </div>
  </div>

  {#if isSmaller}
    <p class="mb-4 text-center text-xs text-white/40">
      Saved {formatFileSize(savedBytes)}
    </p>
  {/if}

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
