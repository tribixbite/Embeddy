<script lang="ts">
  /**
   * Labelled numeric input.
   *
   * `value` is bound as a number so callers keep working with numbers; an empty
   * field reports 0 rather than `undefined`, which previously leaked into the
   * encoder options and relied on `?? 0` defaults further down the pipeline.
   */
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

  function onInput(event: Event) {
    const raw = (event.target as HTMLInputElement).value;
    const parsed = Number.parseFloat(raw);
    const next = Number.isFinite(parsed) ? parsed : 0;
    value = next;
    onchange?.(next);
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
    value={value || ""}
    oninput={onInput}
    aria-describedby={help ? `${id}-help` : undefined}
    class="w-full rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-sm text-white/80 placeholder:text-white/25 focus:border-brand-500/50 focus:outline-none"
  />
  {#if help}
    <p id={`${id}-help`} class="mt-1 text-xs text-white/30">{help}</p>
  {/if}
</div>
