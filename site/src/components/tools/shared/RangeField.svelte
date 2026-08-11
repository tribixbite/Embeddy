<script lang="ts">
  /**
   * Labelled range slider with a live value readout and optional end hints.
   *
   * Exists so every slider gets a real `<label for>` → `<input id>` association;
   * the inline markup this replaces used floating labels that screen readers
   * could not tie to their control.
   */
  let {
    id,
    label,
    value = $bindable(),
    min,
    max,
    step = 1,
    disabled = false,
    /** Text shown next to the label — defaults to the raw value */
    display,
    /** Hint under the left end of the track */
    minHint,
    /** Hint under the right end of the track */
    maxHint,
    /** Longer explanation rendered under the control */
    help,
  }: {
    id: string;
    label: string;
    value: number;
    min: number;
    max: number;
    step?: number;
    disabled?: boolean;
    display?: string;
    minHint?: string;
    maxHint?: string;
    help?: string;
  } = $props();

  let readout = $derived(display ?? String(value));
  let describedBy = $derived(help ? `${id}-help` : undefined);
</script>

<div>
  <div class="mb-2 flex items-center justify-between">
    <label for={id} class="text-xs font-medium text-white/40 uppercase tracking-wider">
      {label}
    </label>
    <span class="text-sm font-mono text-white/60">{readout}</span>
  </div>
  <input
    {id}
    type="range"
    {min}
    {max}
    {step}
    bind:value
    {disabled}
    aria-describedby={describedBy}
    aria-valuetext={readout}
    class="w-full accent-brand-500"
  />
  {#if minHint || maxHint}
    <div class="mt-1 flex justify-between text-xs text-white/30" aria-hidden="true">
      <span>{minHint ?? ""}</span>
      <span>{maxHint ?? ""}</span>
    </div>
  {/if}
  {#if help}
    <p id={`${id}-help`} class="mt-1 text-xs text-white/30">{help}</p>
  {/if}
</div>
