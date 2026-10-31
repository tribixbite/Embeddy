import { describe, expect, test } from "bun:test";
import {
  MetadataStripError,
  canStripExif,
  sniffStrippableFormat,
  stripMetadataBytes,
} from "./strip-exif";

const enc = new TextEncoder();
const bytes = (...parts: (number[] | Uint8Array | string)[]): Uint8Array => {
  const arrays = parts.map((p) =>
    typeof p === "string" ? enc.encode(p) : p instanceof Uint8Array ? p : Uint8Array.from(p),
  );
  const out = new Uint8Array(arrays.reduce((n, a) => n + a.length, 0));
  let o = 0;
  for (const a of arrays) {
    out.set(a, o);
    o += a.length;
  }
  return out;
};
const includes = (hay: Uint8Array, needle: string): boolean =>
  Buffer.from(hay).includes(Buffer.from(needle));

/** JPEG segment: FF marker, big-endian length (incl. itself), payload. */
const seg = (marker: number, payload: string | number[]): Uint8Array => {
  const body = typeof payload === "string" ? enc.encode(payload) : Uint8Array.from(payload);
  const len = body.length + 2;
  return bytes([0xff, marker, len >> 8, len & 0xff], body);
};

describe("JPEG", () => {
  const scan = bytes(seg(0xda, [0, 1, 2]), [0x11, 0x22, 0xff, 0x00, 0x33], [0xff, 0xd9]);
  const jpeg = bytes(
    [0xff, 0xd8],
    seg(0xe0, "JFIF\0"),
    seg(0xe1, "Exif\0\0GPSLAT"),
    seg(0xe1, "http://ns.adobe.com/xap/1.0/\0<xmp gps/>"),
    seg(0xe2, "ICC_PROFILE\0"),
    seg(0xed, "Photoshop 3.0\0IPTC"),
    seg(0xee, "Adobe"),
    seg(0xfe, "secret comment"),
    seg(0xdb, [0, 1, 2, 3]),
    scan,
  );

  test("drops EXIF, XMP, IPTC and comments; keeps JFIF, ICC, Adobe, tables and scan", () => {
    const out = stripMetadataBytes(jpeg);
    for (const gone of ["GPSLAT", "<xmp gps/>", "IPTC", "secret comment"]) {
      expect(includes(out, gone)).toBe(false);
    }
    for (const kept of ["JFIF", "ICC_PROFILE", "Adobe"]) {
      expect(includes(out, kept)).toBe(true);
    }
    // Entropy-coded data and EOI copied byte-for-byte at the end
    expect(Array.from(out.subarray(out.length - scan.length))).toEqual(Array.from(scan));
    expect(Array.from(out.subarray(0, 2))).toEqual([0xff, 0xd8]);
  });

  test("tolerates 0xFF fill bytes before a marker", () => {
    const filled = bytes([0xff, 0xd8, 0xff], seg(0xe1, "Exif\0\0GPS"), scan);
    expect(includes(stripMetadataBytes(filled), "GPS")).toBe(false);
  });

  test("fails closed on a segment length past EOF", () => {
    const broken = bytes([0xff, 0xd8, 0xff, 0xe1, 0x7f, 0xff], "Exif");
    expect(() => stripMetadataBytes(broken)).toThrow(MetadataStripError);
  });
});

/** PNG chunk with a dummy CRC (the stripper never recomputes CRCs). */
const chunk = (type: string, data: string): Uint8Array => {
  const d = enc.encode(data);
  const len = [d.length >>> 24, (d.length >>> 16) & 255, (d.length >>> 8) & 255, d.length & 255];
  return bytes(len, type, d, [1, 2, 3, 4]);
};
const PNG_SIG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

