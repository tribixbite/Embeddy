<script lang="ts">
  /**
   * Inspect tool — three modes:
   *   URL            fetch a page as Discord's crawler does and show the
   *                  preview Discord would build (standard or component
   *                  embed), every documented rule it passes or breaks, and
   *                  all raw tags
   *   Component JSON build/validate a discord:component-embed payload locally
   *   File EXIF      read metadata from a local image (exifr / RIFF parser)
   * States: idle → fetching → done / exif / error
   */
  import { onMount } from "svelte";
  import FileDropZone from "../shared/FileDropZone.svelte";
  import GlobalFileIntake from "../shared/GlobalFileIntake.svelte";
  import Spinner from "../shared/Spinner.svelte";
  import ErrorCard from "../shared/ErrorCard.svelte";
  import ToggleGroup from "../shared/ToggleGroup.svelte";
  import CopyButton from "../shared/CopyButton.svelte";
  import { SECONDARY_BUTTON } from "../shared/buttons";
  import UrlInput from "./UrlInput.svelte";
  import EmbedPreview from "./EmbedPreview.svelte";
  import ComponentEmbedPreview from "./ComponentEmbedPreview.svelte";
  import ComponentEmbedBuilder from "./ComponentEmbedBuilder.svelte";
  import DiscordChecks from "./DiscordChecks.svelte";
  import MetadataTable from "./MetadataTable.svelte";
  import { fetchMetadata } from "../../../lib/inspect/proxy";
  import { analyzeUnfurl, DISCORD_EMBED_DEBUGGER_URL, LAYOUT_LABELS } from "../../../lib/inspect/discord-unfurl";
  import { parseWebP, webpMetadataToEntries } from "../../../lib/inspect/webp-parser";
  import { classifyInspectFile } from "../../../lib/inspect/file-support";
  import type { CrawlerIdentity, MetadataResult, ExifData } from "../../../lib/inspect/types";

  type State = "idle" | "fetching" | "done" | "exif" | "error";
  type Mode = "url" | "component" | "file";

  const MODES: Array<{ value: Mode; label: string }> = [
    { value: "url", label: "URL preview" },
    { value: "component", label: "Component JSON" },
    { value: "file", label: "File EXIF" },
  ];

  let state: State = $state("idle");
  let mode: Mode = $state("url");
  let error = $state("");
  let metadata: MetadataResult | null = $state(null);
  let exifData: ExifData | null = $state(null);
  let exifFileName = $state("");
  let crawler: CrawlerIdentity = $state("discord");
  let lastUrl = $state("");
  /** Payload handed to the builder; changing it remounts the builder */
  let builderSeed: string | undefined = $state(undefined);
  let showFallback = $state(false);
  /** Text in the URL field (bound so ?url= can prefill it) */
  let urlText = $state("");

  // /tools/inspect/?url=… inspects that URL straight away (the showcase links here)
  onMount(() => {
    const requested = new URLSearchParams(window.location.search).get("url");
    if (requested && /^https?:\/\//i.test(requested)) {
      urlText = requested;
      void handleFetch(requested);
    }
  });

  let report = $derived(metadata ? analyzeUnfurl(metadata) : null);
  let active = $derived(report?.activeComponentEmbed);
  /** A component embed exists but Discord can't use it — offer to fix it */
  let brokenEmbed = $derived(report && !active ? report.componentEmbeds.find((c) => c.element.raw !== undefined) : undefined);
  let hostname = $derived.by(() => {
    try {
      return metadata ? new URL(metadata.url).hostname : "";
    } catch {
      return metadata?.url ?? "";
    }
  });
  let favicon = $derived(metadata?.tags.find((t) => t.property === "favicon" && !t.attr)?.content);

  async function handleFetch(url: string) {
    lastUrl = url;
    state = "fetching";
    error = "";
    metadata = null;
    showFallback = false;

    try {
      metadata = await fetchMetadata(url, crawler);
      state = "done";
    } catch (e) {
      error = e instanceof Error ? e.message : "Failed to fetch metadata";
      state = "error";
    }
  }

  /** Open a payload from the inspected page in the builder */
  function editInBuilder(raw: string | undefined) {
    builderSeed = raw;
    mode = "component";
  }

  /** Check if file is WebP by MIME type or extension */
  function isWebP(file: File): boolean {
    return file.type === "image/webp" || file.name.toLowerCase().endsWith(".webp");
  }

  async function handleFile(files: File[]) {
    const file = files[0];
    if (!file) return;

    // exifr has no video parsers — gate non-image picks up front with a
    // friendly message instead of surfacing a raw "Unknown file format"
    const classification = classifyInspectFile(file.type);
    if (!classification.ok) {
      error = classification.message ?? "Unsupported file type";
      state = "error";
      return;
    }

    state = "fetching";
    error = "";
    exifData = null;
    exifFileName = file.name;

    try {
      if (isWebP(file)) {
        // WebP: use custom RIFF parser (exifr doesn't support WebP)
        const buffer = await file.arrayBuffer();
        const webpMeta = parseWebP(buffer);
        const entries = webpMetadataToEntries(webpMeta);

        // If the WebP has embedded EXIF, also parse that with exifr
        if (webpMeta.exifBytes) {
          try {
            const exifr = await import("exifr");
            // Pass the Uint8Array view, not `.buffer` — the view is a window into the
            // whole file, so `.buffer` would hand exifr the RIFF header at offset 0
            // instead of the EXIF chunk.
            const exifParsed = await exifr.parse(webpMeta.exifBytes, {
              translateValues: true,
            });
            if (exifParsed) {
              for (const [key, value] of Object.entries(exifParsed)) {
                entries[`EXIF: ${key}`] = String(value);
              }
            }
          } catch {
            // EXIF parsing failed — just show RIFF metadata
          }
        }

        exifData = entries;
        state = "exif";
      } else {
        // Other formats: use exifr
        const exifr = await import("exifr");
        const parsed = await exifr.parse(file, { translateValues: true });
        if (!parsed || Object.keys(parsed).length === 0) {
          error = "No metadata found in this file";
          state = "error";
          return;
        }
        exifData = parsed;
        state = "exif";
      }
    } catch (e) {
      error = e instanceof Error ? e.message : "Failed to read file metadata";
      state = "error";
    }
  }

  function reset() {
    state = "idle";
    error = "";
    metadata = null;
    exifData = null;
    showFallback = false;
  }

  function switchMode(next: Mode) {
    if (next === mode) return;
    mode = next;
    reset();
  }
