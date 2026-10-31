<script lang="ts">
  /**
   * Upload tool — anonymous file upload with metadata stripping.
   * States: idle → ready → uploading → done / error
   */
  import FileDropZone from "../shared/FileDropZone.svelte";
  import GlobalFileIntake from "../shared/GlobalFileIntake.svelte";
  import Spinner from "../shared/Spinner.svelte";
  import ErrorCard from "../shared/ErrorCard.svelte";
  import { SECONDARY_BUTTON, TEXT_BUTTON } from "../shared/buttons";
  import HostSelector from "./HostSelector.svelte";
  import UploadProgressBar from "./UploadProgress.svelte";
  import UploadResultCard from "./UploadResult.svelte";
  import { CancelledError, uploadFile, type UploadHandle } from "../../../lib/upload/proxy";
  import { stripExif, canStripExif } from "../../../lib/upload/strip-exif";
  import { formatFileSize } from "../shared/format-size";
  import { HOST_LIMITS } from "../../../lib/upload/types";
  import type { UploadHost, UploadResult, UploadProgress } from "../../../lib/upload/types";

  type State = "idle" | "ready" | "stripping" | "uploading" | "done" | "error";

  let state: State = $state("idle");
  let error = $state("");
  let selectedFile: File | null = $state(null);
  let host: UploadHost = $state("0x0.st");
  let progress: UploadProgress = $state({ loaded: 0, total: 0, percent: 0 });
  let result: UploadResult | null = $state(null);
  let stripExifEnabled = $state(true);
  /** Active upload, kept so the user can cancel mid-transfer */
  let activeUpload: UploadHandle | null = null;
  /** Shown while an automatic retry is in flight */
  let retryNotice = $state("");

  /** Size limit for the selected host — checked before spending bandwidth */
  let hostLimit = $derived(HOST_LIMITS[host]);
  let overLimit = $derived(
    selectedFile !== null && selectedFile.size > hostLimit,
  );

  /** Metadata stripping covers JPEG, PNG and WebP; say so rather than implying all images */
  let exifStrippable = $derived(selectedFile !== null && canStripExif(selectedFile));

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

    // Fail fast instead of streaming megabytes only to be rejected upstream
    if (selectedFile.size > hostLimit) {
      error =
        `${selectedFile.name} is ${formatFileSize(selectedFile.size)}, ` +
        `over the ${formatFileSize(hostLimit)} limit for ${host}`;
      state = "error";
      return;
    }

    error = "";
    let fileToUpload = selectedFile;

    // Strip metadata if enabled and the format supports it. Fails CLOSED: a
    // user who asked for stripping must never publish the original (with GPS)
    // to a public host just because the container was unexpected.
    if (stripExifEnabled && canStripExif(selectedFile)) {
      state = "stripping";
      try {
        fileToUpload = await stripExif(selectedFile);
      } catch (e) {
        const reason = e instanceof Error ? e.message : String(e);
        error =
          `Couldn't remove metadata (${reason}). Nothing was uploaded — ` +
          `turn off "Strip metadata" to upload the file unchanged.`;
        state = "error";
        return;
      }
    }

    state = "uploading";
    progress = { loaded: 0, total: fileToUpload.size, percent: 0 };
    retryNotice = "";

    const handle = uploadFile(
      fileToUpload,
      host,
      (p) => { progress = p; },
      (attempt, total) => {
        retryNotice = `Connection failed — retrying (attempt ${attempt} of ${total})`;
        progress = { loaded: 0, total: fileToUpload.size, percent: 0 };
      },
    );
    activeUpload = handle;

    try {
      result = await handle.result;
      state = "done";
    } catch (e) {
      // cancelUpload()/reset() already moved the state machine on; the late
      // CancelledError rejection must not drag it to the error screen (M23).
      if (e instanceof CancelledError) return;
      error = e instanceof Error ? e.message : "Upload failed";
      state = "error";
    } finally {
      activeUpload = null;
    }
  }

  /** Abort an in-flight upload and return to the file-ready screen */
  function cancelUpload() {
    activeUpload?.cancel();
    activeUpload = null;
    state = selectedFile ? "ready" : "idle";
    error = "";
    retryNotice = "";
    progress = { loaded: 0, total: 0, percent: 0 };
  }

  function reset() {
    activeUpload?.cancel();
    activeUpload = null;
    selectedFile = null;
    state = "idle";
    error = "";
    result = null;
    progress = { loaded: 0, total: 0, percent: 0 };
  }
</script>

<!-- Also receives results handed over by Convert/Squoosh ("Send to Upload") -->
<GlobalFileIntake accept="*/*" onfiles={handleFile} enabled={state !== "uploading" && state !== "stripping"} />

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
          class={TEXT_BUTTON}
        >
          Change file
        </button>
      </div>

      <HostSelector bind:selected={host} />

      <!-- Metadata strip toggle (JPEG/PNG/WebP — see lib/upload/strip-exif.ts) -->
      {#if exifStrippable}
        <label class="mt-4 flex cursor-pointer items-center gap-3">
          <input
            type="checkbox"
            bind:checked={stripExifEnabled}
            class="h-4 w-4 rounded border-white/20 bg-white/5 accent-brand-500"
          />
          <span class="text-sm text-white/70">Strip metadata</span>
          <span class="text-xs text-white/40">(EXIF, GPS, XMP, comments)</span>
        </label>
      {:else if selectedFile?.type.startsWith("image/")}
        <p class="mt-4 text-xs text-white/30">
          Metadata stripping is available for JPEG, PNG and WebP — this file will be uploaded as-is.
        </p>
      {/if}

      {#if overLimit}
        <p class="mt-4 text-xs text-red-400">
          {formatFileSize(selectedFile?.size ?? 0)} exceeds the
          {formatFileSize(hostLimit)} limit for {host}. Pick a smaller file or another host.
        </p>
      {/if}
    </div>

    <button
      onclick={handleUpload}
      disabled={overLimit}
      class="w-full rounded-xl bg-brand-500 px-6 py-3 text-sm font-semibold text-white transition-all hover:bg-brand-400 hover:shadow-lg hover:shadow-brand-500/25 disabled:cursor-not-allowed disabled:opacity-40"
    >
      Upload to {host}
    </button>
  {:else if state === "stripping"}
    <Spinner label="Removing metadata…" padding="py-12" />
  {:else if state === "uploading"}
    <UploadProgressBar {progress} />
    {#if retryNotice}
      <p class="text-center text-xs text-yellow-400/70" role="status">{retryNotice}</p>
    {/if}
    <button
      onclick={cancelUpload}
      class="w-full {SECONDARY_BUTTON}"
    >
      Cancel upload
    </button>
  {:else if state === "done" && result}
    <UploadResultCard {result} />
    <button
      onclick={reset}
      class="w-full {SECONDARY_BUTTON}"
    >
      Upload another file
    </button>
  {:else if state === "error"}
    <ErrorCard message={error}>
      <button onclick={handleUpload} class={SECONDARY_BUTTON}>Retry</button>
      <button onclick={reset} class={SECONDARY_BUTTON}>Start over</button>
    </ErrorCard>
  {/if}
</div>
