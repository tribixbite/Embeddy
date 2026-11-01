/**
 * Extra clips appended to a video join, as edited in the Convert UI: each
 * keeps its own trim window and a preview URL for scrubbing. The export
 * itself only needs {@link toJoinClip}'s file + trim (see video-export.ts).
 */
import type { JoinClip } from "./video-export";

/** One added clip with its edit state */
export interface JoinClipItem {
  /** Stable key for keyed {#each} blocks */
  id: number;
  file: File;
  /** Object URL for the clip's preview player; revoke via {@link disposeJoinClips} */
  previewUrl: string;
  /** Clip length in ms (0 when the container doesn't record one) */
  durationMs: number;
  trimStartMs: number;
  /** 0 = to the end */
  trimEndMs: number;
}

let nextId = 1;

/** Reads a clip's duration in seconds; injectable so tests don't need media */
export type DurationProbe = (file: File) => Promise<number>;

/** Default probe: Mediabunny's demuxer, which also rejects files with no video track */
const probeWithMediabunny: DurationProbe = async (file) => {
  const mb = await import("mediabunny");
  const input = new mb.Input({ source: new mb.BlobSource(file), formats: mb.ALL_FORMATS });
  try {
    if (!(await input.getPrimaryVideoTrack())) throw new Error(`${file.name} has no video track`);
    return await input.computeDuration();
  } finally {
    input.dispose();
  }
};

/**
 * Build a clip item for `file`, untrimmed.
 * @throws when the file can't be read as a video
 */
export async function createJoinClip(
  file: File,
  probe: DurationProbe = probeWithMediabunny,
  makeUrl: (f: File) => string = (f) => URL.createObjectURL(f),
): Promise<JoinClipItem> {
  const seconds = await probe(file);
  return {
    id: nextId++,
    file,
    previewUrl: makeUrl(file),
    durationMs: Number.isFinite(seconds) && seconds > 0 ? Math.round(seconds * 1000) : 0,
    trimStartMs: 0,
    trimEndMs: 0,
  };
}

/** Release the preview URLs of clips that are being dropped */
export function disposeJoinClips(
  clips: readonly JoinClipItem[],
  revoke: (url: string) => void = (u) => URL.revokeObjectURL(u),
): void {
  for (const clip of clips) revoke(clip.previewUrl);
}

/** Move the item at `index` by `delta` places (clamped); returns a new array */
export function moveItem<T>(list: readonly T[], index: number, delta: number): T[] {
  if (index < 0 || index >= list.length) return [...list];
  const next = [...list];
  const [item] = next.splice(index, 1);
  next.splice(Math.max(0, Math.min(next.length, index + delta)), 0, item!);
  return next;
}

/** The export's view of a clip: file + trim window */
export function toJoinClip(clip: JoinClipItem): JoinClip {
  return { file: clip.file, trimStartMs: clip.trimStartMs, trimEndMs: clip.trimEndMs };
}

/** True when the clip is trimmed at either end */
export function isTrimmed(clip: JoinClipItem): boolean {
  return clip.trimStartMs > 0 || (clip.trimEndMs > 0 && clip.trimEndMs < clip.durationMs);
}
