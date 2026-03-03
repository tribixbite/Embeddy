/** Minimal type declarations for piexifjs — EXIF data manipulation library */
declare module "piexifjs" {
  /** Remove all EXIF data from a JPEG data URL string. Returns a clean data URL. */
  export function remove(dataUrl: string): string;
  /** Load EXIF data from a JPEG data URL. Returns an EXIF object. */
  export function load(dataUrl: string): Record<string, Record<number, unknown>>;
  /** Dump EXIF object back to binary string for insertion. */
  export function dump(exifObj: Record<string, Record<number, unknown>>): string;
  /** Insert EXIF binary string into a JPEG data URL. */
  export function insert(exifStr: string, dataUrl: string): string;
}
