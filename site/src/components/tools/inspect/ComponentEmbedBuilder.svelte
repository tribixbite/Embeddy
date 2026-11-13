<script lang="ts">
  /**
   * Component-embed builder: edit a `discord:component-embed` payload, see
   * Discord's rules applied as you type, preview the rendered Container, and
   * copy the markup to paste into a page's <head> (inline script, or a linked
   * JSON file). Everything runs locally — nothing is sent anywhere.
   */
  import CopyButton from "../shared/CopyButton.svelte";
  import { SECONDARY_BUTTON } from "../shared/buttons";
  import ComponentEmbedPreview from "./ComponentEmbedPreview.svelte";
  import {
    COMPONENT_EMBED_MAX_BYTES,
    COMPONENT_EMBED_MAX_COMPONENTS,
    COMPONENT_EMBED_MAX_GALLERY_ITEMS,
    accentToHex,
    buildInlineEmbedSnippet,
    hexToAccent,
    validateComponentEmbed,
  } from "../../../lib/inspect/component-embed";
  import { EMBED_TEMPLATES } from "../../../lib/inspect/component-embed-templates";

  let { initial }: { initial?: string } = $props();

  // `initial` seeds the editor once (e.g. "Edit in builder" from an inspection)
  // svelte-ignore state_referenced_locally
  let source = $state(initial ?? EMBED_TEMPLATES[1]!.json);
  let output: "inline" | "linked" = $state("inline");

  let validation = $derived(validateComponentEmbed(source));
  let snippet = $derived(buildInlineEmbedSnippet(source));
  let minified = $derived(snippet ? snippet.html.replace(/^<script[^>]*>|<\/script>$/g, "") : "");
  let errors = $derived(validation.issues.filter((i) => i.severity === "error"));
  let warnings = $derived(validation.issues.filter((i) => i.severity === "warning"));

  /** Current accent as #rrggbb for the color input */
  let accentHex = $derived(
    typeof validation.root?.accent_color === "number" ? accentToHex(validation.root.accent_color) : "#5865f2",
  );

  const LINK_SNIPPET =
    '<link rel="discord:component-embed" type="application/json" href="https://your-site.example/embed.json">';

  /** Re-serialise the payload with a transform; no-op when it doesn't parse */
  function rewrite(transform: (doc: Record<string, unknown>) => void, indent = 2) {
    try {
      const doc = JSON.parse(source) as Record<string, unknown>;
      transform(doc);
      source = JSON.stringify(doc, null, indent);
    } catch {
      // Leave invalid JSON for the user to fix; the validator already says why
    }
  }

  function setAccent(hex: string) {
    const value = hexToAccent(hex);
    if (value === null) return;
    rewrite((doc) => {
      const component = doc.component as Record<string, unknown> | undefined;
      if (component && typeof component === "object") component.accent_color = value;
    });
  }

  /** Meter colour for a used/limit pair */
  function meter(used: number, limit: number): string {
    return used > limit ? "text-red-300" : used > limit * 0.85 ? "text-amber-300" : "text-white/60";
  }
</script>

