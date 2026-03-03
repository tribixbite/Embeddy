<script lang="ts">
  /**
   * Format selection chips, quality slider, max dimension, lossless toggle.
   */
  import type { CompressOptions } from "../../../lib/squoosh/compress";

  let {
    options = $bindable(),
    disabled = false,
    oncompress,
  }: {
    options: CompressOptions;
    disabled?: boolean;
    oncompress: () => void;
  } = $props();

  const formats: { value: CompressOptions["format"]; label: string }[] = [
    { value: "webp", label: "WebP" },
    { value: "jpeg", label: "JPEG" },
    { value: "png", label: "PNG" },
    { value: "avif", label: "AVIF" },
  ];

  /** PNG is always lossless; AVIF/WebP support lossless toggle */
  let showLossless = $derived(options.format !== "jpeg");
  let showQuality = $derived(!options.lossless && options.format !== "png");
</script>

<div class="space-y-5 rounded-2xl border border-white/8 bg-white/[0.03] p-5">
  <!-- Format chips -->
  <div>
    <label class="mb-2 block text-xs font-medium text-white/40 uppercase tracking-wider">Format</label>
    <div class="flex flex-wrap gap-2">
      {#each formats as fmt}
        <button
          {disabled}
          onclick={() => {
            options.format = fmt.value;
            // PNG is always lossless
            if (fmt.value === "png") options.lossless = true;
            // JPEG can't be lossless
            if (fmt.value === "jpeg") options.lossless = false;
          }}
          class="rounded-lg px-4 py-2 text-sm font-medium transition-colors
            {options.format === fmt.value
              ? 'bg-brand-500/20 text-brand-300 border border-brand-500/30'
              : 'border border-white/10 bg-white/5 text-white/50 hover:bg-white/10'}"
        >
          {fmt.label}
        </button>
      {/each}
    </div>
  </div>

  <!-- Quality slider -->
  {#if showQuality}
    <div>
      <div class="mb-2 flex items-center justify-between">
        <label class="text-xs font-medium text-white/40 uppercase tracking-wider">Quality</label>
        <span class="text-sm font-mono text-white/60">{options.quality}</span>
      </div>
      <input
        type="range"
        min="1"
        max="100"
        step="1"
        bind:value={options.quality}
        {disabled}
        class="w-full accent-brand-500"
      />
      <div class="mt-1 flex justify-between text-xs text-white/30">
        <span>Smallest</span>
        <span>Best quality</span>
      </div>
    </div>
  {/if}

  <!-- Max dimension -->
  <div>
    <label class="mb-2 block text-xs font-medium text-white/40 uppercase tracking-wider">
      Max dimension <span class="text-white/25">(0 = no resize)</span>
    </label>
    <input
      type="number"
      min="0"
      max="8192"
      step="1"
      bind:value={options.maxDimension}
      {disabled}
      placeholder="0"
      class="w-full rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-sm text-white/80 placeholder:text-white/25 focus:border-brand-500/50 focus:outline-none"
    />
  </div>

  <!-- Lossless toggle -->
  {#if showLossless}
    <label class="flex cursor-pointer items-center gap-3">
      <input
        type="checkbox"
        bind:checked={options.lossless}
        {disabled}
        class="h-4 w-4 rounded border-white/20 bg-white/5 text-brand-500 accent-brand-500"
      />
      <span class="text-sm text-white/60">Lossless</span>
    </label>
  {/if}

  <!-- Compress button -->
  <button
    onclick={oncompress}
    {disabled}
    class="w-full rounded-xl bg-brand-500 px-6 py-3 text-sm font-semibold text-white transition-all hover:bg-brand-400 hover:shadow-lg hover:shadow-brand-500/25 disabled:opacity-40 disabled:cursor-not-allowed"
  >
    {#if disabled}
      Compressing...
    {:else}
      Compress
    {/if}
  </button>
</div>
