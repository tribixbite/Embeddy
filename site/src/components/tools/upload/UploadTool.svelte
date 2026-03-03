<script lang="ts">
  /**
   * Upload tool — anonymous file upload with EXIF stripping.
   * States: idle → ready → uploading → done / error
   */
  import FileDropZone from "../shared/FileDropZone.svelte";
  import HostSelector from "./HostSelector.svelte";
  import UploadProgressBar from "./UploadProgress.svelte";
  import UploadResultCard from "./UploadResult.svelte";
  import { uploadFile } from "../../../lib/upload/proxy";
  import { stripExif } from "../../../lib/upload/strip-exif";
  import { formatFileSize } from "../shared/format-size";
  import type { UploadHost, UploadResult, UploadProgress } from "../../../lib/upload/types";

  type State = "idle" | "ready" | "stripping" | "uploading" | "done" | "error";

  let state: State = $state("idle");
  let error = $state("");
  let selectedFile: File | null = $state(null);
  let host: UploadHost = $state("0x0.st");
  let progress: UploadProgress = $state({ loaded: 0, total: 0, percent: 0 });
  let result: UploadResult | null = $state(null);
  let stripExifEnabled = $state(true);

  function handleFile(files: File[]) {
    const file = files[0];
    if (!file) return;
    selectedFile = file;
    state = "ready";
    error = "";
    result = null;
  }

  async function handleUpload() {
    if (!selectedFile) return;

    error = "";
    let fileToUpload = selectedFile;

    // Strip EXIF if enabled and file is an image
    if (stripExifEnabled && selectedFile.type.startsWith("image/")) {
      state = "stripping";
      try {
        fileToUpload = await stripExif(selectedFile);
      } catch {
        // Continue with original file if stripping fails
      }
    }

    state = "uploading";
    progress = { loaded: 0, total: fileToUpload.size, percent: 0 };

    try {
      result = await uploadFile(fileToUpload, host, (p) => {
        progress = p;
      });
      state = "done";
    } catch (e) {
      error = e instanceof Error ? e.message : "Upload failed";
      state = "error";
    }
  }

  function reset() {
    selectedFile = null;
    state = "idle";
    error = "";
    result = null;
    progress = { loaded: 0, total: 0, percent: 0 };
  }
</script>

<div class="space-y-6">
  {#if state === "idle"}
    <FileDropZone
      accept="*/*"
      label="Drop a file here or click to browse"
      onfile={handleFile}
    />
  {:else if state === "ready"}
    <!-- File info + settings -->
    <div class="rounded-2xl border border-white/8 bg-white/[0.03] p-5">
      <div class="flex items-center justify-between mb-4">
        <div>
          <p class="text-sm font-medium text-white/80">{selectedFile?.name}</p>
          <p class="text-xs text-white/40">{formatFileSize(selectedFile?.size ?? 0)}</p>
        </div>
        <button
          onclick={reset}
          class="text-xs text-white/40 hover:text-white/60 transition-colors"
        >
          Change file
        </button>
      </div>

      <HostSelector bind:selected={host} />

      <!-- EXIF strip toggle (for images) -->
      {#if selectedFile?.type.startsWith("image/")}
        <label class="mt-4 flex cursor-pointer items-center gap-3">
          <input
            type="checkbox"
            bind:checked={stripExifEnabled}
            class="h-4 w-4 rounded border-white/20 bg-white/5 accent-brand-500"
          />
          <span class="text-sm text-white/60">Strip EXIF metadata</span>
          <span class="text-xs text-white/30">(GPS, camera info)</span>
        </label>
      {/if}
    </div>

    <button
      onclick={handleUpload}
      class="w-full rounded-xl bg-brand-500 px-6 py-3 text-sm font-semibold text-white transition-all hover:bg-brand-400 hover:shadow-lg hover:shadow-brand-500/25"
    >
      Upload to {host}
    </button>
  {:else if state === "stripping"}
    <div class="flex items-center justify-center py-12">
      <div class="h-8 w-8 animate-spin rounded-full border-2 border-white/20 border-t-brand-400"></div>
      <span class="ml-3 text-sm text-white/50">Stripping EXIF data...</span>
    </div>
  {:else if state === "uploading"}
    <UploadProgressBar {progress} />
  {:else if state === "done" && result}
    <UploadResultCard {result} />
    <button
      onclick={reset}
      class="w-full rounded-xl border border-white/10 bg-white/5 px-4 py-2.5 text-sm font-medium text-white/60 transition-colors hover:bg-white/10"
    >
      Upload another file
    </button>
  {:else if state === "error"}
    <div class="rounded-2xl border border-red-500/20 bg-red-500/5 p-6 text-center">
      <p class="text-sm text-red-400">{error}</p>
      <div class="mt-4 flex gap-3 justify-center">
        <button
          onclick={handleUpload}
          class="rounded-lg border border-white/10 bg-white/5 px-4 py-2 text-sm text-white/60 transition-colors hover:bg-white/10"
        >
          Retry
        </button>
        <button
          onclick={reset}
          class="rounded-lg border border-white/10 bg-white/5 px-4 py-2 text-sm text-white/60 transition-colors hover:bg-white/10"
        >
          Start over
        </button>
      </div>
    </div>
  {/if}
</div>
