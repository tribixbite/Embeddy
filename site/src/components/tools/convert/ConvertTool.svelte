<script lang="ts">
  /**
   * Convert tool — GIF/video/WebP to animated WebP or GIF.
   * States: idle → decoding → settings → converting → done → error
   *
   * WebP encoding always uses the streaming encoder (xiaozhuai/webp_encoder WASM)
   * which exposes all libwebp settings (method, minimize, exact, mixed, kmin/kmax).
   * For pre-decoded frames (GIF, small video, WebP), frames are pushed to the Worker
   * from memory. For large videos, frames are decoded lazily via streamDecodeVideo().
   */
  import FileDropZone from "../shared/FileDropZone.svelte";
  import ConvertSettings from "./ConvertSettings.svelte";
  import ConvertResult from "./ConvertResult.svelte";
  import ConvertProgressBar from "./ConvertProgress.svelte";
  import { decodeGif } from "../../../lib/convert/gif-decoder";
  import { decodeVideo, probeVideo, streamDecodeVideo } from "../../../lib/convert/video-decoder";
  import { decodeWebP } from "../../../lib/convert/webp-decoder";
  import { cropFrame, resizeFrame, subsampleFrames } from "../../../lib/convert/encoder";
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
    minimizeSize: true,
    method: 4,
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
   * Encode pre-decoded frames to WebP via the streaming Worker encoder.
   * Handles subsample, crop, resize pipeline then pushes each frame.
   * All libwebp settings (method, minimize, exact, kmin/kmax) are applied.
   */
  async function encodeFramesWebP(
    inputFrames: DecodedFrame[],
    width: number,
    height: number,
    opts: ConvertOptions,
    sourceFps: number,
  ): Promise<Blob> {
    const encoder = new StreamingWebPEncoder();
    activeEncoder = encoder;

    try {
      // Subsample if target FPS is lower than source
      let workFrames = subsampleFrames(inputFrames, sourceFps, opts.targetFps);

      // Crop all frames if crop is set
      let outW = width;
      let outH = height;
      if (opts.crop) {
        outW = Math.max(1, Math.round(opts.crop.w * width));
        outH = Math.max(1, Math.round(opts.crop.h * height));
        workFrames = workFrames.map((frame, i) => {
          progress = {
            phase: "cropping",
            percent: Math.round((i / workFrames.length) * 100),
            frame: i + 1,
            total: workFrames.length,
          };
          const cropped = cropFrame(frame.rgba, width, height, opts.crop!);
          return { rgba: cropped.data, delay: frame.delay };
        });
      }

      // Resize all frames if maxDimension is set
      if (opts.maxDimension > 0 && (outW > opts.maxDimension || outH > opts.maxDimension)) {
        const scale = Math.min(opts.maxDimension / outW, opts.maxDimension / outH);
        const newW = Math.round(outW * scale);
        const newH = Math.round(outH * scale);
        workFrames = workFrames.map((frame, i) => {
          progress = {
            phase: "resizing",
            percent: Math.round((i / workFrames.length) * 100),
            frame: i + 1,
            total: workFrames.length,
          };
          const resized = resizeFrame(frame.rgba, outW, outH, opts.maxDimension);
          return { rgba: resized.data, delay: frame.delay };
        });
        outW = newW;
        outH = newH;
      }

      const totalFrames = workFrames.length;
      await encoder.init({
        minimize: opts.minimizeSize,
        loop: opts.loops,
        mixed: opts.exactColors ? false : true,
        kmin: opts.exactColors ? 3 : 0,
        kmax: opts.exactColors ? 5 : 0,
      });
      encoder.setTotalFrames(totalFrames);

      // Push each frame through the Worker encoder
      for (let i = 0; i < workFrames.length; i++) {
        const frame = workFrames[i];
        progress = {
          phase: "encoding",
          percent: Math.round((i / totalFrames) * 95),
          frame: i + 1,
          total: totalFrames,
        };
        await encoder.pushFrame(
          frame.rgba,
          outW,
          outH,
          {
            duration: frame.delay,
            lossless: opts.lossless,
            quality: opts.quality,
            method: opts.method,
            exact: opts.exactColors,
          },
        );
      }

      progress = { phase: "encoding", percent: 97, frame: totalFrames, total: totalFrames };
      const blob = await encoder.finalize();
      activeEncoder = null;
      return blob;
    } catch (e) {
      activeEncoder = null;
      encoder.abort();
      throw e;
    }
  }

  /**
   * Streaming encode: lazily decode video frames and push to Worker encoder.
   * Memory usage: O(1 frame + compressed output) instead of O(N frames).
   */
  async function streamingConvert(opts: ConvertOptions): Promise<Blob> {
    if (!sourceFile || !sourceInfo) throw new Error("No source file");

    const encoder = new StreamingWebPEncoder();
    activeEncoder = encoder;

    try {
      const totalFrames = Math.min(
        Math.floor((sourceInfo.totalDuration / 1000) * opts.targetFps),
        10000,
      );

      await encoder.init({
        minimize: opts.minimizeSize,
        loop: opts.loops,
        mixed: opts.exactColors ? false : true,
        kmin: opts.exactColors ? 3 : 0,
        kmax: opts.exactColors ? 5 : 0,
      });
      encoder.setTotalFrames(totalFrames);

      const delayMs = Math.round(1000 / opts.targetFps);
      let pushed = 0;

      for await (const frame of streamDecodeVideo(sourceFile, {
        targetFps: opts.targetFps,
        maxFrames: totalFrames,
        crop: opts.crop,
        maxDimension: opts.maxDimension,
      }, (p) => {
        progress = {
          phase: "decoding",
          percent: Math.round(p.percent * 0.95),
          frame: p.frame,
          total: p.total,
        };
      })) {
        await encoder.pushFrame(
          frame.rgba,
          frame.width,
          frame.height,
          {
            duration: delayMs,
            lossless: opts.lossless,
            quality: opts.quality,
            method: opts.method,
            exact: opts.exactColors,
          },
        );
        pushed++;
      }

      streamingFrameCount = pushed;

      progress = { phase: "encoding", percent: 97, frame: pushed, total: totalFrames };
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
    if (!sourceInfo || (!frames.length && !streamingMode)) return;
    state = "converting";
    error = "";

    try {
      let blob: Blob;

      if (streamingMode && options.outputFormat === "webp") {
        // Large video: lazy decode + encode one frame at a time
        blob = await streamingConvert(options);
      } else if (streamingMode && options.outputFormat === "gif") {
        // GIF from large video: decode capped frames then GIF-encode
        const decoded = await decodeVideo(sourceFile!, options.targetFps, 300, (p) => { progress = p; });
        frames = decoded.frames;
        blob = await encodeAnimatedGif(
          frames, sourceInfo.width, sourceInfo.height,
          options, sourceInfo.fps, (p) => { progress = p; },
        );
      } else if (options.outputFormat === "gif") {
        // GIF output from pre-decoded frames
        blob = await encodeAnimatedGif(
          frames, sourceInfo.width, sourceInfo.height,
          options, sourceInfo.fps, (p) => { progress = p; },
        );
      } else {
        // WebP output from pre-decoded frames — all settings applied
        const encode = (opts: ConvertOptions) =>
          encodeFramesWebP(frames, sourceInfo!.width, sourceInfo!.height, opts, sourceInfo!.fps);

        if (options.targetSizeBytes > 0 && !options.lossless) {
          // Adaptive quality: reduce quality until output fits target
          const qualityStep = 10;
          const minQuality = 5;
          let quality = options.quality;
          let bestBlob: Blob | null = null;

          while (quality >= minQuality) {
            blob = await encode({ ...options, quality });
            bestBlob = blob;
            if (blob.size <= options.targetSizeBytes) break;
            quality -= qualityStep;
          }
          blob = bestBlob!;
        } else {
          blob = await encode(options);
        }
      }

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
