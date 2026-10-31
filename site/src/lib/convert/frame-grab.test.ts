import { describe, expect, test } from "bun:test";
import { grabFileName } from "./frame-grab";

describe("grabFileName", () => {
  test("embeds a zero-padded timestamp and the format extension", () => {
    expect(grabFileName("clip.mp4", 3.25, "image/png")).toBe("clip-frame-00m03.25s.png");
    expect(grabFileName("my.video.webm", 125.5, "image/webp")).toBe("my.video-frame-02m05.50s.webp");
  });
  test("handles nameless files and negative times", () => {
    expect(grabFileName(".mp4", -1, "image/png")).toBe("frame-frame-00m00.00s.png");
  });
});
