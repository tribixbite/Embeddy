<script lang="ts">
  /**
   * Labelled numeric input.
   *
   * `value` is bound as a number so callers keep working with numbers; an empty
   * field reports 0 rather than `undefined`, which previously leaked into the
   * encoder options and relied on `?? 0` defaults further down the pipeline.
   *
   * The input renders a local text mirror, never a reformatted number, so
   * transient states while typing ("0", "0.") are not rewritten under the
   * caret (M19) — previously typing "0.5" over a nonzero value produced 5.
   * Parsed values are clamped to min/max before they reach the caller (M50).
   */
  import { parseNumericInput } from "./number-input";

  let {
    id,
    label,
    value = $bindable(),
    min,
    max,
    step = 1,
    disabled = false,
    placeholder = "0",
    /** Muted text appended to the label, e.g. "(0 = off)" */
    hint,
    /** Longer explanation rendered under the control */
    help,
    /** Called with the parsed value — use when the caller stores a derived unit */
    onchange,
  }: {
    id: string;
    label: string;
    value: number;
    min?: number;
    max?: number;
    step?: number;
    disabled?: boolean;
    placeholder?: string;
    hint?: string;
    help?: string;
    onchange?: (value: number) => void;
  } = $props();

  /** 0 renders as "" so the placeholder shows, matching the old `value || ""` */
  function formatValue(v: number): string {
    return v === 0 ? "" : String(v);
  }

  /** What the input displays — updated from the prop only while unfocused */
  let text = $state(formatValue(value));
  /** Plain (untracked) flag: defer external syncs while the user is typing */
  let focused = false;

  // Sync the display when the bound value changes externally (reset, per-file
  // seeding, derived recompute) — but never mid-typing.
  $effect(() => {
    const formatted = formatValue(value);
    if (!focused) text = formatted;
  });

  function onInput(event: Event) {
    const raw = (event.target as HTMLInputElement).value;
    // Keep the local mirror on the raw text — no reformat, no caret jump.
    text = raw;
    const next = parseNumericInput(raw, min, max);
    value = next;
    onchange?.(next);
  }

  function onFocus() {
    focused = true;
  }

  function onBlur() {
    focused = false;
    // Snap the display to the clamped canonical value once editing ends.
    text = formatValue(value);
  }
</script>

<div>
  <label for={id} class="mb-2 block text-xs font-medium text-white/40 uppercase tracking-wider">
    {label}
    {#if hint}<span class="text-white/25">{hint}</span>{/if}
  </label>
  <input
    {id}
    type="number"
    {min}
    {max}
    {step}
    {disabled}
    {placeholder}
    value={text}
    oninput={onInput}
    onfocus={onFocus}
    onblur={onBlur}
    aria-describedby={help ? `${id}-help` : undefined}
    class="w-full rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-sm text-white/80 placeholder:text-white/25 focus:border-brand-500/50 focus:outline-none"
  />
  {#if help}
    <p id={`${id}-help`} class="mt-1 text-xs text-white/30">{help}</p>
  {/if}
</div>
