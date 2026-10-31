import { describe, expect, test } from "bun:test";
import { acceptsFile, isTextEntryTarget, pickAcceptedFiles } from "./file-intake";

const f = (name: string, type: string) => ({ name, type });

describe("acceptsFile", () => {
  test("exact types, wildcards and extensions", () => {
    const accept = "image/gif,video/*,.mkv";
    expect(acceptsFile(f("a.gif", "image/gif"), accept)).toBe(true);
    expect(acceptsFile(f("a.mp4", "video/mp4"), accept)).toBe(true);
    expect(acceptsFile(f("a.MKV", ""), accept)).toBe(true);
    expect(acceptsFile(f("a.png", "image/png"), accept)).toBe(false);
  });
  test("catch-alls and empty accept allow anything", () => {
    expect(acceptsFile(f("a.bin", ""), "*/*")).toBe(true);
    expect(acceptsFile(f("a.bin", ""), "")).toBe(true);
  });
});

describe("pickAcceptedFiles", () => {
  test("keeps order, drops the rest", () => {
    const files = [f("1.mp4", "video/mp4"), f("2.txt", "text/plain"), f("3.webm", "video/webm")];
    expect(pickAcceptedFiles(files, "video/*").map((x) => x.name)).toEqual(["1.mp4", "3.webm"]);
  });
});

describe("isTextEntryTarget", () => {
  test("inputs, textareas and contenteditable are text targets", () => {
    expect(isTextEntryTarget({ tagName: "INPUT" } as unknown as EventTarget)).toBe(true);
    expect(isTextEntryTarget({ tagName: "TEXTAREA" } as unknown as EventTarget)).toBe(true);
    expect(isTextEntryTarget({ tagName: "DIV", isContentEditable: true } as unknown as EventTarget)).toBe(true);
    expect(isTextEntryTarget({ tagName: "DIV", isContentEditable: false } as unknown as EventTarget)).toBe(false);
    expect(isTextEntryTarget(null)).toBe(false);
  });
});
