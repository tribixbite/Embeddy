/**
 * Real video output (MP4 / WebM) via WebCodecs + Mediabunny.
 *
 * Mediabunny (MPL-2.0, https://mediabunny.dev) does the demux → decode →
 * transform → encode → mux pipeline; this module maps Convert's options onto
 * its Conversion API and, for joining clips, drives its sinks/sources
 * directly. Loaded lazily — the library is only fetched when a video format
 * is chosen.
 *
 * Geometry note: Convert applies crop (in source orientation) → flip →
 * rotate; Mediabunny applies rotate → horizontal flip → crop. The pure
 * {@link toMediabunnyGeometry} converts between the two so both paths
 * produce the same picture (tested in video-export.test.ts).
 */

import type { ConvertOptions, CropRect } from "./types";
import { cropRectToPixels } from "./encoder";
import { planFrame, transformFromOptions, type Rotation } from "./prepare-frame";
import { clampSpeed, clampTrim } from "./timeline";
import { throwIfAborted } from "./abort";

/** Container/codec preferences per output format */
export const VIDEO_FORMATS = {
  mp4: {
    mime: "video/mp4",
    // H.264 first for playback everywhere; the rest as fallbacks
    videoCodecs: ["avc", "hevc", "vp9", "av1"] as const,
    // AAC isn't encodable in every browser (not in Linux Chromium); Opus in
    // MP4 is valid and widely playable
    audioCodecs: ["aac", "opus"] as const,
  },
  webm: {
    mime: "video/webm",
    videoCodecs: ["vp9", "vp8", "av1"] as const,
    audioCodecs: ["opus", "vorbis"] as const,
  },
} as const;

export type VideoOutputFormat = keyof typeof VIDEO_FORMATS;

/** Mediabunny's crop rect (pixels, in post-rotation/flip space) */
export interface PixelRect {
  left: number;
  top: number;
  width: number;
  height: number;
}

/** Transform expressed in Mediabunny's operation order */
export interface MediabunnyGeometry {
  rotate: Rotation;
  /** Horizontal flip after rotation */
  flip: boolean;
  /** Crop in the rotated/flipped frame, or undefined for none */
  crop?: PixelRect;
  /** Final output size, even on both axes (4:2:0 encoders require it) */
  width: number;
  height: number;
}

/** Round down to an even number, minimum 2 */
export function evenFloor(n: number): number {
  return Math.max(2, Math.floor(n / 2) * 2);
}

/**
 * Map a point of a w×h image through one of our operations, returning the
 * point in the transformed image (and its size).
 */
function rotatePoint(x: number, y: number, w: number, h: number, rotate: Rotation): [number, number] {
  switch (rotate) {
    case 90:
      return [h - y, x];
    case 180:
      return [w - x, h - y];
    case 270:
      return [y, w - x];
    default:
      return [x, y];
  }
}

/**
 * Convert Convert's crop/flip/rotate/maxDimension into Mediabunny's
 * rotate → flip(H) → crop → resize order.
 *
 * Ours is M = R(r) ∘ F applied to the cropped source. Using H·R(a)·H = R(-a)
 * and V = R(180)·H, every combination reduces to Mediabunny's H' ∘ R(r'):
 *  - no flip:   r' = r,          flip' = false
 *  - H:         r' = -r,         flip' = true
 *  - V:         r' = -(r + 180), flip' = true
 *  - H + V:     r' = r + 180,    flip' = false  (H·V is a half turn)
 * The crop rectangle is carried through the same map M.
 */
