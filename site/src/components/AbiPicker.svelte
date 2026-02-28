<script lang="ts">
  /**
   * Interactive ABI picker — Svelte 5 island hydrated via client:visible.
   * Lets users select their device architecture and get a direct download link.
   */
  const RELEASE_URL =
    "https://github.com/tribixbite/Embeddy/releases/latest";

  interface AbiOption {
    abi: string;
    label: string;
    description: string;
    recommended?: boolean;
  }

  const options: AbiOption[] = [
    {
      abi: "arm64-v8a",
      label: "arm64-v8a",
      description: "Most modern phones (2017+)",
      recommended: true,
    },
    {
      abi: "armeabi-v7a",
      label: "armeabi-v7a",
      description: "Older 32-bit ARM devices",
    },
    {
      abi: "x86_64",
      label: "x86_64",
      description: "Emulators & Chromebooks",
    },
    {
      abi: "universal",
      label: "Universal",
      description: "All architectures (larger size)",
    },
  ];

  let selected = $state("arm64-v8a");
</script>

<div class="space-y-4">
  <div class="grid grid-cols-2 gap-3 sm:grid-cols-4">
    {#each options as opt}
      <button
        class="relative rounded-xl border-2 px-4 py-3 text-left transition-all duration-200
          {selected === opt.abi
          ? 'border-brand-500 bg-brand-500/10 shadow-lg shadow-brand-500/10'
          : 'border-white/10 bg-white/5 hover:border-white/20 hover:bg-white/8'}"
        onclick={() => (selected = opt.abi)}
      >
        {#if opt.recommended}
          <span
            class="absolute -top-2.5 right-2 rounded-full bg-brand-500 px-2 py-0.5 text-[10px] font-semibold tracking-wider text-white uppercase"
          >
            Recommended
          </span>
        {/if}
        <span class="block font-mono text-sm font-medium text-white">
          {opt.label}
        </span>
        <span class="mt-1 block text-xs text-white/50">
          {opt.description}
        </span>
      </button>
    {/each}
  </div>

  <a
    href={RELEASE_URL}
    target="_blank"
    rel="noopener noreferrer"
    class="inline-flex w-full items-center justify-center gap-2 rounded-xl bg-brand-500 px-6 py-3.5 font-semibold text-white transition-all hover:bg-brand-400 hover:shadow-lg hover:shadow-brand-500/20 sm:w-auto"
  >
    <svg
      xmlns="http://www.w3.org/2000/svg"
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
    >
      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
      <polyline points="7 10 12 15 17 10" />
      <line x1="12" y1="15" x2="12" y2="3" />
    </svg>
    Download {selected} APK
  </a>
  <p class="text-xs text-white/40">
    v0.1.12 &middot; Requires Android 7.0+
  </p>
</div>
