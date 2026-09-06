import { describe, expect, test } from "bun:test";
import { classifyInspectFile } from "./file-support";

// M34: the tool used to accept video/* but exifr 7.x has no video parsers —
// every video ended in a raw "Unknown file format" error. Videos must be
// rejected up front with a friendly, actionable message.
describe("classifyInspectFile", () => {
  test("accepts image types", () => {
    for (const type of ["image/jpeg", "image/png", "image/webp", "image/heic", "image/avif", "image/tiff"]) {
      expect(classifyInspectFile(type).ok).toBe(true);
    }
  });

  test("rejects video types with a friendly message, not a raw parser error", () => {
    const result = classifyInspectFile("video/mp4");
    expect(result.ok).toBe(false);
    expect(result.message).toMatch(/video .*not supported/i);
    expect(result.message).not.toMatch(/unknown file format/i);
  });

  test("rejects non-media types", () => {
    const result = classifyInspectFile("application/pdf");
    expect(result.ok).toBe(false);
    expect(result.message).toMatch(/image/i);
  });
});
