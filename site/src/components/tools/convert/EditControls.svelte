<script lang="ts">
  /**
   * Quick-edit controls for the Convert tool: a scrubbable video preview with
   * frame grab, trim range, speed, rotate/flip, mute, and extra clips to join
   * (video output), each with its own trim. Geometry is applied by FramePreparer / Mediabunny; time
   * edits by lib/convert/timeline.ts.
   */
  import type { ConvertOptions, SourceInfo } from "../../../lib/convert/types";
  import { isVideoFormat } from "../../../lib/convert/types";
  import { transformFromOptions, type Rotation } from "../../../lib/convert/prepare-frame";
  import { MAX_SPEED, MIN_SPEED } from "../../../lib/convert/timeline";
  import { grabFileName, grabFrame, type StillFormat } from "../../../lib/convert/frame-grab";
  import TrimControls from "./TrimControls.svelte";
  import RangeField from "../shared/RangeField.svelte";
  import ToggleGroup from "../shared/ToggleGroup.svelte";
  import { SECONDARY_BUTTON } from "../shared/buttons";
  import { formatFileSize } from "../shared/format-size";
  import {
    createJoinClip,
    disposeJoinClips,
    isTrimmed,
    moveItem,
    type JoinClipItem,
  } from "../../../lib/convert/join-clips";

  let {
    options = $bindable(),
    info,
    sourcePreviewUrl,
    sourceName,
    disabled = false,
    joinClips = $bindable(),
  }: {
    options: ConvertOptions;
    info: SourceInfo;
    sourcePreviewUrl: string;
    sourceName: string;
    disabled?: boolean;
    /** Extra videos appended after the source (video output only) */
    joinClips: JoinClipItem[];
  } = $props();

  let video: HTMLVideoElement | undefined = $state();
  let grabError = $state("");
  let isVideo = $derived(info.format === "video");
  let videoOutput = $derived(isVideoFormat(options.outputFormat));
  /** Trim needs a timeline: video, or an animation with more than one frame */
  let canTrim = $derived(info.totalDuration > 0 && (isVideo || info.frameCount > 1));

  const ROTATIONS: Array<{ value: Rotation; label: string }> = [
    { value: 0, label: "0°" },
    { value: 90, label: "90°" },
    { value: 180, label: "180°" },
    { value: 270, label: "270°" },
  ];

  function seek(ms: number) {
    if (video) video.currentTime = ms / 1000;
  }

  /** Set a trim edge to the preview's current position */
  function markStart() {
    if (!video) return;
    const ms = Math.round(video.currentTime * 1000);
    const end = options.trimEndMs > 0 ? options.trimEndMs : info.totalDuration;
    options.trimStartMs = Math.min(ms, end - 100);
  }
  function markEnd() {
    if (!video) return;
    const ms = Math.round(video.currentTime * 1000);
    options.trimEndMs = ms >= info.totalDuration ? 0 : Math.max(ms, options.trimStartMs + 100);
  }

  /** Save the preview's current frame, with crop/rotate/flip applied */
  async function saveFrame(format: StillFormat) {
    if (!video) return;
    grabError = "";
    try {
      const blob = await grabFrame(video, video.videoWidth, video.videoHeight, transformFromOptions(options), format);
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = grabFileName(sourceName, video.currentTime, format);
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 10_000);
    } catch (e) {
      grabError = e instanceof Error ? e.message : "Couldn't capture the frame";
    }
  }

  let clipInput: HTMLInputElement | undefined = $state();
  /** Clips still being probed, and per-file probe failures */
  let addingClips = $state(0);
  let clipError = $state("");
  /** Id of the clip whose trim editor is open (one at a time keeps the page short) */
  let openClipId: number | null = $state(null);
  let clipVideo: HTMLVideoElement | undefined = $state();

  async function addClips(e: Event) {
    const input = e.currentTarget as HTMLInputElement;
    const picked = Array.from(input.files ?? []).filter((f) => f.type.startsWith("video/") || /\.(mp4|webm|mov|mkv|m4v)$/i.test(f.name));
    input.value = "";
    clipError = "";
    addingClips += picked.length;
    const failures: string[] = [];
    // Probe in order so clips keep the order they were picked in
    for (const file of picked) {
      try {
        const clip = await createJoinClip(file);
        joinClips = [...joinClips, clip];
      } catch (err) {
        failures.push(err instanceof Error ? err.message : `${file.name} couldn't be read`);
      } finally {
        addingClips--;
      }
    }
    if (failures.length) clipError = failures.join(" · ");
  }
  function removeClip(index: number) {
    const removed = joinClips[index];
    if (!removed) return;
    disposeJoinClips([removed]);
    if (openClipId === removed.id) openClipId = null;
    joinClips = joinClips.filter((_, i) => i !== index);
  }
  function moveClip(index: number, delta: number) {
    joinClips = moveItem(joinClips, index, delta);
  }
  function toggleClipTrim(id: number) {
    openClipId = openClipId === id ? null : id;
  }
  function seekClip(ms: number) {
    if (clipVideo) clipVideo.currentTime = ms / 1000;
  }
</script>

