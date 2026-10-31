/**
 * Shared Tailwind class strings for the tools' secondary buttons, so touch
 * target sizing (min 44px, A3) is defined once.
 */

/** Bordered secondary action (Try again, Start over, Adjust settings...) */
export const SECONDARY_BUTTON =
  "min-h-11 rounded-xl border border-white/10 bg-white/5 px-4 py-2.5 text-sm font-medium text-white/60 transition-colors hover:bg-white/10";

/** Low-emphasis inline action like "Change file" — still a 44px target */
export const TEXT_BUTTON =
  "-my-2 -mr-2 inline-flex min-h-11 items-center rounded-lg px-3 text-xs text-white/50 transition-colors hover:text-white/80";
