<script lang="ts">
  /**
   * Expandable key/value table showing all extracted meta tags.
   * Groups tags by prefix (og:, twitter:, etc.)
   */
  import type { MetaTag } from "../../../lib/inspect/types";
  import CopyButton from "../shared/CopyButton.svelte";

  let { tags }: { tags: MetaTag[] } = $props();

  let expanded = $state(true);

  /** Group tags by their prefix (og, twitter, etc.) */
  let grouped = $derived(() => {
    const groups = new Map<string, MetaTag[]>();
    for (const tag of tags) {
      const prefix = tag.property.includes(":") ? tag.property.split(":")[0]! : "other";
      if (!groups.has(prefix)) groups.set(prefix, []);
      groups.get(prefix)!.push(tag);
    }
    return groups;
  });
</script>

<div class="rounded-2xl border border-white/8 bg-white/[0.03]">
  <button
    onclick={() => { expanded = !expanded; }}
    class="flex w-full items-center justify-between px-5 py-4 text-left"
  >
    <span class="text-sm font-medium text-white/70">
      All metadata tags
      <span class="ml-1 text-white/30">({tags.length})</span>
    </span>
    <svg
      xmlns="http://www.w3.org/2000/svg"
      fill="none"
      viewBox="0 0 24 24"
      stroke-width="2"
      stroke="currentColor"
      class="h-4 w-4 text-white/40 transition-transform {expanded ? 'rotate-180' : ''}"
    >
      <path stroke-linecap="round" stroke-linejoin="round" d="M19.5 8.25l-7.5 7.5-7.5-7.5" />
    </svg>
  </button>

  {#if expanded}
    <div class="border-t border-white/5 px-5 pb-4">
      {#each [...grouped()] as [prefix, groupTags]}
        <div class="mt-4 first:mt-2">
          <h4 class="mb-2 text-xs font-semibold text-white/30 uppercase tracking-wider">{prefix}</h4>
          <div class="space-y-1">
            {#each groupTags as tag}
              <div class="flex items-start gap-2 rounded-lg px-2 py-1.5 text-sm hover:bg-white/[0.03]">
                <span class="min-w-[140px] shrink-0 font-mono text-xs text-brand-400/70">{tag.property}</span>
                <span class="flex-1 break-all text-xs text-white/60">{tag.content}</span>
                <CopyButton text={tag.content} label="" />
              </div>
            {/each}
          </div>
        </div>
      {/each}
    </div>
  {/if}
</div>
