/**
 * SSRF guards for the inspect proxy.
 *
 * The Worker fetches arbitrary user-supplied URLs, so it must never be usable as
 * a probe into private address space. Kept in its own module so the rules can be
 * unit-tested without instantiating the Hono app.
 */

/** Redirect hops to follow while revalidating each destination. */
export const MAX_REDIRECTS = 5;

/**
 * Thrown when a request is refused by SSRF policy (blocked redirect target or
 * a hostname resolving into private address space) — as opposed to an upstream
 * transport failure. Lets the route map policy refusals to 403 instead of 502.
 */
export class BlockedRequestError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "BlockedRequestError";
  }
}

/** Decimal-dotted IPv4 literal, e.g. 10.0.0.1 */
const IPV4_RE = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/;

/** Hostnames that always resolve to the local machine or a link-local service. */
const BLOCKED_HOSTNAMES = new Set([
  "localhost",
  "localhost.localdomain",
  "metadata.google.internal",
]);

/** Suffixes reserved for internal/private naming schemes. */
const BLOCKED_SUFFIXES = [".local", ".internal", ".localhost", ".home.arpa"];

/** True when an IPv4 literal falls in a private, loopback or otherwise reserved range. */
function isPrivateIpv4(a: number, b: number): boolean {
  return (
    a === 0 ||                            // "this network"
    a === 10 ||                           // RFC1918
    a === 127 ||                          // loopback
    (a === 100 && b >= 64 && b <= 127) || // RFC6598 CGNAT
    (a === 169 && b === 254) ||           // link-local (incl. cloud metadata)
    (a === 172 && b >= 16 && b <= 31) ||  // RFC1918
    (a === 192 && b === 168) ||           // RFC1918
    (a === 192 && b === 0) ||             // IETF protocol assignments
    a >= 224                              // multicast + reserved + broadcast
  );
}

/**
 * Extract the leading two octets of an IPv4 address embedded in the low 32 bits
 * of an IPv6 literal. Accepts both the dotted spelling (`127.0.0.1`) and the
 * canonical hex-group spelling WHATWG URL serializes (`7f00:1`).
 * Returns null when the tail is not a valid embedded IPv4 address.
 */
function embeddedIpv4Octets(tail: string): [number, number] | null {
  const dotted = tail.match(IPV4_RE);
  if (dotted) {
    const octets = dotted.slice(1).map(Number);
    if (octets.some((n) => Number.isNaN(n) || n > 255)) return null;
    return [octets[0]!, octets[1]!];
  }
  // One or two hex groups: "7f00:1" (127.0.0.1) or just "1" (0.0.0.1)
  const hex = tail.match(/^(?:([0-9a-f]{1,4}):)?([0-9a-f]{1,4})$/);
  if (hex) {
    const high = parseInt(hex[1] ?? "0", 16);
    return [high >> 8, high & 0xff];
  }
  return null;
}

/** True when an IPv6 literal is loopback, unspecified, unique-local, link-local,
 * site-local, or embeds a private IPv4 address (IPv4-mapped or NAT64). */
function isPrivateIpv6(host: string): boolean {
  const addr = host.replace(/^\[|\]$/g, "").toLowerCase();
  if (addr === "::1" || addr === "::") return true;
  // IPv4-mapped (::ffff:0:0/96) and NAT64 (64:ff9b::/96) carry a v4 address in
  // the low 32 bits — validate it with the IPv4 rules. WHATWG URL canonicalizes
  // to hex groups (::ffff:7f00:1), so both spellings must be handled.
  const embedded = addr.match(/^(?:::ffff:|64:ff9b::)(.+)$/);
  if (embedded) {
    const octets = embeddedIpv4Octets(embedded[1]!);
    // An unparseable tail is not a routable public address — reject it.
    if (!octets) return true;
    return isPrivateIpv4(octets[0], octets[1]);
  }
  // fc00::/7 unique-local, fe80::/10 link-local, fec0::/10 deprecated site-local
  return /^f[cd]/.test(addr) || /^fe[89a-f]/.test(addr);
}

/** Reject hostnames that point at private, loopback or reserved address space. */
export function isAllowedHostname(rawHost: string): boolean {
  // DNS treats "localhost." as identical to "localhost" — a trailing dot must
  // not evade the blocklist or the suffix checks.
  const hostname = rawHost.toLowerCase().replace(/\.$/, "");
  if (!hostname) return false;
  if (BLOCKED_HOSTNAMES.has(hostname)) return false;
  if (BLOCKED_SUFFIXES.some((suffix) => hostname.endsWith(suffix))) return false;

  if (hostname.includes(":") || hostname.startsWith("[")) {
    return !isPrivateIpv6(hostname);
  }

  const ipMatch = hostname.match(IPV4_RE);
  if (ipMatch) {
    const octets = ipMatch.slice(1).map(Number);
    if (octets.some((n) => Number.isNaN(n) || n > 255)) return false;
    return !isPrivateIpv4(octets[0]!, octets[1]!);
  }

  // Bare integers and 0x/0-prefixed forms are alternate IPv4 encodings
  // (http://2130706433/ is 127.0.0.1). Reject rather than try to decode them.
  if (/^(0x[0-9a-f]+|\d+)$/.test(hostname)) return false;

  return true;
}

