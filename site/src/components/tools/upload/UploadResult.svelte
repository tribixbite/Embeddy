<script lang="ts">
  /**
   * Upload result — shows the file URL with copy button.
   */
  import CopyButton from "../shared/CopyButton.svelte";
  import type { UploadResult } from "../../../lib/upload/types";

  let { result }: { result: UploadResult } = $props();
</script>

<div class="rounded-2xl border border-green-500/20 bg-green-500/5 p-5">
  <div class="mb-3 flex items-center gap-2">
    <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="2" stroke="currentColor" class="h-5 w-5 text-green-400">
      <path stroke-linecap="round" stroke-linejoin="round" d="M9 12.75L11.25 15 15 9.75M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
    </svg>
    <span class="text-sm font-medium text-green-400">Uploaded to {result.host}</span>
    <span class="text-xs text-white/30 ml-auto">{(result.durationMs / 1000).toFixed(1)}s</span>
  </div>

  <div class="flex items-center gap-2 rounded-lg bg-dark-900/50 px-3 py-2">
    <a
      href={result.url}
      target="_blank"
      rel="noopener noreferrer"
      class="flex-1 truncate font-mono text-sm text-brand-300 hover:underline"
    >
      {result.url}
    </a>
    <CopyButton text={result.url} />
  </div>
</div>
