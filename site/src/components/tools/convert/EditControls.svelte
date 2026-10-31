<script lang="ts">
  /**
   * Quick-edit controls for the Convert tool: a scrubbable video preview with
   * frame grab, trim range, speed, rotate/flip, mute, and extra clips to join
   * (video output). Geometry is applied by FramePreparer / Mediabunny; time
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
    joinClips: File[];
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
  function addClips(e: Event) {
    const input = e.currentTarget as HTMLInputElement;
    const picked = Array.from(input.files ?? []).filter((f) => f.type.startsWith("video/") || /\.(mp4|webm|mov|mkv|m4v)$/i.test(f.name));
    joinClips = [...joinClips, ...picked];
    input.value = "";
  }
  function removeClip(index: number) {
    joinClips = joinClips.filter((_, i) => i !== index);
  }
  function moveClip(index: number, delta: number) {
    const next = [...joinClips];
    const [clip] = next.splice(index, 1);
    next.splice(Math.max(0, Math.min(next.length, index + delta)), 0, clip!);
    joinClips = next;
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
      help={videoOutput && options.speed !== 1 ? "Audio is removed when speed changes" : undefined}
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
        <button type="button" class={SECONDARY_BUTTON} onclick={() => clipInput?.click()} {disabled}>Add videos…</button>
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
      {#if joinClips.length > 0}
        <ol class="space-y-1 text-xs text-white/60">
          <li class="rounded-lg bg-white/[0.03] px-3 py-2">1. {sourceName} <span class="text-white/30">(trimmed as above)</span></li>
          {#each joinClips as clip, i (clip)}
            <li class="flex items-center gap-2 rounded-lg bg-white/[0.03] px-3 py-1">
              <span class="flex-1 truncate">{i + 2}. {clip.name} <span class="text-white/30">{formatFileSize(clip.size)}</span></span>
              <button type="button" class="min-h-11 min-w-11 text-white/50 hover:text-white" onclick={() => moveClip(i, -1)} disabled={disabled || i === 0} aria-label="Move {clip.name} up">↑</button>
              <button type="button" class="min-h-11 min-w-11 text-white/50 hover:text-white" onclick={() => moveClip(i, 1)} disabled={disabled || i === joinClips.length - 1} aria-label="Move {clip.name} down">↓</button>
              <button type="button" class="min-h-11 min-w-11 text-white/50 hover:text-red-300" onclick={() => removeClip(i)} {disabled} aria-label="Remove {clip.name}">✕</button>
            </li>
          {/each}
        </ol>
        <p class="text-xs text-white/30">
          Clips play in order, scaled to fit the first clip's size. Crop, rotate and speed apply only without joined clips.
        </p>
      {/if}
    </div>
  {/if}
</section>
