<script lang="ts">
  /**
   * Discord readiness checklist: every documented rule the page passes or
   * breaks, grouped by area, failures first within each group.
   */
  import type { CheckGroup, CheckStatus, PreviewCheck } from "../../../lib/inspect/discord-unfurl";

  let { checks }: { checks: PreviewCheck[] } = $props();

  const GROUP_LABELS: Record<CheckGroup, string> = {
    component: "Component embed",
    fetch: "Fetch",
    text: "Text, color & date",
    media: "Images & video",
    oembed: "oEmbed",
  };
  const GROUP_ORDER: CheckGroup[] = ["component", "fetch", "text", "media", "oembed"];
  const STATUS_RANK: Record<CheckStatus, number> = { fail: 0, warn: 1, pass: 2, info: 3 };
  const STATUS_STYLE: Record<CheckStatus, { icon: string; cls: string; sr: string }> = {
    fail: { icon: "✕", cls: "bg-red-500/15 text-red-300", sr: "Fails" },
    warn: { icon: "!", cls: "bg-amber-500/15 text-amber-300", sr: "Warning" },
    pass: { icon: "✓", cls: "bg-emerald-500/15 text-emerald-300", sr: "Passes" },
    info: { icon: "i", cls: "bg-white/8 text-white/50", sr: "Note" },
  };

  let groups = $derived(
    GROUP_ORDER.map((group) => ({
      group,
      items: checks.filter((c) => c.group === group).sort((a, b) => STATUS_RANK[a.status] - STATUS_RANK[b.status]),
    })).filter((g) => g.items.length > 0),
  );

  let counts = $derived({
    fail: checks.filter((c) => c.status === "fail").length,
    warn: checks.filter((c) => c.status === "warn").length,
  });
</script>

<section class="rounded-2xl border border-white/8 bg-white/[0.03] p-5">
  <div class="mb-3 flex flex-wrap items-center justify-between gap-2">
    <h3 class="text-sm font-medium text-white/70">Discord checks</h3>
    <p class="text-xs text-white/40">
      {#if counts.fail === 0 && counts.warn === 0}
        <span class="text-emerald-300/80">No problems found</span>
      {:else}
        {#if counts.fail}<span class="text-red-300/90">{counts.fail} failing</span>{/if}
        {#if counts.fail && counts.warn} · {/if}
        {#if counts.warn}<span class="text-amber-300/90">{counts.warn} warning{counts.warn === 1 ? "" : "s"}</span>{/if}
      {/if}
    </p>
  </div>

  <div class="space-y-4">
    {#each groups as { group, items } (group)}
      <div>
        <h4 class="mb-1.5 text-xs font-semibold tracking-wider text-white/30 uppercase">{GROUP_LABELS[group]}</h4>
        <ul class="space-y-1">
          {#each items as item, i (i)}
            {@const style = STATUS_STYLE[item.status]}
            <li class="flex items-start gap-2.5 rounded-lg px-2 py-1.5 hover:bg-white/[0.03]">
              <span
                class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full text-[11px] font-bold {style.cls}"
                aria-hidden="true">{style.icon}</span
              >
              <div class="min-w-0 flex-1">
                <p class="text-sm text-white/80"><span class="sr-only">{style.sr}: </span>{item.title}</p>
                {#if item.detail}
                  <p class="mt-0.5 text-xs break-words text-white/45">{item.detail}</p>
                {/if}
              </div>
            </li>
          {/each}
        </ul>
      </div>
    {/each}
  </div>
</section>
