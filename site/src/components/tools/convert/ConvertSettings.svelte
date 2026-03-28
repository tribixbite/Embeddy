<script lang="ts">
  /**
   * Settings panel for the Convert tool.
   * Output format selector, quality, crop overlay, max dimension,
   * target FPS, loop count, lossless toggle.
   */
  import type { ConvertOptions, SourceInfo } from "../../../lib/convert/types";
  import CropOverlay from "./CropOverlay.svelte";

  let {
    options = $bindable(),
    info,
    disabled = false,
    sourcePreviewUrl = "",
    onconvert,
  }: {
    options: ConvertOptions;
    info: SourceInfo;
    disabled?: boolean;
    sourcePreviewUrl?: string;
    onconvert: () => void;
  } = $props();

  /** Show quality slider in lossy WebP mode or always for GIF */
  let showQuality = $derived(options.outputFormat === "gif" || !options.lossless);

  /** Show target FPS control for video and WebP sources (GIFs have intrinsic timing) */
  let showFps = $derived(info.format === "video" || info.format === "webp");

  /** Crop toggle state */
  let cropEnabled = $state(false);

  /** Initialize crop rect when toggled on */
  function toggleCrop() {
    cropEnabled = !cropEnabled;
    if (cropEnabled && !options.crop) {
      options.crop = { x: 0.1, y: 0.1, w: 0.8, h: 0.8 };
    } else if (!cropEnabled) {
      options.crop = null;
    }
  }
</script>

<div class="space-y-5 rounded-2xl border border-white/8 bg-white/[0.03] p-5">
  <!-- Output format -->
  <div>
    <label class="mb-2 block text-xs font-medium text-white/40 uppercase tracking-wider">
      Output Format
    </label>
    <div class="flex gap-2">
      {#each ["webp", "gif"] as fmt}
        <button
          class="rounded-lg px-4 py-2 text-sm font-medium transition-colors
            {options.outputFormat === fmt
              ? 'bg-brand-500 text-white'
              : 'bg-white/5 text-white/50 hover:bg-white/10'}"
          onclick={() => { options.outputFormat = fmt as "webp" | "gif"; }}
          {disabled}
        >
          {fmt.toUpperCase()}
        </button>
      {/each}
    </div>
  </div>

  <!-- Quality slider -->
  {#if showQuality}
    <div>
      <div class="mb-2 flex items-center justify-between">
        <label class="text-xs font-medium text-white/40 uppercase tracking-wider">
          {options.outputFormat === "gif" ? "Color quality" : "Quality"}
        </label>
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
        <span>{options.outputFormat === "gif" ? "Fewer colors" : "Smallest"}</span>
        <span>{options.outputFormat === "gif" ? "More colors" : "Best quality"}</span>
      </div>
    </div>
  {/if}

  <!-- Target file size (optional) -->
  <div>
    <label class="mb-2 block text-xs font-medium text-white/40 uppercase tracking-wider">
      Target size <span class="text-white/25">(MB, 0 = off)</span>
    </label>
    <input
      type="number"
      min="0"
      max="100"
      step="0.5"
      value={options.targetSizeBytes > 0 ? +(options.targetSizeBytes / 1_000_000).toFixed(1) : ""}
      oninput={(e) => {
        const mb = parseFloat((e.target as HTMLInputElement).value);
        options.targetSizeBytes = mb > 0 ? Math.round(mb * 1_000_000) : 0;
      }}
      {disabled}
      placeholder="Off"
      class="w-full rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-sm text-white/80 placeholder:text-white/25 focus:border-brand-500/50 focus:outline-none"
    />
    {#if options.targetSizeBytes > 0}
      <p class="mt-1 text-xs text-white/30">
        Will reduce quality from {options.quality} until output fits
      </p>
    {/if}
  </div>

  <!-- Crop section -->
  <div>
    <label class="flex cursor-pointer items-center gap-3">
      <input
        type="checkbox"
        checked={cropEnabled}
        onchange={toggleCrop}
        {disabled}
        class="h-4 w-4 rounded border-white/20 bg-white/5 text-brand-500 accent-brand-500"
      />
      <span class="text-sm text-white/60">Crop</span>
    </label>

    {#if cropEnabled && options.crop && sourcePreviewUrl}
      <div class="mt-3">
        <CropOverlay
          bind:crop={options.crop}
          sourceWidth={info.width}
          sourceHeight={info.height}
          {disabled}
        >
          <img
            src={sourcePreviewUrl}
            alt="Source preview"
            class="block w-full"
            draggable="false"
          />
        </CropOverlay>
      </div>
    {/if}
  </div>

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

  <!-- Target FPS (video/WebP only) -->
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

  <!-- WebP-only settings -->
  {#if options.outputFormat === "webp"}
    <!-- Compression effort slider -->
    <div>
      <div class="mb-2 flex items-center justify-between">
        <label class="text-xs font-medium text-white/40 uppercase tracking-wider">
          Compression effort
        </label>
        <span class="text-sm font-mono text-white/60">{options.method}</span>
      </div>
      <input
        type="range"
        min="0"
        max="6"
        step="1"
        bind:value={options.method}
        {disabled}
        class="w-full accent-brand-500"
      />
      <div class="mt-1 flex justify-between text-xs text-white/30">
        <span>Fastest</span>
        <span>Smallest</span>
      </div>
    </div>

    <div class="space-y-3">
      <label class="flex cursor-pointer items-center gap-3">
        <input
          type="checkbox"
          bind:checked={options.lossless}
          {disabled}
          class="h-4 w-4 rounded border-white/20 bg-white/5 text-brand-500 accent-brand-500"
        />
        <span class="text-sm text-white/60">Lossless</span>
      </label>

      <label class="flex cursor-pointer items-center gap-3">
        <input
          type="checkbox"
          bind:checked={options.minimizeSize}
          {disabled}
          class="h-4 w-4 rounded border-white/20 bg-white/5 text-brand-500 accent-brand-500"
        />
        <div>
          <span class="text-sm text-white/60">Minimize size</span>
          <p class="text-xs text-white/30">Reorders chunks for smallest file, slower for long videos</p>
        </div>
      </label>

      <label class="flex cursor-pointer items-center gap-3">
        <input
          type="checkbox"
          bind:checked={options.exactColors}
          {disabled}
          class="h-4 w-4 rounded border-white/20 bg-white/5 text-brand-500 accent-brand-500"
        />
        <div>
          <span class="text-sm text-white/60">Exact colors</span>
          <p class="text-xs text-white/30">Fixes ghosting on dark content, larger file size</p>
        </div>
      </label>
    </div>
  {/if}

  <!-- Source info summary -->
  <div class="flex flex-wrap gap-3 text-xs text-white/40">
    <span>{info.width} &times; {info.height}</span>
    <span>{info.frameCount} frames</span>
    <span>{info.fps} fps</span>
    <span>{(info.totalDuration / 1000).toFixed(1)}s</span>
    {#if info.frameCount >= 1500}
      <span class="text-yellow-400/70">frame cap reached</span>
    {/if}
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
      Convert to {options.outputFormat.toUpperCase()}
    {/if}
  </button>
</div>
