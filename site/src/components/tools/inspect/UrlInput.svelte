<script lang="ts">
  /**
   * URL text input with fetch button for the Inspect tool.
   */

  let {
    disabled = false,
    onfetch,
  }: {
    disabled?: boolean;
    onfetch: (url: string) => void;
  } = $props();

  let url = $state("");

  function handleSubmit(e: Event) {
    e.preventDefault();
    const trimmed = url.trim();
    if (!trimmed) return;

    // Prepend https:// if no protocol specified
    const normalized = trimmed.match(/^https?:\/\//) ? trimmed : `https://${trimmed}`;
    onfetch(normalized);
  }
</script>

<form onsubmit={handleSubmit} class="flex gap-2">
  <input
    type="text"
    bind:value={url}
    placeholder="Enter a URL to inspect (e.g., github.com)"
    {disabled}
    class="flex-1 rounded-xl border border-white/10 bg-white/5 px-4 py-3 text-sm text-white/80 placeholder:text-white/30 focus:border-brand-500/50 focus:outline-none disabled:opacity-50"
  />
  <button
    type="submit"
    {disabled}
    class="rounded-xl bg-brand-500 px-6 py-3 text-sm font-semibold text-white transition-all hover:bg-brand-400 hover:shadow-lg hover:shadow-brand-500/25 disabled:opacity-40 disabled:cursor-not-allowed"
  >
    {#if disabled}
      <div class="h-4 w-4 animate-spin rounded-full border-2 border-white/30 border-t-white"></div>
    {:else}
      Fetch
    {/if}
  </button>
</form>
