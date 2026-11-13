import { describe, expect, test } from "bun:test";
import {
  COMPONENT_EMBED_MAX_BYTES,
  accentToHex,
  buildInlineEmbedSnippet,
  collectMediaUrls,
  hexToAccent,
  mediaKind,
  validateComponentEmbed,
} from "./component-embed";

/** The "Full Example" payload from Discord's component-embed docs */
const DISCORD_FULL_EXAMPLE = {
  component: {
    type: 17,
    spoiler: false,
    accent_color: 1752220,
    components: [
      {
        type: 9,
        components: [{ type: 10, content: "# **[New version out!](https://watchanimeattheoffice.com/posts/version2)**\nThe dungeon is deceptively tougher." }],
        accessory: { type: 2, style: 5, url: "https://watchanimeattheoffice.com/posts/version2", label: "Open" },
      },
      {
        type: 12,
        items: [
          { media: { url: "https://watchanimeattheoffice.com/photos/1.png" }, description: "The main character entering the dungeon" },
          { media: { url: "https://watchanimeattheoffice.com/photos/2.png" } },
          { media: { url: "https://watchanimeattheoffice.com/photos/3.png" } },
        ],
      },
      { type: 10, content: "*Screenshots of new content*" },
      { type: 14, spacing: 1 },
      {
        type: 1,
        components: [
          { type: 2, style: 5, url: "https://watchanimeattheoffice.com/store", label: "Store" },
          { type: 2, style: 5, url: "https://watchanimeattheoffice.com/community", label: "Community" },
          { type: 2, style: 5, url: "https://watchanimeattheoffice.com/patch-notes", label: "Patch Notes" },
        ],
      },
      { type: 14 },
      {
        type: 9,
        components: [{ type: 10, content: "## **[??????](https://watchanimeattheoffice.com/me)**\nWho could this be?" }],
        accessory: { type: 11, media: { url: "https://watchanimeattheoffice.com/new-boss.png" }, spoiler: true },
      },
    ],
  },
};

/** Wrap container children in a minimal valid payload */
const payload = (components: unknown[], extra: Record<string, unknown> = {}) =>
  JSON.stringify({ component: { type: 17, components, ...extra } });

const errors = (raw: string) => validateComponentEmbed(raw).issues.filter((i) => i.severity === "error");

describe("validateComponentEmbed — Discord's examples", () => {
  test("the documented full example is valid", () => {
    const v = validateComponentEmbed(JSON.stringify(DISCORD_FULL_EXAMPLE, null, 2));
    expect(v.issues).toEqual([]);
    expect(v.ok).toBe(true);
    // container, section(+text+button), gallery, text, separator, row(+3 buttons), separator, section(+text+thumbnail)
    expect(v.componentCount).toBe(1 + 3 + 1 + 1 + 1 + 4 + 1 + 3);
    expect(v.galleryItemCount).toBe(3);
    expect(v.root?.accent_color).toBe(1752220);
  });

  test("the documented minimal text payload is valid", () => {
    expect(validateComponentEmbed(payload([{ type: 10, content: "# Patch Notes\n- Fixed" }], { accent_color: 5793266 })).ok).toBe(true);
  });
});

