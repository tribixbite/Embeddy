<script lang="ts">
  /**
   * Settings panel for the Convert tool.
   * Output format selector, quality, crop overlay, max dimension,
   * target FPS, loop count, lossless toggle, and the full libwebp flag set.
   *
   * Controls are built from RangeField/NumberField/ToggleGroup so every input
   * carries a real label association and the segmented groups expose radiogroup
   * semantics.
   */
  import type { ConvertOptions, SourceInfo } from "../../../lib/convert/types";
  import CropOverlay from "./CropOverlay.svelte";
  import RangeField from "../shared/RangeField.svelte";
  import NumberField from "../shared/NumberField.svelte";
  import ToggleGroup from "../shared/ToggleGroup.svelte";

  let {
    options = $bindable(),
    info,
    disabled = false,
    sourcePreviewUrl = "",
    streamingMode = false,
    onconvert,
  }: {
    options: ConvertOptions;
    info: SourceInfo;
    disabled?: boolean;
    sourcePreviewUrl?: string;
    /** True when frames are decoded lazily — the adaptive re-encode loop can't run */
    streamingMode?: boolean;
    onconvert: () => void;
  } = $props();

  /** Show quality slider in lossy WebP mode or always for GIF */
  let showQuality = $derived(options.outputFormat === "gif" || !options.lossless);

  /**
   * The adaptive target-size loop re-encodes buffered frames at decreasing quality.
   * It only applies to lossy WebP output from pre-decoded frames — showing the field
   * in the other modes would promise behaviour the pipeline silently ignores.
   */
  let showTargetSize = $derived(
    options.outputFormat === "webp" && !options.lossless && !streamingMode,
  );

  /** Show target FPS control for video and WebP sources (GIFs have intrinsic timing) */
  let showFps = $derived(info.format === "video" || info.format === "webp");

  /** Crop toggle state */
  let cropEnabled = $state(false);

  /** Advanced settings disclosure state */
  let advancedOpen = $state(false);

  /** Initialize crop rect when toggled on */
  function toggleCrop() {
    cropEnabled = !cropEnabled;
    if (cropEnabled && !options.crop) {
      options.crop = { x: 0.1, y: 0.1, w: 0.8, h: 0.8 };
    } else if (!cropEnabled) {
      options.crop = null;
    }
  }

  /** Target size is edited in MB but stored in bytes */
  let targetSizeMb = $derived(
    options.targetSizeBytes > 0 ? +(options.targetSizeBytes / 1_000_000).toFixed(1) : 0,
  );
  function setTargetSizeMb(mb: number) {
    options.targetSizeBytes = mb > 0 ? Math.round(mb * 1_000_000) : 0;
  }

  /** libwebp int-as-bool fields render as checkboxes but store 0/1 */
  function toggleFlag(current: number): number {
    return current ? 0 : 1;
  }

  const FORMAT_OPTIONS = [
    { value: "webp" as const, label: "WebP" },
    { value: "gif" as const, label: "GIF" },
  ];
  const FILTER_TYPE_OPTIONS = [
    { value: 0, label: "Simple" },
    { value: 1, label: "Strong" },
  ];
  const ALPHA_FILTER_OPTIONS = [
    { value: 0, label: "None" },
    { value: 1, label: "Fast" },
    { value: 2, label: "Best" },
  ];
  const PREPROCESSING_OPTIONS = [
    { value: 0, label: "None" },
    { value: 1, label: "Segment smooth" },
    { value: 2, label: "Dithering" },
  ];
</script>

