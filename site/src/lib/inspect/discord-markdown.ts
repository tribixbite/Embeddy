/**
 * Discord-flavoured markdown → HTML, for previewing Text Display components
 * and `og:type=rich` descriptions the way Discord renders them.
 *
 * Safety model: the source is HTML-escaped before any markup is produced, so
 * the only tags in the output are the ones this module emits, and links are
 * only ever created for http(s) URLs. The result is safe for {@html}.
 *
 * Supported (Discord's message-formatting subset):
 *   blocks — `# / ## / ###` headings, `-# ` subtext, `> ` and `>>> ` quotes,
 *            `- ` / `* ` / `1. ` lists (indent = nesting), ``` code fences
 *            (```ansi blocks get Discord's colour palette)
 *   inline — ***bold italic***, **bold**, __underline__, *italic*, _italic_,
 *            ~~strike~~, ||spoiler||, `code`, [masked](https://links),
 *            bare / <angle-bracketed> URLs, <t:unix:style> timestamps,
 *            backslash escapes
 */

/** Characters a backslash can escape in Discord markdown */
const ESCAPABLE = /\\([*_~`|\\>#[\]()<:-])/g;

/** Escape the five HTML-significant characters */
function escapeHtml(text: string): string {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

/** Render an http(s) URL (already HTML-escaped) as a link, or return null */
function linkHtml(escapedUrl: string, innerHtml: string): string | null {
  // Escaping never touches ":" or "/", so the scheme check is still exact
  if (!/^https?:\/\//i.test(escapedUrl)) return null;
  return `<a href="${escapedUrl}" target="_blank" rel="noopener noreferrer nofollow">${innerHtml}</a>`;
}

/**
 * Format a Discord `<t:unix:style>` timestamp. Styles follow Discord:
 * t short time, T long time, d short date, D long date, f (default) short
 * date+time, F long date+time, R relative.
 */
export function formatDiscordTimestamp(unixSeconds: number, style = "f", now = Date.now()): string {
  const date = new Date(unixSeconds * 1000);
  if (Number.isNaN(date.getTime())) return String(unixSeconds);
  const fmt = (opts: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat(undefined, opts).format(date);
  switch (style) {
    case "t":
      return fmt({ hour: "numeric", minute: "2-digit" });
    case "T":
      return fmt({ hour: "numeric", minute: "2-digit", second: "2-digit" });
    case "d":
      return fmt({ year: "numeric", month: "2-digit", day: "2-digit" });
    case "D":
      return fmt({ year: "numeric", month: "long", day: "numeric" });
    case "F":
      return fmt({ weekday: "long", year: "numeric", month: "long", day: "numeric", hour: "numeric", minute: "2-digit" });
    case "R": {
      const diffSec = Math.round((date.getTime() - now) / 1000);
      const abs = Math.abs(diffSec);
      const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });
      const units: Array<[Intl.RelativeTimeFormatUnit, number]> = [
        ["year", 31_536_000],
        ["month", 2_592_000],
        ["day", 86_400],
        ["hour", 3_600],
        ["minute", 60],
      ];
      for (const [unit, seconds] of units) {
        if (abs >= seconds) return rtf.format(Math.round(diffSec / seconds), unit);
      }
      return rtf.format(diffSec, "second");
    }
    default:
      return fmt({ year: "numeric", month: "long", day: "numeric", hour: "numeric", minute: "2-digit" });
  }
}

/** Placeholder store: rendered fragments are swapped out so later passes can't touch them */
class Slots {
  private items: string[] = [];
  /** Store a fragment and return its placeholder token */
  put(html: string): string {
    this.items.push(html);
    return `\u0000${this.items.length - 1}\u0000`;
  }
  /** Replace every placeholder (repeatedly, since fragments can nest) */
  restore(text: string): string {
    let out = text;
    for (let pass = 0; pass < 4 && out.includes("\u0000"); pass++) {
      out = out.replace(/\u0000(\d+)\u0000/g, (_, i: string) => this.items[Number(i)] ?? "");
    }
    return out;
  }
}

/** Bold/italic/underline/strike/spoiler on already-escaped text */
function emphasis(text: string): string {
  return text
    .replace(/\*\*\*(?=\S)([\s\S]*?\S)\*\*\*/g, "<strong><em>$1</em></strong>")
    .replace(/\*\*(?=\S)([\s\S]*?\S)\*\*/g, "<strong>$1</strong>")
    .replace(/__(?=\S)([\s\S]*?\S)__/g, "<u>$1</u>")
    .replace(/\*(?=\S)([^*]*?\S)\*/g, "<em>$1</em>")
    // `_x_` only at word boundaries, so snake_case_names stay intact
    .replace(/(^|[^\w])_(?=\S)([^_]*?\S)_(?!\w)/g, "$1<em>$2</em>")
    .replace(/~~(?=\S)([\s\S]*?\S)~~/g, "<s>$1</s>")
    .replace(/\|\|(?=\S)([\s\S]*?\S)\|\|/g, '<span class="dmd-spoiler" tabindex="0">$1</span>');
}

/** Render one line of inline markdown (raw text in, safe HTML out) */
export function renderInline(raw: string, now = Date.now()): string {
  const slots = new Slots();
  let text = raw.replace(/\u0000/g, "");

  // 1. Code spans first — nothing inside them is markdown
  text = text.replace(/``([^`]+?)``|`([^`\n]+?)`/g, (_, a: string | undefined, b: string | undefined) =>
    slots.put(`<code>${escapeHtml(a ?? b ?? "")}</code>`),
  );
  // 2. Backslash escapes
  text = text.replace(ESCAPABLE, (_, ch: string) => slots.put(escapeHtml(ch)));
  // 3. Escape the rest; markup below is generated on escaped text only
  text = escapeHtml(text);
  // 4. Timestamps: <t:1700000000:R>
  text = text.replace(/&lt;t:(-?\d{1,13})(?::([tTdDfFR]))?&gt;/g, (_, sec: string, style: string | undefined) =>
    slots.put(`<span class="dmd-time">${escapeHtml(formatDiscordTimestamp(Number(sec), style ?? "f", now))}</span>`),
  );
  // 5. Masked links: [text](https://url) or [text](<https://url>)
  text = text.replace(
    /\[([^\]\n]+)\]\(\s*(?:&lt;)?(https?:\/\/[^\s)]+?)(?:&gt;)?\s*\)/gi,
    (whole: string, label: string, url: string) => {
      const html = linkHtml(url, emphasis(label));
      return html ? slots.put(html) : whole;
    },
  );
  // 6. Angle-bracketed URLs (<https://…> suppresses Discord's own unfurl)
  text = text.replace(/&lt;(https?:\/\/[^\s]+?)&gt;/gi, (whole: string, url: string) => {
    const html = linkHtml(url, url);
    return html ? slots.put(html) : whole;
  });
  // 7. Bare URLs, minus trailing punctuation
  text = text.replace(/https?:\/\/[^\s<]+[^\s<.,:;!?)\]'"*_~|]/gi, (url: string) => {
    const html = linkHtml(url, url);
    return html ? slots.put(html) : url;
  });
  // 8. Emphasis, then put every slot back
  return slots.restore(emphasis(text));
}

/** A parsed list item */
interface ListItem {
  ordered: boolean;
  depth: number;
  start: number;
  html: string;
}

/** Render a run of list items, nesting by indent depth */
function renderList(items: ListItem[]): string {
  let html = "";
  const stack: Array<{ ordered: boolean; depth: number }> = [];
  for (const item of items) {
    while (stack.length && stack[stack.length - 1]!.depth > item.depth) {
      html += stack.pop()!.ordered ? "</ol>" : "</ul>";
    }
    const top = stack[stack.length - 1];
    if (!top || top.depth < item.depth || top.ordered !== item.ordered) {
      if (top && top.depth === item.depth) html += stack.pop()!.ordered ? "</ol>" : "</ul>";
      html += item.ordered ? (item.start !== 1 ? `<ol start="${item.start}">` : "<ol>") : "<ul>";
      stack.push({ ordered: item.ordered, depth: item.depth });
    }
    html += `<li>${item.html}</li>`;
  }
  while (stack.length) html += stack.pop()!.ordered ? "</ol>" : "</ul>";
  return html;
}

/** Render non-code markdown lines into block HTML */
function renderBlocks(lines: string[], now: number): string {
  let html = "";
  let para: string[] = [];
  let list: ListItem[] = [];
  let quote: string[] = [];

  const flushPara = () => {
    if (para.length) html += `<p>${para.join("<br>")}</p>`;
    para = [];
  };
  const flushList = () => {
    if (list.length) html += renderList(list);
    list = [];
  };
  const flushQuote = () => {
    if (quote.length) html += `<blockquote>${renderBlocks(quote, now)}</blockquote>`;
    quote = [];
  };
  const flushAll = () => {
    flushPara();
    flushList();
    flushQuote();
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]!;

    // `>>> ` quotes everything to the end of the message
    const multi = /^>>> ?(.*)$/.exec(line);
    if (multi) {
      flushAll();
      html += `<blockquote>${renderBlocks([multi[1]!, ...lines.slice(i + 1)], now)}</blockquote>`;
      return html;
    }
    const q = /^> ?(.*)$/.exec(line);
    if (q && (line.startsWith("> ") || line === ">")) {
      flushPara();
      flushList();
      quote.push(q[1]!);
      continue;
    }
    flushQuote();

    const heading = /^(#{1,3}) +(.+)$/.exec(line);
    if (heading) {
      flushAll();
      const level = heading[1]!.length;
      html += `<h${level}>${renderInline(heading[2]!, now)}</h${level}>`;
      continue;
    }
    const sub = /^-# +(.+)$/.exec(line);
    if (sub) {
      flushAll();
      html += `<p class="dmd-subtext">${renderInline(sub[1]!, now)}</p>`;
      continue;
    }
    // TODO: Discord mobile (2026-11-14) drew "1. a\n2. b\n - c" as an ordered list 1–3: a bullet
    // indented one space under ordered items continued the numbering instead of nesting. The
    // exact indent rule (and whether desktop agrees) is unconfirmed, so it is not modelled yet.
    const bullet = /^( *)[-*] +(.+)$/.exec(line);
    const numbered = /^( *)(\d{1,9})\. +(.+)$/.exec(line);
    if (bullet || numbered) {
      flushPara();
      const indent = (bullet ?? numbered)![1]!.length;
      list.push({
        ordered: !!numbered,
        depth: Math.floor(indent / 2),
        start: numbered ? Number(numbered[2]) : 1,
        html: renderInline(bullet ? bullet[2]! : numbered![3]!, now),
      });
      continue;
    }
    flushList();
    if (line.trim() === "") {
      flushPara();
      continue;
    }
    para.push(renderInline(line, now));
  }
  flushAll();
  return html;
}

/**
 * Discord's ```ansi palette (Solarized-based), indexed by SGR code. Discord
 * honours only these: 0 reset, 1 bold, 4 underline, 30–37 foreground and
 * 40–47 background; anything else is ignored.
 */