export function toMediabunnyGeometry(
  srcW: number,
  srcH: number,
  opts: Pick<ConvertOptions, "crop" | "maxDimension" | "rotate" | "flipH" | "flipV">,
): MediabunnyGeometry {
  const norm = (deg: number) => ((((deg % 360) + 360) % 360) as Rotation);
  let rotate: Rotation;
  let flip: boolean;
  if (opts.flipH && opts.flipV) {
    rotate = norm(opts.rotate + 180);
    flip = false;
  } else if (opts.flipH) {
    rotate = norm(-opts.rotate);
    flip = true;
  } else if (opts.flipV) {
    rotate = norm(-(opts.rotate + 180));
    flip = true;
  } else {
    rotate = opts.rotate;
    flip = false;
  }

  let crop: PixelRect | undefined;
  if (opts.crop) {
    const { px, py, pw, ph } = cropRectToPixels(srcW, srcH, opts.crop as CropRect);
    // Carry the rect's corners through our own map M = R(r) ∘ F
    const corners: Array<[number, number]> = [
      [px, py],
      [px + pw, py + ph],
    ].map(([x, y]) => {
      const fx = opts.flipH ? srcW - x! : x!;
      const fy = opts.flipV ? srcH - y! : y!;
      return rotatePoint(fx, fy, srcW, srcH, opts.rotate);
    });
    const [a, b] = corners as [[number, number], [number, number]];
    crop = {
      left: Math.min(a[0], b[0]),
      top: Math.min(a[1], b[1]),
      width: Math.abs(a[0] - b[0]),
      height: Math.abs(a[1] - b[1]),
    };
  }

  // Final size = our own plan (crop → resize → rotate), made even
  const plan = planFrame(srcW, srcH, transformFromOptions(opts));
  return { rotate, flip, crop, width: evenFloor(plan.outW), height: evenFloor(plan.outH) };
}

/** Map our 1-100 quality slider onto Mediabunny's qualitative levels */
export function qualityLevel(quality: number): "very-low" | "low" | "medium" | "high" | "very-high" {
  if (quality < 20) return "very-low";
  if (quality < 45) return "low";
  if (quality < 70) return "medium";
  if (quality < 90) return "high";
  return "very-high";
}

/** Result of a video export */
export interface VideoExportResult {
  blob: Blob;
  /** User-facing caveats (audio dropped, etc.) */
  notices: string[];
}

/**
 * True when this browser can produce `format` at all (WebCodecs present and
 * at least one of the format's video codecs encodable). Cheap enough to call
 * on file load; loads Mediabunny lazily.
 */
export async function canExportVideo(format: VideoOutputFormat, width = 640, height = 480): Promise<boolean> {
  if (typeof VideoEncoder === "undefined") return false;
  try {
    const { getFirstEncodableVideoCodec } = await import("mediabunny");
    const codec = await getFirstEncodableVideoCodec([...VIDEO_FORMATS[format].videoCodecs], {
      width: evenFloor(width),
      height: evenFloor(height),
    });
    return codec !== null;
  } catch {
    return false;
  }
}

/**
 * Re-encode one video with trim, crop, rotate/flip, resize, speed and mute.
 * Audio is dropped when muted, when speed ≠ 1 (re-timing audio needs pitch-
 * corrected resampling — reported as a notice), or when no audio codec is
 * encodable here.
 *
 * @param signal - Aborting cancels the conversion and rejects with AbortError
 */
export async function exportVideo(
  file: File,
  srcW: number,
  srcH: number,
  totalMs: number,
  opts: ConvertOptions,
  format: VideoOutputFormat,
  onProgress?: (fraction: number) => void,
  signal?: AbortSignal,
): Promise<VideoExportResult> {
  throwIfAborted(signal);
  const mb = await import("mediabunny");
  const spec = VIDEO_FORMATS[format];
  const notices: string[] = [];
  const geometry = toMediabunnyGeometry(srcW, srcH, opts);
  const trim = clampTrim(totalMs, opts.trimStartMs, opts.trimEndMs);
  const speed = clampSpeed(opts.speed);
  const quality = new mb.Quality(qualityLevel(opts.quality));

  const videoCodec = await mb.getFirstEncodableVideoCodec([...spec.videoCodecs], {
    width: geometry.width,
    height: geometry.height,
    quality,
  });
  if (!videoCodec) throw new Error(`This browser can't encode ${format.toUpperCase()} video`);

  let dropAudio = opts.muted;
  if (!dropAudio && speed !== 1) {
    dropAudio = true;
    notices.push("Audio was removed: changing speed doesn't re-time audio yet.");
  }
  const audioCodec = dropAudio ? null : await mb.getFirstEncodableAudioCodec([...spec.audioCodecs]);

  const input = new mb.Input({ source: new mb.BlobSource(file), formats: mb.ALL_FORMATS });
  const output = new mb.Output({
    format: format === "mp4" ? new mb.Mp4OutputFormat({ fastStart: "in-memory" }) : new mb.WebMOutputFormat(),
    target: new mb.BufferTarget(),
  });

  const conversion = await mb.Conversion.init({
    input,
    output,
    trim: { start: trim.startMs / 1000, end: trim.endMs / 1000 },
    video: {
      rotate: geometry.rotate,
      flip: geometry.flip,
      crop: geometry.crop,
      width: geometry.width,
      height: geometry.height,
      fit: "fill",
      codec: videoCodec,
      quality,
      forceTranscode: true,
      // Speed: re-time each frame (output = source time / speed)
      process:
        speed === 1
          ? undefined
          : (sample) => {
              sample.setTimestamp(sample.timestamp / speed);
              sample.setDuration(sample.duration / speed);
              return sample;
            },
    },
    audio: dropAudio || !audioCodec ? { discard: true } : { codec: audioCodec, quality },
  });

  if (!dropAudio && !audioCodec && (await input.getPrimaryAudioTrack())) {
    notices.push(`Audio was removed: this browser can't encode ${spec.audioCodecs.join("/")} audio.`);
  }
  if (!conversion.isValid) {
    const reasons = conversion.discardedTracks.map((d) => `${d.track.type}: ${d.reason}`).join(", ");
    input.dispose();
    throw new Error(`Can't convert this video (${reasons || "no usable tracks"})`);
  }

  conversion.onProgress = (p) => onProgress?.(p);
  const onAbort = () => void conversion.cancel();
  signal?.addEventListener("abort", onAbort, { once: true });
  try {
    await conversion.execute();
  } catch (e) {
    if (signal?.aborted) throw new DOMException("The operation was aborted", "AbortError");
    throw e;
  } finally {
    signal?.removeEventListener("abort", onAbort);
    input.dispose();
  }

  const buffer = output.target.buffer;
  if (!buffer) throw new Error("Video encoder produced no output");
  return { blob: new Blob([buffer], { type: spec.mime }), notices };
}

