import { describe, expect, test } from "bun:test";
import { getDecoder } from "./codecs";

// M60: GIF is a common pick that Squoosh can't decode — the rejection must
// point users at the Convert tool instead of a bare "unsupported" error.
describe("getDecoder — unsupported inputs", () => {
  test("rejects GIF with a hint pointing at /tools/convert", async () => {
    expect(getDecoder("image/gif")).rejects.toThrow(/\/tools\/convert/);
  });

  test("rejects other unknown formats with the supported-format list", async () => {
    expect(getDecoder("image/bmp")).rejects.toThrow(/JPEG, PNG, WebP.*AVIF/);
  });
});
