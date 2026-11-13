import { describe, expect, test } from "bun:test";
import {
  analyzeUnfurl,
  assessComponentEmbed,
  cacheBustUrl,
  isRfc3339,
  parseThemeColor,
  resolveDiscordUrl,
  trimForDiscord,
} from "./discord-unfurl";
import type { MetaTag, MetadataResult } from "./types";

const PAGE = "https://example.com/post";

/** Build an inspect result from [key, value] pairs in document order */
function page(pairs: Array<[string, string]>, extra: Partial<MetadataResult> = {}): MetadataResult {
  const tags: MetaTag[] = pairs.map(([property, content]) => ({
    property,
    content,
    attr: property.startsWith("og:") ? "property" : "name",
  }));
  return {
    url: PAGE,
    status: 200,
    title: "",
    tags,
    fetch: {
      requestedUrl: PAGE,
      redirects: [],
      userAgent: "discord",
      contentType: "text/html",
      bytesRead: 1000,
      readCapped: false,
      ms: 300,
    },
    componentEmbeds: [],
    jsonLd: [],
    ...extra,
  };
}

const failing = (r: ReturnType<typeof analyzeUnfurl>) => r.checks.filter((c) => c.status === "fail").map((c) => c.title);

describe("analyzeUnfurl — the docs' Quick Start", () => {
  const quickStart = page([
    ["og:site_name", "Dungeon Delvers Wiki"],
    ["og:title", "How to Get Through the Labyrinth"],
    ["og:description", "Step by step guide on how to navigate the hardest maze in the game."],
    ["og:image", "https://watchanimeattheoffice.com/maze.png"],
    ["og:image:width", "1200"],
    ["og:image:height", "630"],
    ["theme-color", "#5865F2"],
    ["twitter:card", "summary_large_image"],
  ]);

  test("resolves to a large-image card with every field", () => {
    const { preview, checks } = analyzeUnfurl(quickStart);
    expect(preview.layout).toBe("large-image");
    expect(preview.siteName).toBe("Dungeon Delvers Wiki");
    expect(preview.title?.text).toBe("How to Get Through the Labyrinth");
    expect(preview.color).toBe("#5865f2");
    expect(preview.images).toEqual([
      { url: "https://watchanimeattheoffice.com/maze.png", key: "og:image", width: 1200, height: 630 },
    ]);
    expect(checks.filter((c) => c.status === "fail" || c.status === "warn")).toEqual([]);
  });
});

describe("analyzeUnfurl — tag rules", () => {
  test("first non-empty wins for title; last wins for layout and color", () => {
    const { preview } = analyzeUnfurl(
      page([
        ["og:title", ""],
        ["twitter:title", "Twitter wins"],
        ["og:title", "Later OG"],
        ["twitter:card", "summary_large_image"],
        ["twitter:card", "summary"],
        ["theme-color", "#111111"],
        ["theme-color", "#222222cc"],
        ["og:image", "/a.png"],
      ]),
    );
    expect(preview.title?.text).toBe("Twitter wins");
    expect(preview.layout).toBe("thumbnail");
    expect(preview.color).toBe("#222222");
  });

  test("<title> + meta description only as a pair, only without OG/Twitter text", () => {
    const pair = analyzeUnfurl({ ...page([["description", "Plain"]]), title: "Doc title" });
    expect(pair.preview.title?.text).toBe("Doc title");
    expect(pair.preview.description?.text).toBe("Plain");

    const single = analyzeUnfurl({ ...page([]), title: "Only title" });
    expect(single.preview.title).toBeUndefined();
    expect(single.preview.layout).toBe("none");
  });

  test("title and description are trimmed by bytes and lines", () => {
    const { preview, checks } = analyzeUnfurl(page([["og:title", "é".repeat(40)], ["og:description", "x\n".repeat(25)]]));
    expect(preview.title?.trimmed).toBe(true);
    expect(new TextEncoder().encode(preview.title!.text).byteLength).toBeLessThanOrEqual(70);
    expect(preview.description?.text.split("\n")).toHaveLength(20);
    expect(checks.filter((c) => c.status === "warn").map((c) => c.title)).toEqual([
      "Title trimmed (80 bytes, 1 line)",
      "Description trimmed (49 bytes, 25 lines)",
    ]);
  });

  test("invalid theme-color and pubdate are flagged and dropped", () => {
    const { preview, checks } = analyzeUnfurl(
      page([["og:title", "t"], ["theme-color", "blue"], ["og:pubdate", "2026-09-15"]]),
    );
    expect(preview.color).toBeUndefined();
    expect(preview.timestamp).toBeUndefined();
    expect(checks.filter((c) => c.status === "warn")).toHaveLength(2);
  });

  test("valid pubdate becomes the timestamp; article:published_time gets a tip", () => {
    expect(analyzeUnfurl(page([["og:title", "t"], ["og:pubdate", "2026-09-15T12:00:00Z"]])).preview.timestamp).toBe(
      "2026-09-15T12:00:00.000Z",
    );
    const tip = analyzeUnfurl(page([["og:title", "t"], ["article:published_time", "2026-09-15T12:00:00Z"]]));
    expect(tip.checks.some((c) => c.title === "article:published_time isn't read")).toBe(true);
  });
});