/** One input of a join, with its own trim window */
export interface JoinClip {
  file: File;
  /** Trim start in ms */
  trimStartMs: number;
  /** Trim end in ms (0 = to the end) */
  trimEndMs: number;
}

/** Output sample rate / layout every joined clip's audio is rendered to */
const JOIN_SAMPLE_RATE = 48_000;
const JOIN_CHANNELS = 2;

/**
 * Render one clip's audio window into a single AudioBuffer at the join's
 * common rate/layout, so clips with different sample rates or channel counts
 * can be appended back to back. Clips without audio yield silence of the
 * same length, keeping audio and video aligned.
 */
async function renderClipAudio(
  mb: typeof import("mediabunny"),
  input: InstanceType<typeof mb.Input>,
  startS: number,
  endS: number,
  signal?: AbortSignal,
): Promise<AudioBuffer> {
  const length = Math.max(1, Math.ceil((endS - startS) * JOIN_SAMPLE_RATE));
  const ctx = new OfflineAudioContext(JOIN_CHANNELS, length, JOIN_SAMPLE_RATE);
  const track = await input.getPrimaryAudioTrack();
  if (track && (await track.canDecode())) {
    const sink = new mb.AudioSampleSink(track);
    for await (const sample of sink.samples(startS, endS)) {
      throwIfAborted(signal);
      const node = ctx.createBufferSource();
      node.buffer = sample.toAudioBuffer();
      node.connect(ctx.destination);
      // Samples straddling the window start are played from their offset in
      const when = Math.max(0, sample.timestamp - startS);
      const offset = Math.max(0, startS - sample.timestamp);
      node.start(when, offset);
      sample.close();
    }
  }
  return ctx.startRendering();
}

/**
 * Join several videos end to end into one MP4/WebM. Every clip is scaled to
 * fit (letterboxed) the first clip's size after `maxDimension`, trimmed to
 * its own window, and its audio resampled to 48 kHz stereo. Crop/rotate and
 * speed are not applied to joins.
 *
 * @param onProgress - Fraction of total output duration written
 * @param signal - Aborting cancels the output and rejects with AbortError
 */
