import { describe, expect, test } from "bun:test";
import { isAllowedHostname, isAllowedUrl } from "./ssrf";

describe("isAllowedUrl — schemes and credentials", () => {
  test("allows ordinary http and https URLs", () => {
    expect(isAllowedUrl("https://example.com/page")).toBe(true);
    expect(isAllowedUrl("http://example.com:8080/page")).toBe(true);
  });

  test("rejects non-HTTP schemes", () => {
    for (const url of [
      "file:///etc/passwd",
      "ftp://example.com/x",
      "gopher://example.com/",
      "data:text/html,<h1>x</h1>",
      "javascript:alert(1)",
    ]) {
      expect(isAllowedUrl(url)).toBe(false);
    }
  });

  test("rejects embedded credentials used to disguise the host", () => {
    expect(isAllowedUrl("http://example.com@127.0.0.1/")).toBe(false);
    expect(isAllowedUrl("http://user:pass@example.com/")).toBe(false);
  });

  test("rejects unparseable input", () => {
    expect(isAllowedUrl("")).toBe(false);
    expect(isAllowedUrl("not a url")).toBe(false);
  });
});

describe("isAllowedHostname — loopback and internal names", () => {
  test("blocks localhost and internal suffixes", () => {
    for (const host of [
      "localhost",
      "LOCALHOST",
      "localhost.localdomain",
      "printer.local",
      "db.internal",
      "svc.home.arpa",
      "metadata.google.internal",
    ]) {
      expect(isAllowedHostname(host)).toBe(false);
    }
  });

  test("allows ordinary public hostnames", () => {
    for (const host of ["example.com", "sub.example.co.uk", "1.1.1.1", "8.8.8.8"]) {
      expect(isAllowedHostname(host)).toBe(true);
    }
  });
});

describe("isAllowedHostname — IPv4 reserved ranges", () => {
  const blocked = [
    "0.0.0.0",
    "10.0.0.1",
    "10.255.255.254",
    "127.0.0.1",
    "127.1.2.3",
    "100.64.0.1", // CGNAT
    "169.254.169.254", // cloud metadata
    "172.16.0.1",
    "172.31.255.254",
    "192.0.0.1",
    "192.168.1.1",
    "224.0.0.1", // multicast
    "255.255.255.255",
  ];

  test.each(blocked)("blocks %s", (host: string) => {
    expect(isAllowedHostname(host)).toBe(false);
    expect(isAllowedUrl(`http://${host}/`)).toBe(false);
  });

  test("allows public addresses adjacent to the blocked ranges", () => {
    for (const host of ["11.0.0.1", "172.15.0.1", "172.32.0.1", "192.167.1.1", "100.63.0.1"]) {
      expect(isAllowedHostname(host)).toBe(true);
    }
  });

  test("rejects octets out of range rather than treating them as a domain", () => {
    expect(isAllowedHostname("999.1.1.1")).toBe(false);
  });
});

describe("isAllowedHostname — alternate IPv4 encodings", () => {
  test("rejects bare-integer and hex forms of 127.0.0.1", () => {
    // http://2130706433/ and http://0x7f000001/ both reach loopback
    expect(isAllowedHostname("2130706433")).toBe(false);
    expect(isAllowedHostname("0x7f000001")).toBe(false);
    expect(isAllowedUrl("http://2130706433/")).toBe(false);
  });
});

describe("isAllowedHostname — IPv6", () => {
  const blocked = [
    "[::1]",
    "::1",
    "[::]",
    "[fc00::1]", // unique-local
    "[fd12:3456::1]",
    "[fe80::1]", // link-local
    "[::ffff:127.0.0.1]", // IPv4-mapped loopback
    "[::ffff:169.254.169.254]",
  ];

  test.each(blocked)("blocks %s", (host: string) => {
    expect(isAllowedHostname(host)).toBe(false);
  });

  test("allows public IPv6", () => {
    expect(isAllowedHostname("[2606:4700:4700::1111]")).toBe(true);
    expect(isAllowedUrl("http://[2606:4700:4700::1111]/")).toBe(true);
  });

  test("allows an IPv4-mapped public address", () => {
    expect(isAllowedHostname("[::ffff:8.8.8.8]")).toBe(true);
  });
});