export const ANSI_FOREGROUND: Record<number, string> = {
  30: "#4f545c",
  31: "#dc322f",
  32: "#859900",
  33: "#b58900",
  34: "#268bd2",
  35: "#d33682",
  36: "#2aa198",
  37: "#ffffff",
};
export const ANSI_BACKGROUND: Record<number, string> = {
  40: "#002b36",
  41: "#cb4b16",
  42: "#586e75",
  43: "#657b83",
  44: "#839496",
  45: "#6c71c4",
  46: "#93a1a1",
  47: "#fdf6e3",
};

/**
 * Render the body of an ```ansi block: SGR escapes (`ESC[1;35m`) become
 * styled spans and are removed from the text. Text is HTML-escaped and
 * styles only ever come from the fixed palettes above, so the output stays
 * safe for {@html}.
 */
export function renderAnsi(code: string): string {
  let bold = false;
  let underline = false;
  let fg: string | undefined;
  let bg: string | undefined;
  let out = "";
  const sgr = /\u001b\[([\d;]*)m/g;
  let last = 0;
  const emit = (text: string) => {
    if (!text) return;
    const style = [
      fg ? `color:${fg}` : "",
      bg ? `background-color:${bg}` : "",
      bold ? "font-weight:700" : "",
      underline ? "text-decoration:underline" : "",
    ].filter(Boolean);
    const escaped = escapeHtml(text);
    out += style.length ? `<span style="${style.join(";")}">${escaped}</span>` : escaped;
  };
  for (let m = sgr.exec(code); m; m = sgr.exec(code)) {
    emit(code.slice(last, m.index));
    last = m.index + m[0].length;
    // An empty parameter list means reset, as in ESC[m
    for (const part of (m[1] || "0").split(";")) {
      const n = Number(part || "0");
      if (n === 0) {
        bold = underline = false;
        fg = bg = undefined;
      } else if (n === 1) bold = true;
      else if (n === 4) underline = true;
      else if (ANSI_FOREGROUND[n]) fg = ANSI_FOREGROUND[n];
      else if (ANSI_BACKGROUND[n]) bg = ANSI_BACKGROUND[n];
    }
  }
  emit(code.slice(last));
  // A stray ESC without a complete sequence is not printed by Discord either
  return out.replace(/\u001b/g, "");
}

/**
 * Render Discord markdown to safe HTML.
 * @param src Raw markdown as Discord would receive it
 * @param now Clock for relative timestamps (injectable for tests)
 */
export function renderDiscordMarkdown(src: string, now = Date.now()): string {
  const text = src.replace(/\r\n?/g, "\n").replace(/\u0000/g, "");
  let html = "";
  let last = 0;
  const fence = /```([\w+-]*)\n?([\s\S]*?)```/g;
  for (let m = fence.exec(text); m; m = fence.exec(text)) {
    html += renderBlocks(text.slice(last, m.index).replace(/\n$/, "").split("\n"), now);
    const lang = m[1] ? ` data-lang="${escapeHtml(m[1])}"` : "";
    const code = m[2]!.replace(/\n$/, "");
    html += `<pre${lang}><code>${m[1]?.toLowerCase() === "ansi" ? renderAnsi(code) : escapeHtml(code)}</code></pre>`;
    last = m.index + m[0].length;
  }
  const tail = text.slice(last).replace(/^\n/, "");
  if (tail || !html) html += renderBlocks(tail.split("\n"), now);
  return html;
}
