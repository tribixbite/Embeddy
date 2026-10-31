import { describe, expect, test } from "bun:test";
import { isShareLaunch, SHARE_REDIRECT_PATH } from "./share-target";

describe("isShareLaunch", () => {
  test("matches the redirect the service worker produces", () => {
    expect(isShareLaunch(SHARE_REDIRECT_PATH)).toBe(true);
    expect(isShareLaunch("?a=1&shared=1")).toBe(true);
  });
  test("ignores ordinary loads", () => {
    expect(isShareLaunch("")).toBe(false);
    expect(isShareLaunch("?shared=0")).toBe(false);
  });
});
