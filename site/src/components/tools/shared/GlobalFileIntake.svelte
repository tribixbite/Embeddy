<script lang="ts">
  /**
   * Page-wide file intake for a tool:
   * - drag-and-drop anywhere on the page (with a full-screen drop overlay),
   * - clipboard paste of files/screenshots (Ctrl/Cmd+V),
   * - files handed over by the OS share sheet or another tool (?shared=1),
   * - files opened with the installed PWA (manifest file_handlers).
   *
   * Mount once per tool; renders nothing but the overlay.
   */
  import { onMount } from "svelte";
  import { isTextEntryTarget, pickAcceptedFiles } from "../../../lib/media/file-intake";
  import { consumeShareLaunch } from "../../../lib/pwa/share-target";

  let {
    accept,
    onfiles,
    /** False while the tool is busy — new files are ignored rather than racing */
    enabled = true,
    /** Accept several files at once (otherwise only the first is passed on) */
    multiple = false,
  }: {
    accept: string;
    onfiles: (files: File[]) => void;
    enabled?: boolean;
    multiple?: boolean;
  } = $props();

  let dragging = $state(false);
  let rejected = $state("");

  /** Forward accepted files, or explain why nothing happened */
  function deliver(files: File[]) {
    const accepted = pickAcceptedFiles(files, accept);
    if (accepted.length === 0) {
      if (files.length > 0) {
        rejected = `${files[0]!.name || "That file"} isn't a supported type here`;
        setTimeout(() => (rejected = ""), 4000);
      }
      return;
    }
    onfiles(multiple ? accepted : accepted.slice(0, 1));
  }

  /** Only react to drags that carry files (not text/links dragged around) */
  function hasFiles(e: DragEvent): boolean {
    return Array.from(e.dataTransfer?.types ?? []).includes("Files");
  }

  onMount(() => {
    let depth = 0;
    const onDragEnter = (e: DragEvent) => {
      if (!enabled || !hasFiles(e)) return;
      depth++;
      dragging = true;
    };
    const onDragOver = (e: DragEvent) => {
      // preventDefault marks the whole page as a drop target
      if (enabled && hasFiles(e)) e.preventDefault();
    };
    const onDragLeave = () => {
      depth = Math.max(0, depth - 1);
      if (depth === 0) dragging = false;
    };
    const onDrop = (e: DragEvent) => {
      depth = 0;
      dragging = false;
      // A FileDropZone already handled this drop (it preventDefault()s first)
      if (!enabled || e.defaultPrevented || !hasFiles(e)) return;
      e.preventDefault();
      deliver(Array.from(e.dataTransfer?.files ?? []));
    };
    const onPaste = (e: ClipboardEvent) => {
      if (!enabled || isTextEntryTarget(e.target)) return;
      const files = Array.from(e.clipboardData?.files ?? []);
      if (files.length === 0) return;
      e.preventDefault();
      deliver(files);
    };

    window.addEventListener("dragenter", onDragEnter);
    window.addEventListener("dragover", onDragOver);
    window.addEventListener("dragleave", onDragLeave);
    window.addEventListener("drop", onDrop);
    window.addEventListener("paste", onPaste);

    // Share-sheet / cross-tool hand-off
    consumeShareLaunch()
      .then((files) => files.length && deliver(files))
      .catch(() => {});

    // Installed-PWA "Open with" (manifest file_handlers); Chromium only
    const launchQueue = (window as unknown as {
      launchQueue?: { setConsumer(cb: (p: { files?: FileSystemFileHandle[] }) => void): void };
    }).launchQueue;
    launchQueue?.setConsumer(async (params) => {
      if (!params.files?.length) return;
      deliver(await Promise.all(params.files.map((h) => h.getFile())));
    });

    return () => {
      window.removeEventListener("dragenter", onDragEnter);
      window.removeEventListener("dragover", onDragOver);
      window.removeEventListener("dragleave", onDragLeave);
      window.removeEventListener("drop", onDrop);
      window.removeEventListener("paste", onPaste);
    };
  });
</script>

{#if dragging}
  <div
    class="pointer-events-none fixed inset-0 z-[90] flex items-center justify-center bg-dark-950/80 backdrop-blur-sm"
    aria-hidden="true"
  >
    <div class="rounded-2xl border-2 border-dashed border-brand-400 px-8 py-6 text-sm text-white/80">
      Drop to open
    </div>
  </div>
{/if}

{#if rejected}
  <p
    class="fixed inset-x-4 bottom-[calc(1rem+env(safe-area-inset-bottom))] z-[90] mx-auto max-w-md rounded-xl border border-red-500/20 bg-dark-900/95 px-4 py-3 text-center text-sm text-red-300"
    role="alert"
  >
    {rejected}
  </p>
{/if}
