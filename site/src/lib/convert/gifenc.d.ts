/** Type declarations for gifenc (mattdesl/gifenc) — no bundled types */
declare module "gifenc" {
  export function GIFEncoder(opts?: {
    auto?: boolean;
    initialCapacity?: number;
  }): {
    writeFrame(
      index: Uint8Array,
      width: number,
      height: number,
      opts?: {
        palette?: number[][];
        delay?: number;
        repeat?: number;
        transparent?: boolean;
        transparentIndex?: number;
        dispose?: number;
      },
    ): void;
    finish(): void;
    bytes(): Uint8Array;
    bytesView(): Uint8Array;
  };

  export function quantize(
    rgba: Uint8Array | Uint8ClampedArray,
    maxColors: number,
    opts?: {
      format?: string;
      oneBitAlpha?: boolean | number;
      clearAlpha?: boolean;
      clearAlphaThreshold?: number;
      clearAlphaColor?: number;
    },
  ): number[][];

  export function applyPalette(
    rgba: Uint8Array | Uint8ClampedArray,
    palette: number[][],
    format?: string,
  ): Uint8Array;

  export function nearestColorIndex(
    palette: number[][],
    pixel: number[],
  ): number;

  export function nearestColorIndexWithDistance(
    palette: number[][],
    pixel: number[],
  ): [number, number];
}
