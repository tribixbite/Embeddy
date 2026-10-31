<script lang="ts">
  /**
   * Result hand-off buttons: the OS share sheet (where the Web Share API can
   * share files) and "Send to Upload", which carries the result to the
   * Upload tool for hosting.
   */
  import { canShareFiles, sendToTool, shareFile } from "../../../lib/media/handoff";
  import { SECONDARY_BUTTON } from "./buttons";

  let { blob, filename }: { blob: Blob; filename: string } = $props();

  let file = $derived(new File([blob], filename, { type: blob.type }));
  let shareable = $derived(canShareFiles([file]));
  let busy = $state(false);
  let error = $state("");

  async function share() {
    error = "";
    try {
      await shareFile(file);
    } catch (e) {
      error = e instanceof Error ? e.message : "Sharing failed";
    }
  }

  async function toUpload() {
    busy = true;
    error = "";
    try {
      await sendToTool(file, "/tools/upload/");
    } catch (e) {
      busy = false;
      error = e instanceof Error ? e.message : "Couldn't hand the file to Upload";
    }
  }
</script>

<div class="flex flex-wrap gap-3">
  {#if shareable}
    <button type="button" onclick={share} class="flex-1 {SECONDARY_BUTTON}">Share…</button>
  {/if}
  <button type="button" onclick={toUpload} disabled={busy} class="flex-1 {SECONDARY_BUTTON} disabled:opacity-40">
    {busy ? "Opening Upload…" : "Send to Upload"}
  </button>
</div>
{#if error}
  <p class="text-xs text-red-400" role="alert">{error}</p>
{/if}
