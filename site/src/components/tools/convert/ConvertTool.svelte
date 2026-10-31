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
  import {
    decodeVideo,
    isAbortError,
    probeVideo,
    streamDecodeVideo,
  } from "../../../lib/convert/video-decoder";
  import { decodeWebP } from "../../../lib/convert/webp-decoder";
  import { subsampleFrames } from "../../../lib/convert/encoder";
  import { encodeAnimatedGif } from "../../../lib/convert/gif-encoder";
  import { StreamingWebPEncoder } from "../../../lib/convert/streaming-encoder";
  import {
    getWebPFrameOptions,
    getWebPInitOptions,
  } from "../../../lib/convert/webp-frame-options";
  import { FramePreparer, transformFromOptions } from "../../../lib/convert/prepare-frame";
  import {
    MAX_BATCH_FRAMES,
    chooseVideoRoute,
    encodeToTarget,
    needsFpsRedecode,
  } from "../../../lib/convert/pipeline";
  import { sniffFile } from "../../../lib/media/sniff";
  import { formatFileSize } from "../shared/format-size";
  import { createGenerationGuard } from "./conversion-guard";
  import { recoverFromChunkError } from "../../../lib/pwa/chunk-reload";
  import { BUILD_ID } from "../../../lib/build";
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
  // Raw (non-proxied) state: these are replaced wholesale, never mutated, and
  // a deep $state proxy over 1500 {rgba, delay} objects is pure overhead (P6)
  let sourceInfo: SourceInfo | null = $state.raw(null);
  /** Decoded frames (empty in streaming mode — frames are never buffered) */
  let frames: DecodedFrame[] = $state.raw([]);
  let resultBlob: Blob | null = $state.raw(null);
  let resultUrl = $state("");
  /** Shown on the result when the adaptive loop could not reach the target (L3) */
  let resultNotice = $state("");
  let sourcePreviewUrl = $state("");
  let progress: ConvertProgress = $state.raw({
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

  /**
   * Generation guard for in-flight async work: reset()/handleFile invalidate,
   * every async pipeline bails after each await once its token goes stale.
   * Prevents "Change file" from landing on the error screen when the aborted
   * encoder's promise rejects (M17) and stops zombie conversions from
   * clobbering a newly loaded file's state or dead-ending the UI (M18).
   */
  const guard = createGenerationGuard();

  /**
   * Companion to the generation guard: the guard makes orphaned async work
   * state-inert, this actually STOPS it. Every decode and encode holds a
   * signal from the controller armed when its work started; cancel, reset,
   * a new file or a new convert abort the previous controller.
   */
  let abortController: AbortController | null = null;

  /** Abort any in-flight decode and arm a fresh signal for the work starting now */
  function nextAbortSignal(): AbortSignal {
    abortController?.abort();
    abortController = new AbortController();
    return abortController.signal;
  }

  /** Stop every in-flight decode/encode and orphan its pending promises */
  function stopInFlightWork() {
    guard.invalidate();
    abortController?.abort();
    abortController = null;
    if (activeEncoder) {
      activeEncoder.abort();
      activeEncoder = null;
    }
  }

  /** Per-file defaults — reset() restores these so file B never inherits file A's crop/fps/etc (M6) */
  function defaultOptions(): ConvertOptions {
    return {
      quality: 75,
      lossless: false,
      maxDimension: 0,
      loops: 0,
      targetFps: 10,
      outputFormat: "webp",
      crop: null,
      rotate: 0,
      flipH: false,
      flipV: false,
      targetSizeBytes: 0,
      method: 4,
      minimizeSize: true,
      exact: false,
      mixed: true,
      kmin: 0,
      kmax: 0,
      snsStrength: 50,
      filterStrength: 60,
      filterSharpness: 0,
      filterType: 1,
      autofilter: 0,
      alphaQuality: 100,
      alphaCompression: 1,
      alphaFiltering: 1,
      passes: 1,
      preprocessing: 0,
      nearLossless: 100,
      sharpYuv: 0,
      targetSize: 0,
      segments: 4,
      partitionLimit: 0,
      lowMemory: 0,
      emulateJpegSize: 0,
    };
  }

  let options: ConvertOptions = $state(defaultOptions());

  /**
   * Wall-clock elapsed within the CURRENT progress phase, for the ETA display.
   * reportProgress() restarts the clock whenever the phase changes, and
   * resetProgressClock() forces a restart at conversion boundaries (new
   * decode, new convert, each attempt of the adaptive target-size loop) so
   * an ETA never extrapolates from a previous phase's or attempt's elapsed.
   */
  let progressElapsedMs = $state(0);
  let phaseStartedAt = 0;
  let lastPhase: ConvertProgress["phase"] | null = null;

  /** Force the next reportProgress() to restart the phase clock */
  function resetProgressClock() {
    lastPhase = null;
    progressElapsedMs = 0;
  }

  /** Update progress state and keep the per-phase elapsed clock in sync */
  function reportProgress(p: ConvertProgress) {
    const now = performance.now();
    if (p.phase !== lastPhase) {
      lastPhase = p.phase;
      phaseStartedAt = now;
    }
    progressElapsedMs = now - phaseStartedAt;
    progress = p;
  }

  /** Accepted file types: GIF, WebP, and common video formats */
  const acceptTypes = "image/gif,image/webp,video/mp4,video/webm,video/quicktime,video/x-matroska";

  async function handleFile(files: File[]) {
    const file = files[0];
    if (!file) return;

    // A new file supersedes any in-flight decode/convert work: invalidate the
    // generation (state-inert) AND abort the old signal (stop the CPU burn)
    const gen = guard.invalidate();
    const signal = nextAbortSignal();

    sourceFile = file;
    state = "decoding";
    resetProgressClock();
    error = "";
    streamingMode = false;
    streamingFrameCount = 0;

    // Create source preview URL for crop overlay
    if (sourcePreviewUrl) URL.revokeObjectURL(sourcePreviewUrl);
    sourcePreviewUrl = URL.createObjectURL(file);

    const onDecodeProgress = (p: ConvertProgress) => {
      if (guard.isCurrent(gen)) reportProgress(p);
    };

    try {
      // Route on the file's bytes, not its name/MIME — an MP4 renamed .gif
      // must not reach the GIF parser (L6)
      const type = await sniffFile(file);
      if (!guard.isCurrent(gen)) return;

      if (type === "image/gif") {
        const buffer = await file.arrayBuffer();
        const decoded = await decodeGif(buffer, onDecodeProgress, signal);
        if (!guard.isCurrent(gen)) return;
        frames = decoded.frames;
        sourceInfo = decoded.info;
        options.outputFormat = "webp";
        options.targetFps = Math.round(decoded.info.fps);
      } else if (type === "image/webp") {
        const decoded = await decodeWebP(file, MAX_BATCH_FRAMES, onDecodeProgress, signal);
        if (!guard.isCurrent(gen)) return;
        frames = decoded.frames;
        sourceInfo = decoded.info;
        options.outputFormat = "gif";
        // Match the GIF branch: default to the source's own rate so the output
        // preserves timing instead of silently dropping frames to the 10 fps default.
        if (decoded.info.fps > 0) {
          options.targetFps = Math.min(30, Math.max(1, Math.round(decoded.info.fps)));
        }
      } else if (type.startsWith("video/")) {
        // Probe first to decide batch vs streaming
        const { info, videoUrl } = await probeVideo(file, options.targetFps, signal);
        if (!guard.isCurrent(gen)) {
          URL.revokeObjectURL(videoUrl);
          return;
        }

        if (chooseVideoRoute(info, options.targetFps) === "streaming") {
          // Streaming mode: skip frame decode, just use metadata
          streamingMode = true;
          sourceInfo = info;
          frames = [];
          // probeVideo created an object URL — use it for preview, revoke the old one
          if (sourcePreviewUrl !== videoUrl) {
            URL.revokeObjectURL(sourcePreviewUrl);
            sourcePreviewUrl = videoUrl;
          }
        } else {
          // Batch mode: decode all frames into memory
          URL.revokeObjectURL(videoUrl); // probeVideo's URL not needed
          const decoded = await decodeVideo(file, options.targetFps, MAX_BATCH_FRAMES, onDecodeProgress, signal);
          if (!guard.isCurrent(gen)) return;
          frames = decoded.frames;
          sourceInfo = decoded.info;
        }
        options.outputFormat = "webp";
      } else {
        throw new Error(`Unsupported format: ${type || file.name}`);
      }

      // Preserve the source's own loop behaviour (0 = infinite) so a
      // play-once input doesn't silently become an infinite loop.
      if (sourceInfo) options.loops = sourceInfo.loopCount;

      state = "settings";
    } catch (e) {
      // A superseded decode's failure (including deliberate aborts) must not
      // paint the error screen over whatever the user is doing now
      if (!guard.isCurrent(gen) || isAbortError(e)) return;
      // A stale deploy's missing chunk: reload once instead of erroring (M4)
      if (recoverFromChunkError(e, BUILD_ID)) return;
      error = e instanceof Error ? e.message : "Failed to decode file";
      state = "error";
    }
  }

  /**
   * Encode pre-decoded frames to WebP via the streaming Worker encoder.
   * Each frame is subsampled, transformed (crop/resize/rotate in a single
   * drawImage) and pushed one at a time, so no transformed copy of the whole
   * animation is ever buffered (H2). All libwebp settings are applied.
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
      const workFrames = subsampleFrames(inputFrames, sourceFps, opts.targetFps);
      const preparer = new FramePreparer(width, height, transformFromOptions(opts));
      const totalFrames = workFrames.length;
      await encoder.init(getWebPInitOptions(opts));
      encoder.setTotalFrames(totalFrames);

      for (let i = 0; i < totalFrames; i++) {
        const frame = workFrames[i]!;
        reportProgress({
          phase: "encoding",
          percent: Math.round((i / totalFrames) * 95),
          frame: i + 1,
          total: totalFrames,
        });
        const prepared = preparer.fromRgba(frame.rgba);
        // A freshly transformed buffer is throwaway — hand it over zero-copy;
        // untouched source frames are copied so re-encodes still have them
        await encoder.pushFrame(
          prepared.rgba,
          prepared.width,
          prepared.height,
          getWebPFrameOptions(opts, frame.delay),
          prepared.fresh,
        );
      }

      reportProgress({ phase: "encoding", percent: 97, frame: totalFrames, total: totalFrames });
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
  async function streamingConvert(opts: ConvertOptions, signal: AbortSignal): Promise<Blob> {
    if (!sourceFile || !sourceInfo) throw new Error("No source file");

    const encoder = new StreamingWebPEncoder();
    activeEncoder = encoder;

    try {
      const totalFrames = Math.min(
        Math.floor((sourceInfo.totalDuration / 1000) * opts.targetFps),
        10000,
      );

      await encoder.init(getWebPInitOptions(opts));
      encoder.setTotalFrames(totalFrames);

      const delayMs = Math.round(1000 / opts.targetFps);
      let pushed = 0;

      for await (const frame of streamDecodeVideo(sourceFile, {
        targetFps: opts.targetFps,
        maxFrames: totalFrames,
        transform: transformFromOptions(opts),
      }, (p) => {
        reportProgress({
          phase: "decoding",
          percent: Math.round(p.percent * 0.95),
          frame: p.frame,
          total: p.total,
        });
      }, signal)) {
        // Each decoded frame is a fresh buffer — transfer without copying
        await encoder.pushFrame(
          frame.rgba,
          frame.width,
          frame.height,
          getWebPFrameOptions(opts, delayMs),
          true,
        );
        pushed++;
      }

      streamingFrameCount = pushed;

      reportProgress({ phase: "encoding", percent: 97, frame: pushed, total: totalFrames });
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
    resetProgressClock();
    error = "";
    resultNotice = "";

    // Capture the live generation — cancel/reset invalidates it, after which
    // this conversion must neither touch state nor surface errors.
    const gen = guard.token();
    const signal = nextAbortSignal();
    const onEncodeProgress = (p: ConvertProgress) => {
      if (guard.isCurrent(gen)) reportProgress(p);
    };

    try {
      let blob: Blob;

      // Batch video frames were decoded at the load-time fps; raising the
      // slider needs frames that don't exist yet. Re-decode at the higher
      // rate, or fall to the streaming pipeline when that many decoded
      // frames would not fit the batch path (M31, M2).
      if (sourceFile && needsFpsRedecode(sourceInfo, options.targetFps, streamingMode)) {
        if (chooseVideoRoute(sourceInfo, options.targetFps) === "streaming") {
          streamingMode = true;
          frames = [];
        } else {
          const decoded = await decodeVideo(sourceFile, options.targetFps, MAX_BATCH_FRAMES, onEncodeProgress, signal);
          if (!guard.isCurrent(gen)) return;
          frames = decoded.frames;
          sourceInfo = decoded.info;
        }
      }

      if (streamingMode && options.outputFormat === "webp") {
        // Large video: lazy decode + encode one frame at a time
        blob = await streamingConvert(options, signal);
      } else if (streamingMode && options.outputFormat === "gif") {
        // GIF from large video: decode capped frames then GIF-encode.
        // The frames come back already sampled at options.targetFps, so pass that
        // as the source FPS. They are kept in a local, not in `frames`, so the
        // streaming state isn't left holding up to 300 frames (L2).
        const decoded = await decodeVideo(sourceFile!, options.targetFps, 300, onEncodeProgress, signal);
        if (!guard.isCurrent(gen)) return; // don't clobber a newly loaded file (M18)
        streamingFrameCount = decoded.frames.length;
        if (decoded.info.frameCapped) {
          resultNotice = `GIF output from long videos is capped at ${decoded.frames.length} frames — use WebP for the full length.`;
        }
        blob = await encodeAnimatedGif(
          decoded.frames, decoded.info.width, decoded.info.height,
          options, decoded.info.fps, onEncodeProgress, signal,
        );
      } else if (options.outputFormat === "gif") {
        // GIF output from pre-decoded frames
        blob = await encodeAnimatedGif(
          frames, sourceInfo.width, sourceInfo.height,
          options, sourceInfo.fps, onEncodeProgress, signal,
        );
      } else {
        // WebP output from pre-decoded frames — all settings applied. The
        // adaptive loop only runs with a target size and lossy output.
        const info = sourceInfo;
        const targetBytes = options.lossless ? 0 : options.targetSizeBytes;
        const result = await encodeToTarget(
          (quality) => encodeFramesWebP(frames, info.width, info.height, { ...options, quality }, info.fps),
          {
            startQuality: options.quality,
            targetBytes,
            // Each attempt restarts the phase clock — an ETA extrapolated
            // from a previous attempt's elapsed time would be nonsense
            onAttempt: () => resetProgressClock(),
          },
        );
        blob = result.blob;
        if (!result.fits) {
          resultNotice =
            `Couldn't reach ${formatFileSize(targetBytes)} — smallest result at quality ` +
            `${result.quality} is ${formatFileSize(blob.size)}. Try a smaller max dimension or lower FPS.`;
        }
      }

      // A zombie conversion finishing after reset() must not set "done" —
      // with sourceFile nulled no template branch would match (blank screen),
      // or worse it would clobber a newly loaded file's state (M18)
      if (!guard.isCurrent(gen)) return;

      if (resultUrl) URL.revokeObjectURL(resultUrl);
      resultBlob = blob;
      resultUrl = URL.createObjectURL(blob);
      state = "done";
    } catch (e) {
      // Cancel aborts the encoder (whose pending promise then rejects with
      // "Encoder aborted" on a later microtask) and the decode signal (whose
      // rejection is an AbortError) — swallowing stale-generation and abort
      // failures keeps cancel/"Change file" off the error screen (M17)
      if (!guard.isCurrent(gen) || isAbortError(e)) return;
      if (recoverFromChunkError(e, BUILD_ID)) return;
      error = e instanceof Error ? e.message : "Encoding failed";
      state = "error";
    }
  }

  /** Cancel a running conversion and return to the settings panel (H1) */
  function cancelConvert() {
    stopInFlightWork();
    state = "settings";
  }

  function reset() {
    // Orphan AND stop all in-flight async work first — late rejections and
    // results must not touch state after this point (M17/M18)
    stopInFlightWork();
    if (resultUrl) URL.revokeObjectURL(resultUrl);
    if (sourcePreviewUrl) URL.revokeObjectURL(sourcePreviewUrl);
    sourceFile = null;
    sourceInfo = null;
    frames = [];
    resultBlob = null;
    resultUrl = "";
    resultNotice = "";
    sourcePreviewUrl = "";
    streamingMode = false;
    streamingFrameCount = 0;
    // Restore per-file defaults — otherwise file B silently inherits file A's
    // crop rect, targetFps, loop seed, etc (M6)
    options = defaultOptions();
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
    <!-- Cancel discards the half-decoded file — decoding can run for minutes (H1) -->
    <ConvertProgressBar {progress} elapsedMs={progressElapsedMs} oncancel={reset} />

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
      {streamingMode}
      onconvert={handleConvert}
    />

    {#if state === "converting"}
      <ConvertProgressBar {progress} elapsedMs={progressElapsedMs} oncancel={cancelConvert} />
    {/if}

  {:else if state === "done" && resultBlob && sourceFile}
    <ConvertResult
      originalSize={sourceFile.size}
      resultSize={resultBlob.size}
      {resultUrl}
      filename={sourceFile.name}
      frameCount={displayFrameCount}
      outputFormat={options.outputFormat}
      notice={resultNotice}
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
