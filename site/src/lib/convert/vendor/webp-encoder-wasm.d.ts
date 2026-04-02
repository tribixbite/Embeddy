/**
 * Type declarations for the vendored webp_encoder WASM module.
 * @see https://github.com/xiaozhuai/webp_encoder
 *
 * The JS file is a ~1MB Emscripten MODULARIZE build with embedded WASM.
 * This .d.ts prevents TypeScript from parsing the huge JS file.
 */

export interface WebpFileOptions {
  /** Minimize output size (slow). Default: true */
  minimize?: boolean;
  /** Loop count (0 = infinite). Default: 0 */
  loop?: number;
  /** Min key-frame distance. Default: 0 */
  kmin?: number;
  /** Max key-frame distance. Default: 0 */
  kmax?: number;
  /** Allow mixed lossy/lossless frames. Default: true */
  mixed?: boolean;
}

export interface WebpFrameOptions {
  /** Frame duration in milliseconds. Default: 100 */
  duration?: number;
  /** Lossless encoding. Default: false */
  lossless?: boolean;
  /** Quality 0-100. Default: 100 */
  quality?: number;
  /** Speed/quality tradeoff 0=fast, 6=slow-better. Default: 0 */
  method?: number;
  /** Preserve exact RGB under transparent areas. Default: false */
  exact?: boolean;
  /** Spatial Noise Shaping strength 0-100. Default: 50 */
  sns_strength?: number;
  /** Deblocking filter strength 0-100. Default: 60 */
  filter_strength?: number;
  /** Filter sharpness 0-7. Default: 0 */
  filter_sharpness?: number;
  /** Filter type: 0=simple, 1=strong. Default: 1 */
  filter_type?: number;
  /** Auto-adjust filter strength. Default: 0 */
  autofilter?: number;
  /** Alpha transparency quality 0-100. Default: 100 */
  alpha_quality?: number;
  /** Alpha compression: 0=none, 1=lossless. Default: 1 */
  alpha_compression?: number;
  /** Alpha filtering: 0=none, 1=fast, 2=best. Default: 1 */
  alpha_filtering?: number;
  /** Entropy-analysis passes 1-10. Default: 1 */
  pass?: number;
  /** Preprocessing: 0=none, 1=segment-smooth, 2=dithering. Default: 0 */
  preprocessing?: number;
  /** Near-lossless quality 0-100 (100=off). Default: 100 */
  near_lossless?: number;
  /** Sharp YUV conversion (0 or 1). Default: 0 */
  sharp_yuv?: number;
  /** Target size in bytes (0=off). Default: 0 */
  target_size?: number;
  /** Max segments 1-4. Default: 4 */
  segments?: number;
  /** Partition limit 0-100 (0=off). Default: 0 */
  partition_limit?: number;
  /** Reduce memory usage (0 or 1). Default: 0 */
  low_memory?: number;
  /** Emulate JPEG size metric (0 or 1). Default: 0 */
  emulate_jpeg_size?: number;
}

export interface WebpEncoderInstance {
  init(options: WebpFileOptions): boolean;
  /** Push a frame. Returns boolean or Promise<boolean> due to Asyncify (emscripten_sleep). */
  push(pixels: Uint8Array, width: number, height: number, options: WebpFrameOptions): boolean | Promise<boolean>;
  encode(): Uint8Array;
  release(): void;
}

export interface WebpEncoderModule {
  WebpEncoder: new () => WebpEncoderInstance;
}

/**
 * Emscripten factory function. Call to instantiate the WASM module.
 * Returns a Promise that resolves to the module with WebpEncoder class.
 */
declare function WebpEncoderWasm(): Promise<WebpEncoderModule>;
export default WebpEncoderWasm;
