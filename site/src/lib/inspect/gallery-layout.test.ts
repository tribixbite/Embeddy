import { describe, expect, test } from "bun:test";
import { galleryLayout } from "./gallery-layout";

describe("galleryLayout", () => {
  test("returns one tile per item, clamped to 1–10", () => {
    for (let n = 1; n <= 10; n++) expect(galleryLayout(n)).toHaveLength(n);
    expect(galleryLayout(0)).toHaveLength(1);
    expect(galleryLayout(14)).toHaveLength(10);
  });

  test("every row fills exactly 6 columns", () => {
    for (let n = 1; n <= 10; n++) {
      // A rowSpan-2 tile occupies its columns on two rows
      const cells = galleryLayout(n).reduce((sum, t) => sum + t.colSpan * t.rowSpan, 0);
      expect(cells % 6).toBe(0);
    }
  });

  test("three items: one tall tile beside two stacked", () => {
    expect(galleryLayout(3)).toEqual([
      { colSpan: 3, rowSpan: 2 },
      { colSpan: 3, rowSpan: 1 },
      { colSpan: 3, rowSpan: 1 },
    ]);
  });

  test("ten items: one full row then three rows of three", () => {
    const tiles = galleryLayout(10);
    expect(tiles[0]!.colSpan).toBe(6);
    expect(tiles.slice(1).every((t) => t.colSpan === 2)).toBe(true);
  });
});
