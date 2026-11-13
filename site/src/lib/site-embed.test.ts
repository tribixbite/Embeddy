import { describe, expect, test } from "bun:test";
import { buildSiteEmbed, siteEmbedJson } from "./site-embed";
import { validateComponentEmbed } from "./inspect/component-embed";
import { renderInline } from "./inspect/discord-markdown";

const input = {
  title: "Embeddy — Convert, trim and share video & animated media",
  description:
    "Animated WebP/GIF converter, quick video trimmer and joiner, image compressor, OG metadata inspector, and anonymous file uploader — on Android and in your browser.",
  url: "https://embeddy.link/",
  siteUrl: "https://embeddy.link",
  imageUrl: "https://embeddy.link/og-preview.webp",
  imageAlt: "Embeddy preview",
};

describe("site component embed", () => {
  test("the landing page payload is valid with no warnings", () => {
    const v = validateComponentEmbed(siteEmbedJson(input));
    expect(v.issues).toEqual([]);
    expect(v.ok).toBe(true);
  });

  test("a worst-case page (long title, description and alt) still fits 3,000 bytes", () => {
    const v = validateComponentEmbed(
      siteEmbedJson({ ...input, title: "T".repeat(120), description: "D".repeat(400), imageAlt: "A".repeat(2000) }),
    );
    expect(v.ok).toBe(true);
    expect(v.bytes).toBeLessThanOrEqual(3_000);
  });

  test("markdown in the title can't break the masked link", () => {
    const payload = buildSiteEmbed({ ...input, title: "a](https://evil.example) [b" });
    const section = payload.component.components[0] as { components: Array<{ content: string }> };
    const html = renderInline(section.components[0]!.content.split("\n")[0]!.replace(/^## /, ""));
    expect(html).not.toContain('href="https://evil.example"');
    expect(html).toContain('href="https://embeddy.link/"');
  });

  test("tool buttons link to each tool under the site base", () => {
    const row = buildSiteEmbed(input).component.components[2] as { components: Array<{ url: string }> };
    expect(row.components.map((b) => b.url)).toEqual([
      "https://embeddy.link/tools/convert/",
      "https://embeddy.link/tools/squoosh/",
      "https://embeddy.link/tools/inspect/",
      "https://embeddy.link/tools/upload/",
    ]);
  });
});
