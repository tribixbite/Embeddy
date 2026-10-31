<script lang="ts">
  /**
   * Error panel shared by every tool (D4). role="alert" so the failure is
   * announced when it replaces the working view. Action buttons go in the
   * children snippet; ErrorCard styles them consistently via `actionClass`.
   */
  import type { Snippet } from "svelte";

  let { message, children }: { message: string; children?: Snippet } = $props();
</script>

<div class="rounded-2xl border border-red-500/20 bg-red-500/5 p-6 text-center" role="alert">
  <p class="text-sm text-red-400">{message}</p>
  {#if children}
    <div class="mt-4 flex justify-center gap-3">
      {@render children()}
    </div>
  {/if}
</div>
