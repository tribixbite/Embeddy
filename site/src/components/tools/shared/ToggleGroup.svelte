<script lang="ts" generics="T">
  /**
   * Segmented single-choice control.
   *
   * Rendered as a real radiogroup so assistive tech announces both the group
   * name and which option is selected; the plain `<button>` rows this replaces
   * exposed neither, and arrow-key navigation did not work.
   */
  let {
    label,
    value = $bindable(),
    options,
    disabled = false,
    /** Longer explanation rendered under the group */
    help,
  }: {
    label: string;
    value: T;
    options: Array<{ value: T; label: string }>;
    disabled?: boolean;
    help?: string;
  } = $props();

  /** Move selection with arrow keys, as expected of a radiogroup. */
  function onKeydown(event: KeyboardEvent) {
    const delta =
      event.key === "ArrowRight" || event.key === "ArrowDown" ? 1
      : event.key === "ArrowLeft" || event.key === "ArrowUp" ? -1
      : 0;
    if (delta === 0 || disabled) return;
    event.preventDefault();
    const index = options.findIndex((o) => o.value === value);
    const next = (index + delta + options.length) % options.length;
    value = options[next]!.value;
  }
</script>

<div>
  <span class="mb-2 block text-xs font-medium text-white/40 uppercase tracking-wider">
    {label}
  </span>
  <div
    role="radiogroup"
    aria-label={label}
    class="flex flex-wrap gap-2"
    onkeydown={onKeydown}
  >
    {#each options as option (String(option.value))}
      <button
        type="button"
        role="radio"
        aria-checked={option.value === value}
        tabindex={option.value === value ? 0 : -1}
        {disabled}
        onclick={() => { value = option.value; }}
        class="rounded-lg px-3 py-1.5 text-sm font-medium transition-colors disabled:opacity-40
          {option.value === value
            ? 'bg-brand-500 text-white'
            : 'bg-white/5 text-white/50 hover:bg-white/10'}"
      >
        {option.label}
      </button>
    {/each}
  </div>
  {#if help}
    <p class="mt-1 text-xs text-white/30">{help}</p>
  {/if}
</div>
