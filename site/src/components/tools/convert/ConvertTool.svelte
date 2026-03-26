<script lang="ts">
  /**
   * Convert tool — GIF/video/WebP to animated WebP or GIF.
   * States: idle → decoding → settings → converting → done → error
   */
  import FileDropZone from "../shared/FileDropZone.svelte";
  import ConvertSettings from "./ConvertSettings.svelte";
  import ConvertResult from "./ConvertResult.svelte";
  import ConvertProgressBar from "./ConvertProgress.svelte";
  import { decodeGif } from "../../../lib/convert/gif-decoder";
  import { decodeVideo } from "../../../lib/convert/video-decoder";
  import { decodeWebP } from "../../../lib/convert/webp-decoder";
  import { encodeAnimatedWebP } from "../../../lib/convert/encoder";
  import { encodeAnimatedGif } from "../../../lib/convert/gif-encoder";
  import type {
    ConvertOptions,
    ConvertProgress,
    DecodedFrame,
    SourceInfo,
  } from "../../../lib/convert/types";

  type State = "idle" | "decoding" | "settings" | "converting" | "done" | "error";

  let state: State = $state("idle");
  let error = $state("");
  let sourceFile: File | null = $state(null);
  let sourceInfo: SourceInfo | null = $state(null);
  let frames: DecodedFrame[] = $state([]);
  let resultBlob: Blob | null = $state(null);
  let resultUrl = $state("");
  let sourcePreviewUrl = $state("");
  let progress: ConvertProgress = $state({
    phase: "decoding",
    percent: 0,
    frame: 0,
    total: 0,
  });

  let options: ConvertOptions = $state({
    quality: 75,
    lossless: false,
    maxDimension: 0,
    loops: 0,
    targetFps: 10,
    outputFormat: "webp",
    crop: null,
    targetSizeBytes: 0,
  });

  /** Accepted file types: GIF, WebP, and common video formats */
  const acceptTypes = "image/gif,image/webp,video/mp4,video/webm,video/quicktime,video/x-matroska";

  function isGif(file: File): boolean {
    return file.type === "image/gif" || file.name.toLowerCase().endsWith(".gif");
  }

  function isWebP(file: File): boolean {
    return file.type === "image/webp" || file.name.toLowerCase().endsWith(".webp");
  }

  async function handleFile(files: File[]) {
    const file = files[0];
    if (!file) return;

    sourceFile = file;
    state = "decoding";
    error = "";

    // Create source preview URL for crop overlay
    if (sourcePreviewUrl) URL.revokeObjectURL(sourcePreviewUrl);
    sourcePreviewUrl = URL.createObjectURL(file);

    try {
      let decoded: { frames: DecodedFrame[]; info: SourceInfo };

      if (isGif(file)) {
        const buffer = await file.arrayBuffer();
        decoded = await decodeGif(buffer, (p) => { progress = p; });
        // GIF input → default output WebP (conversion)
        options.outputFormat = "webp";
      } else if (isWebP(file)) {
        decoded = await decodeWebP(file, options.targetFps, 1500, (p) => { progress = p; });
        // WebP input → default output GIF (reverse conversion)
        options.outputFormat = "gif";
      } else if (file.type.startsWith("video/")) {
        decoded = await decodeVideo(file, options.targetFps, 1500, (p) => { progress = p; });
        // Video input → default output WebP
        options.outputFormat = "webp";
      } else {
        throw new Error(`Unsupported format: ${file.type || file.name}`);
      }

      frames = decoded.frames;
      sourceInfo = decoded.info;

      // Set initial target FPS to match source for GIFs
      if (decoded.info.format === "gif") {
        options.targetFps = Math.round(decoded.info.fps);
      }

      state = "settings";
    } catch (e) {
      error = e instanceof Error ? e.message : "Failed to decode file";
      state = "error";
    }
  }

  async function handleConvert() {
    if (!sourceInfo || !frames.length) return;
    state = "converting";
    error = "";

    try {
      const encode = async (opts: ConvertOptions) => {
        if (opts.outputFormat === "gif") {
          return encodeAnimatedGif(
            frames, sourceInfo!.width, sourceInfo!.height,
            opts, sourceInfo!.fps, (p) => { progress = p; },
          );
        }
        return encodeAnimatedWebP(
          frames, sourceInfo!.width, sourceInfo!.height,
          opts, sourceInfo!.fps, (p) => { progress = p; },
        );
      };

      let blob: Blob;

      if (options.targetSizeBytes > 0 && !options.lossless) {
        // Adaptive quality loop: reduce quality until output fits target
        const qualityStep = 10;
        const minQuality = 5;
        let quality = options.quality;
        let bestBlob: Blob | null = null;

        while (quality >= minQuality) {
          const attemptOpts = { ...options, quality };
          blob = await encode(attemptOpts);

          // Always keep the latest result (smallest = lowest quality tried)
          bestBlob = blob;

          if (blob.size <= options.targetSizeBytes) break;
          quality -= qualityStep;
        }

        blob = bestBlob!;
      } else {
        blob = await encode(options);
      }

      // Clean up previous result
      if (resultUrl) URL.revokeObjectURL(resultUrl);
      resultBlob = blob;
      resultUrl = URL.createObjectURL(blob);
      state = "done";
    } catch (e) {
      error = e instanceof Error ? e.message : "Encoding failed";
      state = "error";
    }
  }

  function reset() {
    if (resultUrl) URL.revokeObjectURL(resultUrl);
    if (sourcePreviewUrl) URL.revokeObjectURL(sourcePreviewUrl);
    sourceFile = null;
    sourceInfo = null;
    frames = [];
    resultBlob = null;
    resultUrl = "";
    sourcePreviewUrl = "";
    state = "idle";
    error = "";
  }
</script>

<div class="space-y-6">
  {#if state === "idle"}
    <FileDropZone
      accept={acceptTypes}
      label="Drop a GIF, WebP, or video here to convert"
      onfile={handleFile}
    />
    <p class="text-center text-xs text-white/30">
      Supports GIF, WebP, MP4, WebM, MOV, MKV
    </p>

  {:else if state === "decoding"}
    <ConvertProgressBar {progress} />

  {:else if (state === "settings" || state === "converting") && sourceInfo}
    <!-- Source file info -->
    <div class="rounded-2xl border border-white/8 bg-white/[0.03] p-4">
      <div class="flex items-center justify-between">
        <div class="text-sm text-white/50">
          <span class="font-medium text-white/80">{sourceFile?.name}</span>
          <span class="ml-2">{sourceInfo.width} &times; {sourceInfo.height}</span>
          <span class="ml-2">{sourceInfo.frameCount} frames</span>
        </div>
        <button
          onclick={reset}
          class="text-xs text-white/40 hover:text-white/60 transition-colors"
        >
          Change file
        </button>
      </div>
    </div>

    <ConvertSettings
      bind:options
      info={sourceInfo}
      disabled={state === "converting"}
      {sourcePreviewUrl}
      onconvert={handleConvert}
    />

    {#if state === "converting"}
      <ConvertProgressBar {progress} />
    {/if}

  {:else if state === "done" && resultBlob && sourceFile}
    <ConvertResult
      originalSize={sourceFile.size}
      resultSize={resultBlob.size}
      {resultUrl}
      filename={sourceFile.name}
      frameCount={frames.length}
      outputFormat={options.outputFormat}
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
        New file
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
