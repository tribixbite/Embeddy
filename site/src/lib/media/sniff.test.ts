import { describe, expect, test } from "bun:test";
import { sniffMimeType } from "./sniff";

const bytes = (...b: number[]) => new Uint8Array(b);
/** 12-byte ISO-BMFF header with the given major brand */
const bmff = (brand: string) =>
  new Uint8Array([0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, ...[...brand].map((c) => c.charCodeAt(0))]);

describe("sniffMimeType", () => {
  test("image magic bytes", () => {
    expect(sniffMimeType(bytes(0xff, 0xd8, 0xff, 0xe0), "")).toBe("image/jpeg");
    expect(sniffMimeType(bytes(0x89, 0x50, 0x4e, 0x47), "")).toBe("image/png");
    expect(sniffMimeType(bytes(0x47, 0x49, 0x46, 0x38, 0x39, 0x61), "")).toBe("image/gif");
    expect(
      sniffMimeType(bytes(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50), ""),
    ).toBe("image/webp");
  });

  test("content wins over a lying extension/MIME (L6)", () => {
    // An MP4 renamed .gif must not be fed to the GIF decoder
    expect(sniffMimeType(bmff("isom"), "image/gif")).toBe("video/mp4");
    expect(sniffMimeType(bytes(0x47, 0x49, 0x46, 0x38), "video/mp4")).toBe("image/gif");
  });

  test("ISO-BMFF brands", () => {
    expect(sniffMimeType(bmff("avif"), "")).toBe("image/avif");
    expect(sniffMimeType(bmff("avis"), "")).toBe("image/avif");
    expect(sniffMimeType(bmff("qt  "), "")).toBe("video/quicktime");
    expect(sniffMimeType(bmff("mp42"), "")).toBe("video/mp4");
    // HEIF family is not video — keep the reported type
    expect(sniffMimeType(bmff("heic"), "image/heic")).toBe("image/heic");
    expect(sniffMimeType(bmff("mif1"), "image/heif")).toBe("image/heif");
    // A more specific reported video type is kept
    expect(sniffMimeType(bmff("isom"), "video/x-m4v")).toBe("video/x-m4v");
  });

  test("EBML is WebM unless the reported type is a more specific video type", () => {
    expect(sniffMimeType(bytes(0x1a, 0x45, 0xdf, 0xa3), "")).toBe("video/webm");
    expect(sniffMimeType(bytes(0x1a, 0x45, 0xdf, 0xa3), "video/x-matroska")).toBe("video/x-matroska");
  });

  test("unknown bytes fall back", () => {
    expect(sniffMimeType(bytes(1, 2, 3, 4), "image/x-unknown")).toBe("image/x-unknown");
    expect(sniffMimeType(new ArrayBuffer(0), "x")).toBe("x");
  });
});
