<script lang="ts">
  /**
   * Discord-flavoured markdown, styled the way Discord's dark theme renders it.
   * renderDiscordMarkdown escapes all input before emitting markup, so the
   * {@html} below can only contain the tags that module generates.
   */
  import { renderDiscordMarkdown } from "../../../lib/inspect/discord-markdown";

  let { text, compact = false }: { text: string; compact?: boolean } = $props();

  let html = $derived(renderDiscordMarkdown(text));
</script>

<div class="dmd" class:dmd-compact={compact}>
  <!-- eslint-disable-next-line svelte/no-at-html-tags — input is escaped by renderDiscordMarkdown -->
  {@html html}
</div>

<style>
  .dmd {
    color: #dbdee1;
    font-size: 0.875rem;
    line-height: 1.375rem;
    overflow-wrap: anywhere;
  }
  .dmd :global(p) {
    margin: 0;
  }
  .dmd :global(h1),
  .dmd :global(h2),
  .dmd :global(h3) {
    color: #f2f3f5;
    font-weight: 700;
    margin: 0.25rem 0 0.125rem;
    line-height: 1.25;
  }
  .dmd :global(h1) {
    font-size: 1.5rem;
  }
  .dmd :global(h2) {
    font-size: 1.25rem;
  }
  .dmd :global(h3) {
    font-size: 1rem;
  }
  .dmd-compact :global(h1) {
    font-size: 1.25rem;
  }
  .dmd :global(.dmd-subtext) {
    color: #949ba4;
    font-size: 0.75rem;
    line-height: 1rem;
  }
  .dmd :global(a) {
    color: #00a8fc;
    text-decoration: none;
  }
  .dmd :global(a:hover) {
    text-decoration: underline;
  }
  .dmd :global(ul),
  .dmd :global(ol) {
    margin: 0.125rem 0;
    padding-left: 1.25rem;
  }
  .dmd :global(ul) {
    list-style: disc;
  }
  .dmd :global(ol) {
    list-style: decimal;
  }
  .dmd :global(blockquote) {
    border-left: 4px solid #4e5058;
    padding-left: 0.75rem;
    margin: 0.125rem 0;
  }
  .dmd :global(code) {
    background: #1e1f22;
    border-radius: 4px;
    padding: 0.1em 0.3em;
    font-size: 0.85em;
    font-family: "JetBrains Mono", ui-monospace, monospace;
  }
  .dmd :global(pre) {
    background: #1e1f22;
    border: 1px solid #1a1b1e;
    border-radius: 4px;
    padding: 0.5rem;
    margin: 0.25rem 0;
    overflow-x: auto;
  }
  .dmd :global(pre code) {
    background: none;
    padding: 0;
  }
  .dmd :global(.dmd-time) {
    background: rgb(255 255 255 / 0.06);
    border-radius: 3px;
    padding: 0 2px;
  }
  .dmd :global(.dmd-spoiler) {
    border-radius: 3px;
    padding: 0 2px;
    cursor: pointer;
    transition: background 0.1s, color 0.1s;
  }
  .dmd :global(.dmd-spoiler:not(:focus)) {
    background: #1e1f22;
    color: transparent;
  }
  .dmd :global(.dmd-spoiler:not(:focus) *) {
    color: transparent;
  }
  .dmd :global(.dmd-spoiler:focus) {
    background: rgb(255 255 255 / 0.1);
    outline: none;
  }
</style>
