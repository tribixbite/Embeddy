<script lang="ts">
  /**
   * Settings panel for the Convert tool.
   * Quality slider, max dimension, loop count, target FPS, lossless toggle.
   */
  import type { ConvertOptions, SourceInfo } from "../../../lib/convert/types";

  let {
    options = $bindable(),
    info,
    disabled = false,
    onconvert,
  }: {
    options: ConvertOptions;
    info: SourceInfo;
    disabled?: boolean;
    onconvert: () => void;
  } = $props();

  /** Show quality slider only in lossy mode */
  let showQuality = $derived(!options.lossless);

  /** Show target FPS control only for video sources (GIFs have intrinsic timing) */
  let showFps = $derived(info.format === "video");
</script>

<div class="space-y-5 rounded-2xl border border-white/8 bg-white/[0.03] p-5">
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
      max="4096"
      step="1"
      bind:value={options.maxDimension}
      {disabled}
      placeholder="0"
      class="w-full rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-sm text-white/80 placeholder:text-white/25 focus:border-brand-500/50 focus:outline-none"
    />
  </div>

  <!-- Target FPS (video only) -->
  {#if showFps}
    <div>
      <div class="mb-2 flex items-center justify-between">
        <label class="text-xs font-medium text-white/40 uppercase tracking-wider">
          Target FPS
        </label>
        <span class="text-sm font-mono text-white/60">{options.targetFps}</span>
      </div>
      <input
        type="range"
        min="1"
        max="30"
        step="1"
        bind:value={options.targetFps}
        {disabled}
        class="w-full accent-brand-500"
      />
      <div class="mt-1 flex justify-between text-xs text-white/30">
        <span>1 fps</span>
        <span>30 fps</span>
      </div>
    </div>
  {/if}

  <!-- Loop count -->
  <div>
    <label class="mb-2 block text-xs font-medium text-white/40 uppercase tracking-wider">
      Loop count <span class="text-white/25">(0 = infinite)</span>
    </label>
    <input
      type="number"
      min="0"
      max="65535"
      step="1"
      bind:value={options.loops}
      {disabled}
      placeholder="0"
      class="w-full rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-sm text-white/80 placeholder:text-white/25 focus:border-brand-500/50 focus:outline-none"
    />
  </div>

  <!-- Lossless toggle -->
  <label class="flex cursor-pointer items-center gap-3">
    <input
      type="checkbox"
      bind:checked={options.lossless}
      {disabled}
      class="h-4 w-4 rounded border-white/20 bg-white/5 text-brand-500 accent-brand-500"
    />
    <span class="text-sm text-white/60">Lossless</span>
  </label>

  <!-- Source info summary -->
  <div class="flex flex-wrap gap-3 text-xs text-white/40">
    <span>{info.width} x {info.height}</span>
    <span>{info.frameCount} frames</span>
    <span>{info.fps} fps</span>
    <span>{(info.totalDuration / 1000).toFixed(1)}s</span>
  </div>

  <!-- Convert button -->
  <button
    onclick={onconvert}
    {disabled}
    class="w-full rounded-xl bg-brand-500 px-6 py-3 text-sm font-semibold text-white transition-all hover:bg-brand-400 hover:shadow-lg hover:shadow-brand-500/25 disabled:opacity-40 disabled:cursor-not-allowed"
  >
    {#if disabled}
      Converting...
    {:else}
      Convert to WebP
    {/if}
  </button>
</div>
