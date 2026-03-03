<script lang="ts">
  /**
   * Discord-style embed card rendering for OG metadata preview.
   * Mimics how Discord renders link embeds with colored left border.
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
</script>

<div class="overflow-hidden rounded-lg border-l-4 border-brand-400 bg-dark-800">
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

  <!-- Footer with URL -->
  <div class="border-t border-white/5 px-4 py-2">
    <p class="truncate text-xs text-white/30 font-mono">{data.url}</p>
  </div>
</div>
