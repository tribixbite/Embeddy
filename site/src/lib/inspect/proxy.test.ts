import { afterEach, describe, expect, test } from "bun:test";
import { fetchMetadata } from "./proxy";

/**
 * The worker reports failures as `{"error":"..."}` JSON. fetchMetadata must
 * surface the message, not the raw JSON blob (M45) — mirroring how
 * lib/upload/proxy.ts already unwraps the same shape.
 */

const realFetch = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = realFetch;
});

/** Stub global fetch to always return `response`. */
function installFetch(response: Response): void {
  globalThis.fetch = (async () => response) as unknown as typeof fetch;
}

describe("fetchMetadata — error surfacing", () => {
  test("unwraps the worker's {error} JSON into the thrown message", async () => {
    installFetch(
      new Response(
        JSON.stringify({ error: "URL not allowed (private/reserved address)" }),
        { status: 403, headers: { "content-type": "application/json" } },
      ),
    );

    await expect(fetchMetadata("http://127.0.0.1/")).rejects.toThrow(
      "URL not allowed (private/reserved address)",
    );
    // Specifically: the message must not be the raw JSON string
    installFetch(
      new Response(JSON.stringify({ error: "boom" }), { status: 502 }),
    );
    let message = "";
    try {
      await fetchMetadata("https://example.com/");
    } catch (err) {
      message = err instanceof Error ? err.message : String(err);
    }
    expect(message).toBe("boom");
  });

  test("falls back to the raw body for non-JSON errors", async () => {
    installFetch(new Response("Bad Gateway", { status: 502 }));
    await expect(fetchMetadata("https://example.com/")).rejects.toThrow(
      "Bad Gateway",
    );
  });

  test("falls back to the HTTP status for an empty body", async () => {
    installFetch(new Response("", { status: 500 }));
    await expect(fetchMetadata("https://example.com/")).rejects.toThrow(
      "HTTP 500",
    );
  });

  test("returns parsed metadata on success", async () => {
    installFetch(
      new Response(JSON.stringify({ url: "https://example.com/", status: 200, title: "t", tags: [] }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    const result = await fetchMetadata("https://example.com/");
    expect(result.title).toBe("t");
  });
});
