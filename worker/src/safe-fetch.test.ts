import { afterEach, describe, expect, test } from "bun:test";
import { BlockedRequestError, areDnsAnswersAllowed, safeFetch } from "./ssrf";

/**
 * safeFetch is driven with a stubbed global fetch so redirect handling, DNS
 * pre-resolution (DoH), and body-draining behavior are pinned without network.
 */

const realFetch = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = realFetch;
});

/** A DoH JSON response body carrying the given answer records. */
function dohJson(answers: Array<{ type: number; data: string }>): Response {
  return new Response(
    JSON.stringify({
      Status: 0,
      Answer: answers.map((a) => ({ name: "stub.", TTL: 60, ...a })),
    }),
    { headers: { "content-type": "application/dns-json" } },
  );
}

/** Install a fetch stub routed by URL; returns the list of URLs fetched. */
function installFetch(
  handler: (url: string) => Response | Promise<Response>,
): string[] {
  const calls: string[] = [];
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    const url =
      typeof input === "string"
        ? input
        : input instanceof URL
          ? input.toString()
          : input.url;
    calls.push(url);
    return handler(url);
  }) as typeof fetch;
  return calls;
}

/** Route DoH lookups to `answers`; everything else to `page`. */
function routeDoh(
  answers: Array<{ type: number; data: string }>,
  page: (url: string) => Response | Promise<Response>,
): (url: string) => Response | Promise<Response> {
  return (url) => (url.includes("cloudflare-dns.com") ? dohJson(answers) : page(url));
}

const PUBLIC_A = [{ type: 1, data: "93.184.216.34" }];

describe("areDnsAnswersAllowed (M11)", () => {
  test("allows public A and AAAA answers", () => {
    expect(
      areDnsAnswersAllowed([
        { type: 1, data: "93.184.216.34" },
        { type: 28, data: "2606:4700:4700::1111" },
      ]),
    ).toBe(true);
  });

  test("blocks private A answers", () => {
    for (const data of ["127.0.0.1", "10.0.0.1", "169.254.169.254", "192.168.1.1"]) {
      expect(areDnsAnswersAllowed([{ type: 1, data }])).toBe(false);
    }
  });

  test("blocks private AAAA answers", () => {
    for (const data of ["::1", "fd00::1", "fe80::1", "::ffff:7f00:1"]) {
      expect(areDnsAnswersAllowed([{ type: 28, data }])).toBe(false);
    }
  });

  test("ignores non-address records (CNAME chains)", () => {
    expect(
      areDnsAnswersAllowed([
        { type: 5, data: "target.example." },
        { type: 1, data: "1.2.3.4" },
      ]),
    ).toBe(true);
  });

  test("treats an empty or missing answer section as allowed", () => {
    expect(areDnsAnswersAllowed([])).toBe(true);
    expect(areDnsAnswersAllowed(undefined)).toBe(true);
  });
});

describe("safeFetch — DNS pre-resolution (M11)", () => {
  test("blocks a public-looking hostname that resolves to a private address", async () => {
    const calls = installFetch(
      routeDoh([{ type: 1, data: "127.0.0.1" }], () => new Response("should not be reached")),
    );

    await expect(safeFetch("http://rebind.example/", {})).rejects.toBeInstanceOf(
      BlockedRequestError,
    );
    // The target itself must never have been fetched
    expect(calls.some((u) => u.startsWith("http://rebind.example/"))).toBe(false);
  });

  test("fails open when the DoH lookup itself errors", async () => {
    installFetch((url) =>
      url.includes("cloudflare-dns.com")
        ? new Response("doh outage", { status: 500 })
        : new Response("page ok"),
    );

    const { response } = await safeFetch("https://example.com/", {});
    expect(await response.text()).toBe("page ok");
  });

  test("skips DNS resolution for IP-literal hosts", async () => {
    const calls = installFetch(() => new Response("ip ok"));
    const { response } = await safeFetch("http://93.184.216.34/", {});
    expect(await response.text()).toBe("ip ok");
    expect(calls.some((u) => u.includes("cloudflare-dns.com"))).toBe(false);
  });
});

describe("safeFetch — redirects", () => {
  test("cancels each redirect hop's body before following (M43)", async () => {
    let cancelled = false;
    const hopBody = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode("interstitial"));
      },
      cancel() {
        cancelled = true;
      },
    });

    installFetch(
      routeDoh(PUBLIC_A, (url) =>
        url === "https://a.example/"
          ? new Response(hopBody, {
              status: 302,
              headers: { location: "https://b.example/final" },
            })
          : new Response("final"),
      ),
    );

    const { response, finalUrl } = await safeFetch("https://a.example/", {});
    expect(finalUrl).toBe("https://b.example/final");
    expect(await response.text()).toBe("final");
    expect(cancelled).toBe(true);
  });

  test("throws a typed BlockedRequestError for a blocked redirect target (M44)", async () => {
    installFetch(
      routeDoh(
        PUBLIC_A,
        () =>
          new Response(null, {
            status: 302,
            headers: { location: "http://169.254.169.254/latest/meta-data/" },
          }),
      ),
    );

    await expect(safeFetch("https://a.example/", {})).rejects.toBeInstanceOf(
      BlockedRequestError,
    );
  });
});
