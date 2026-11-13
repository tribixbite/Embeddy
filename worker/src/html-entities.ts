/**
 * HTML character-reference decoding for values read through HTMLRewriter.
 *
 * lol-html (the engine behind HTMLRewriter) hands back attribute values and
 * text chunks exactly as written, so `content="Tom &amp; Jerry"` arrives with
 * the entity intact. Browsers and Discord's unfurler decode these, so the
 * inspector must too — otherwise titles show "&amp;" and URLs such as an
 * oEmbed href with "&amp;url=" point at the wrong resource.
 *
 * Numeric references (&#39; &#x27;) are decoded in full. Named references
 * cover the set that occurs in real-world meta tags (markup, quotes, dashes,
 * spaces, Latin-1 symbols and letters); an unrecognised name is left as
 * written, which is also what a browser shows for an unknown entity.
 * TODO: vendor the full WHATWG named-reference table (2,231 names) if pages
 * using rarer names (e.g. &NotNestedGreaterGreater;) ever matter here.
 */

/** Named references → code points */
const NAMED: Record<string, string> = {
  amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " ",
  ensp: " ", emsp: " ", thinsp: " ", zwnj: "‌", zwj: "‍", lrm: "‎", rlm: "‏",
  ndash: "–", mdash: "—", hellip: "…", bull: "•", middot: "·", prime: "′", Prime: "″",
  lsquo: "‘", rsquo: "’", sbquo: "‚", ldquo: "“", rdquo: "”", bdquo: "„",
  laquo: "«", raquo: "»", lsaquo: "‹", rsaquo: "›",
  copy: "©", reg: "®", trade: "™", deg: "°", plusmn: "±", times: "×", divide: "÷",
  frac12: "½", frac14: "¼", frac34: "¾", sup1: "¹", sup2: "²", sup3: "³",
  micro: "µ", para: "¶", sect: "§", cent: "¢", pound: "£", yen: "¥", euro: "€", curren: "¤",
  iexcl: "¡", iquest: "¿", ordf: "ª", ordm: "º", not: "¬", shy: "­", macr: "¯",
  acute: "´", cedil: "¸", uml: "¨", brvbar: "¦", larr: "←", rarr: "→", uarr: "↑",
  darr: "↓", harr: "↔", hearts: "♥", spades: "♠", clubs: "♣", diams: "♦", check: "✓",
  star: "☆", starf: "★", infin: "∞", ne: "≠", le: "≤", ge: "≥", minus: "−",
  Agrave: "À", Aacute: "Á", Acirc: "Â", Atilde: "Ã", Auml: "Ä", Aring: "Å", AElig: "Æ",
  Ccedil: "Ç", Egrave: "È", Eacute: "É", Ecirc: "Ê", Euml: "Ë", Igrave: "Ì", Iacute: "Í",
  Icirc: "Î", Iuml: "Ï", ETH: "Ð", Ntilde: "Ñ", Ograve: "Ò", Oacute: "Ó", Ocirc: "Ô",
  Otilde: "Õ", Ouml: "Ö", Oslash: "Ø", Ugrave: "Ù", Uacute: "Ú", Ucirc: "Û", Uuml: "Ü",
  Yacute: "Ý", THORN: "Þ", szlig: "ß",
  agrave: "à", aacute: "á", acirc: "â", atilde: "ã", auml: "ä", aring: "å", aelig: "æ",
  ccedil: "ç", egrave: "è", eacute: "é", ecirc: "ê", euml: "ë", igrave: "ì", iacute: "í",
  icirc: "î", iuml: "ï", eth: "ð", ntilde: "ñ", ograve: "ò", oacute: "ó", ocirc: "ô",
  otilde: "õ", ouml: "ö", oslash: "ø", ugrave: "ù", uacute: "ú", ucirc: "û", uuml: "ü",
  yacute: "ý", thorn: "þ", yuml: "ÿ", OElig: "Œ", oelig: "œ", Scaron: "Š", scaron: "š",
  Yuml: "Ÿ", fnof: "ƒ", circ: "ˆ", tilde: "˜",
};

/** Code points the HTML spec remaps or forbids in numeric references */
function fromCodePoint(cp: number): string {
  if (cp === 0 || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff)) return "�";
  return String.fromCodePoint(cp);
}

/**
 * Decode HTML character references in an attribute value or text chunk.
 * `&name;`, `&#123;` and `&#x7b;` forms; the trailing semicolon is required
 * for named references (the legacy no-semicolon forms are attribute-unsafe).
 */
export function decodeHtmlEntities(value: string): string {
  if (!value.includes("&")) return value;
  return value.replace(/&(?:#(\d{1,7})|#[xX]([0-9a-fA-F]{1,6})|([A-Za-z][A-Za-z0-9]{1,31}));/g, (whole, dec, hex, name) => {
    if (dec !== undefined) return fromCodePoint(Number.parseInt(dec, 10));
    if (hex !== undefined) return fromCodePoint(Number.parseInt(hex, 16));
    return NAMED[name as string] ?? whole;
  });
}
