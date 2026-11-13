<script lang="ts">
  /**
   * Discord-style media mosaic for Media Gallery components and multi-image
   * link previews. One item keeps its natural aspect ratio; several items tile
   * on a 6-column grid (see gallery-layout.ts). Spoilered items are blurred
   * until tapped, and video items show a poster frame with controls but never
   * autoplay — matching Discord's component-embed behaviour.
   */
  import { galleryLayout, type MosaicItem } from "../../../lib/inspect/gallery-layout";
  import { mediaKind } from "../../../lib/inspect/component-embed";

  let { items }: { items: MosaicItem[] } = $props();

  let shown = $derived(items.slice(0, 10));
  let tiles = $derived(galleryLayout(shown.length));
  /** Indices of spoilers the viewer has revealed */
  let revealed = $state<Set<number>>(new Set());
  /** Indices whose media failed to load */
  let broken = $state<Set<number>>(new Set());

  function reveal(i: number) {
    revealed = new Set(revealed).add(i);
  }
  function markBroken(i: number) {
    broken = new Set(broken).add(i);
  }
</script>

<div
  class="grid max-w-[520px] grid-cols-6 gap-1 overflow-hidden rounded-lg"
  style:grid-auto-rows={shown.length === 1 ? "auto" : shown.length <= 4 ? "9rem" : "7rem"}
>
  {#each shown as item, i (i)}
    {@const tile = tiles[i]!}
    {@const isVideo = mediaKind(item.url) === "video"}
    {@const hidden = item.spoiler && !revealed.has(i)}
    <div
      class="relative overflow-hidden bg-[#1e1f22]"
      style:grid-column="span {tile.colSpan} / span {tile.colSpan}"
      style:grid-row="span {tile.rowSpan} / span {tile.rowSpan}"
    >
      {#if broken.has(i)}
        <div class="flex h-full min-h-24 items-center justify-center p-2 text-center text-[11px] text-white/40">
          Couldn't load {isVideo ? "video" : "image"}
        </div>
      {:else if isVideo}
        <!-- svelte-ignore a11y_media_has_caption — preview of third-party media -->
        <video
          src={item.url}
          controls={!hidden}
          preload="metadata"
          playsinline
          class="h-full w-full {shown.length === 1 ? 'max-h-[300px] object-contain' : 'object-cover'}"
          class:blur-2xl={hidden}
          onerror={() => markBroken(i)}
        ></video>
      {:else}
        <img
          src={item.url}
          alt={item.alt ?? ""}
          loading="lazy"
          class="h-full w-full {shown.length === 1 ? 'max-h-[300px] object-contain' : 'object-cover'}"
          class:blur-2xl={hidden}
          onerror={() => markBroken(i)}
        />
      {/if}
      {#if hidden}
        <button
          type="button"
          onclick={() => reveal(i)}
          class="absolute inset-0 flex items-center justify-center bg-black/30"
          aria-label="Reveal spoiler"
        >
          <span class="rounded-full bg-black/70 px-3 py-1 text-xs font-bold tracking-wide text-white">SPOILER</span>
        </button>
      {/if}
      {#if item.alt && !hidden}
        <span class="absolute bottom-1 left-1 rounded bg-black/70 px-1 text-[10px] font-semibold text-white" title={item.alt}>ALT</span>
      {/if}
    </div>
  {/each}
</div>