describe("validateComponentEmbed — documented hard limits", () => {
  test("payload over 3,000 bytes, counting escapes at written length", () => {
    const content = "\\u003c".repeat(500);
    const raw = `{"component":{"type":17,"components":[{"type":10,"content":"${content}"}]}}`;
    const v = validateComponentEmbed(raw);
    expect(v.bytes).toBeGreaterThan(COMPONENT_EMBED_MAX_BYTES);
    expect(v.ok).toBe(false);
    expect(v.issues[0]!.message).toContain("3,000");
  });

  test("more than 40 components", () => {
    const rows = Array.from({ length: 8 }, () => ({
      type: 1,
      components: Array.from({ length: 5 }, (_, i) => ({ type: 2, style: 5, url: "https://e.com", label: `b${i}` })),
    }));
    const v = validateComponentEmbed(payload(rows));
    expect(v.componentCount).toBe(1 + 8 * 6);
    expect(v.issues.some((i) => i.message.includes("limit is 40"))).toBe(true);
  });

  test("more than 10 gallery items across the embed", () => {
    const gallery = (n: number) => ({ type: 12, items: Array.from({ length: n }, (_, i) => ({ media: { url: `https://e.com/${i}.png` } })) });
    const v = validateComponentEmbed(payload([gallery(6), gallery(5)]));
    expect(v.galleryItemCount).toBe(11);
    expect(v.issues.some((i) => i.message.includes("across the whole embed"))).toBe(true);
  });

  test("root must be a single Container; containers don't nest", () => {
    expect(errors(JSON.stringify({ component: { type: 10, content: "x" } }))[0]!.path).toBe("component.type");
    expect(errors(payload([{ type: 17, components: [] }]))[0]!.message).toContain("another Container");
    expect(errors("[1]")[0]!.message).toContain("JSON object");
    expect(errors("{nope")[0]!.message).toContain("Not valid JSON");
  });

  test("buttons: link style only, whitelisted keys only, label or emoji", () => {
    const btn = (b: Record<string, unknown>) => payload([{ type: 1, components: [{ type: 2, ...b }] }]);
    expect(errors(btn({ style: 1, custom_id: "x", label: "a" })).map((e) => e.path)).toEqual([
      "component.components[0].components[0].custom_id",
      "component.components[0].components[0].style",
      "component.components[0].components[0].url",
    ]);
    expect(errors(btn({ style: 5, url: "https://e.com" }))[0]!.message).toContain("label, an emoji");
    expect(errors(btn({ style: 5, url: "https://e.com", emoji: { name: "🔥" } }))).toEqual([]);
  });

  test("unsupported component types are rejected with their names", () => {
    expect(errors(payload([{ type: 13, file: { url: "attachment://a.png" } }]))[0]!.message).toContain("File");
    expect(errors(payload([{ type: 1, components: [{ type: 3, custom_id: "s" }] }]))[0]!.message).toContain("String Select");
    expect(errors(payload([{ type: 2, style: 5, url: "https://e.com", label: "x" }]))[0]!.message).toContain("Action Row");
  });

  test("sections need 1–3 text displays and a thumbnail/button accessory", () => {
    expect(errors(payload([{ type: 9, components: [], accessory: { type: 11, media: { url: "https://e.com/a.png" } } }]))[0]!.message).toContain("one to three");
    expect(errors(payload([{ type: 9, components: [{ type: 10, content: "x" }] }]))[0]!.message).toContain("accessory");
  });

  test("media must be http(s) and at most 2,048 chars; attachment:// is bot-only", () => {
    const thumb = (url: string) => payload([{ type: 9, components: [{ type: 10, content: "x" }], accessory: { type: 11, media: { url } } }]);
    expect(errors(thumb("attachment://a.png"))[0]!.message).toContain("bot messages");
    expect(errors(thumb("ftp://e.com/a.png"))[0]!.message).toContain("http");
    expect(errors(thumb(`https://e.com/${"a".repeat(2050)}.png`))[0]!.message).toContain("2048");
  });

  test("accent_color must be a 24-bit integer", () => {
    expect(errors(payload([], { accent_color: "#5865F2" }))[0]!.path).toBe("component.accent_color");
    expect(errors(payload([], { accent_color: 0x1000000 }))).toHaveLength(1);
    expect(errors(payload([{ type: 10, content: "x" }], { accent_color: null }))).toEqual([]);
  });
});

describe("validateComponentEmbed — warnings", () => {
  test("unknown media extensions and Discord-filled media fields warn but don't fail", () => {
    const v = validateComponentEmbed(payload([{ type: 12, items: [{ media: { url: "https://e.com/img", width: 10 } }] }]));
    expect(v.ok).toBe(true);
    expect(v.issues.map((i) => i.severity)).toEqual(["warning", "warning"]);
  });

  test("Components-reference limits (label 80, alt 1,024, separator spacing) warn", () => {
    const v = validateComponentEmbed(
      payload([
        { type: 1, components: [{ type: 2, style: 5, url: "https://e.com", label: "x".repeat(81) }] },
        { type: 14, spacing: 3 },
      ]),
    );
    expect(v.ok).toBe(true);
    expect(v.issues).toHaveLength(2);
  });
});

describe("helpers", () => {
  test("mediaKind by extension, ignoring the query string", () => {
    expect(mediaKind("https://e.com/a.AVIF?x=1")).toBe("image");
    expect(mediaKind("https://e.com/a.mov")).toBe("video");
    expect(mediaKind("https://e.com/a")).toBe("unknown");
  });

  test("accent color conversion round-trips", () => {
    expect(hexToAccent("#5865F2")).toBe(5793266);
    expect(accentToHex(5793266)).toBe("#5865f2");
    expect(hexToAccent("blue")).toBeNull();
  });

  test("collectMediaUrls finds thumbnails and gallery items", () => {
    const v = validateComponentEmbed(JSON.stringify(DISCORD_FULL_EXAMPLE));
    expect(collectMediaUrls(v.root!)).toHaveLength(4);
  });

  test("snippet builder minifies and neutralises </script>", () => {
    const snippet = buildInlineEmbedSnippet(payload([{ type: 10, content: "</script><b>" }]));
    expect(snippet!.html).toStartWith('<script id="discord:component-embed" type="application/json">{"component"');
    expect(snippet!.html).not.toContain("</script><b>");
    expect(snippet!.html.endsWith("</script>")).toBe(true);
    expect(buildInlineEmbedSnippet("{bad")).toBeNull();
  });
});
