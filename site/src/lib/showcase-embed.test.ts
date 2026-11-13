import { describe, expect, test } from "bun:test";
import { existsSync, statSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { buildShowcaseEmbed, SHOWCASE_MEDIA, SHOWCASE_THUMB, showcaseEmbedJson } from "./showcase-embed";
import {
  collectMediaUrls,
  COMPONENT_EMBED_MAX_BYTES,
  COMPONENT_EMBED_MAX_COMPONENTS,
  COMPONENT_EMBED_MAX_GALLERY_ITEMS,
  validateComponentEmbed,
} from "./inspect/component-embed";
import { renderDiscordMarkdown } from "./inspect/discord-markdown";

const input = { siteUrl: "https://embeddy.link/", builtAt: 1_794_600_000 };
const publicDir = fileURLToPath(new URL("../../public/", import.meta.url));

describe("showcase component embed", () => {
  test("sits exactly at every limit and is valid with no warnings", () => {
    const v = validateComponentEmbed(showcaseEmbedJson(input));
    expect(v.issues).toEqual([]);
    expect(v.ok).toBe(true);
    expect(v.bytes).toBe(COMPONENT_EMBED_MAX_BYTES);
    expect(v.componentCount).toBe(COMPONENT_EMBED_MAX_COMPONENTS);
    expect(v.galleryItemCount).toBe(COMPONENT_EMBED_MAX_GALLERY_ITEMS);
  });

  test("any 10-digit build time keeps the payload the same size", () => {
    for (const builtAt of [1_000_000_000, 9_999_999_999]) {
      expect(validateComponentEmbed(showcaseEmbedJson({ ...input, builtAt })).bytes).toBe(COMPONENT_EMBED_MAX_BYTES);
    }
  });

  test("uses every component type a component embed allows", () => {
    const types = new Set<number>();
    const visit = (node: unknown) => {
      if (typeof node !== "object" || node === null) return;
      const n = node as { type?: number; components?: unknown[]; accessory?: unknown };
      if (typeof n.type === "number") types.add(n.type);
      n.components?.forEach(visit);
      if (n.accessory) visit(n.accessory);
    };
    visit(buildShowcaseEmbed(input).component);
    expect([...types].sort((a, b) => a - b)).toEqual([1, 2, 9, 10, 11, 12, 14, 17]);
  });

  test("covers spoilered media, a disabled button and an emoji-only button", () => {
    const json = showcaseEmbedJson(input);
    expect(json).toContain('"spoiler":true');
    expect(json).toContain('"disabled":true');
    const rows = buildShowcaseEmbed(input).component.components.filter((c) => c.type === 1);
    expect(rows.some((r) => r.type === 1 && r.components.some((b) => !b.label && b.emoji))).toBe(true);
  });

  test("every media URL points at a generated file under public/s", () => {
    const urls = collectMediaUrls(buildShowcaseEmbed(input).component);
    expect(urls).toHaveLength(SHOWCASE_MEDIA.length + 2);
    for (const url of urls) {
      const path = new URL(url).pathname;
      expect(path.startsWith("/s/")).toBe(true);
      const file = `${publicDir}${path.slice(1)}`;
      expect(existsSync(file)).toBe(true);
      // Generated media, not an empty placeholder
      expect(statSync(file).size).toBeGreaterThan(10_000);
    }
    expect(urls).toContain(`https://embeddy.link/s/${SHOWCASE_THUMB}`);
  });

  test("the ANSI block renders in colour and the timestamp is live", () => {
    const texts = JSON.parse(showcaseEmbedJson(input)).component.components.filter((c: { type: number }) => c.type === 10);
    const ansi = texts.find((t: { content: string }) => t.content.startsWith("```ansi"));
    expect(renderDiscordMarkdown(ansi.content)).toContain('<span style="color:#d33682;font-weight:700">3000</span>');
    expect(showcaseEmbedJson(input)).toContain(`<t:${input.builtAt}:R>`);
  });
});
