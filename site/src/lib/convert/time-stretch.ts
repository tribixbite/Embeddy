/**
 * Streaming pitch-preserving time-stretch (WSOLA).
 *
 * Changing playback speed by re-timing samples would also shift pitch
 * (chipmunk / slow-motion voice). WSOLA — waveform-similarity overlap-add —
 * instead cuts the input into overlapping Hann-windowed frames read at an
 * analysis hop of `speed × synthesisHop` and lays them down at a fixed
 * synthesis hop. Before each frame is placed, its read position is nudged
 * within ±`tolerance` samples to the offset whose waveform best continues
 * the previous frame, which avoids the phasing/warble of plain OLA. This is
 * the same family of algorithm as FFmpeg's `atempo`, which the Android app
 * uses, so both platforms sound alike.
 *
 * Streaming: input arrives in arbitrary chunks ({@link WsolaStretcher.push})
 * and output is returned as soon as it is final; {@link WsolaStretcher.flush}
 * drains the tail. Output length is exactly `round(inputFrames / speed)`.
 *
 * Pure (no Web Audio / WebCodecs) so it is unit-tested under bun.
 */

/** Minimum/maximum speed this stretcher supports (matches the speed slider) */
export const MIN_STRETCH_SPEED = 0.25;
export const MAX_STRETCH_SPEED = 4;

/** Window length in seconds; ~40 ms suits both speech and music */
const WINDOW_S = 0.04;
/** Search radius in seconds for the best-matching read offset */
const TOLERANCE_S = 0.012;
/** Correlation is evaluated on every Nth sample during the coarse search */
const COARSE_STEP = 4;

/** Per-channel planar audio: one Float32Array per channel, equal lengths */
export type PlanarAudio = Float32Array[];
/** Stretcher output: always freshly allocated (never a shared/foreign buffer) */
export type PlanarOutput = Float32Array<ArrayBuffer>[];

export class WsolaStretcher {
  readonly speed: number;
  readonly channels: number;
  /** Frame (window) length in samples, even */
  readonly frameLen: number;
  /** Synthesis hop = frameLen / 2 (Hann windows at 50% overlap sum to 1) */
  readonly hop: number;
  readonly tolerance: number;
  private readonly window: Float32Array;

  /** Buffered input per channel; inBuf[c][0] is absolute input frame `inBase` */
  private inBuf: Float32Array[];
  /** Mono mix of inBuf (same indexing), used only by the similarity search */
  private monoBuf: Float32Array;
  private inLen = 0;
  private inBase = 0;
  /** Total input frames pushed */
  private inTotal = 0;

  /** Overlap-add accumulator per channel (length frameLen) */
  private acc: Float32Array[];
  /** Frames placed so far (k-th frame reads near k × hop × speed) */
  private framesPlaced = 0;
  /** Absolute input position the previous frame was actually read from */
  private prevRead = 0;
  /** Output frames returned so far */
  private outTotal = 0;
  private flushed = false;

