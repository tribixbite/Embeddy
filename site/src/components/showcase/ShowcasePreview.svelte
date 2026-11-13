<script lang="ts">
  /**
   * The showcase's live Discord preview: a mock message containing the
   * page's own component embed, rendered by the same ComponentEmbedPreview
   * the Inspect tool uses (spoilers reveal on tap, the timestamp is live).
   */
  import ComponentEmbedPreview from "../tools/inspect/ComponentEmbedPreview.svelte";
  import type { ContainerComponent } from "../../lib/inspect/component-embed";

  let { root, url }: { root: ContainerComponent; url: string } = $props();
</script>

<div class="rounded-xl bg-[#313338] p-3 text-left sm:p-4">
  <div class="flex gap-3">
    <div
      class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-[#ff3d9a] to-brand-500 text-sm font-bold text-white"
      aria-hidden="true"
    >
      E
    </div>
    <div class="min-w-0 flex-1 space-y-1.5">
      <p class="text-sm">
        <span class="font-medium text-[#f2f3f5]">You</span>
        <span class="ml-1 text-xs text-[#949ba4]">Today</span>
      </p>
      <a href={url} class="block truncate text-sm text-[#00a8fc] hover:underline">{url}</a>
      <ComponentEmbedPreview {root} />
    </div>
  </div>
</div>
