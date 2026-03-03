/**
 * Client-side EXIF metadata removal using piexifjs.
 * Strips all EXIF/GPS data from JPEG images before upload for privacy.
 */

/**
 * Strip EXIF metadata from a JPEG file.
 * Returns a new File without EXIF data.
 * Non-JPEG files are returned as-is (EXIF is only in JPEG).
 */
export async function stripExif(file: File): Promise<File> {
  // Only JPEG files have EXIF data that piexifjs can handle
  if (!file.type.startsWith("image/jpeg") && !file.name.toLowerCase().match(/\.jpe?g$/)) {
    return file;
  }

  try {
    const piexif = await import("piexifjs");
    const dataUrl = await fileToDataUrl(file);
    const stripped = piexif.remove(dataUrl);
    const blob = dataUrlToBlob(stripped);
    return new File([blob], file.name, { type: file.type, lastModified: file.lastModified });
  } catch {
    // If stripping fails, return original file rather than blocking upload
    console.warn("EXIF stripping failed, using original file");
    return file;
  }
}

/** Convert a File to a data URL string */
function fileToDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as string);
    reader.onerror = () => reject(new Error("Failed to read file"));
    reader.readAsDataURL(file);
  });
}

/** Convert a data URL string back to a Blob */
function dataUrlToBlob(dataUrl: string): Blob {
  const [header, base64] = dataUrl.split(",");
  const mime = header?.match(/:(.*?);/)?.[1] ?? "application/octet-stream";
  const binary = atob(base64!);
  const array = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    array[i] = binary.charCodeAt(i);
  }
  return new Blob([array], { type: mime });
}
