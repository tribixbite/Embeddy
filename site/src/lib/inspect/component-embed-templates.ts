/**
 * Starter payloads for the component-embed builder. Each one is valid as
 * written (see component-embed-templates.test.ts) and uses images that
 * embeddy.link actually serves, so the live preview renders real media.
 * Only reference files tracked under site/public — og.png is git-ignored and
 * therefore 404s in production.
 */

export interface EmbedTemplate {
  id: string;
  label: string;
  /** Pretty-printed payload */
  json: string;
}

const SITE = "https://embeddy.link";

/** Pretty-print a payload object */
const pretty = (payload: unknown) => JSON.stringify(payload, null, 2);

export const EMBED_TEMPLATES: EmbedTemplate[] = [
  {
    id: "text",
    label: "Text card",
    json: pretty({
      component: {
        type: 17,
        accent_color: 0x5865f2,
        components: [
          {
            type: 10,
            content: "# Patch notes\n- Faster video trimming\n- Animated WebP export keeps transparency\n-# Updated <t:1790000000:R>",
          },
        ],
      },
    }),
  },
  {
    id: "article",
    label: "Article + button",
    json: pretty({
      component: {
        type: 17,
        accent_color: 0x7c5cff,
        components: [
          {
            type: 9,
            components: [
              { type: 10, content: `## [Embeddy](${SITE}/)\nConvert, trim and share video & animated media.` },
            ],
            accessory: { type: 11, media: { url: `${SITE}/icon-512.png` }, description: "Embeddy icon" },
          },
          { type: 12, items: [{ media: { url: `${SITE}/og-preview.webp` }, description: "Embeddy preview card" }] },
          {
            type: 1,
            components: [
              { type: 2, style: 5, label: "Open", url: `${SITE}/` },
              { type: 2, style: 5, label: "Convert", emoji: { name: "🎞️" }, url: `${SITE}/tools/convert` },
            ],
          },
        ],
      },
    }),
  },
  {
    id: "gallery",
    label: "Gallery",
    json: pretty({
      component: {
        type: 17,
        components: [
          { type: 10, content: "### Screenshots" },
          {
            type: 12,
            items: [
              { media: { url: `${SITE}/og-preview.webp` } },
              { media: { url: `${SITE}/icon-512.png` } },
              { media: { url: `${SITE}/icon-maskable-512.png` }, spoiler: true },
            ],
          },
          { type: 14, spacing: 2 },
          {
            type: 9,
            components: [{ type: 10, content: "**3 images**, one of them a spoiler.\n-# Galleries hold up to 10 items per embed." }],
            accessory: { type: 2, style: 5, label: "View all", url: `${SITE}/` },
          },
        ],
      },
    }),
  },
];
