/**
 * embeddy.link's own Discord component embed.
 *
 * Every page ships a `discord:component-embed` payload in <head> (see
 * Layout.astro), so a link to any page unfurls in Discord as a Components V2
 * card: the page title and description beside the app icon, the preview
 * image, and link buttons to each tool. The Open Graph tags stay in place as
 * the fallback Discord uses when it can't show the component embed.
 */

import { serializeComponentEmbed } from "./inspect/component-embed";
import type { ContainerComponent } from "./inspect/component-embed";

/** brand-500 (oklch 0.55 0.20 275) as Discord's integer accent color */
export const SITE_EMBED_ACCENT = 0x555de3;

/** Tool links shown as buttons, in nav order */
export const SITE_EMBED_TOOLS = [
  { label: "Convert", path: "tools/convert/" },
  { label: "Squoosh", path: "tools/squoosh/" },
  { label: "Inspect", path: "tools/inspect/" },
  { label: "Upload", path: "tools/upload/" },
] as const;

export interface SiteEmbedInput {
  /** Page title (og:title) */
  title: string;
  /** Page description (og:description) */
  description: string;
  /** Canonical page URL */
  url: string;
  /** Site origin + base, e.g. https://embeddy.link/ */
  siteUrl: string;
  /** Absolute preview image URL (og:image) */
  imageUrl: string;
  /** Alt text for the preview image */
  imageAlt: string;
}

/** Characters that would start Discord markdown inside a masked-link label */
function escapeMarkdown(text: string): string {
  return text.replace(/([\\*_~`|[\]()#>-])/g, "\\$1");
}

/** Alt text is capped at 1,024 characters per media item */
function clampAlt(text: string): string {
  return text.length <= 1024 ? text : `${text.slice(0, 1023)}…`;
}

/**
 * Build the payload object for a page.
 */
export function buildSiteEmbed(input: SiteEmbedInput): { component: ContainerComponent } {
  const site = input.siteUrl.endsWith("/") ? input.siteUrl : `${input.siteUrl}/`;
  return {
    component: {
      type: 17,
      accent_color: SITE_EMBED_ACCENT,
      components: [
        {
          type: 9,
          components: [
            {
              type: 10,
              content: `## [${escapeMarkdown(input.title)}](${input.url})\n${escapeMarkdown(input.description)}`,
            },
          ],
          accessory: { type: 11, media: { url: `${site}icon-512.png` }, description: "Embeddy app icon" },
        },
        { type: 12, items: [{ media: { url: input.imageUrl }, description: clampAlt(input.imageAlt) }] },
        {
          type: 1,
          components: SITE_EMBED_TOOLS.map((tool) => ({
            type: 2 as const,
            style: 5 as const,
            label: tool.label,
            url: `${site}${tool.path}`,
          })),
        },
      ],
    },
  };
}

/** The payload as minified JSON, safe to place inside the <script> element */
export function siteEmbedJson(input: SiteEmbedInput): string {
  return serializeComponentEmbed(buildSiteEmbed(input));
}
