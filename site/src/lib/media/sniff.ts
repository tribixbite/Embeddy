/**
 * Content sniffing for dropped/picked media.
 *
 * File.type and the extension are both caller-controlled (an MP4 renamed
 * `.gif`, a share target with no MIME), so every tool decides which decoder
 * to use from the leading bytes instead. Shared by Convert (routing between
 * the GIF / WebP / video decoders) and Squoosh (picking a @jsquash codec).
 */

/** Bytes needed by {@link sniffMimeType}; read this many from the file head. */
export const SNIFF_BYTES = 12;

/** ISO-BMFF major brands of the HEIF still-image family (not video). */
const HEIF_BRANDS = new Set(["heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1"]);

/**
 * Identify a media type from its first {@link SNIFF_BYTES} bytes.
 *
 * @param head - The file's leading bytes (fewer is fine; unknown → fallback)
 * @param fallback - The reported MIME type, returned when nothing matches and
 *   preferred when it is a more specific variant of the sniffed container
 *   (e.g. `video/x-matroska` for an EBML file, `video/x-m4v` for ISO-BMFF)
 * @returns A MIME type string
 */
export function sniffMimeType(head: ArrayBuffer | Uint8Array, fallback: string): string {
  const v = head instanceof Uint8Array ? head : new Uint8Array(head);
  const at = (i: number) => v[i] ?? -1;
  const ascii = (from: number, to: number) =>
    String.fromCharCode(...Array.from(v.subarray(from, to)));

  // JPEG: FF D8 FF
  if (at(0) === 0xff && at(1) === 0xd8 && at(2) === 0xff) return "image/jpeg";
  // PNG: 89 'PNG'
  if (at(0) === 0x89 && at(1) === 0x50 && at(2) === 0x4e && at(3) === 0x47) return "image/png";
  // GIF: 'GIF8' (GIF87a / GIF89a)
  if (v.length >= 4 && ascii(0, 4) === "GIF8") return "image/gif";
  // WebP: 'RIFF' .... 'WEBP'
  if (v.length >= 12 && ascii(0, 4) === "RIFF" && ascii(8, 12) === "WEBP") return "image/webp";
  // EBML (WebM / Matroska): 1A 45 DF A3
  if (at(0) === 0x1a && at(1) === 0x45 && at(2) === 0xdf && at(3) === 0xa3) {
    return fallback.startsWith("video/") ? fallback : "video/webm";
  }
  // ISO-BMFF: ....'ftyp' + major brand
  if (v.length >= 12 && ascii(4, 8) === "ftyp") {
    const brand = ascii(8, 12);
    if (brand === "avif" || brand === "avis") return "image/avif";
    // "ftyp" alone is any ISO-BMFF file — HEIC must not reach the AVIF or
    // video decoders, so the reported type stands
    if (HEIF_BRANDS.has(brand)) return fallback;
    if (brand === "qt  ") return "video/quicktime";
    return fallback.startsWith("video/") ? fallback : "video/mp4";
  }
  return fallback;
}

/** Sniff a File by reading only its first {@link SNIFF_BYTES} bytes. */
export async function sniffFile(file: Blob & { type: string }): Promise<string> {
  return sniffMimeType(await file.slice(0, SNIFF_BYTES).arrayBuffer(), file.type);
}
