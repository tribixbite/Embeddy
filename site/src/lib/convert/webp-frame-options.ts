/**
 * Mapping from the UI's camelCase ConvertOptions to the streaming WebP
 * encoder Worker's snake_case libwebp option names.
 *
 * Extracted from ConvertTool so the mapping is unit-testable and shared
 * verbatim by both the batch (pre-decoded frames) and streaming (lazy video
 * decode) encode paths — a field added to ConvertOptions only needs wiring
 * here to reach the encoder in both.
 */

import type { FrameOptions, InitOptions } from "./streaming-encoder.worker";
import type { ConvertOptions } from "./types";

/** Per-frame libwebp settings for StreamingWebPEncoder.pushFrame() */
export function getWebPFrameOptions(opts: ConvertOptions, duration: number): FrameOptions {
  return {
    duration,
    lossless: opts.lossless,
    quality: opts.quality,
    method: opts.method,
    exact: opts.exact,
    sns_strength: opts.snsStrength,
    filter_strength: opts.filterStrength,
    filter_sharpness: opts.filterSharpness,
    filter_type: opts.filterType,
    autofilter: opts.autofilter,
    alpha_quality: opts.alphaQuality,
    alpha_compression: opts.alphaCompression,
    alpha_filtering: opts.alphaFiltering,
    pass: opts.passes,
    preprocessing: opts.preprocessing,
    near_lossless: opts.nearLossless,
    sharp_yuv: opts.sharpYuv,
    target_size: opts.targetSize,
    segments: opts.segments,
    partition_limit: opts.partitionLimit,
    low_memory: opts.lowMemory,
    emulate_jpeg_size: opts.emulateJpegSize,
  };
}

/** Animation-level settings for StreamingWebPEncoder.init() */
export function getWebPInitOptions(opts: ConvertOptions): InitOptions {
  return {
    minimize: opts.minimizeSize,
    loop: opts.loops,
    mixed: opts.mixed,
    kmin: opts.kmin,
    kmax: opts.kmax,
  };
}
