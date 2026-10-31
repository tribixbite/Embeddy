<script lang="ts">
  /**
   * Squoosh tool — client-side image compression via @jsquash WASM codecs.
   * States: idle → loading → settings → compressing → done
   */
  import FileDropZone from "../shared/FileDropZone.svelte";
  import Spinner from "../shared/Spinner.svelte";
  import ErrorCard from "../shared/ErrorCard.svelte";
  import { SECONDARY_BUTTON, TEXT_BUTTON } from "../shared/buttons";
  import SquooshSettings from "./SquooshSettings.svelte";
  import SquooshResult from "./SquooshResult.svelte";
  import BeforeAfter from "./BeforeAfter.svelte";
  import { compress, type CompressOptions } from "../../../lib/squoosh/compress";
  import { GIF_UNSUPPORTED_MESSAGE } from "../../../lib/squoosh/codecs";
  import { createGenerationGuard } from "../convert/conversion-guard";
  import { recoverFromChunkError } from "../../../lib/pwa/chunk-reload";
  import { BUILD_ID } from "../../../lib/build";

  type State = "idle" | "loading" | "settings" | "compressing" | "done" | "error";

  let state: State = $state("idle");
  let error = $state("");
  let sourceFile: File | null = $state(null);
  let sourceUrl = $state("");
  let sourceWidth = $state(0);
  let sourceHeight = $state(0);
  let resultBlob: Blob | null = $state(null);
  let resultUrl = $state("");

  let options: CompressOptions = $state({
    format: "webp",
    quality: 75,
    maxDimension: 0,
    lossless: false,
  });

  /**
   * Orphans in-flight image loads/compressions on reset: "Change file" mid
   * compress used to let the late result set state "done" with sourceFile
   * nulled — no template branch matched and the screen went blank (H3).
   */
  const guard = createGenerationGuard();

  function handleFile(files: File[]) {
    const file = files[0];
    if (!file) return;

    // Validate image type
    if (!file.type.startsWith("image/")) {
      error = "Please select an image file";
      state = "error";
      return;
    }

    // GIF loads fine in <img> but no @jsquash decoder handles it — fail fast
    // with a pointer at the Convert tool instead of erroring after settings
    if (file.type === "image/gif") {
      error = GIF_UNSUPPORTED_MESSAGE;
      state = "error";
      return;
    }

    const gen = guard.invalidate();
    sourceFile = file;
    state = "loading";
    error = "";

    // Read image dimensions
    const img = new Image();
    const url = URL.createObjectURL(file);
    img.onload = () => {
      if (!guard.isCurrent(gen)) {
        URL.revokeObjectURL(url);
        return;
      }
      sourceWidth = img.naturalWidth;
      sourceHeight = img.naturalHeight;
      sourceUrl = url;
      state = "settings";
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      if (!guard.isCurrent(gen)) return;
      error = "Failed to load image";
      state = "error";
    };
    img.src = url;
  }

  async function handleCompress() {
    if (!sourceFile) return;
    state = "compressing";
    error = "";
    const gen = guard.token();

    try {
      const blob = await compress(sourceFile, options);
      // Superseded by reset/new file — drop the result, don't touch state
      if (!guard.isCurrent(gen)) return;
      // Clean up previous result
      if (resultUrl) URL.revokeObjectURL(resultUrl);
      resultBlob = blob;
      resultUrl = URL.createObjectURL(blob);
      state = "done";
    } catch (e) {
      if (!guard.isCurrent(gen)) return;
      // A stale deploy's missing codec chunk: reload once instead of erroring (M4)
      if (recoverFromChunkError(e, BUILD_ID)) return;
      error = e instanceof Error ? e.message : "Compression failed";
      state = "error";
    }
  }

  function reset() {
    guard.invalidate();
    if (sourceUrl) URL.revokeObjectURL(sourceUrl);
    if (resultUrl) URL.revokeObjectURL(resultUrl);
    sourceFile = null;
    sourceUrl = "";
    resultBlob = null;
    resultUrl = "";
    state = "idle";
    error = "";
  }
</script>

<div class="space-y-6">
  {#if state === "idle"}
    <FileDropZone
      accept="image/jpeg,image/png,image/webp,image/avif"
      label="Drop a JPEG, PNG, WebP, or AVIF here or click to browse"
      onfile={handleFile}
    />
  {:else if state === "loading"}
    <Spinner label="Loading image..." padding="py-16" />
  {:else if state === "settings" || state === "compressing"}
    <!-- Source preview + settings -->
    <div class="rounded-2xl border border-white/8 bg-white/[0.03] p-4">
      <div class="mb-4 flex items-center justify-between">
        <div class="text-sm text-white/50">
          <span class="font-medium text-white/80">{sourceFile?.name}</span>
          <span class="ml-2">{sourceWidth} x {sourceHeight}</span>
        </div>
        <button
          onclick={reset}
          class={TEXT_BUTTON}
        >
          Change file
        </button>
      </div>
      <img
        src={sourceUrl}
        alt="Source preview"
        class="mx-auto max-h-64 rounded-lg object-contain"
      />
    </div>

    <SquooshSettings
      bind:options
      disabled={state === "compressing"}
      oncompress={handleCompress}
    />

    {#if state === "compressing"}
      <Spinner label="Compressing..." padding="py-8" />
    {/if}
  {:else if state === "done" && resultBlob && sourceFile}
    <BeforeAfter
      {sourceUrl}
      {resultUrl}
      sourceLabel="{sourceFile.name}"
      resultLabel="{options.format.toUpperCase()}"
    />

    <SquooshResult
      originalSize={sourceFile.size}
      compressedSize={resultBlob.size}
      {resultUrl}
      format={options.format}
      filename={sourceFile.name}
    />

    <div class="flex gap-3">
      <button
        onclick={() => { state = "settings"; }}
        class="flex-1 {SECONDARY_BUTTON}"
      >
        Adjust settings
      </button>
      <button
        onclick={reset}
        class="flex-1 {SECONDARY_BUTTON}"
      >
        New image
      </button>
    </div>
  {:else if state === "error"}
    <ErrorCard message={error}>
      <button onclick={reset} class={SECONDARY_BUTTON}>Try again</button>
    </ErrorCard>
  {/if}
</div>
