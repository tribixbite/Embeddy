<script lang="ts">
  /**
   * Convert tool — GIF/video to animated WebP via wasm-webp.
   * States: idle → decoding → settings → converting → done → error
   */
  import FileDropZone from "../shared/FileDropZone.svelte";
  import ConvertSettings from "./ConvertSettings.svelte";
  import ConvertResult from "./ConvertResult.svelte";
  import ConvertProgressBar from "./ConvertProgress.svelte";
  import { decodeGif } from "../../../lib/convert/gif-decoder";
  import { decodeVideo } from "../../../lib/convert/video-decoder";
  import { encodeAnimatedWebP } from "../../../lib/convert/encoder";
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
  });

  /** Accepted file types: GIF and common video formats */
  const acceptTypes = "image/gif,video/mp4,video/webm,video/quicktime,video/x-matroska";

  /** Check if the file is a GIF by magic bytes or MIME */
  function isGif(file: File): boolean {
    return file.type === "image/gif" || file.name.toLowerCase().endsWith(".gif");
  }

  async function handleFile(files: File[]) {
    const file = files[0];
    if (!file) return;

    sourceFile = file;
    state = "decoding";
    error = "";

    try {
      let decoded: { frames: DecodedFrame[]; info: SourceInfo };

      if (isGif(file)) {
        const buffer = await file.arrayBuffer();
        decoded = await decodeGif(buffer, (p) => { progress = p; });
      } else if (file.type.startsWith("video/")) {
        decoded = await decodeVideo(file, options.targetFps, 300, (p) => { progress = p; });
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
      const blob = await encodeAnimatedWebP(
        frames,
        sourceInfo.width,
        sourceInfo.height,
        options,
        sourceInfo.fps,
        (p) => { progress = p; },
      );

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
    sourceFile = null;
    sourceInfo = null;
    frames = [];
    resultBlob = null;
    resultUrl = "";
    state = "idle";
    error = "";
  }
</script>

<div class="space-y-6">
  {#if state === "idle"}
    <FileDropZone
      accept={acceptTypes}
      label="Drop a GIF or video here to convert"
      onfile={handleFile}
    />
    <p class="text-center text-xs text-white/30">
      Supports GIF, MP4, WebM, MOV, MKV
    </p>

  {:else if state === "decoding"}
    <ConvertProgressBar {progress} />

  {:else if (state === "settings" || state === "converting") && sourceInfo}
    <!-- Source file info -->
    <div class="rounded-2xl border border-white/8 bg-white/[0.03] p-4">
      <div class="flex items-center justify-between">
        <div class="text-sm text-white/50">
          <span class="font-medium text-white/80">{sourceFile?.name}</span>
          <span class="ml-2">{sourceInfo.width} x {sourceInfo.height}</span>
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