<section class="space-y-5" aria-label="Edit">
  {#if isVideo && sourcePreviewUrl}
    <div class="space-y-3">
      <!-- svelte-ignore a11y_media_has_caption -->
      <video
        bind:this={video}
        src={sourcePreviewUrl}
        controls
        playsinline
        preload="metadata"
        class="mx-auto max-h-72 w-full rounded-lg bg-black"
        aria-label="Source preview"
      ></video>
      <div class="flex flex-wrap gap-2">
        <button type="button" class={SECONDARY_BUTTON} onclick={markStart} {disabled}>Start here</button>
        <button type="button" class={SECONDARY_BUTTON} onclick={markEnd} {disabled}>End here</button>
        <button type="button" class={SECONDARY_BUTTON} onclick={() => saveFrame("image/png")} {disabled}>Save frame (PNG)</button>
        <button type="button" class={SECONDARY_BUTTON} onclick={() => saveFrame("image/webp")} {disabled}>Save frame (WebP)</button>
      </div>
      {#if grabError}<p class="text-xs text-red-400" role="alert">{grabError}</p>{/if}
    </div>
  {/if}

  {#if canTrim}
    <TrimControls
      totalMs={info.totalDuration}
      bind:startMs={options.trimStartMs}
      bind:endMs={options.trimEndMs}
      {disabled}
      onseek={seek}
    />
  {/if}

  {#if canTrim}
    <RangeField
      id="cv-speed"
      label="Speed"
      bind:value={options.speed}
      min={MIN_SPEED}
      max={MAX_SPEED}
      step={0.25}
      {disabled}
      display={`${options.speed}×`}
      minHint="Slower"
      maxHint="Faster"
    />
  {/if}

  <ToggleGroup label="Rotate" bind:value={options.rotate} options={ROTATIONS} {disabled} />

  <div class="flex flex-wrap gap-x-6 gap-y-2">
    <label class="flex min-h-11 cursor-pointer items-center gap-3">
      <input type="checkbox" bind:checked={options.flipH} {disabled} class="h-5 w-5 accent-brand-500" />
      <span class="text-sm text-white/60">Flip horizontally</span>
    </label>
    <label class="flex min-h-11 cursor-pointer items-center gap-3">
      <input type="checkbox" bind:checked={options.flipV} {disabled} class="h-5 w-5 accent-brand-500" />
      <span class="text-sm text-white/60">Flip vertically</span>
    </label>
    {#if videoOutput}
      <label class="flex min-h-11 cursor-pointer items-center gap-3">
        <input type="checkbox" bind:checked={options.muted} {disabled} class="h-5 w-5 accent-brand-500" />
        <span class="text-sm text-white/60">Remove audio</span>
      </label>
    {/if}
  </div>

  {#if videoOutput}
    <div class="space-y-2">
      <div class="flex items-center justify-between">
        <span class="text-sm text-white/60">Join clips</span>
        <button type="button" class={SECONDARY_BUTTON} onclick={() => clipInput?.click()} disabled={disabled || addingClips > 0}>
          {addingClips > 0 ? "Reading…" : "Add videos…"}
        </button>
        <input
          bind:this={clipInput}
          type="file"
          accept="video/*,.mkv,.mov"
          multiple
          class="hidden"
          tabindex="-1"
          aria-hidden="true"
          onchange={addClips}
        />
      </div>
      {#if clipError}<p class="text-xs text-red-400" role="alert">{clipError}</p>{/if}
      {#if joinClips.length > 0}
        <ol class="space-y-1 text-xs text-white/60">
          <li class="rounded-lg bg-white/[0.03] px-3 py-2">1. {sourceName} <span class="text-white/30">(trimmed as above)</span></li>
          {#each joinClips as clip, i (clip.id)}
            <li class="rounded-lg bg-white/[0.03] px-3 py-1">
              <div class="flex items-center gap-2">
                <span class="flex-1 truncate">
                  {i + 2}. {clip.file.name}
                  <span class="text-white/30">{formatFileSize(clip.file.size)}{isTrimmed(clip) ? " · trimmed" : ""}</span>
                </span>
                {#if clip.durationMs > 0}
                  <button
                    type="button"
                    class="min-h-11 rounded-lg px-2 text-white/50 hover:text-white {openClipId === clip.id ? 'text-brand-300' : ''}"
                    onclick={() => toggleClipTrim(clip.id)}
                    {disabled}
                    aria-expanded={openClipId === clip.id}
                    aria-label="Trim {clip.file.name}"
                  >Trim</button>
                {/if}
                <button type="button" class="min-h-11 min-w-11 text-white/50 hover:text-white" onclick={() => moveClip(i, -1)} disabled={disabled || i === 0} aria-label="Move {clip.file.name} up">↑</button>
                <button type="button" class="min-h-11 min-w-11 text-white/50 hover:text-white" onclick={() => moveClip(i, 1)} disabled={disabled || i === joinClips.length - 1} aria-label="Move {clip.file.name} down">↓</button>
                <button type="button" class="min-h-11 min-w-11 text-white/50 hover:text-red-300" onclick={() => removeClip(i)} {disabled} aria-label="Remove {clip.file.name}">✕</button>
              </div>
              {#if openClipId === clip.id}
                <div class="space-y-3 pb-3 pt-1">
                  <!-- svelte-ignore a11y_media_has_caption -->
                  <video
                    bind:this={clipVideo}
                    src={clip.previewUrl}
                    controls
                    playsinline
                    preload="metadata"
                    class="mx-auto max-h-56 w-full rounded-lg bg-black"
                    aria-label="Preview of {clip.file.name}"
                  ></video>
                  <TrimControls
                    label="Trim clip {i + 2}"
                    totalMs={clip.durationMs}
                    bind:startMs={joinClips[i]!.trimStartMs}
                    bind:endMs={joinClips[i]!.trimEndMs}
                    {disabled}
                    onseek={seekClip}
                  />
                </div>
              {/if}
            </li>
          {/each}
        </ol>
        <p class="text-xs text-white/30">
          Clips play in order, scaled to fit the first clip's size. Speed applies to the whole join; crop and rotate apply only without joined clips.
        </p>
      {/if}
    </div>
  {/if}
</section>
