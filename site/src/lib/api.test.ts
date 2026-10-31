import { describe, expect, test } from "bun:test";
import { DEFAULT_API_BASE, apiErrorMessage, parseApiError, resolveApiBase } from "./api";

describe("resolveApiBase", () => {
  test("defaults to production when unset or blank", () => {
    expect(resolveApiBase(undefined)).toBe(DEFAULT_API_BASE);
    expect(resolveApiBase("  ")).toBe(DEFAULT_API_BASE);
  });
  test("uses the configured base without trailing slashes", () => {
    expect(resolveApiBase("http://localhost:8787/")).toBe("http://localhost:8787");
    expect(resolveApiBase("http://localhost:8787")).toBe("http://localhost:8787");
  });
});

describe("apiErrorMessage", () => {
  test("extracts the Worker's { error } message", () => {
    expect(apiErrorMessage('{"error":"URL not allowed"}', 403)).toBe("URL not allowed");
  });
  test("keeps plain text and falls back to the status", () => {
    expect(apiErrorMessage("Bad Gateway", 502)).toBe("Bad Gateway");
    expect(apiErrorMessage("", 500)).toBe("HTTP 500");
    expect(apiErrorMessage('{"other":1}', 500)).toBe('{"other":1}');
  });
});

describe("parseApiError", () => {
  test("only returns a JSON error string", () => {
    expect(parseApiError('{"error":"Too large"}')).toBe("Too large");
    expect(parseApiError("<html>nope</html>")).toBeNull();
    expect(parseApiError('{"error":""}')).toBeNull();
  });
});
