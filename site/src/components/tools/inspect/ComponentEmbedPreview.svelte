<script lang="ts">
  /**
   * Renders a component-embed payload (a Components V2 Container) the way
   * Discord draws it in a link preview: accent strip, markdown Text Displays,
   * Sections with a Thumbnail or link-Button accessory, Media Gallery mosaics,
   * Separators and Action Rows of link Buttons. Component embeds are
   * display-only, so buttons are plain links.
   *
   * Input may come from a payload with validation errors (the builder previews
   * as you type), so every field is read defensively.
   */
  import type {
    ButtonComponent,
    ContainerComponent,
    ContainerChild,
    ThumbnailComponent,
  } from "../../../lib/inspect/component-embed";
  import { accentToHex, ComponentType } from "../../../lib/inspect/component-embed";
  import DiscordMarkdown from "./DiscordMarkdown.svelte";
  import MediaMosaic from "./MediaMosaic.svelte";

  let { root }: { root: ContainerComponent } = $props();

  let accent = $derived(
    typeof root.accent_color === "number" && Number.isInteger(root.accent_color) ? accentToHex(root.accent_color) : null,
  );
  let children = $derived(Array.isArray(root.components) ? root.components : []);
  let containerRevealed = $state(false);
  let thumbRevealed = $state<Set<unknown>>(new Set());

  /** Discord CDN URL for a custom emoji */
  function emojiUrl(emoji: NonNullable<ButtonComponent["emoji"]>): string | null {
    if (!emoji.id || !/^\d+$/.test(emoji.id)) return null;
    return `https://cdn.discordapp.com/emojis/${emoji.id}.${emoji.animated ? "gif" : "webp"}?size=32`;
  }

  /** Only http(s) URLs become live links in the preview */
  function safeHref(url: unknown): string | undefined {
    return typeof url === "string" && /^https?:\/\//i.test(url) ? url : undefined;
  }

  function revealThumb(node: ThumbnailComponent) {
    thumbRevealed = new Set(thumbRevealed).add(node);
  }
</script>

{#snippet linkButton(button: ButtonComponent)}
  {@const href = safeHref(button.url)}
  {@const custom = button.emoji ? emojiUrl(button.emoji) : null}
  <a
    href={button.disabled ? undefined : href}
    target="_blank"
    rel="noopener noreferrer nofollow"
    aria-disabled={button.disabled ? "true" : undefined}
    class="inline-flex h-8 min-w-15 items-center justify-center gap-1.5 rounded-lg bg-[#4e5058] px-4 text-sm font-medium text-white transition-colors hover:bg-[#6d6f78]"
    class:pointer-events-none={button.disabled}
    class:opacity-50={button.disabled}
  >
    {#if custom}
      <img src={custom} alt={button.emoji?.name ?? ""} class="h-5 w-5" />
    {:else if button.emoji?.name}
      <span class="text-base leading-none">{button.emoji.name}</span>
    {/if}
    {#if button.label}<span class="truncate">{button.label}</span>{/if}
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="h-4 w-4 shrink-0 opacity-80" aria-hidden="true">
      <path stroke-linecap="round" stroke-linejoin="round" d="M13.5 6H5.25A2.25 2.25 0 003 8.25v10.5A2.25 2.25 0 005.25 21h10.5A2.25 2.25 0 0018 18.75V10.5m-10.5 6L21 3m0 0h-5.25M21 3v5.25" />
    </svg>
  </a>
{/snippet}

{#snippet thumbnail(node: ThumbnailComponent)}
  {@const url = safeHref(node.media?.url)}
  {@const hidden = node.spoiler && !thumbRevealed.has(node)}
  <div class="relative h-[85px] w-[85px] shrink-0 overflow-hidden rounded-lg bg-[#1e1f22]">
    {#if url}
      <img src={url} alt={node.description ?? ""} class="h-full w-full object-cover" class:blur-xl={hidden} loading="lazy" />
    {/if}
    {#if hidden}
      <button type="button" onclick={() => revealThumb(node)} class="absolute inset-0 flex items-center justify-center bg-black/30" aria-label="Reveal spoiler">
        <span class="rounded-full bg-black/70 px-2 py-0.5 text-[10px] font-bold text-white">SPOILER</span>
      </button>
    {/if}
  </div>
{/snippet}

{#snippet child(node: ContainerChild)}
  {#if node?.type === ComponentType.TextDisplay}
    <DiscordMarkdown text={typeof node.content === "string" ? node.content : ""} />
  {:else if node?.type === ComponentType.Section}
    <div class="flex items-start gap-3">
      <div class="min-w-0 flex-1 space-y-2">
        {#each Array.isArray(node.components) ? node.components : [] as text, i (i)}
          <DiscordMarkdown text={typeof text?.content === "string" ? text.content : ""} />
        {/each}
      </div>
      {#if node.accessory?.type === ComponentType.Thumbnail}
        {@render thumbnail(node.accessory)}
      {:else if node.accessory?.type === ComponentType.Button}
        <div class="shrink-0 self-center">{@render linkButton(node.accessory)}</div>
      {/if}
    </div>
  {:else if node?.type === ComponentType.MediaGallery}
    <MediaMosaic
      items={(Array.isArray(node.items) ? node.items : [])
        .filter((item) => typeof item?.media?.url === "string")
        .map((item) => ({ url: item.media.url, alt: item.description, spoiler: item.spoiler === true }))}
    />
  {:else if node?.type === ComponentType.Separator}
    <div class={node.spacing === 2 ? "py-2" : "py-0.5"}>
      {#if node.divider !== false}<hr class="border-[#3f4147]" />{/if}
    </div>
  {:else if node?.type === ComponentType.ActionRow}
    <div class="flex flex-wrap gap-2">
      {#each Array.isArray(node.components) ? node.components : [] as button, i (i)}
        {#if button?.type === ComponentType.Button}{@render linkButton(button)}{/if}
      {/each}
    </div>
  {/if}
{/snippet}

<article class="relative max-w-[520px] overflow-hidden rounded-lg border border-[#3f4147] bg-[#2b2d31]">
  {#if accent}
    <span class="absolute inset-y-0 left-0 w-1" style:background-color={accent} aria-hidden="true"></span>
  {/if}
  <div class="space-y-2 p-4" class:pl-5={accent} class:blur-xl={root.spoiler === true && !containerRevealed}>
    {#each children as node, i (i)}
      {@render child(node)}
    {/each}
  </div>
  {#if root.spoiler === true && !containerRevealed}
    <button
      type="button"
      onclick={() => (containerRevealed = true)}
      class="absolute inset-0 flex items-center justify-center bg-black/20"
      aria-label="Reveal spoiler"
    >
      <span class="rounded-full bg-black/70 px-3 py-1 text-xs font-bold tracking-wide text-white">SPOILER</span>
    </button>
  {/if}
</article>
