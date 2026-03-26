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
}

export interface WebpEncoderInstance {
  init(options: WebpFileOptions): boolean;
  push(pixels: Uint8Array, width: number, height: number, options: WebpFrameOptions): boolean;
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
