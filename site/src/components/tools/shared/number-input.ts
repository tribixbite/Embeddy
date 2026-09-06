/**
 * Pure parse/clamp helpers for NumberField (M19/M50).
 *
 * Kept out of the component so the range enforcement is unit-testable —
 * the DOM `min`/`max` attributes only affect the spinner buttons, not typed
 * input, so out-of-range values previously reached the encoders unchecked.
 */

/** Clamp a value to an optional [min, max] range; missing bounds are open. */
export function clampToRange(value: number, min?: number, max?: number): number {
  let result = value;
  if (min !== undefined && result < min) result = min;
  if (max !== undefined && result > max) result = max;
  return result;
}

/**
 * Parse raw text-field input into a number the pipeline can trust:
 * non-numeric/empty input falls back to 0, then the result is clamped to the
 * field's declared range.
 */
export function parseNumericInput(raw: string, min?: number, max?: number): number {
  const parsed = Number.parseFloat(raw);
  const value = Number.isFinite(parsed) ? parsed : 0;
  return clampToRange(value, min, max);
}
