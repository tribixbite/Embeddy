<script lang="ts">
  /**
   * Animated feature cards — fade in on scroll using IntersectionObserver.
   * Svelte 5 island hydrated via client:visible.
   */
  import { onMount } from "svelte";

  interface Feature {
    icon: string;
    title: string;
    description: string;
    badges: string[];
  }

  const features: Feature[] = [
    {
      icon: "M15.91 11.672a.375.375 0 010 .656l-5.603 3.113a.375.375 0 01-.557-.328V8.887c0-.286.307-.466.557-.327l5.603 3.112z",
      title: "Convert",
      description:
        "Video and GIF to animated WebP with adaptive quality loop. Platform presets for Discord (10 MB / 720p), Telegram (256 KB sticker), Slack (5 MB), or fully custom targets.",
      badges: ["FFmpeg-kit", "Adaptive quality", "Trim & stitch"],
    },
    {
      icon: "M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z",
      title: "Inspect",
      description:
        "Fetch any URL and preview its Open Graph and Twitter Card metadata. See exactly how your links will embed on Discord, Slack, and Twitter before sharing.",
      badges: ["OG tags", "Twitter Card", "Embed preview"],
    },
    {
      icon: "M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5m-13.5-9L12 3m0 0l4.5 4.5M12 3v13.5",
      title: "Upload",
      description:
        "Anonymous file uploads to 0x0.st and catbox.moe. EXIF metadata stripping protects your privacy. Retry with linear backoff on transient failures.",
      badges: ["0x0.st", "catbox.moe", "EXIF strip"],
    },
    {
      icon: "M2.25 15.75l5.159-5.159a2.25 2.25 0 013.182 0l5.159 5.159m-1.5-1.5l1.409-1.41a2.25 2.25 0 013.182 0l2.909 2.91m-18 3.75h16.5a1.5 1.5 0 001.5-1.5V6a1.5 1.5 0 00-1.5-1.5H3.75A1.5 1.5 0 002.25 6v12a1.5 1.5 0 001.5 1.5zm10.5-11.25h.008v.008h-.008V8.25zm.375 0a.375.375 0 11-.75 0 .375.375 0 01.75 0z",
      title: "Squoosh",
      description:
        "Still image compression to WebP, JPEG, PNG, or AVIF. Quality slider, lossless toggle, exact crop dimensions, and a before/after comparison slider.",
      badges: ["WebP", "AVIF", "Lossless", "Before/After"],
    },
  ];

  let cards: HTMLElement[] = $state([]);

  onMount(() => {
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            entry.target.classList.add("animate-[fade-up_0.5s_ease-out_both]");
            observer.unobserve(entry.target);
          }
        }
      },
      { threshold: 0.15 },
    );

    for (const card of cards) {
      if (card) observer.observe(card);
    }

    return () => observer.disconnect();
  });
</script>

<div class="grid gap-6 md:grid-cols-2">
  {#each features as feature, i}
    <div
      bind:this={cards[i]}
      class="group rounded-2xl border border-white/8 bg-white/[0.03] p-6 opacity-0 backdrop-blur-sm transition-colors hover:border-brand-500/30 hover:bg-white/[0.06]"
    >
      <!-- Icon -->
      <div
        class="mb-4 inline-flex rounded-xl bg-brand-500/10 p-3 text-brand-400"
      >
        <svg
          xmlns="http://www.w3.org/2000/svg"
          fill="none"
          viewBox="0 0 24 24"
          stroke-width="1.5"
          stroke="currentColor"
          class="h-6 w-6"
        >
          <path
            stroke-linecap="round"
            stroke-linejoin="round"
            d={feature.icon}
          />
        </svg>
      </div>

      <!-- Title -->
      <h3
        class="mb-2 text-lg font-semibold text-white group-hover:text-brand-300"
      >
        {feature.title}
      </h3>

      <!-- Description -->
      <p class="mb-4 text-sm leading-relaxed text-white/60">
        {feature.description}
      </p>

      <!-- Badges -->
      <div class="flex flex-wrap gap-2">
        {#each feature.badges as badge}
          <span
            class="rounded-full border border-white/10 bg-white/5 px-2.5 py-0.5 text-xs font-medium text-white/50"
          >
            {badge}
          </span>
        {/each}
      </div>
    </div>
  {/each}
</div>
