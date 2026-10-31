/**
 * Client-side metadata removal before a public upload.
 *
 * A byte-level container walker rather than a re-encode: pixels are untouched,
 * and only the segments/chunks that carry identifying metadata are dropped.
 *
 * - JPEG: drops APP1 (EXIF, XMP — both can hold GPS), APP3-APP13 (incl. IPTC
 *   in APP13), APP15 and COM. Keeps APP0 (JFIF), APP2 (ICC profile / MPF) and
 *   APP14 (Adobe colour transform), which affect how the image decodes.
 * - PNG: drops eXIf, tEXt, zTXt, iTXt and tIME chunks.
 * - WebP: drops EXIF and XMP chunks and clears their VP8X flag bits.
 *
 * Unlike the previous piexifjs path this fails CLOSED: anything malformed
 * throws [MetadataStripError], so the caller never uploads a file the user
 * asked to clean with its GPS data still in it.
 */

/** Raised when a file cannot be safely stripped. The upload must not proceed. */
export class MetadataStripError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "MetadataStripError";
  }
}

/** Container formats [stripMetadataBytes] understands. */
export type StrippableFormat = "jpeg" | "png" | "webp";

const PNG_SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

/** PNG ancillary chunks that carry free text, EXIF or timestamps. */
const PNG_METADATA_CHUNKS = new Set(["eXIf", "tEXt", "zTXt", "iTXt", "tIME"]);

/** VP8X flag bits (byte 0 of the VP8X payload). */
const VP8X_EXIF_FLAG = 0x08;
const VP8X_XMP_FLAG = 0x04;

/** Identify the container from magic bytes, or null if unsupported. */
export function sniffStrippableFormat(bytes: Uint8Array): StrippableFormat | null {
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) {
    return "jpeg";
  }
  if (bytes.length >= 8 && PNG_SIGNATURE.every((b, i) => bytes[i] === b)) {
    return "png";
  }
  if (
    bytes.length >= 12 &&
    ascii(bytes, 0, 4) === "RIFF" &&
    ascii(bytes, 8, 4) === "WEBP"
  ) {
    return "webp";
  }
  return null;
}

/**
 * Whether the UI should offer metadata stripping for this file. Decided from
 * the declared type/extension so it can run synchronously while rendering;
 * [stripExif] re-checks the actual bytes and throws on a mismatch.
 */
export function canStripExif(file: File): boolean {
  const type = file.type.toLowerCase();
  if (["image/jpeg", "image/jpg", "image/png", "image/webp"].includes(type)) return true;
  return /\.(jpe?g|png|webp)$/.test(file.name.toLowerCase());
}

/**
 * Strip identifying metadata from an image File.
 * @throws MetadataStripError if the bytes are not a supported, well-formed container.
 */
export async function stripExif(file: File): Promise<File> {
  const bytes = new Uint8Array(await file.arrayBuffer());
  const stripped = stripMetadataBytes(bytes);
  return new File([stripped as Uint8Array<ArrayBuffer>], file.name, {
    type: file.type,
    lastModified: file.lastModified,
  });
}

/**
 * Pure core of [stripExif]: returns new bytes with metadata removed.
 * @throws MetadataStripError for unsupported or malformed input.
 */
export function stripMetadataBytes(bytes: Uint8Array): Uint8Array {
  switch (sniffStrippableFormat(bytes)) {
    case "jpeg":
      return stripJpeg(bytes);
    case "png":
      return stripPng(bytes);
    case "webp":
      return stripWebP(bytes);
    default:
      throw new MetadataStripError("Unsupported format — only JPEG, PNG and WebP can be stripped");
  }
}

/** JPEG APPn markers kept because they change how the image decodes. */
function keepJpegMarker(marker: number): boolean {
  return marker === 0xe0 || marker === 0xe2 || marker === 0xee; // APP0, APP2, APP14
}

