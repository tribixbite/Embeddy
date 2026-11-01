<script lang="ts">
  /**
   * Two-thumb trim range over the source timeline. Two native range inputs
   * share one track (each keeps full keyboard/AT support); CSS lets only
   * the thumbs take pointer input so both stay draggable on touch.
   */
  import { MIN_TRIM_MS } from "../../../lib/convert/timeline";

  let {
    totalMs,
    startMs = $bindable(),
    endMs = $bindable(),
    disabled = false,
    /** Called with the time a thumb moved to, so a preview can seek there */
    onseek,
    /** Visible group label (several trims can share a page, e.g. join clips) */
    label = "Trim",
  }: {
    totalMs: number;
    startMs: number;
    /** 0 means "to the end" */
    endMs: number;
    disabled?: boolean;
    onseek?: (ms: number) => void;
    label?: string;
  } = $props();

  /** Unique per instance so aria-labelledby never points at another trim */
  const labelId = $props.id();

  /** Step: 10 ms resolution, coarser for long sources so keys move sensibly */
  let step = $derived(totalMs > 60_000 ? 100 : 10);
  let effectiveEnd = $derived(endMs > 0 ? Math.min(endMs, totalMs) : totalMs);

  function fmt(ms: number): string {
    const s = ms / 1000;
    const m = Math.floor(s / 60);
    return m > 0 ? `${m}:${(s - m * 60).toFixed(2).padStart(5, "0")}` : `${s.toFixed(2)}s`;
  }

  function setStart(e: Event) {
    const v = Number((e.currentTarget as HTMLInputElement).value);
    startMs = Math.min(v, effectiveEnd - MIN_TRIM_MS);
    (e.currentTarget as HTMLInputElement).value = String(startMs);
    onseek?.(startMs);
  }

  function setEnd(e: Event) {
    const v = Number((e.currentTarget as HTMLInputElement).value);
    const clamped = Math.max(v, startMs + MIN_TRIM_MS);
    // Store 0 for "to the end" so a later longer source isn't cut
    endMs = clamped >= totalMs ? 0 : clamped;
    (e.currentTarget as HTMLInputElement).value = String(clamped);
    onseek?.(clamped);
  }

  let startPct = $derived((startMs / totalMs) * 100);
  let endPct = $derived((effectiveEnd / totalMs) * 100);
</script>

<div>
  <div class="mb-2 flex items-center justify-between text-sm">
    <span class="text-white/60" id={labelId}>{label}</span>
    <span class="font-mono text-xs text-white/50">
      {fmt(startMs)} – {fmt(effectiveEnd)}
      <span class="text-white/30">({fmt(effectiveEnd - startMs)})</span>
    </span>
  </div>
  <div class="trim relative h-11" role="group" aria-labelledby={labelId}>
    <div class="absolute inset-x-0 top-1/2 h-1.5 -translate-y-1/2 rounded-full bg-white/10"></div>
    <div
      class="absolute top-1/2 h-1.5 -translate-y-1/2 rounded-full bg-brand-500"
      style="left: {startPct}%; right: {100 - endPct}%"
    ></div>
    <input
      type="range"
      min={0}
      max={totalMs}
      {step}
      value={startMs}
      {disabled}
      oninput={setStart}
      aria-label="{label} start"
      aria-valuetext={fmt(startMs)}
    />
    <input
      type="range"
      min={0}
      max={totalMs}
      {step}
      value={effectiveEnd}
      {disabled}
      oninput={setEnd}
      aria-label="{label} end"
      aria-valuetext={fmt(effectiveEnd)}
    />
  </div>
</div>

<style>
  .trim input[type="range"] {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    margin: 0;
    background: transparent;
    appearance: none;
    -webkit-appearance: none;
    /* The track itself ignores the pointer so the other input's thumb is reachable */
    pointer-events: none;
  }
  .trim input[type="range"]::-webkit-slider-thumb {
    -webkit-appearance: none;
    pointer-events: auto;
    width: 28px;
    height: 28px;
    border-radius: 9999px;
    background: white;
    border: 3px solid var(--color-brand-500);
    cursor: grab;
  }
  .trim input[type="range"]::-moz-range-thumb {
    pointer-events: auto;
    width: 22px;
    height: 22px;
    border-radius: 9999px;
    background: white;
    border: 3px solid var(--color-brand-500);
    cursor: grab;
  }
  .trim input[type="range"]:focus-visible::-webkit-slider-thumb {
    outline: 2px solid white;
    outline-offset: 2px;
  }
  .trim input[type="range"]:disabled::-webkit-slider-thumb {
    opacity: 0.4;
  }
</style>
