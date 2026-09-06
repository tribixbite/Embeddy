/**
 * Input gating for the Inspect tool's local-file mode.
 *
 * exifr 7.x registers parsers only for JPEG, TIFF, HEIC/HEIF, AVIF and PNG,
 * and WebP goes through our own RIFF parser — there is NO video support, so
 * video picks must be rejected up front with a friendly message instead of
 * letting exifr throw a raw "Unknown file format" at the user.
 */

/** Result of gating a picked file before it reaches a metadata parser. */
export interface InspectFileClassification {
  /** Whether the file may be handed to the metadata parsers */
  ok: boolean;
  /** User-facing rejection message when not ok */
  message?: string;
}

/**
 * Classify a picked file's MIME type for the Inspect tool.
 * Images pass through (unparseable subtypes get a parser-level error later);
 * everything else is rejected with an actionable message.
 */
export function classifyInspectFile(mimeType: string): InspectFileClassification {
  if (mimeType.startsWith("image/")) {
    return { ok: true };
  }
  if (mimeType.startsWith("video/")) {
    return {
      ok: false,
      message:
        "Video metadata is not supported — inspection works with image files (JPEG, PNG, WebP, HEIC, AVIF, TIFF)",
    };
  }
  return {
    ok: false,
    message: "Please select an image file to inspect",
  };
}
