/**
 * Tile placement for Discord's media mosaics (Media Gallery components and
 * multi-image link previews), on a 6-column grid.
 *
 * Discord fills rows of 2 or 3 and puts any odd leading item on its own row:
 *   1 → one full tile        6 → 3 + 3
 *   2 → 2                    7 → 1 + 3 + 3
 *   3 → 1 tall + 2 stacked   8 → 2 + 3 + 3
 *   4 → 2 + 2                9 → 3 + 3 + 3
 *   5 → 2 + 3               10 → 1 + 3 + 3 + 3
 */

/** One piece of media in a mosaic */
export interface MosaicItem {
  url: string;
  /** Alt text (Media Gallery `description`, og:image:alt) */
  alt?: string | null;
  spoiler?: boolean;
}

/** Grid placement for one tile */
export interface GalleryTile {
  /** Columns spanned out of 6 */
  colSpan: number;
  /** Rows spanned (2 only for the tall tile of a 3-item mosaic) */
  rowSpan: number;
}

/**
 * Placement for `count` tiles (clamped to 1–10, Discord's gallery maximum).
 */
export function galleryLayout(count: number): GalleryTile[] {
  const n = Math.max(1, Math.min(10, Math.floor(count)));
  const full: GalleryTile = { colSpan: 6, rowSpan: 1 };
  const half: GalleryTile = { colSpan: 3, rowSpan: 1 };
  const third: GalleryTile = { colSpan: 2, rowSpan: 1 };
  const rowsOf = (tile: GalleryTile, k: number) => Array.from({ length: k }, () => ({ ...tile }));

  switch (n) {
    case 1:
      return [full];
    case 2:
      return rowsOf(half, 2);
    case 3:
      return [{ colSpan: 3, rowSpan: 2 }, half, half];
    case 4:
      return rowsOf(half, 4);
    case 5:
      return [...rowsOf(half, 2), ...rowsOf(third, 3)];
    case 6:
      return rowsOf(third, 6);
    case 7:
      return [full, ...rowsOf(third, 6)];
    case 8:
      return [...rowsOf(half, 2), ...rowsOf(third, 6)];
    case 9:
      return rowsOf(third, 9);
    default:
      return [full, ...rowsOf(third, 9)];
  }
}