<div class="space-y-5">
  <div class="flex flex-wrap items-center gap-2">
    <span class="text-xs font-medium tracking-wider text-white/40 uppercase">Start from</span>
    {#each EMBED_TEMPLATES as template (template.id)}
      <button type="button" class="{SECONDARY_BUTTON} min-h-9 py-1.5 text-xs" onclick={() => (source = template.json)}>
        {template.label}
      </button>
    {/each}
  </div>

  <div class="grid gap-5 lg:grid-cols-2">
    <!-- Editor -->
    <div class="space-y-3">
      <label class="block">
        <span class="mb-2 block text-xs font-medium tracking-wider text-white/40 uppercase">Payload JSON</span>
        <textarea
          bind:value={source}
          spellcheck="false"
          autocapitalize="off"
          rows="18"
          class="w-full resize-y rounded-xl border border-white/10 bg-black/30 p-3 font-mono text-xs leading-relaxed text-white/80 focus:border-brand-500/50 focus:outline-none"
        ></textarea>
      </label>
      <div class="flex flex-wrap items-center gap-2">
        <button type="button" class="{SECONDARY_BUTTON} min-h-9 py-1.5 text-xs" onclick={() => rewrite(() => {}, 2)}>Format</button>
        <button type="button" class="{SECONDARY_BUTTON} min-h-9 py-1.5 text-xs" onclick={() => rewrite(() => {}, 0)}>Minify</button>
        <label class="inline-flex min-h-9 items-center gap-2 rounded-xl border border-white/10 bg-white/5 px-3 text-xs text-white/60">
          Accent
          <input
            type="color"
            value={accentHex}
            onchange={(e) => setAccent((e.currentTarget as HTMLInputElement).value)}
            class="h-6 w-8 cursor-pointer rounded border-0 bg-transparent"
            aria-label="Accent color"
          />
        </label>
      </div>

      <dl class="grid grid-cols-3 gap-2 text-center">
        <div class="rounded-xl border border-white/8 bg-white/[0.03] px-2 py-2">
          <dt class="text-[10px] tracking-wider text-white/35 uppercase">Bytes (as written)</dt>
          <dd class="font-mono text-xs whitespace-nowrap sm:text-sm {meter(validation.bytes, COMPONENT_EMBED_MAX_BYTES)}">
            {validation.bytes.toLocaleString()}<span class="text-white/30">&nbsp;/ 3,000</span>
          </dd>
        </div>
        <div class="rounded-xl border border-white/8 bg-white/[0.03] px-2 py-2">
          <dt class="text-[10px] tracking-wider text-white/35 uppercase">Components</dt>
          <dd class="font-mono text-xs whitespace-nowrap sm:text-sm {meter(validation.componentCount, COMPONENT_EMBED_MAX_COMPONENTS)}">
            {validation.componentCount}<span class="text-white/30">&nbsp;/ 40</span>
          </dd>
        </div>
        <div class="rounded-xl border border-white/8 bg-white/[0.03] px-2 py-2">
          <dt class="text-[10px] tracking-wider text-white/35 uppercase">Gallery items</dt>
          <dd class="font-mono text-xs whitespace-nowrap sm:text-sm {meter(validation.galleryItemCount, COMPONENT_EMBED_MAX_GALLERY_ITEMS)}">
            {validation.galleryItemCount}<span class="text-white/30">&nbsp;/ 10</span>
          </dd>
        </div>
      </dl>
      {#if snippet && validation.bytes > COMPONENT_EMBED_MAX_BYTES && snippet.bytes <= COMPONENT_EMBED_MAX_BYTES}
        <p class="text-xs text-amber-300/80">
          Minified, this is {snippet.bytes.toLocaleString()} bytes — the copied snippet below fits.
        </p>
      {/if}

      {#if validation.issues.length}
        <ul class="space-y-1.5" aria-live="polite">
          {#each [...errors, ...warnings] as issue, i (i)}
            <li
              class="rounded-lg border px-3 py-2 text-xs {issue.severity === 'error'
                ? 'border-red-500/20 bg-red-500/5 text-red-200'
                : 'border-amber-500/20 bg-amber-500/5 text-amber-100'}"
            >
              {#if issue.path}<code class="mr-1 font-mono text-[11px] opacity-70">{issue.path}</code>{/if}
              {issue.message}
            </li>
          {/each}
        </ul>
      {:else}
        <p class="text-xs text-emerald-300/80" aria-live="polite">Valid component embed.</p>
      {/if}
    </div>

    <!-- Preview -->
    <div class="space-y-3">
      <span class="block text-xs font-medium tracking-wider text-white/40 uppercase">Preview</span>
      <div class="rounded-xl bg-[#313338] p-3 sm:p-4">
        {#if validation.root}
          <ComponentEmbedPreview root={validation.root} />
        {:else}
          <p class="py-8 text-center text-sm text-white/40">Fix the payload to see a preview.</p>
        {/if}
      </div>
      {#if validation.root && !validation.ok}
        <p class="text-xs text-red-300/80">
          With errors, Discord won't render this — it falls back to the page's Open Graph preview.
        </p>
      {/if}
    </div>
  </div>

  <!-- Output -->
  <section class="space-y-3 rounded-2xl border border-white/8 bg-white/[0.03] p-5">
    <div class="flex flex-wrap items-center justify-between gap-2">
      <h3 class="text-sm font-medium text-white/70">Add it to your page</h3>
      <div class="flex gap-1 rounded-lg border border-white/8 p-0.5" role="radiogroup" aria-label="Embed delivery">
        {#each [["inline", "Inline script"], ["linked", "Linked JSON"]] as [value, label] (value)}
          <button
            type="button"
            role="radio"
            aria-checked={output === value}
            onclick={() => (output = value as "inline" | "linked")}
            class="min-h-9 rounded-md px-3 text-xs font-medium transition-colors {output === value
              ? 'bg-brand-500/20 text-brand-300'
              : 'text-white/50 hover:bg-white/5'}"
          >
            {label}
          </button>
        {/each}
      </div>
    </div>

    {#if !snippet}
      <p class="text-xs text-white/40">The payload must be valid JSON to generate markup.</p>
    {:else if output === "inline"}
      <p class="text-xs text-white/45">Paste inside <code>&lt;head&gt;</code>, in the HTML your server sends (Discord runs no JavaScript).</p>
      <pre class="max-h-48 overflow-auto rounded-lg bg-black/30 p-3 font-mono text-[11px] break-all whitespace-pre-wrap text-white/70">{snippet.html}</pre>
      <CopyButton text={snippet.html} label="Copy script tag" />
    {:else}
      <p class="text-xs text-white/45">
        Serve the JSON from your site (same host, a subdomain, or the parent domain), then link it from <code>&lt;head&gt;</code>.
      </p>
      <pre class="max-h-48 overflow-auto rounded-lg bg-black/30 p-3 font-mono text-[11px] break-all whitespace-pre-wrap text-white/70">{minified}</pre>
      <div class="flex flex-wrap gap-2">
        <CopyButton text={minified} label="Copy JSON" />
        <CopyButton text={LINK_SNIPPET} label="Copy link tag" />
      </div>
    {/if}
    <p class="text-xs text-white/40">
      Keep <code>og:title</code>, <code>og:description</code> and <code>og:image</code> on the page too — Discord falls back to them
      whenever a component embed can't be shown.
    </p>
  </section>
</div>