describe("analyzeUnfurl — images", () => {
  test("two og:image tags make a gallery capped at 4; twitter:image duplicates merge", () => {
    const urls = [1, 2, 3, 4, 5].map((i) => `https://e.com/${i}.png`);
    const { preview, checks } = analyzeUnfurl(
      page([["og:title", "t"], ...urls.map((u) => ["og:image", u] as [string, string]), ["twitter:image", urls[0]!]]),
    );
    expect(preview.layout).toBe("gallery");
    expect(preview.images.map((i) => i.url)).toEqual(urls.slice(0, 4));
    expect(preview.allImages).toHaveLength(5);
    expect(checks.some((c) => c.title.includes("only 4 are shown"))).toBe(true);
  });

  test("dimensions attach to the most recent plain og:image only", () => {
    const { preview, checks } = analyzeUnfurl(
      page([
        ["og:title", "t"],
        ["og:image", "https://e.com/a.png"],
        ["og:image:secure_url", "https://e.com/b.png"],
        ["og:image:width", "100"],
        ["og:image:height", "50"],
      ]),
    );
    const a = preview.allImages.find((i) => i.url.endsWith("a.png"))!;
    const b = preview.allImages.find((i) => i.url.endsWith("b.png"))!;
    expect([a.width, a.height]).toEqual([100, 50]);
    expect(b.width).toBeUndefined();
    expect(checks.some((c) => c.title.startsWith("Image dimensions follow og:image:url"))).toBe(true);
  });

  test("relative image URLs must start with '/'", () => {
    expect(resolveDiscordUrl("/img/a.png", PAGE)).toBe("https://example.com/img/a.png");
    expect(resolveDiscordUrl("img/a.png", PAGE)).toBeNull();
    const r = analyzeUnfurl(page([["og:title", "t"], ["og:image", "img/a.png"]]));
    expect(r.preview.layout).toBe("text");
    expect(failing(r)).toEqual(["Relative image URL not read"]);
  });

  test("unsupported og:image:type fails; missing dimensions warn", () => {
    const r = analyzeUnfurl(page([["og:title", "t"], ["og:image", "https://e.com/a"], ["og:image:type", "image/svg+xml"]]));
    expect(failing(r)).toEqual(["og:image:type image/svg+xml isn't supported"]);
    expect(r.checks.some((c) => c.title === "Image has no declared width/height")).toBe(true);
  });

  test("alt text over 4,096 characters means no preview at all", () => {
    const r = analyzeUnfurl(page([["og:title", "t"], ["og:image", "https://e.com/a.png"], ["og:image:alt", "a".repeat(4097)]]));
    expect(r.preview.layout).toBe("none");
    expect(failing(r)).toContain("No preview");
  });

  test("twitter:card photo shows the image alone", () => {
    const { preview } = analyzeUnfurl(page([["og:title", "t"], ["og:description", "d"], ["og:image", "https://e.com/a.png"], ["twitter:card", "photo"]]));
    expect(preview.layout).toBe("photo");
    expect(preview.description).toBeUndefined();
  });
});

