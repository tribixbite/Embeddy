<script lang="ts">
  /**
   * Upload host selection chips — 0x0.st vs catbox.moe.
   */
  import type { UploadHost } from "../../../lib/upload/types";

  let {
    selected = $bindable(),
    disabled = false,
  }: {
    selected: UploadHost;
    disabled?: boolean;
  } = $props();

  const hosts: { value: UploadHost; label: string; desc: string }[] = [
    { value: "0x0.st", label: "0x0.st", desc: "512 MB max, temp storage" },
    { value: "catbox.moe", label: "catbox.moe", desc: "200 MB max, persistent" },
  ];
</script>

<div>
  <label class="mb-2 block text-xs font-medium text-white/40 uppercase tracking-wider">Host</label>
  <div class="flex gap-2">
    {#each hosts as host}
      <button
        {disabled}
        onclick={() => { selected = host.value; }}
        class="flex flex-1 flex-col items-center gap-1 rounded-xl px-4 py-3 text-sm transition-colors
          {selected === host.value
            ? 'bg-brand-500/20 text-brand-300 border border-brand-500/30'
            : 'border border-white/10 bg-white/5 text-white/50 hover:bg-white/10'}"
      >
        <span class="font-medium">{host.label}</span>
        <span class="text-xs text-white/30">{host.desc}</span>
      </button>
    {/each}
  </div>
</div>
