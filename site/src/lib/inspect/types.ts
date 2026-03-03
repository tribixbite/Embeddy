/**
 * Types for the Inspect tool — OG/Twitter metadata inspection.
 */

/** A single metadata tag extracted from a page */
export interface MetaTag {
  /** Property/name attribute (e.g., "og:title", "twitter:card") */
  property: string;
  /** Content value */
  content: string;
}

/** Full metadata result from inspecting a URL */
export interface MetadataResult {
  /** The resolved URL that was fetched */
  url: string;
  /** HTTP status code */
  status: number;
  /** Page title from <title> tag */
  title: string;
  /** All extracted meta tags */
  tags: MetaTag[];
  /** Resolved OG image URL (absolute) */
  ogImage?: string;
  /** OG title */
  ogTitle?: string;
  /** OG description */
  ogDescription?: string;
  /** OG site name */
  ogSiteName?: string;
  /** Twitter card type */
  twitterCard?: string;
  /** Theme color */
  themeColor?: string;
  /** Favicon URL */
  favicon?: string;
}

/** EXIF data from a local image file (via exifr) */
export interface ExifData {
  [key: string]: unknown;
}
