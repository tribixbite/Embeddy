<script lang="ts">
  /**
   * Upload progress bar with percentage and byte counts.
   */
  import { formatFileSize } from "../shared/format-size";
  import type { UploadProgress } from "../../../lib/upload/types";

  let { progress }: { progress: UploadProgress } = $props();
</script>

<div class="rounded-2xl border border-white/8 bg-white/[0.03] p-5">
  <div class="mb-2 flex items-center justify-between text-sm">
    <span class="text-white/50">Uploading...</span>
    <span class="font-mono text-white/60">{progress.percent}%</span>
  </div>

  <!-- Progress bar -->
  <div class="h-2 overflow-hidden rounded-full bg-white/10">
    <div
      class="h-full rounded-full bg-brand-500 transition-[width] duration-300 ease-out"
      style="width: {progress.percent}%"
    ></div>
  </div>

  <p class="mt-2 text-xs text-white/30 text-right">
    {formatFileSize(progress.loaded)} / {formatFileSize(progress.total)}
  </p>
</div>
