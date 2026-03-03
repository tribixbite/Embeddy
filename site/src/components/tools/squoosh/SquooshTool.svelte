<script lang="ts">
  /**
   * Squoosh tool — client-side image compression via @jsquash WASM codecs.
   * States: idle → loading → settings → compressing → done
   */
  import FileDropZone from "../shared/FileDropZone.svelte";
  import SquooshSettings from "./SquooshSettings.svelte";
  import SquooshResult from "./SquooshResult.svelte";
  import BeforeAfter from "./BeforeAfter.svelte";
  import { compress, type CompressOptions } from "../../../lib/squoosh/compress";

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

  function handleFile(files: File[]) {
    const file = files[0];
    if (!file) return;

    // Validate image type
    if (!file.type.startsWith("image/")) {
      error = "Please select an image file";
      state = "error";
      return;
    }

    sourceFile = file;
    state = "loading";
    error = "";

    // Read image dimensions
    const img = new Image();
    const url = URL.createObjectURL(file);
    img.onload = () => {
      sourceWidth = img.naturalWidth;
      sourceHeight = img.naturalHeight;
      sourceUrl = url;
      state = "settings";
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      error = "Failed to load image";
      state = "error";
    };
    img.src = url;
  }

  async function handleCompress() {
    if (!sourceFile) return;
    state = "compressing";
    error = "";

    try {
      const blob = await compress(sourceFile, options);
      // Clean up previous result
      if (resultUrl) URL.revokeObjectURL(resultUrl);
      resultBlob = blob;
      resultUrl = URL.createObjectURL(blob);
      state = "done";
    } catch (e) {
      error = e instanceof Error ? e.message : "Compression failed";
      state = "error";
    }
  }

  function reset() {
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
      accept="image/*"
      label="Drop an image here or click to browse"
      onfile={handleFile}
    />
  {:else if state === "loading"}
    <div class="flex items-center justify-center py-16">
      <div class="h-8 w-8 animate-spin rounded-full border-2 border-white/20 border-t-brand-400"></div>
      <span class="ml-3 text-sm text-white/50">Loading image...</span>
    </div>
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
          class="text-xs text-white/40 hover:text-white/60 transition-colors"
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
      <div class="flex items-center justify-center py-8">
        <div class="h-8 w-8 animate-spin rounded-full border-2 border-white/20 border-t-brand-400"></div>
        <span class="ml-3 text-sm text-white/50">Compressing...</span>
      </div>
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
        class="flex-1 rounded-xl border border-white/10 bg-white/5 px-4 py-2.5 text-sm font-medium text-white/60 transition-colors hover:bg-white/10"
      >
        Adjust settings
      </button>
      <button
        onclick={reset}
        class="flex-1 rounded-xl border border-white/10 bg-white/5 px-4 py-2.5 text-sm font-medium text-white/60 transition-colors hover:bg-white/10"
      >
        New image
      </button>
    </div>
  {:else if state === "error"}
    <div class="rounded-2xl border border-red-500/20 bg-red-500/5 p-6 text-center">
      <p class="text-sm text-red-400">{error}</p>
      <button
        onclick={reset}
        class="mt-4 rounded-lg border border-white/10 bg-white/5 px-4 py-2 text-sm text-white/60 transition-colors hover:bg-white/10"
      >
        Try again
      </button>
    </div>
  {/if}
</div>
