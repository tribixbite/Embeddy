<script lang="ts">
  /**
   * Discord standard link preview, drawn for the layout Discord would pick
   * (see discord-unfurl.ts): text with a small thumbnail, a large image, image
   * only, inline video, a gallery of up to 4, or a rich markdown description.
   * Colours follow Discord's dark theme so the card reads like the real one.
   */
  import type { StandardPreview } from "../../../lib/inspect/discord-unfurl";
  import DiscordMarkdown from "./DiscordMarkdown.svelte";
  import MediaMosaic from "./MediaMosaic.svelte";

  let { preview }: { preview: StandardPreview } = $props();

  let firstImage = $derived(preview.images[0]);
  let bare = $derived(preview.layout === "photo" || preview.layout === "direct-image" || preview.layout === "direct-video");
  let showThumb = $derived((preview.layout === "thumbnail" || preview.layout === "rich") && !!firstImage);

  /** Footer timestamp in Discord's "Today at 3:04 PM" / date style */
  let timestampLabel = $derived.by(() => {
    if (!preview.timestamp) return "";
    const date = new Date(preview.timestamp);
    const sameDay = date.toDateString() === new Date().toDateString();
    const time = date.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
    return sameDay ? `Today at ${time}` : `${date.toLocaleDateString()} ${time}`;
  });

  /** Hide an <img> that fails to load rather than show a broken icon */
  function hideOnError(e: Event) {
    (e.currentTarget as HTMLElement).style.display = "none";
  }
</script>

{#if preview.layout === "none"}
  <p class="rounded-md border border-dashed border-white/10 px-4 py-6 text-center text-sm text-white/40">
    Discord shows the bare link — no preview.
  </p>
{:else if bare}
  {#if preview.layout === "direct-video" && preview.video}
    <!-- svelte-ignore a11y_media_has_caption — preview of third-party media -->
    <video src={preview.video.url} controls preload="metadata" playsinline class="max-h-[350px] max-w-full rounded-lg bg-black"></video>
  {:else if firstImage}
    <img
      src={firstImage.url}
      alt={firstImage.alt ?? ""}
      class="max-h-[350px] max-w-full rounded-lg"
      loading="lazy"
      onerror={hideOnError}
    />
  {/if}
{:else}
  <article
    class="grid max-w-[432px] rounded border-l-4 bg-[#2b2d31] py-2 pr-4 pl-3"
    style:border-left-color={preview.color ?? "#1e1f22"}
  >
    <div class="flex gap-4">
      <div class="min-w-0 flex-1 space-y-2 pt-1">
        {#if preview.siteName}
          <p class="text-xs text-[#dbdee1]">
            {#if preview.providerUrl}
              <a href={preview.providerUrl} target="_blank" rel="noopener noreferrer" class="hover:underline">{preview.siteName}</a>
            {:else}
              {preview.siteName}
            {/if}
          </p>
        {/if}
        {#if preview.author}
          <p class="text-sm font-semibold text-[#f2f3f5]">
            {#if preview.author.url}
              <a href={preview.author.url} target="_blank" rel="noopener noreferrer" class="hover:underline">{preview.author.name}</a>
            {:else}
              {preview.author.name}
            {/if}
          </p>
        {/if}
        {#if preview.title}
          <a
            href={preview.url}
            target="_blank"
            rel="noopener noreferrer"
            class="block text-base font-semibold whitespace-pre-line text-[#00a8fc] hover:underline"
            title={preview.title.trimmed ? preview.title.full : undefined}
          >
            {preview.title.text}
          </a>
        {/if}
        {#if preview.description}
          {#if preview.markdown}
            <DiscordMarkdown text={preview.description.text} compact />
          {:else}
            <p class="text-sm leading-[1.375rem] whitespace-pre-line text-[#dbdee1]" style="overflow-wrap:anywhere">
              {preview.description.text}
            </p>
          {/if}
        {/if}
      </div>
      {#if showThumb && firstImage}
        <img
          src={firstImage.url}
          alt={firstImage.alt ?? ""}
          class="mt-2 h-20 w-20 shrink-0 rounded object-cover"
          loading="lazy"
          onerror={hideOnError}
        />
      {/if}
    </div>

    {#if preview.layout === "large-image" && firstImage}
      <img
        src={firstImage.url}
        alt={firstImage.alt ?? ""}
        class="mt-4 max-h-[300px] w-full rounded object-contain object-left"
        loading="lazy"
        onerror={hideOnError}
      />
    {:else if preview.layout === "gallery"}
      <div class="mt-4">
        <MediaMosaic items={preview.images.map((i) => ({ url: i.url, alt: i.alt }))} />
      </div>
    {:else if preview.layout === "video" && preview.video}
      <!-- svelte-ignore a11y_media_has_caption — preview of third-party media -->
      <video
        src={preview.video.url}
        poster={firstImage?.url}
        controls
        preload="none"
        playsinline
        class="mt-4 max-h-[300px] w-full rounded bg-black"
      ></video>
    {/if}

    {#if timestampLabel}
      <p class="mt-2 text-xs text-[#dbdee1]/70">{timestampLabel}</p>
    {/if}
  </article>
{/if}
