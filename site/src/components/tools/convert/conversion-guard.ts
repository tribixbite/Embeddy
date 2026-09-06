/**
 * Generation guard for in-flight async work (M17/M18).
 *
 * The Convert tool's decode/encode pipelines span many awaits; reset() or a
 * new file must be able to orphan them so a zombie conversion can neither
 * paint the error screen (the "Encoder aborted" rejection after "Change
 * file") nor clobber a newly loaded file's state with a stale result.
 *
 * Usage: capture `token()` (or `invalidate()` when starting work that
 * supersedes everything prior), then after every await — and in catch
 * blocks — bail out if `isCurrent(token)` is false.
 */
export interface GenerationGuard {
  /** Invalidate all in-flight work and return the new generation's token. */
  invalidate(): number;
  /** Capture the current generation token. */
  token(): number;
  /** True while the captured token still belongs to the live generation. */
  isCurrent(token: number): boolean;
}

/** Create an independent generation guard. */
export function createGenerationGuard(): GenerationGuard {
  let current = 0;
  return {
    invalidate(): number {
      current += 1;
      return current;
    },
    token(): number {
      return current;
    },
    isCurrent(token: number): boolean {
      return token === current;
    },
  };
}
