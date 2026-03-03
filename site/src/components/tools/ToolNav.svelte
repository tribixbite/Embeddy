<script lang="ts">
  /**
   * Horizontal tab bar linking the 3 browser tools.
   * Highlights the currently active tool based on the current path.
   */
  interface Tool {
    href: string;
    label: string;
    icon: string;
  }

  const tools: Tool[] = [
    {
      href: "/tools/squoosh",
      label: "Squoosh",
      icon: "M2.25 15.75l5.159-5.159a2.25 2.25 0 013.182 0l5.159 5.159m-1.5-1.5l1.409-1.41a2.25 2.25 0 013.182 0l2.909 2.91m-18 3.75h16.5a1.5 1.5 0 001.5-1.5V6a1.5 1.5 0 00-1.5-1.5H3.75A1.5 1.5 0 002.25 6v12a1.5 1.5 0 001.5 1.5zm10.5-11.25h.008v.008h-.008V8.25zm.375 0a.375.375 0 11-.75 0 .375.375 0 01.75 0z",
    },
    {
      href: "/tools/inspect",
      label: "Inspect",
      icon: "M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z",
    },
    {
      href: "/tools/upload",
      label: "Upload",
      icon: "M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5m-13.5-9L12 3m0 0l4.5 4.5M12 3v13.5",
    },
  ];

  /** Current path, set on mount for SSR compat */
  let { currentPath = "" }: { currentPath?: string } = $props();
</script>

<nav class="mb-8 flex gap-1 rounded-xl border border-white/8 bg-white/[0.03] p-1">
  {#each tools as tool}
    {@const active = currentPath.startsWith(tool.href)}
    <a
      href={tool.href}
      class="flex flex-1 items-center justify-center gap-2 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors
        {active
          ? 'bg-brand-500/20 text-brand-300 shadow-sm'
          : 'text-white/50 hover:bg-white/5 hover:text-white/70'}"
    >
      <svg
        xmlns="http://www.w3.org/2000/svg"
        fill="none"
        viewBox="0 0 24 24"
        stroke-width="1.5"
        stroke="currentColor"
        class="h-4 w-4"
      >
        <path stroke-linecap="round" stroke-linejoin="round" d={tool.icon} />
      </svg>
      <span class="hidden sm:inline">{tool.label}</span>
    </a>
  {/each}
</nav>