</script>

<GlobalFileIntake
  accept="image/*"
  onfiles={(files) => { mode = "file"; handleFile(files); }}
  enabled={state !== "fetching" && mode !== "component"}
/>

<div class="space-y-6">
  <!-- Mode switcher -->
  <div class="flex gap-1 rounded-xl border border-white/8 bg-white/[0.03] p-1" role="tablist" aria-label="Inspect mode">
    {#each MODES as m (m.value)}
      <button
        role="tab"
        aria-selected={mode === m.value}
        onclick={() => switchMode(m.value)}
        class="min-h-11 flex-1 rounded-lg px-2 py-2 text-xs font-medium transition-colors sm:text-sm
          {mode === m.value ? 'bg-brand-500/20 text-brand-300' : 'text-white/50 hover:bg-white/5'}"
      >
        {m.label}
      </button>
    {/each}
  </div>

  {#if mode === "component"}
    {#key builderSeed}
      <ComponentEmbedBuilder initial={builderSeed} />
    {/key}
  {:else}
    {#if mode === "url"}
      <div class="space-y-3">
        <UrlInput disabled={state === "fetching"} onfetch={handleFetch} bind:url={urlText} />
        <ToggleGroup
          label="Fetch as"
          bind:value={crawler}
          disabled={state === "fetching"}
          options={[
            { value: "discord", label: "Discordbot" },
            { value: "embeddy", label: "Embeddy" },
          ]}
          help="Discordbot sends the same user agent as Discord's crawler, so you see the HTML Discord gets."
        />
      </div>
    {:else}
      <FileDropZone
        accept="image/*"
        label="Drop an image to inspect metadata"
        onfile={handleFile}
      />
    {/if}

    {#if state === "fetching"}
      <Spinner label={mode === "url" ? "Fetching like Discord does..." : "Reading EXIF data..."} padding="py-12" />
    {:else if state === "done" && metadata && report}
      <section class="space-y-3">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <h3 class="text-sm font-medium text-white/70">
            Discord preview
            <span class="ml-1 rounded bg-white/5 px-1.5 py-0.5 font-mono text-[10px] text-white/45">
              {active ? "Component embed" : LAYOUT_LABELS[report.preview.layout]}
            </span>
          </h3>
          <div class="flex flex-wrap gap-2">
            <CopyButton text={report.cacheBustUrl} label="Copy cache-busted URL" />
            <a
              href={DISCORD_EMBED_DEBUGGER_URL}
              target="_blank"
              rel="noopener noreferrer"
              class="inline-flex items-center gap-1.5 rounded-lg border border-white/10 bg-white/5 px-3 py-1.5 text-xs font-medium text-white/60 transition-colors hover:bg-white/10"
            >
              Discord Embed Debugger ↗
            </a>
          </div>
        </div>

        <!-- Mock Discord message -->
        <div class="rounded-xl bg-[#313338] p-3 sm:p-4">
          <div class="flex gap-3">
            <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-brand-500 text-sm font-bold text-white" aria-hidden="true">
              E
            </div>
            <div class="min-w-0 flex-1 space-y-1.5">
              <p class="text-sm">
                <span class="font-medium text-[#f2f3f5]">You</span>
                <span class="ml-1 text-xs text-[#949ba4]">Today</span>
              </p>
              <a href={metadata.url} target="_blank" rel="noopener noreferrer" class="block truncate text-sm text-[#00a8fc] hover:underline">
                {lastUrl || metadata.url}
              </a>
              {#if active?.validation?.root}
                <ComponentEmbedPreview root={active.validation.root} />
              {:else}
                <EmbedPreview preview={report.preview} />
              {/if}
            </div>
          </div>
        </div>

        <!-- Inspector footer: favicon, final URL, status -->
        <div class="flex items-center gap-2 px-1">
          {#if favicon}
            <img
              src={favicon}
              alt=""
              class="h-4 w-4 shrink-0 rounded-sm"
              loading="lazy"
              onerror={(e) => { (e.currentTarget as HTMLImageElement).style.display = "none"; }}
            />
          {/if}
          <p class="min-w-0 flex-1 truncate font-mono text-xs text-white/30" title={metadata.url}>{hostname}</p>
          <span
            class="shrink-0 rounded px-1.5 py-0.5 font-mono text-[10px]
              {metadata.status >= 200 && metadata.status < 300 ? 'bg-emerald-500/10 text-emerald-400/80' : 'bg-red-500/10 text-red-400/80'}"
          >
            HTTP {metadata.status}
          </span>
        </div>

        {#if active}
          <div class="flex flex-wrap gap-2">
            <button type="button" class={SECONDARY_BUTTON} onclick={() => (showFallback = !showFallback)} aria-expanded={showFallback}>
              {showFallback ? "Hide" : "Show"} Open Graph fallback
            </button>
            <button type="button" class={SECONDARY_BUTTON} onclick={() => editInBuilder(active?.element.raw)}>Edit in builder</button>
          </div>
          {#if showFallback}
            <div class="rounded-xl bg-[#313338] p-3 sm:p-4">
              <p class="mb-2 text-xs text-[#949ba4]">
                Shown when the component embed can't be — {LAYOUT_LABELS[report.preview.layout]}
              </p>
              <EmbedPreview preview={report.preview} />
            </div>
          {/if}
        {:else if brokenEmbed}
          <div class="flex flex-wrap items-center gap-3 rounded-xl border border-amber-500/20 bg-amber-500/5 px-4 py-3">
            <p class="flex-1 text-xs text-amber-100/80">
              This page has a component embed Discord can't use, so it shows the Open Graph preview above. See the checks below.
            </p>
            <button type="button" class={SECONDARY_BUTTON} onclick={() => editInBuilder(brokenEmbed?.element.raw)}>Fix in builder</button>
          </div>
        {/if}

        <p class="px-1 text-xs text-white/35">
          Discord caches previews for about 30 minutes — share the cache-busted URL to see changes now. Layout precedence
          Discord doesn't document is the inspector's best reading; the Embed Debugger is authoritative.
        </p>
      </section>

      <DiscordChecks checks={report.checks} />
      <MetadataTable tags={metadata.tags} />
      <div class="grid gap-2 sm:grid-cols-2">
        <button onclick={() => handleFetch(lastUrl)} class="w-full {SECONDARY_BUTTON}">Fetch again</button>
        <button onclick={reset} class="w-full {SECONDARY_BUTTON}">Inspect another URL</button>
      </div>
    {:else if state === "exif" && exifData}
      <div class="rounded-2xl border border-white/8 bg-white/[0.03] p-5">
        <h3 class="mb-4 text-sm font-medium text-white/70">
          EXIF data for <span class="text-white/90 font-mono">{exifFileName}</span>
        </h3>
        <div class="space-y-1 max-h-[60vh] overflow-y-auto">
          {#each Object.entries(exifData) as [key, value]}
            <div class="flex items-start gap-2 rounded-lg px-2 py-1.5 text-sm hover:bg-white/[0.03]">
              <span class="min-w-[160px] shrink-0 font-mono text-xs text-brand-400/70">{key}</span>
              <span class="flex-1 break-all text-xs text-white/60">{String(value)}</span>
            </div>
          {/each}
        </div>
      </div>
      <button
        onclick={reset}
        class="w-full {SECONDARY_BUTTON}"
      >
        Inspect another file
      </button>
    {:else if state === "error"}
      <ErrorCard message={error}>
        <button onclick={reset} class={SECONDARY_BUTTON}>Try again</button>
      </ErrorCard>
    {/if}
  {/if}
</div>
