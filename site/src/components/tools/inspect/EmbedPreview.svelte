<script lang="ts">
  /**
   * Discord-style embed card rendering for OG metadata preview.
   * Mimics how Discord renders link embeds: the left strip uses the page's
   * theme-color when one is declared (falling back to the brand color), and
   * the footer surfaces the favicon, HTTP status, and Twitter card type.
   */
  import type { MetadataResult } from "../../../lib/inspect/types";

  let { data }: { data: MetadataResult } = $props();

  /** Extract domain for display */
  let domain = $derived(() => {
    try {
      return new URL(data.url).hostname;
    } catch {
      return data.url;
    }
  });

  let statusOk = $derived(data.status >= 200 && data.status < 300);
</script>

<div
  class="overflow-hidden rounded-lg border-l-4 border-brand-400 bg-dark-800"
  style:border-left-color={data.themeColor}
>
  <div class="p-4">
    <!-- Site name -->
    {#if data.ogSiteName}
      <p class="mb-1 text-xs font-medium text-white/40">{data.ogSiteName}</p>
    {/if}

    <!-- Title -->
    <a
      href={data.url}
      target="_blank"
      rel="noopener noreferrer"
      class="mb-1 block text-sm font-semibold text-brand-300 hover:underline"
    >
      {data.ogTitle || data.title || domain()}
    </a>

    <!-- Description -->
    {#if data.ogDescription}
      <p class="mb-3 text-sm leading-relaxed text-white/50 line-clamp-3">
        {data.ogDescription}
      </p>
    {/if}

    <!-- OG Image -->
    {#if data.ogImage}
      <div class="overflow-hidden rounded-lg">
        <img
          src={data.ogImage}
          alt="OG preview"
          class="max-h-64 w-full object-cover"
          loading="lazy"
          onerror={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }}
        />
      </div>
    {/if}
  </div>

  <!-- Footer with favicon, URL, card type, and fetch status -->
  <div class="flex items-center gap-2 border-t border-white/5 px-4 py-2">
    {#if data.favicon}
      <img
        src={data.favicon}
        alt=""
        class="h-4 w-4 shrink-0 rounded-sm"
        loading="lazy"
        onerror={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }}
      />
    {/if}
    <p class="min-w-0 flex-1 truncate text-xs text-white/30 font-mono">{data.url}</p>
    {#if data.twitterCard}
      <span class="shrink-0 rounded bg-white/5 px-1.5 py-0.5 font-mono text-[10px] text-white/40">
        twitter:{data.twitterCard}
      </span>
    {/if}
    <span
      class="shrink-0 rounded px-1.5 py-0.5 font-mono text-[10px]
        {statusOk ? 'bg-emerald-500/10 text-emerald-400/80' : 'bg-red-500/10 text-red-400/80'}"
    >
      HTTP {data.status}
    </span>
  </div>
</div>