describe("PNG", () => {
  test("drops eXIf/tEXt/zTXt/iTXt/tIME and keeps image chunks intact", () => {
    const ihdr = chunk("IHDR", "0123456789abc");
    const idat = chunk("IDAT", "pixels");
    const iend = chunk("IEND", "");
    const png = bytes(
      PNG_SIG,
      ihdr,
      chunk("eXIf", "GPSDATA"),
      chunk("tEXt", "Author\0me"),
      chunk("iTXt", "XML:com.adobe.xmp\0..."),
      chunk("zTXt", "Comment\0z"),
      chunk("tIME", "1234567"),
      idat,
      iend,
    );
    expect(Array.from(stripMetadataBytes(png))).toEqual(Array.from(bytes(PNG_SIG, ihdr, idat, iend)));
  });

  test("fails closed without IEND", () => {
    expect(() => stripMetadataBytes(bytes(PNG_SIG, chunk("IHDR", "x")))).toThrow(MetadataStripError);
  });
});

/** RIFF chunk, little-endian size, padded to even length. */
const riffChunk = (fourcc: string, data: number[] | string): Uint8Array => {
  const d = typeof data === "string" ? enc.encode(data) : Uint8Array.from(data);
  const n = d.length;
  return bytes(fourcc, [n & 255, (n >> 8) & 255, (n >> 16) & 255, n >>> 24], d, n & 1 ? [0] : []);
};
const webp = (...chunks: Uint8Array[]): Uint8Array => {
  const body = bytes(...chunks);
  const n = body.length + 4;
  return bytes("RIFF", [n & 255, (n >> 8) & 255, (n >> 16) & 255, n >>> 24], "WEBP", body);
};

describe("WebP", () => {
  test("drops EXIF/XMP, clears their VP8X flags and fixes the RIFF size", () => {
    // flags: ICC(0x20) | alpha(0x10) | EXIF(0x08) | XMP(0x04)
    const vp8x = riffChunk("VP8X", [0x3c, 0, 0, 0, 9, 0, 0, 9, 0, 0]);
    const image = riffChunk("VP8L", "abcde"); // odd length -> padded
    const out = stripMetadataBytes(
      webp(vp8x, riffChunk("ICCP", "icc"), image, riffChunk("EXIF", "GPS!"), riffChunk("XMP ", "<x/>")),
    );
    expect(includes(out, "GPS!")).toBe(false);
    expect(includes(out, "<x/>")).toBe(false);
    expect(includes(out, "icc")).toBe(true);
    const view = new DataView(out.buffer);
    expect(view.getUint32(4, true)).toBe(out.length - 8);
    expect(out[20]).toBe(0x30); // only ICC + alpha remain
  });

  test("does not mutate the input buffer", () => {
    const input = webp(riffChunk("VP8X", [0x08, 0, 0, 0, 0, 0, 0, 0, 0, 0]), riffChunk("EXIF", "x"));
    const copy = input.slice();
    stripMetadataBytes(input);
    expect(Array.from(input)).toEqual(Array.from(copy));
  });
});

describe("format detection", () => {
  test("sniffs by magic bytes and rejects everything else", () => {
    expect(sniffStrippableFormat(Uint8Array.from([0xff, 0xd8, 0xff, 0xe0]))).toBe("jpeg");
    expect(sniffStrippableFormat(Uint8Array.from(PNG_SIG))).toBe("png");
    expect(sniffStrippableFormat(webp())).toBe("webp");
    expect(() => stripMetadataBytes(enc.encode("GIF89a......"))).toThrow(MetadataStripError);
  });

  test("canStripExif offers JPEG, PNG and WebP only", () => {
    const f = (name: string, type: string) => new File([], name, { type });
    expect(canStripExif(f("a.jpg", "image/jpeg"))).toBe(true);
    expect(canStripExif(f("a.png", "image/png"))).toBe(true);
    expect(canStripExif(f("a.webp", ""))).toBe(true);
    expect(canStripExif(f("a.heic", "image/heic"))).toBe(false);
    expect(canStripExif(f("a.gif", "image/gif"))).toBe(false);
  });
});