<div class="space-y-5 rounded-2xl border border-white/8 bg-white/[0.03] p-5">
  <ToggleGroup
    label="Output format"
    bind:value={options.outputFormat}
    options={FORMAT_OPTIONS}
    {disabled}
  />

  {#if showQuality}
    <RangeField
      id="cv-quality"
      label={options.outputFormat === "gif" ? "Color quality" : "Quality"}
      bind:value={options.quality}
      min={1}
      max={100}
      {disabled}
      minHint={options.outputFormat === "gif" ? "Fewer colors" : "Smallest"}
      maxHint={options.outputFormat === "gif" ? "More colors" : "Best quality"}
    />
  {/if}

  <!-- Target file size (lossy WebP from buffered frames only) -->
  {#if showTargetSize}
    <NumberField
      id="cv-target-size"
      label="Adaptive target size"
      hint=" (MB, 0 = off)"
      value={targetSizeMb}
      onchange={setTargetSizeMb}
      min={0}
      max={100}
      step={0.5}
      {disabled}
      placeholder="Off"
      help={options.targetSizeBytes > 0
        ? "Will re-encode at lower quality until output fits"
        : undefined}
    />
  {/if}

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

  <NumberField
    id="cv-max-dimension"
    label="Max dimension"
    hint=" (0 = no resize)"
    bind:value={options.maxDimension}
    min={0}
    max={4096}
    {disabled}
  />

  {#if showFps}
    <RangeField
      id="cv-fps"
      label="Target FPS"
      bind:value={options.targetFps}
      min={1}
      max={30}
      {disabled}
      display={`${options.targetFps}`}
      minHint="1 fps"
      maxHint="30 fps"
    />
  {/if}

  <NumberField
    id="cv-loops"
    label="Loop count"
    hint=" (0 = infinite)"
    bind:value={options.loops}
    min={0}
    max={65535}
    {disabled}
  />

  <!-- WebP-only settings -->
  {#if options.outputFormat === "webp"}
    <RangeField
      id="cv-method"
      label="Compression effort"
      bind:value={options.method}
      min={0}
      max={6}
      {disabled}
      minHint="Fastest"
      maxHint="Smallest"
    />

    <div class="grid grid-cols-2 gap-3">
      <NumberField
        id="cv-kmin"
        label="Kf min"
        hint=" (0 = auto)"
        bind:value={options.kmin}
        min={0}
        max={9999}
        {disabled}
      />
      <NumberField
        id="cv-kmax"
        label="Kf max"
        hint=" (0 = auto)"
        bind:value={options.kmax}
        min={0}
        max={9999}
        {disabled}
      />
      <p class="col-span-2 -mt-1 text-xs text-white/30">
        Keyframe distance — lower = more keyframes, larger file, better seeking
      </p>
    </div>

    <!-- Toggle switches -->
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
          <p class="text-xs text-white/30">Reorders chunks for smallest file, slower for many frames</p>
        </div>
      </label>

      <label class="flex cursor-pointer items-center gap-3">
        <input
          type="checkbox"
          bind:checked={options.mixed}
          {disabled}
          class="h-4 w-4 rounded border-white/20 bg-white/5 text-brand-500 accent-brand-500"
        />
        <div>
          <span class="text-sm text-white/60">Mixed mode</span>
          <p class="text-xs text-white/30">Auto-pick lossy/lossless per frame for smallest size</p>
        </div>
      </label>

      <label class="flex cursor-pointer items-center gap-3">
        <input
          type="checkbox"
          bind:checked={options.exact}
          {disabled}
          class="h-4 w-4 rounded border-white/20 bg-white/5 text-brand-500 accent-brand-500"
        />
        <div>
          <span class="text-sm text-white/60">Exact colors</span>
          <p class="text-xs text-white/30">Preserve RGB under transparent areas, fixes dark-content ghosting</p>
        </div>
      </label>
    </div>

    <!-- Advanced settings (collapsible) -->
    <div>
      <button
        type="button"
        onclick={() => { advancedOpen = !advancedOpen; }}
        class="flex w-full items-center gap-2 text-xs font-medium text-white/40 uppercase tracking-wider hover:text-white/60 transition-colors"
        aria-expanded={advancedOpen}
        aria-controls="cv-advanced"
        {disabled}
      >
        <span class="inline-block transition-transform duration-150 {advancedOpen ? 'rotate-90' : ''}" aria-hidden="true">&rsaquo;</span>
        Advanced
      </button>

      {#if advancedOpen}
        <div id="cv-advanced" class="mt-4 space-y-5">
          <RangeField
            id="cv-sns"
            label="SNS strength"
            bind:value={options.snsStrength}
            min={0}
            max={100}
            {disabled}
            minHint="Off"
            maxHint="Aggressive"
          />

          <RangeField
            id="cv-filter-strength"
            label="Filter strength"
            bind:value={options.filterStrength}
            min={0}
            max={100}
            {disabled}
            minHint="No deblocking"
            maxHint="Strong"
          />

          <RangeField
            id="cv-filter-sharpness"
            label="Filter sharpness"
            bind:value={options.filterSharpness}
            min={0}
            max={7}
            {disabled}
            minHint="Smooth"
            maxHint="Sharp"
          />

          <RangeField
            id="cv-alpha-quality"
            label="Alpha quality"
            bind:value={options.alphaQuality}
            min={0}
            max={100}
            {disabled}
            minHint="Smallest"
            maxHint="Best alpha"
          />

          <RangeField
            id="cv-near-lossless"
            label="Near lossless"
            bind:value={options.nearLossless}
            min={0}
            max={100}
            {disabled}
            minHint="Smaller lossless"
            maxHint="100 = off"
          />

          <RangeField
            id="cv-passes"
            label="Passes"
            bind:value={options.passes}
            min={1}
            max={10}
            {disabled}
            minHint="Fast"
            maxHint="Smallest"
          />

          <RangeField
            id="cv-segments"
            label="Segments"
            bind:value={options.segments}
            min={1}
            max={4}
            {disabled}
            minHint="1 (fast)"
            maxHint="4 (best)"
          />

          <ToggleGroup
            label="Filter type"
            bind:value={options.filterType}
            options={FILTER_TYPE_OPTIONS}
            {disabled}
            help="Strong is slower but reduces blocking artifacts"
          />

          <ToggleGroup
            label="Alpha filtering"
            bind:value={options.alphaFiltering}
            options={ALPHA_FILTER_OPTIONS}
            {disabled}
          />

          <ToggleGroup
            label="Preprocessing"
            bind:value={options.preprocessing}
            options={PREPROCESSING_OPTIONS}
            {disabled}
          />

          <!-- Checkbox toggles -->
          <div class="space-y-3">
            <label class="flex cursor-pointer items-center gap-3">
              <input
                type="checkbox"
                checked={options.alphaCompression === 1}
                onchange={() => { options.alphaCompression = toggleFlag(options.alphaCompression); }}
                {disabled}
                class="h-4 w-4 rounded border-white/20 bg-white/5 text-brand-500 accent-brand-500"
              />
              <div>
                <span class="text-sm text-white/60">Compress alpha</span>
                <p class="text-xs text-white/30">Lossless alpha compression — off stores the raw channel</p>
              </div>
            </label>

            <label class="flex cursor-pointer items-center gap-3">
              <input
                type="checkbox"
                checked={options.autofilter === 1}
                onchange={() => { options.autofilter = toggleFlag(options.autofilter); }}
                {disabled}
                class="h-4 w-4 rounded border-white/20 bg-white/5 text-brand-500 accent-brand-500"
              />
              <div>
                <span class="text-sm text-white/60">Auto filter</span>
                <p class="text-xs text-white/30">Auto-adjust deblocking filter strength</p>
              </div>
            </label>

            <label class="flex cursor-pointer items-center gap-3">
              <input
                type="checkbox"
                checked={options.sharpYuv === 1}
                onchange={() => { options.sharpYuv = toggleFlag(options.sharpYuv); }}
                {disabled}
                class="h-4 w-4 rounded border-white/20 bg-white/5 text-brand-500 accent-brand-500"
              />
              <div>
                <span class="text-sm text-white/60">Sharp YUV</span>
                <p class="text-xs text-white/30">Better chroma quality on sharp edges</p>
              </div>
            </label>

            <label class="flex cursor-pointer items-center gap-3">
              <input
                type="checkbox"
                checked={options.lowMemory === 1}
                onchange={() => { options.lowMemory = toggleFlag(options.lowMemory); }}
                {disabled}
                class="h-4 w-4 rounded border-white/20 bg-white/5 text-brand-500 accent-brand-500"
              />
              <div>
                <span class="text-sm text-white/60">Low memory</span>
                <p class="text-xs text-white/30">Reduce memory usage at cost of speed</p>
              </div>
            </label>

            <label class="flex cursor-pointer items-center gap-3">
              <input
                type="checkbox"
                checked={options.emulateJpegSize === 1}
                onchange={() => { options.emulateJpegSize = toggleFlag(options.emulateJpegSize); }}
                {disabled}
                class="h-4 w-4 rounded border-white/20 bg-white/5 text-brand-500 accent-brand-500"
              />
              <div>
                <span class="text-sm text-white/60">Emulate JPEG size</span>
                <p class="text-xs text-white/30">Map quality to JPEG-equivalent scale</p>
              </div>
            </label>
          </div>

          <div class="grid grid-cols-2 gap-3">
            <NumberField
              id="cv-encoder-target"
              label="Encoder target size"
              hint=" (bytes)"
              bind:value={options.targetSize}
              min={0}
              step={1000}
              {disabled}
              placeholder="0 = off"
            />
            <NumberField
              id="cv-partition-limit"
              label="Partition limit"
              hint=" (0 = off)"
              bind:value={options.partitionLimit}
              min={0}
              max={100}
              {disabled}
            />
            <p class="col-span-2 -mt-1 text-xs text-white/30">
              Encoder hint — may not be met exactly. Partition limit allows quality degradation to fit partitions
            </p>
          </div>
        </div>
      {/if}
    </div>
  {/if}

  <!-- Source info summary -->
  <div class="flex flex-wrap gap-3 text-xs text-white/40">
    <span>{info.width} &times; {info.height}</span>
    <span>{info.frameCount} frames</span>
    <span>{info.fps} fps</span>
    <span>{(info.totalDuration / 1000).toFixed(1)}s</span>
    {#if info.frameCapped}
      <span class="text-yellow-400/70" title="Decoding stopped at the memory budget — the output will be shorter than the source">
        truncated at frame limit
      </span>
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
