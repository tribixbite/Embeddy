<script lang="ts">
  /**
   * Centered busy indicator with a text label, shared by every tool (D4).
   * Announced politely to assistive tech via role="status".
   */
  let { label, padding = "py-12" }: { label: string; padding?: string } = $props();
</script>

<div class="flex items-center justify-center {padding}" role="status">
  <div
    class="h-8 w-8 animate-spin rounded-full border-2 border-white/20 border-t-brand-400"
    aria-hidden="true"
  ></div>
  <span class="ml-3 text-sm text-white/50">{label}</span>
</div>
