import { describe, expect, test } from "bun:test";
import { formatDiscordTimestamp, renderDiscordMarkdown, renderInline } from "./discord-markdown";

describe("renderInline — safety", () => {
  test("escapes raw HTML instead of emitting it", () => {
    expect(renderInline('<img src=x onerror="alert(1)">')).toBe(
      "&lt;img src=x onerror=&quot;alert(1)&quot;&gt;",
    );
  });

  test("never links a non-http scheme", () => {
    const html = renderInline("[click](javascript:alert(1))");
    expect(html).not.toContain("<a");
    expect(html).toContain("[click]");
  });

  test("escapes quotes in link URLs so they can't break out of href", () => {
    const html = renderInline('[x](https://e.com/"onmouseover="a)');
    expect(html).not.toContain('"onmouseover');
  });

  test("strips NUL so placeholder tokens can't be forged", () => {
    expect(renderInline("\u00000\u0000 ok")).toBe("0 ok");
  });
});

describe("renderInline — formatting", () => {
  test("emphasis family", () => {
    expect(renderInline("**b** *i* __u__ ~~s~~ ***bi***")).toBe(
      "<strong>b</strong> <em>i</em> <u>u</u> <s>s</s> <strong><em>bi</em></strong>",
    );
    expect(renderInline("_it_ but snake_case_name")).toBe("<em>it</em> but snake_case_name");
  });

  test("spoiler and code (no markdown inside code)", () => {
    expect(renderInline("||secret||")).toBe('<span class="dmd-spoiler" tabindex="0">secret</span>');
    expect(renderInline("`**not bold** <b>`")).toBe("<code>**not bold** &lt;b&gt;</code>");
  });

  test("masked, angle-bracketed and bare links", () => {
    expect(renderInline("[**Docs**](https://e.com/a?x=1&y=2)")).toBe(
      '<a href="https://e.com/a?x=1&amp;y=2" target="_blank" rel="noopener noreferrer nofollow"><strong>Docs</strong></a>',
    );
    expect(renderInline("<https://e.com>")).toContain('href="https://e.com"');
    expect(renderInline("see https://e.com/p.")).toBe(
      'see <a href="https://e.com/p" target="_blank" rel="noopener noreferrer nofollow">https://e.com/p</a>.',
    );
  });

  test("backslash escapes keep markers literal", () => {
    expect(renderInline("\\*not italic\\*")).toBe("*not italic*");
  });

  test("timestamps render as formatted text", () => {
    const html = renderInline("<t:0:R>", 60_000);
    expect(html).toContain('class="dmd-time"');
    expect(html).not.toContain("&lt;t:");
  });
});

describe("renderDiscordMarkdown — blocks", () => {
  test("headings, subtext and paragraphs", () => {
    expect(renderDiscordMarkdown("# Big\n## Mid\n### Small\n-# tiny\nline one\nline two")).toBe(
      '<h1>Big</h1><h2>Mid</h2><h3>Small</h3><p class="dmd-subtext">tiny</p><p>line one<br>line two</p>',
    );
  });

  test("#### is not a heading (Discord supports three levels)", () => {
    expect(renderDiscordMarkdown("#### no")).toBe("<p>#### no</p>");
  });

  test("bullet, numbered and nested lists", () => {
    expect(renderDiscordMarkdown("- a\n  - b\n- c")).toBe("<ul><li>a</li><ul><li>b</li></ul><li>c</li></ul>");
    expect(renderDiscordMarkdown("3. x\n4. y")).toBe('<ol start="3"><li>x</li><li>y</li></ol>');
  });

  test("single-line and rest-of-message quotes", () => {
    expect(renderDiscordMarkdown("> q1\n> q2\nafter")).toBe("<blockquote><p>q1<br>q2</p></blockquote><p>after</p>");
    expect(renderDiscordMarkdown("intro\n>>> all\nof this")).toBe(
      "<p>intro</p><blockquote><p>all<br>of this</p></blockquote>",
    );
  });

  test("code fences keep their content literal", () => {
    expect(renderDiscordMarkdown("```js\nconst a = '<b>';\n```\nafter")).toBe(
      "<pre data-lang=\"js\"><code>const a = &#39;&lt;b&gt;&#39;;</code></pre><p>after</p>",
    );
  });
});

describe("formatDiscordTimestamp", () => {
  test("relative style counts from the injected clock", () => {
    expect(formatDiscordTimestamp(0, "R", 2 * 3_600_000)).toMatch(/2 hours ago/);
  });
  test("invalid input falls back to the number", () => {
    expect(formatDiscordTimestamp(Number.NaN, "f")).toBe("NaN");
  });
});