describe("analyzeUnfurl — video", () => {
  const videoTags: Array<[string, string]> = [
    ["og:type", "video.other"],
    ["og:video", "https://watchanimeattheoffice.com/videos/update.mp4"],
    ["og:video:type", "video/mp4"],
    ["og:video:width", "1280"],
    ["og:video:height", "720"],
    ["og:image", "https://watchanimeattheoffice.com/videos/still.png"],
    ["og:image:width", "1280"],
    ["og:image:height", "720"],
  ];

  test("the docs' video example plays inline", () => {
    const { preview } = analyzeUnfurl(page(videoTags));
    expect(preview.layout).toBe("video");
    expect(preview.video).toMatchObject({ type: "video/mp4", width: 1280, height: 720 });
  });

  test("video without og:image or layout tag falls back, with fixes listed", () => {
    const noImage = analyzeUnfurl(page(videoTags.filter(([k]) => !k.startsWith("og:image"))));
    expect(noImage.preview.layout).not.toBe("video");
    expect(noImage.checks.find((c) => c.title === "Video won't play inline")?.detail).toContain("og:image");

    const noLayout = analyzeUnfurl(page(videoTags.filter(([k]) => k !== "og:type")));
    expect(noLayout.preview.layout).toBe("thumbnail");
  });

  test("a top-level JSON-LD VideoObject supplies the video and thumbnail", () => {
    const { preview } = analyzeUnfurl(
      page([["og:type", "video"], ["og:title", "t"]], {
        jsonLd: [
          {
            raw: JSON.stringify({
              "@type": "VideoObject",
              embedUrl: "https://e.com/v.webm",
              thumbnailUrl: ["https://e.com/t.jpg"],
              uploadDate: "2026-01-02T03:04:05+00:00",
            }),
            bytes: 0,
            truncated: false,
          },
        ],
      }),
    );
    expect(preview.layout).toBe("video");
    expect(preview.video?.url).toBe("https://e.com/v.webm");
    expect(preview.images[0]?.url).toBe("https://e.com/t.jpg");
    expect(preview.timestamp).toBe("2026-01-02T03:04:05.000Z");
  });
});

describe("analyzeUnfurl — oEmbed", () => {
  const oembedPage = (doc: Record<string, unknown>, pairs: Array<[string, string]> = []) =>
    page(pairs, { oembed: { href: "https://e.com/oembed", count: 1, status: 200, ms: 50, raw: JSON.stringify(doc), bytes: 0, truncated: false } });

  test("author, provider override and gap-filling title/thumbnail", () => {
    const { preview } = analyzeUnfurl(
      oembedPage(
        {
          title: "OE title",
          author_name: "Anthony",
          author_url: "https://e.com/ant",
          provider_name: "Dungeon Delvers",
          provider_url: "https://e.com",
          thumbnail_url: "https://e.com/d.png",
          thumbnail_width: 1200,
          thumbnail_height: 630,
        },
        [["og:site_name", "Wiki"], ["og:description", "d"]],
      ),
    );
    expect(preview.title?.text).toBe("OE title");
    expect(preview.author).toEqual({ name: "Anthony", url: "https://e.com/ant" });
    expect(preview.siteName).toBe("Dungeon Delvers");
    expect(preview.providerUrl).toBe("https://e.com");
    expect(preview.images[0]).toMatchObject({ url: "https://e.com/d.png", width: 1200 });
  });

  test("Open Graph title beats oEmbed; a thumbnail without both sizes is ignored", () => {
    const r = analyzeUnfurl(oembedPage({ title: "OE", thumbnail_url: "https://e.com/d.png", thumbnail_width: 10 }, [["og:title", "OG"]]));
    expect(r.preview.title?.text).toBe("OG");
    expect(r.preview.images).toEqual([]);
    expect(r.checks.some((c) => c.title === "oEmbed thumbnail ignored")).toBe(true);
  });

  test("oEmbed type sets the layout only when no layout tag exists", () => {
    const img: Array<[string, string]> = [["og:title", "t"], ["og:image", "https://e.com/a.png"]];
    expect(analyzeUnfurl(oembedPage({ type: "photo" }, img)).preview.layout).toBe("photo");
    expect(analyzeUnfurl(oembedPage({ type: "photo" }, [...img, ["twitter:card", "summary_large_image"]])).preview.layout).toBe("large-image");
  });
});

describe("analyzeUnfurl — fetch rules and direct media", () => {
  test("Content-Disposition attachment means no preview", () => {
    const base = page([["og:title", "t"]]);
    const r = analyzeUnfurl({ ...base, fetch: { ...base.fetch!, contentDisposition: "attachment; filename=a.html" } });
    expect(r.preview.layout).toBe("none");
    expect(failing(r)).toContain("Served as a download");
  });

  test("a direct image link previews the image itself", () => {
    const r = analyzeUnfurl({ ...page([]), url: "https://e.com/a.png", direct: { kind: "image" } });
    expect(r.preview.layout).toBe("direct-image");
    expect(r.preview.images[0]?.url).toBe("https://e.com/a.png");
  });
});

