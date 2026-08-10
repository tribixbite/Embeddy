/**
 * SSRF guards for the inspect proxy.
 *
 * The Worker fetches arbitrary user-supplied URLs, so it must never be usable as
 * a probe into private address space. Kept in its own module so the rules can be
 * unit-tested without instantiating the Hono app.
 */

/** Redirect hops to follow while revalidating each destination. */
export const MAX_REDIRECTS = 5;

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

/** True when an IPv6 literal is loopback, unspecified, unique-local or link-local. */
function isPrivateIpv6(host: string): boolean {
  const addr = host.replace(/^\[|\]$/g, "").toLowerCase();
  if (addr === "::1" || addr === "::") return true;
  // IPv4-mapped (::ffff:127.0.0.1) — validate the embedded v4 address
  const mapped = addr.match(/^::ffff:(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})$/);
  if (mapped) return !isAllowedHostname(mapped[1]!);
  // fc00::/7 unique-local, fe80::/10 link-local
  return /^f[cd]/.test(addr) || /^fe[89ab]/.test(addr);
}

/** Reject hostnames that point at private, loopback or reserved address space. */
export function isAllowedHostname(rawHost: string): boolean {
  const hostname = rawHost.toLowerCase();
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

/**
 * Fetch a URL, following redirects manually so every hop is re-checked.
 *
 * With `redirect: "follow"` an allowed public URL can 302 straight to
 * `http://169.254.169.254/` and the initial isAllowedUrl() check buys nothing.
 */
export async function safeFetch(
  startUrl: string,
  init: RequestInit,
): Promise<{ response: Response; finalUrl: string }> {
  let currentUrl = startUrl;

  for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
    const response = await fetch(currentUrl, { ...init, redirect: "manual" });

    const isRedirect = response.status >= 300 && response.status < 400;
    const location = response.headers.get("location");
    if (!isRedirect || !location) {
      return { response, finalUrl: currentUrl };
    }

    const next = new URL(location, currentUrl).toString();
    if (!isAllowedUrl(next)) {
      throw new Error("Redirect target not allowed (private/reserved address)");
    }
    currentUrl = next;
  }

  throw new Error(`Too many redirects (limit ${MAX_REDIRECTS})`);
}
