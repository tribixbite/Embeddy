import { describe, expect, test } from "bun:test";
import { canShareFiles, handoffUrl } from "./handoff";
import { isShareLaunch } from "../pwa/share-target";

describe("handoffUrl", () => {
  test("targets the tool's directory URL with the share marker", () => {
    expect(handoffUrl("/tools/upload")).toBe("/tools/upload/?shared=1");
    expect(handoffUrl("/tools/upload/")).toBe("/tools/upload/?shared=1");
    expect(isShareLaunch(new URL(handoffUrl("/tools/upload"), "https://x").search)).toBe(true);
  });
});

describe("canShareFiles", () => {
  test("false where the Web Share API is missing", () => {
    expect(canShareFiles([new File(["x"], "a.txt")])).toBe(false);
  });
});