/** Block requests to private/reserved IP ranges and non-HTTP schemes. */
export function isAllowedUrl(urlStr: string): boolean {
  let url: URL;
  try {
    url = new URL(urlStr);
  } catch {
    return false;
  }

  // Only allow http/https schemes
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    return false;
  }

  // Credentials in the URL are a classic filter-bypass trick (http://evil@127.0.0.1)
  if (url.username || url.password) {
    return false;
  }

  return isAllowedHostname(url.hostname);
}

// ── DNS pre-resolution ──────────────────────────────────────────────────────

/** DoH resolver queried before fetching non-literal hostnames. */
const DOH_ENDPOINT = "https://cloudflare-dns.com/dns-query";

/** DNS record types that carry addresses. */
const DNS_TYPE_A = 1;
const DNS_TYPE_AAAA = 28;

/** Shape of an answer record in a DoH `application/dns-json` response. */
export interface DohRecord {
  type: number;
  data: string;
}

/**
 * True when every A/AAAA record in a DoH answer section points at public
 * address space. Non-address records (CNAME etc.) are ignored; an empty or
 * absent answer section is allowed — the actual fetch will fail on its own.
 */
export function areDnsAnswersAllowed(
  answers: readonly DohRecord[] | undefined,
): boolean {
  if (!answers) return true;
  return answers.every((record) => {
    if (record.type !== DNS_TYPE_A && record.type !== DNS_TYPE_AAAA) return true;
    // isAllowedHostname handles both dotted IPv4 and IPv6 literal forms
    return isAllowedHostname(record.data);
  });
}

/**
 * Resolve a hostname over DoH and check that no A/AAAA answer lands in private
 * address space. Closes the lexical gap where a public NAME (e.g. localtest.me)
 * resolves to 127.0.0.1.
 *
 * Limitations, by design:
 *  - The subsequent fetch() resolves independently, so a 0-TTL rebinding race
 *    remains theoretically possible; Cloudflare's egress filtering is the
 *    backstop for that window.
 *  - Fails OPEN on DoH transport errors/outages so a resolver hiccup does not
 *    take the inspect tool down — this is a hardening layer, not the only one.
 */
async function resolvesToPublicAddresses(hostname: string): Promise<boolean> {
  // IP literals were already fully validated lexically — no DNS involved.
  if (hostname.startsWith("[") || hostname.includes(":") || IPV4_RE.test(hostname)) {
    return true;
  }

  const results = await Promise.all(
    (["A", "AAAA"] as const).map(async (type) => {
      try {
        const res = await fetch(
          `${DOH_ENDPOINT}?name=${encodeURIComponent(hostname)}&type=${type}`,
          { headers: { accept: "application/dns-json" } },
        );
        if (!res.ok) return true; // fail open on resolver errors
        const json = (await res.json()) as { Answer?: DohRecord[] };
        return areDnsAnswersAllowed(json.Answer);
      } catch {
        return true; // fail open on transport errors
      }
    }),
  );

  return results.every(Boolean);
}

// ── Fetch with revalidated redirects ────────────────────────────────────────

/**
 * Fetch a URL, following redirects manually so every hop is re-checked —
 * lexically, and against the addresses its hostname actually resolves to.
 *
 * With `redirect: "follow"` an allowed public URL can 302 straight to
 * `http://169.254.169.254/` and the initial isAllowedUrl() check buys nothing.
 *
 * @throws {BlockedRequestError} when a hop is refused by SSRF policy
 */
export async function safeFetch(
  startUrl: string,
  init: RequestInit,
): Promise<{ response: Response; finalUrl: string }> {
  let currentUrl = startUrl;

  for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
    if (!(await resolvesToPublicAddresses(new URL(currentUrl).hostname))) {
      throw new BlockedRequestError(
        "URL not allowed (hostname resolves to a private/reserved address)",
      );
    }

    const response = await fetch(currentUrl, { ...init, redirect: "manual" });

    const isRedirect = response.status >= 300 && response.status < 400;
    const location = response.headers.get("location");
    if (!isRedirect || !location) {
      return { response, finalUrl: currentUrl };
    }

    // The interstitial body is never read — cancel it so the connection is
    // released instead of pinning memory ("body was not consumed" warnings).
    await response.body?.cancel().catch(() => {});

    const next = new URL(location, currentUrl).toString();
    if (!isAllowedUrl(next)) {
      throw new BlockedRequestError(
        "Redirect target not allowed (private/reserved address)",
      );
    }
    currentUrl = next;
  }

  throw new Error(`Too many redirects (limit ${MAX_REDIRECTS})`);
}