function stripJpeg(bytes: Uint8Array): Uint8Array {
  const parts: Uint8Array[] = [bytes.subarray(0, 2)]; // SOI
  let pos = 2;
  while (pos < bytes.length) {
    if (bytes[pos] !== 0xff) {
      throw new MetadataStripError(`Corrupt JPEG: expected a marker at byte ${pos}`);
    }
    // Any number of 0xFF fill bytes may precede a marker code.
    let markerPos = pos;
    while (markerPos < bytes.length && bytes[markerPos] === 0xff) markerPos++;
    if (markerPos >= bytes.length) throw new MetadataStripError("Corrupt JPEG: truncated marker");
    const marker = bytes[markerPos]!;
    const segStart = markerPos - 1;

    // Standalone markers carry no length field.
    if (marker === 0xd8 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
      parts.push(bytes.subarray(segStart, markerPos + 1));
      pos = markerPos + 1;
      continue;
    }
    if (marker === 0xd9) {
      // EOI before any scan: copy it and anything after verbatim.
      parts.push(bytes.subarray(segStart));
      break;
    }
    if (markerPos + 2 >= bytes.length) throw new MetadataStripError("Corrupt JPEG: truncated segment");
    const length = (bytes[markerPos + 1]! << 8) | bytes[markerPos + 2]!;
    const segEnd = markerPos + 1 + length;
    if (length < 2 || segEnd > bytes.length) {
      throw new MetadataStripError("Corrupt JPEG: segment length runs past end of file");
    }

    if (marker === 0xda) {
      // Start of scan: entropy-coded data follows; no metadata can appear in
      // a conforming stream from here on, so copy the remainder verbatim.
      parts.push(bytes.subarray(segStart));
      break;
    }

    const isApp = marker >= 0xe0 && marker <= 0xef;
    const isComment = marker === 0xfe;
    if (!(isComment || (isApp && !keepJpegMarker(marker)))) {
      parts.push(bytes.subarray(segStart, segEnd));
    }
    pos = segEnd;
  }
  return concat(parts);
}

function stripPng(bytes: Uint8Array): Uint8Array {
  const parts: Uint8Array[] = [bytes.subarray(0, 8)];
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let pos = 8;
  let sawEnd = false;
  while (pos < bytes.length) {
    if (pos + 8 > bytes.length) throw new MetadataStripError("Corrupt PNG: truncated chunk header");
    const length = view.getUint32(pos);
    const type = ascii(bytes, pos + 4, 4);
    const chunkEnd = pos + 12 + length; // length + type + data + CRC
    if (chunkEnd > bytes.length) throw new MetadataStripError(`Corrupt PNG: ${type} chunk runs past end of file`);
    if (!PNG_METADATA_CHUNKS.has(type)) parts.push(bytes.subarray(pos, chunkEnd));
    pos = chunkEnd;
    if (type === "IEND") {
      sawEnd = true;
      break;
    }
  }
  if (!sawEnd) throw new MetadataStripError("Corrupt PNG: missing IEND");
  return concat(parts);
}

function stripWebP(bytes: Uint8Array): Uint8Array {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const riffEnd = Math.min(bytes.length, 8 + view.getUint32(4, true));
  const chunks: Uint8Array[] = [];
  let pos = 12;
  while (pos + 8 <= riffEnd) {
    const fourcc = ascii(bytes, pos, 4);
    const size = view.getUint32(pos + 4, true);
    const padded = size + (size & 1);
    const end = pos + 8 + padded;
    if (pos + 8 + size > riffEnd) throw new MetadataStripError(`Corrupt WebP: ${fourcc} chunk runs past end of file`);
    if (fourcc !== "EXIF" && fourcc !== "XMP ") {
      // Copy so the VP8X flag edit below never mutates the caller's buffer.
      const chunk = bytes.slice(pos, Math.min(end, riffEnd));
      if (fourcc === "VP8X" && size >= 1) chunk[8]! &= ~(VP8X_EXIF_FLAG | VP8X_XMP_FLAG);
      chunks.push(chunk);
    }
    pos = end;
  }
  const body = concat(chunks);
  const out = new Uint8Array(12 + body.length);
  out.set(bytes.subarray(0, 12));
  new DataView(out.buffer).setUint32(4, 4 + body.length, true); // "WEBP" + chunks
  out.set(body, 12);
  return out;
}

function ascii(bytes: Uint8Array, offset: number, length: number): string {
  return String.fromCharCode(...bytes.subarray(offset, offset + length));
}

function concat(parts: Uint8Array[]): Uint8Array {
  const total = parts.reduce((n, p) => n + p.length, 0);
  const out = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}
