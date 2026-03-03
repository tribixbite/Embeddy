<script lang="ts">
  /**
   * Drag-and-drop + click file picker. Reused by all tool pages.
   * Emits `onfile` when a file is selected or dropped.
   */

  let {
    accept = "image/*",
    multiple = false,
    label = "Drop a file here or click to browse",
    onfile,
  }: {
    accept?: string;
    multiple?: boolean;
    label?: string;
    onfile: (files: File[]) => void;
  } = $props();

  let dragging = $state(false);
  let inputRef: HTMLInputElement | undefined = $state();

  function handleDrop(e: DragEvent) {
    e.preventDefault();
    dragging = false;
    const files = e.dataTransfer?.files;
    if (files?.length) {
      onfile(Array.from(files));
    }
  }

  function handleDragOver(e: DragEvent) {
    e.preventDefault();
    dragging = true;
  }

  function handleDragLeave() {
    dragging = false;
  }

  function handleClick() {
    inputRef?.click();
  }

  function handleInput(e: Event) {
    const target = e.target as HTMLInputElement;
    const files = target.files;
    if (files?.length) {
      onfile(Array.from(files));
      // Reset so re-selecting the same file triggers again
      target.value = "";
    }
  }
</script>

<!-- svelte-ignore a11y_no_static_element_interactions -->
<div
  role="button"
  tabindex="0"
  class="relative flex min-h-[200px] cursor-pointer flex-col items-center justify-center gap-3 rounded-2xl border-2 border-dashed transition-colors
    {dragging
      ? 'border-brand-400 bg-brand-500/10'
      : 'border-white/10 bg-white/[0.02] hover:border-white/20 hover:bg-white/[0.04]'}"
  ondrop={handleDrop}
  ondragover={handleDragOver}
  ondragleave={handleDragLeave}
  onclick={handleClick}
  onkeydown={(e) => { if (e.key === 'Enter' || e.key === ' ') handleClick(); }}
>
  <svg
    xmlns="http://www.w3.org/2000/svg"
    fill="none"
    viewBox="0 0 24 24"
    stroke-width="1.5"
    stroke="currentColor"
    class="h-10 w-10 text-white/30"
  >
    <path
      stroke-linecap="round"
      stroke-linejoin="round"
      d="M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5m-13.5-9L12 3m0 0l4.5 4.5M12 3v13.5"
    />
  </svg>
  <p class="text-sm text-white/50">{label}</p>
  <p class="text-xs text-white/30">
    {#if multiple}Multiple files allowed{:else}Single file{/if}
  </p>

  <input
    bind:this={inputRef}
    type="file"
    {accept}
    {multiple}
    class="hidden"
    oninput={handleInput}
  />
</div>
