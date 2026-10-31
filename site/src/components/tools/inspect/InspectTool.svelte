<script lang="ts">
  /**
   * Inspect tool — fetch and preview OG/Twitter metadata for any URL.
   * Also supports local file EXIF reading via exifr.
   * States: idle → fetching → done / error
   */
  import FileDropZone from "../shared/FileDropZone.svelte";
  import GlobalFileIntake from "../shared/GlobalFileIntake.svelte";
  import Spinner from "../shared/Spinner.svelte";
  import ErrorCard from "../shared/ErrorCard.svelte";
  import { SECONDARY_BUTTON } from "../shared/buttons";
  import UrlInput from "./UrlInput.svelte";
  import EmbedPreview from "./EmbedPreview.svelte";
  import MetadataTable from "./MetadataTable.svelte";
  import { fetchMetadata } from "../../../lib/inspect/proxy";
  import { parseWebP, webpMetadataToEntries } from "../../../lib/inspect/webp-parser";
  import { classifyInspectFile } from "../../../lib/inspect/file-support";
  import type { MetadataResult, ExifData } from "../../../lib/inspect/types";

  type State = "idle" | "fetching" | "done" | "exif" | "error";
  type Mode = "url" | "file";

  let state: State = $state("idle");
  let mode: Mode = $state("url");
  let error = $state("");
  let metadata: MetadataResult | null = $state(null);
  let exifData: ExifData | null = $state(null);
  let exifFileName = $state("");

  async function handleFetch(url: string) {
    state = "fetching";
    error = "";
    metadata = null;

    try {
      metadata = await fetchMetadata(url);
      state = "done";
    } catch (e) {
      error = e instanceof Error ? e.message : "Failed to fetch metadata";
      state = "error";
    }
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
  }
</script>

<GlobalFileIntake
  accept="image/*"
  onfiles={(files) => { mode = "file"; handleFile(files); }}
  enabled={state !== "fetching"}
/>

<div class="space-y-6">
  <!-- Mode switcher -->
  <div class="flex gap-2 rounded-xl border border-white/8 bg-white/[0.03] p-1">
    <button
      onclick={() => { mode = "url"; reset(); }}
      class="flex-1 rounded-lg px-3 py-2 text-sm font-medium transition-colors
        {mode === 'url' ? 'bg-brand-500/20 text-brand-300' : 'text-white/50 hover:bg-white/5'}"
    >
      URL Metadata
    </button>
    <button
      onclick={() => { mode = "file"; reset(); }}
      class="flex-1 rounded-lg px-3 py-2 text-sm font-medium transition-colors
        {mode === 'file' ? 'bg-brand-500/20 text-brand-300' : 'text-white/50 hover:bg-white/5'}"
    >
      File EXIF
    </button>
  </div>

  {#if mode === "url"}
    <UrlInput
      disabled={state === "fetching"}
      onfetch={handleFetch}
    />
  {:else}
    <FileDropZone
      accept="image/*"
      label="Drop an image to inspect metadata"
      onfile={handleFile}
    />
  {/if}

  {#if state === "fetching"}
    <Spinner label={mode === "url" ? "Fetching metadata..." : "Reading EXIF data..."} padding="py-12" />
  {:else if state === "done" && metadata}
    <EmbedPreview data={metadata} />
    <MetadataTable tags={metadata.tags} />
    <button
      onclick={reset}
      class="w-full {SECONDARY_BUTTON}"
    >
      Inspect another URL
    </button>
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
</div>