export async function joinVideos(
  clips: JoinClip[],
  opts: Pick<ConvertOptions, "quality" | "maxDimension" | "muted">,
  format: VideoOutputFormat,
  onProgress?: (fraction: number) => void,
  signal?: AbortSignal,
): Promise<VideoExportResult> {
  if (clips.length === 0) throw new Error("No clips to join");
  throwIfAborted(signal);
  const mb = await import("mediabunny");
  const spec = VIDEO_FORMATS[format];
  const notices: string[] = [];
  const quality = new mb.Quality(qualityLevel(opts.quality));

  // Open every input up front: sizes, durations and audio presence decide
  // the output configuration before anything is written
  const inputs = clips.map((c) => new mb.Input({ source: new mb.BlobSource(c.file), formats: mb.ALL_FORMATS }));
  try {
    const windows: Array<{ startS: number; endS: number }> = [];
    let anyAudio = false;
    for (let i = 0; i < clips.length; i++) {
      const input = inputs[i]!;
      const clip = clips[i]!;
      const track = await input.getPrimaryVideoTrack();
      if (!track) throw new Error(`${clip.file.name} has no video track`);
      const totalMs = (await input.computeDuration()) * 1000;
      const trim = clampTrim(totalMs, clip.trimStartMs, clip.trimEndMs);
      windows.push({ startS: trim.startMs / 1000, endS: trim.endMs / 1000 });
      if (await input.getPrimaryAudioTrack()) anyAudio = true;
    }

    const firstTrack = (await inputs[0]!.getPrimaryVideoTrack())!;
    const plan = planFrame(
      await firstTrack.getDisplayWidth(),
      await firstTrack.getDisplayHeight(),
      { crop: null, maxDimension: opts.maxDimension, rotate: 0, flipH: false, flipV: false },
    );
    const width = evenFloor(plan.outW);
    const height = evenFloor(plan.outH);

    const videoCodec = await mb.getFirstEncodableVideoCodec([...spec.videoCodecs], { width, height, quality });
    if (!videoCodec) throw new Error(`This browser can't encode ${format.toUpperCase()} video`);
    const audioCodec =
      anyAudio && !opts.muted
        ? await mb.getFirstEncodableAudioCodec([...spec.audioCodecs], {
            numberOfChannels: JOIN_CHANNELS,
            sampleRate: JOIN_SAMPLE_RATE,
          })
        : null;
    if (anyAudio && !opts.muted && !audioCodec) {
      notices.push(`Audio was removed: this browser can't encode ${spec.audioCodecs.join("/")} audio.`);
    }

    const output = new mb.Output({
      format: format === "mp4" ? new mb.Mp4OutputFormat({ fastStart: "in-memory" }) : new mb.WebMOutputFormat(),
      target: new mb.BufferTarget(),
    });
    const videoSource = new mb.VideoSampleSource({ codec: videoCodec, quality, sizeChangeBehavior: "contain" });
    output.addVideoTrack(videoSource);
    const audioSource = audioCodec ? new mb.AudioBufferSource({ codec: audioCodec, quality }) : null;
    if (audioSource) output.addAudioTrack(audioSource);

    const totalS = windows.reduce((sum, w) => sum + (w.endS - w.startS), 0);
    const onAbort = () => void output.cancel();
    signal?.addEventListener("abort", onAbort, { once: true });
    try {
      await output.start();
      let offsetS = 0;
      for (let i = 0; i < clips.length; i++) {
        const { startS, endS } = windows[i]!;
        // Audio first: it is small, so the muxer buffers it while this
        // clip's video streams through
        if (audioSource) await audioSource.add(await renderClipAudio(mb, inputs[i]!, startS, endS, signal));

        const track = (await inputs[i]!.getPrimaryVideoTrack())!;
        const sink = new mb.CanvasSink(track, { width, height, fit: "contain", poolSize: 2 });
        for await (const wrapped of sink.canvases(startS, endS)) {
          throwIfAborted(signal);
          const t = Math.max(0, wrapped.timestamp - startS);
          const sample = new mb.VideoSample(wrapped.canvas, {
            timestamp: offsetS + t,
            duration: Math.min(wrapped.duration, endS - startS - t) || wrapped.duration,
          });
          await videoSource.add(sample);
          sample.close();
          onProgress?.(Math.min(1, (offsetS + t) / totalS));
        }
        offsetS += endS - startS;
      }
      videoSource.close();
      audioSource?.close();
      await output.finalize();
    } catch (e) {
      if (signal?.aborted) throw new DOMException("The operation was aborted", "AbortError");
      await output.cancel().catch(() => {});
      throw e;
    } finally {
      signal?.removeEventListener("abort", onAbort);
    }

    const buffer = output.target.buffer;
    if (!buffer) throw new Error("Video encoder produced no output");
    return { blob: new Blob([buffer], { type: spec.mime }), notices };
  } finally {
    for (const input of inputs) input.dispose();
  }
}
