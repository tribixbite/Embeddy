<script lang="ts">
  /**
   * Convert tool — GIF/video/WebP to animated WebP or GIF.
   * States: idle → decoding → settings → converting → done → error
   *
   * Two encode paths:
   *  - Batch: decode all frames → encode (GIF sources, small WebP, short video)
   *  - Streaming: probe video → decode+encode one frame at a time via Web Worker
   *    (long/high-res video → WebP, where buffering all frames would OOM)
   */
  import FileDropZone from "../shared/FileDropZone.svelte";
  import ConvertSettings from "./ConvertSettings.svelte";
  import ConvertResult from "./ConvertResult.svelte";
  import ConvertProgressBar from "./ConvertProgress.svelte";
  import { decodeGif } from "../../../lib/convert/gif-decoder";
  import { decodeVideo, probeVideo, streamDecodeVideo } from "../../../lib/convert/video-decoder";
  import { decodeWebP } from "../../../lib/convert/webp-decoder";
  import { encodeAnimatedWebP } from "../../../lib/convert/encoder";
  import { encodeAnimatedGif } from "../../../lib/convert/gif-encoder";
  import { StreamingWebPEncoder } from "../../../lib/convert/streaming-encoder";
  import type {
    ConvertOptions,
    ConvertProgress,
    DecodedFrame,
    SourceInfo,
  } from "../../../lib/convert/types";

  type State = "idle" | "decoding" | "settings" | "converting" | "done" | "error";

  /** Memory threshold for batch decode — above this, use streaming path */
  const BATCH_MEMORY_LIMIT = 512 * 1024 * 1024; // 512 MB

  let state: State = $state("idle");
  let error = $state("");
  let sourceFile: File | null = $state(null);
  let sourceInfo: SourceInfo | null = $state(null);
  /** Decoded frames (empty in streaming mode — frames are never buffered) */
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

  /** True when video is too large for batch decode — uses streaming pipeline */
  let streamingMode = $state(false);
  /** Frame count from streaming encode (since frames[] is empty in that mode) */
  let streamingFrameCount = $state(0);
  /** Active streaming encoder reference (for abort support) */
  let activeEncoder: StreamingWebPEncoder | null = null;

  let options: ConvertOptions = $state({
    quality: 75,
    lossless: false,
    maxDimension: 0,
    loops: 0,
    targetFps: 10,
    outputFormat: "webp",
    crop: null,
    targetSizeBytes: 0,
    exactColors: false,
  });

  /** Accepted file types: GIF, WebP, and common video formats */
  const acceptTypes = "image/gif,image/webp,video/mp4,video/webm,video/quicktime,video/x-matroska";

  function isGif(file: File): boolean {
    return file.type === "image/gif" || file.name.toLowerCase().endsWith(".gif");
  }

  function isWebP(file: File): boolean {
    return file.type === "image/webp" || file.name.toLowerCase().endsWith(".webp");
  }

  /**
   * Check if batch decode of a video would exceed memory budget.
   * If so, we only probe metadata and defer frame decode to the streaming encoder.
   */
  function wouldExceedMemory(info: SourceInfo, fps: number): boolean {
    const bytesPerFrame = info.width * info.height * 4;
    const frameCount = Math.floor((info.totalDuration / 1000) * fps);
    return frameCount * bytesPerFrame > BATCH_MEMORY_LIMIT;
  }

  async function handleFile(files: File[]) {
    const file = files[0];
    if (!file) return;

    sourceFile = file;
    state = "decoding";
    error = "";
    streamingMode = false;
    streamingFrameCount = 0;

    // Create source preview URL for crop overlay
    if (sourcePreviewUrl) URL.revokeObjectURL(sourcePreviewUrl);
    sourcePreviewUrl = URL.createObjectURL(file);

    try {
      if (isGif(file)) {
        const buffer = await file.arrayBuffer();
        const decoded = await decodeGif(buffer, (p) => { progress = p; });
        frames = decoded.frames;
        sourceInfo = decoded.info;
        options.outputFormat = "webp";
        if (decoded.info.format === "gif") {
          options.targetFps = Math.round(decoded.info.fps);
        }
      } else if (isWebP(file)) {
        const decoded = await decodeWebP(file, options.targetFps, 1500, (p) => { progress = p; });
        frames = decoded.frames;
        sourceInfo = decoded.info;
        options.outputFormat = "gif";
      } else if (file.type.startsWith("video/")) {
        // Probe first to decide batch vs streaming
        const { info, videoUrl } = await probeVideo(file, options.targetFps);

        if (wouldExceedMemory(info, options.targetFps)) {
          // Streaming mode: skip frame decode, just use metadata
          streamingMode = true;
          sourceInfo = info;
          frames = [];
          // probeVideo created an object URL — use it for preview, revoke the old one
          if (sourcePreviewUrl !== videoUrl) {
            URL.revokeObjectURL(sourcePreviewUrl);
            sourcePreviewUrl = videoUrl;
          }
          options.outputFormat = "webp";
        } else {
          // Batch mode: decode all frames into memory
          URL.revokeObjectURL(videoUrl); // probeVideo's URL not needed
          const decoded = await decodeVideo(file, options.targetFps, 1500, (p) => { progress = p; });
          frames = decoded.frames;
          sourceInfo = decoded.info;
          options.outputFormat = "webp";
        }
      } else {
        throw new Error(`Unsupported format: ${file.type || file.name}`);
      }

      state = "settings";
    } catch (e) {
      error = e instanceof Error ? e.message : "Failed to decode file";
      state = "error";
    }
  }

  /**
   * Streaming encode: decode video frames one-by-one and push to Worker encoder.
   * Memory usage: O(1 frame + compressed output) instead of O(N frames).
   */
  async function streamingConvert(opts: ConvertOptions): Promise<Blob> {
    if (!sourceFile || !sourceInfo) throw new Error("No source file");

    const encoder = new StreamingWebPEncoder();
    activeEncoder = encoder;

    try {
      // Calculate expected frame count
      const totalFrames = Math.min(
        Math.floor((sourceInfo.totalDuration / 1000) * opts.targetFps),
        10000, // hard safety cap for streaming mode
      );

      await encoder.init(
        {
          minimize: false, // O(n²) with minimize=true — too slow for 600+ frame streaming
          loop: opts.loops,
          mixed: opts.exactColors ? false : true,
          // Periodic keyframes reset error accumulation (only when exactColors enabled)
          kmin: opts.exactColors ? 3 : 0,
          kmax: opts.exactColors ? 5 : 0,
        },
        (p) => { progress = p; },
      );

      encoder.setTotalFrames(totalFrames);

      const delayMs = Math.round(1000 / opts.targetFps);
      let pushed = 0;

      // Stream decode + encode: one frame at a time
      for await (const frame of streamDecodeVideo(sourceFile, {
        targetFps: opts.targetFps,
        maxFrames: totalFrames,
        crop: opts.crop,
        maxDimension: opts.maxDimension,
      }, (p) => {
        // Show decode progress in the first 50% of the bar
        progress = {
          phase: "decoding",
          percent: Math.round(p.percent * 0.5),
          frame: p.frame,
          total: p.total,
        };
      })) {
        // Push the processed frame to the Worker encoder
        await encoder.pushFrame(
          frame.rgba,
          frame.width,
          frame.height,
          {
            duration: delayMs,
            lossless: opts.lossless,
            quality: opts.quality,
            method: 0, // fastest for streaming
            exact: opts.exactColors,
          },
        );
        pushed++;

        // Progress: 50-95% for encoding (5% reserved for assembly)
        progress = {
          phase: "encoding",
          percent: 50 + Math.round((pushed / totalFrames) * 45),
          frame: pushed,
          total: totalFrames,
        };
      }

      streamingFrameCount = pushed;

      // Finalize — calls WebPAnimEncoderAssemble
      const blob = await encoder.finalize();
      activeEncoder = null;
      return blob;
    } catch (e) {
      activeEncoder = null;
      encoder.abort();
      throw e;
    }
  }

  async function handleConvert() {
    // In streaming mode, frames[] is empty — check sourceInfo instead
    if (!sourceInfo || (!frames.length && !streamingMode)) return;
    state = "converting";
    error = "";

    try {
      let blob: Blob;

      if (streamingMode && options.outputFormat === "webp") {
        // Streaming path: decode+encode one frame at a time via Worker
        // Note: adaptive quality not supported in streaming mode (would require
        // re-decoding the entire video for each quality attempt)
        blob = await streamingConvert(options);
      } else if (streamingMode && options.outputFormat === "gif") {
        // GIF output from large video: not practical (GIF palette quantization
        // can't easily stream). Fall back to batch with reduced frame cap.
        const decoded = await decodeVideo(sourceFile!, options.targetFps, 300, (p) => { progress = p; });
        frames = decoded.frames;
        blob = await encodeAnimatedGif(
          frames, sourceInfo.width, sourceInfo.height,
          options, sourceInfo.fps, (p) => { progress = p; },
        );
      } else {
        // Batch path: all frames already in memory
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

        if (options.targetSizeBytes > 0 && !options.lossless) {
          // Adaptive quality loop: reduce quality until output fits target
          const qualityStep = 10;
          const minQuality = 5;
          let quality = options.quality;
          let bestBlob: Blob | null = null;

          while (quality >= minQuality) {
            const attemptOpts = { ...options, quality };
            blob = await encode(attemptOpts);
            bestBlob = blob;
            if (blob.size <= options.targetSizeBytes) break;
            quality -= qualityStep;
          }

          blob = bestBlob!;
        } else {
          blob = await encode(options);
        }
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
    // Abort any active streaming encode
    if (activeEncoder) {
      activeEncoder.abort();
      activeEncoder = null;
    }
    if (resultUrl) URL.revokeObjectURL(resultUrl);
    if (sourcePreviewUrl) URL.revokeObjectURL(sourcePreviewUrl);
    sourceFile = null;
    sourceInfo = null;
    frames = [];
    resultBlob = null;
    resultUrl = "";
    sourcePreviewUrl = "";
    streamingMode = false;
    streamingFrameCount = 0;
    state = "idle";
    error = "";
  }

  /** Actual frame count for display — uses streaming count if frames[] is empty */
  let displayFrameCount = $derived(
    frames.length > 0 ? frames.length : streamingFrameCount,
  );
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
          {#if streamingMode}
            <span class="ml-2 text-xs text-emerald-400/60">streaming</span>
          {/if}
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
      frameCount={displayFrameCount}
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