describe("analyzeUnfurl — component embeds", () => {
  const valid = '{"component":{"type":17,"components":[{"type":10,"content":"hi"}]}}';

  test("a valid inline payload in <head> becomes the active embed", () => {
    const r = analyzeUnfurl(
      page([["og:title", "t"], ["og:description", "d"], ["og:image", "https://e.com/a.png"]], {
        componentEmbeds: [{ source: "inline", type: "application/json", inHead: true, raw: valid, bytes: 70, truncated: false }],
      }),
    );
    expect(r.activeComponentEmbed?.usable).toBe(true);
    expect(r.checks.find((c) => c.title === "Open Graph fallback present")?.status).toBe("pass");
  });

  test("element rules: <head> placement, type attribute, same-site link", () => {
    expect(assessComponentEmbed({ source: "inline", type: "application/json", inHead: false, raw: valid, bytes: 1, truncated: false }).elementErrors).toEqual([
      "The script must be inside <head>.",
    ]);
    expect(assessComponentEmbed({ source: "inline", type: "text/plain", inHead: true, raw: valid, bytes: 1, truncated: false }).usable).toBe(false);
    expect(
      assessComponentEmbed({ source: "inline", type: "application/vnd.discord.component-embed+json", inHead: true, raw: valid, bytes: 1, truncated: false }).usable,
    ).toBe(true);
    expect(assessComponentEmbed({ source: "linked", type: "application/json", href: "https://evil.net/e.json", hostAllowed: false, bytes: 0, truncated: false }).usable).toBe(false);
  });

  test("an invalid payload is reported and the OG preview stays active", () => {
    const r = analyzeUnfurl(
      page([["og:title", "t"]], {
        componentEmbeds: [{ source: "inline", type: "application/json", inHead: true, raw: '{"component":{"type":10}}', bytes: 24, truncated: false }],
      }),
    );
    expect(r.activeComponentEmbed).toBeUndefined();
    expect(r.preview.layout).toBe("text");
    expect(r.checks.find((c) => c.title === "Open Graph fallback incomplete")?.status).toBe("warn");
  });
});

describe("helpers", () => {
  test("trimForDiscord never splits a code point", () => {
    const t = trimForDiscord("😀".repeat(30), 70, 3);
    expect(t.trimmed).toBe(true);
    expect(t.text.endsWith("…")).toBe(true);
    expect([...t.text.slice(0, -1)].every((c) => c === "😀")).toBe(true);
  });

  test("isRfc3339 requires time and offset", () => {
    expect(isRfc3339("2026-09-15T12:00:00Z")).toBe(true);
    expect(isRfc3339("2026-09-15T12:00:00.5+02:00")).toBe(true);
    expect(isRfc3339("2026-09-15T12:00:00")).toBe(false);
    expect(isRfc3339("2026-09-15")).toBe(false);
  });

  test("parseThemeColor accepts 6/8-digit hex only", () => {
    expect(parseThemeColor("#ABCDEF")).toBe("#abcdef");
    expect(parseThemeColor("#abcdef80")).toBe("#abcdef");
    expect(parseThemeColor("#abc")).toBeNull();
    expect(parseThemeColor("rgb(0,0,0)")).toBeNull();
  });

  test("cacheBustUrl sets v= and keeps other params", () => {
    expect(cacheBustUrl("https://e.com/p?a=1", 35)).toBe("https://e.com/p?a=1&v=z");
  });
});

describe("analyzeUnfurl — player pages", () => {
  test("an og:video of type text/html is a warning, not a failure", () => {
    const r = analyzeUnfurl(
      page([
        ["og:type", "video.other"],
        ["og:title", "t"],
        ["og:image", "https://e.com/a.jpg"],
        ["og:video:url", "https://www.youtube.com/embed/x"],
        ["og:video:type", "text/html"],
      ]),
    );
    expect(failing(r)).toEqual([]);
    expect(r.checks.find((c) => c.title === "og:video is a player page, not a video file")?.status).toBe("warn");
    expect(r.preview.layout).toBe("thumbnail");
  });
});
