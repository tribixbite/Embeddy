import { describe, expect, test } from "bun:test";
import { BYTES_PER_MB, formatFileSize, formatSavings } from "./format-size";

describe("formatFileSize", () => {
  test("binary units, one decimal above bytes", () => {
    expect(formatFileSize(0)).toBe("0 B");
    expect(formatFileSize(512)).toBe("512 B");
    expect(formatFileSize(1536)).toBe("1.5 KB");
    expect(formatFileSize(BYTES_PER_MB)).toBe("1.0 MB");
    expect(formatFileSize(50 * BYTES_PER_MB)).toBe("50.0 MB");
    expect(formatFileSize(3 * 1024 ** 3)).toBe("3.0 GB");
  });

  test("TB and beyond never index past the unit table", () => {
    expect(formatFileSize(2 * 1024 ** 4)).toBe("2.0 TB");
    expect(formatFileSize(5 * 1024 ** 5)).toBe("5120.0 TB");
  });

  test("sub-byte, negative and non-finite input", () => {
    expect(formatFileSize(0.5)).toBe("1 B");
    expect(formatFileSize(-1536)).toBe("-1.5 KB");
    expect(formatFileSize(NaN)).toBe("—");
  });
});

describe("formatSavings", () => {
  test("signs and zero original", () => {
    expect(formatSavings(100, 58)).toBe("-42.0%");
    expect(formatSavings(100, 105)).toBe("+5.0%");
    expect(formatSavings(0, 10)).toBe("0%");
  });
});