  /**
   * @param speed - Playback speed (2 = twice as fast / half as long)
   * @param sampleRate - Hz; sets the window and search sizes
   * @param channels - Channel count of every pushed chunk
   */
  constructor(speed: number, sampleRate: number, channels: number) {
    if (!(speed >= MIN_STRETCH_SPEED && speed <= MAX_STRETCH_SPEED)) {
      throw new RangeError(`speed must be within ${MIN_STRETCH_SPEED}–${MAX_STRETCH_SPEED}, got ${speed}`);
    }
    if (!Number.isInteger(channels) || channels < 1) throw new RangeError("channels must be a positive integer");
    if (!(sampleRate > 0)) throw new RangeError("sampleRate must be positive");
    this.speed = speed;
    this.channels = channels;
    this.frameLen = Math.max(64, Math.round((sampleRate * WINDOW_S) / 2) * 2);
    this.hop = this.frameLen / 2;
    this.tolerance = Math.max(8, Math.round(sampleRate * TOLERANCE_S));
    // Periodic Hann: overlapping copies at hop = N/2 sum to exactly 1
    this.window = new Float32Array(this.frameLen);
    for (let i = 0; i < this.frameLen; i++) {
      this.window[i] = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / this.frameLen);
    }
    const initialCap = this.frameLen * 4;
    this.inBuf = Array.from({ length: channels }, () => new Float32Array(initialCap));
    this.monoBuf = new Float32Array(initialCap);
    this.acc = Array.from({ length: channels }, () => new Float32Array(this.frameLen));
  }

  /** Output frames that {@link flush} will have produced in total for the input so far */
  get expectedOutputFrames(): number {
    return Math.round(this.inTotal / this.speed);
  }

  /**
   * Append input and return every output frame that is now final.
   * @param chunk - One Float32Array per channel, all the same length
   */
  push(chunk: PlanarAudio): PlanarOutput {
    if (this.flushed) throw new Error("push() after flush()");
    if (chunk.length !== this.channels) throw new RangeError(`expected ${this.channels} channels, got ${chunk.length}`);
    const n = chunk[0]!.length;
    this.ensureCapacity(this.inLen + n);
    for (let c = 0; c < this.channels; c++) this.inBuf[c]!.set(chunk[c]!, this.inLen);
    const mono = this.monoBuf;
    mono.fill(0, this.inLen, this.inLen + n);
    for (let c = 0; c < this.channels; c++) {
      const src = chunk[c]!;
      for (let i = 0; i < n; i++) mono[this.inLen + i] = mono[this.inLen + i]! + src[i]!;
    }
    this.inLen += n;
    this.inTotal += n;
    return this.drain(false);
  }

  /** Process the remaining input (zero-padded) and return the final output */
  flush(): PlanarOutput {
    if (this.flushed) return this.emptyChunk();
    const out = this.drain(true);
    this.flushed = true;
    return out;
  }

  /** Place frames while enough input is buffered; `final` pads with silence */
  private drain(final: boolean): PlanarOutput {
    const pieces: PlanarOutput[] = [];
    const target = this.expectedOutputFrames;
    for (;;) {
      // Output already returned + the hop this frame would finalize
      if (final && this.outTotal >= target) break;
      const nominal = Math.round(this.framesPlaced * this.hop * this.speed);
      // Need the search span and the frame itself in the buffer
      const needEnd = nominal + this.tolerance + this.frameLen;
      if (!final && needEnd > this.inBase + this.inLen) break;
      const read = this.framesPlaced === 0 ? 0 : this.bestOffset(nominal);
      this.overlapAdd(read);
      this.prevRead = read;
      this.framesPlaced++;
      pieces.push(this.emitHop(final ? target - this.outTotal : this.hop));
      this.discardBefore(Math.min(this.prevRead + this.hop, Math.round(this.framesPlaced * this.hop * this.speed)) - this.tolerance);
    }
    return concat(pieces, this.channels);
  }

  /**
   * Read position in [nominal − tol, nominal + tol] whose waveform best
   * continues the previous frame (normalised cross-correlation on a mono
   * mix; coarse pass on every {@link COARSE_STEP}th lag, then a fine pass).
   */
  private bestOffset(nominal: number): number {
    const natural = this.prevRead + this.hop; // where the old frame "wants" to continue
    const overlap = this.frameLen - this.hop;
    const lo = Math.max(this.inBase, nominal - this.tolerance);
    const hi = nominal + this.tolerance;
    const mono = this.monoBuf;
    const len = this.inLen;
    const nat = natural - this.inBase;
    // Both spans start at or after inBase (natural ≥ the discard point and
    // lo ≥ inBase); positions past the buffered end read as silence
    const score = (pos: number, step: number): number => {
      const cand = pos - this.inBase;
      let dot = 0;
      let energy = 1e-9;
      for (let i = 0; i < overlap; i += step) {
        const ia = nat + i;
        const ib = cand + i;
        if (ib >= len) break;
        const b = mono[ib]!;
        dot += (ia < len ? mono[ia]! : 0) * b;
        energy += b * b;
      }
      return dot / Math.sqrt(energy);
    };
    let best = Math.min(Math.max(nominal, lo), hi);
    let bestScore = -Infinity;
    for (let pos = lo; pos <= hi; pos += COARSE_STEP) {
      const s = score(pos, COARSE_STEP);
      if (s > bestScore) {
        bestScore = s;
        best = pos;
      }
    }
    const coarse = best;
    bestScore = -Infinity;
    for (let pos = Math.max(lo, coarse - COARSE_STEP); pos <= Math.min(hi, coarse + COARSE_STEP); pos++) {
      const s = score(pos, 2);
      if (s > bestScore) {
        bestScore = s;
        best = pos;
      }
    }
    return best;
  }

  /** Window the frame read at `read` and add it into the accumulator */
  private overlapAdd(read: number): void {
    const off = read - this.inBase;
    for (let c = 0; c < this.channels; c++) {
      const src = this.inBuf[c]!;
      const acc = this.acc[c]!;
      for (let i = 0; i < this.frameLen; i++) {
        const j = off + i;
        const v = j >= 0 && j < this.inLen ? src[j]! : 0;
        acc[i] = acc[i]! + v * this.window[i]!;
      }
    }
  }

  /**
   * Return the first `count` (≤ hop) accumulator samples, which no later
   * frame overlaps, and shift the accumulator left by one hop.
   */
  private emitHop(count: number): PlanarOutput {
    const n = Math.max(0, Math.min(this.hop, count));
    const out: PlanarOutput = [];
    for (let c = 0; c < this.channels; c++) {
      const acc = this.acc[c]!;
      out.push(acc.slice(0, n));
      acc.copyWithin(0, this.hop);
      acc.fill(0, this.frameLen - this.hop);
    }
    this.outTotal += n;
    return out;
  }

  /** Drop buffered input before absolute position `abs` (no longer reachable) */
  private discardBefore(abs: number): void {
    const drop = Math.min(this.inLen, abs - this.inBase);
    if (drop <= 0) return;
    for (let c = 0; c < this.channels; c++) this.inBuf[c]!.copyWithin(0, drop, this.inLen);
    this.monoBuf.copyWithin(0, drop, this.inLen);
    this.inLen -= drop;
    this.inBase += drop;
  }

  private ensureCapacity(n: number): void {
    if (n <= this.inBuf[0]!.length) return;
    let cap = this.inBuf[0]!.length;
    while (cap < n) cap *= 2;
    this.inBuf = this.inBuf.map((old) => {
      const grown = new Float32Array(cap);
      grown.set(old.subarray(0, this.inLen));
      return grown;
    });
    const mono = new Float32Array(cap);
    mono.set(this.monoBuf.subarray(0, this.inLen));
    this.monoBuf = mono;
  }

  private emptyChunk(): PlanarOutput {
    return Array.from({ length: this.channels }, () => new Float32Array(0));
  }
}

/** Concatenate per-channel pieces into one planar chunk */
function concat(pieces: PlanarOutput[], channels: number): PlanarOutput {
  const total = pieces.reduce((sum, p) => sum + p[0]!.length, 0);
  const out: PlanarOutput = Array.from({ length: channels }, () => new Float32Array(total));
  let at = 0;
  for (const p of pieces) {
    for (let c = 0; c < channels; c++) out[c]!.set(p[c]!, at);
    at += p[0]!.length;
  }
  return out;
}

/**
 * Stretch a complete signal in one call (for audio that is already fully
 * rendered, e.g. a join clip's AudioBuffer). Returns `round(len / speed)`
 * frames per channel.
 */
export function stretchPlanar(planes: PlanarAudio, speed: number, sampleRate: number): PlanarOutput {
  const stretcher = new WsolaStretcher(speed, sampleRate, planes.length);
  const head = stretcher.push(planes);
  const tail = stretcher.flush();
  return concat([head, tail], planes.length);
}
