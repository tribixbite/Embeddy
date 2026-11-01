import { describe, expect, test } from "bun:test";
import { createJoinClip, disposeJoinClips, isTrimmed, moveItem, toJoinClip } from "./join-clips";

const file = (name: string) => new File([new Uint8Array(4)], name, { type: "video/mp4" });

describe("createJoinClip", () => {
  test("probes duration and starts untrimmed", async () => {
    const clip = await createJoinClip(file("a.mp4"), async () => 3.2567, () => "blob:a");
    expect(clip).toMatchObject({ durationMs: 3257, trimStartMs: 0, trimEndMs: 0, previewUrl: "blob:a" });
  });

  test("unknown or invalid durations become 0", async () => {
    expect((await createJoinClip(file("b.webm"), async () => Infinity, () => "u")).durationMs).toBe(0);
    expect((await createJoinClip(file("c.webm"), async () => NaN, () => "u")).durationMs).toBe(0);
  });

  test("gives each clip a distinct id (same file added twice)", async () => {
    const f = file("a.mp4");
    const a = await createJoinClip(f, async () => 1, () => "u1");
    const b = await createJoinClip(f, async () => 1, () => "u2");
    expect(a.id).not.toBe(b.id);
  });

  test("propagates probe failures and creates no URL", async () => {
    let made = 0;
    const failing = createJoinClip(
      file("x.txt"),
      async () => {
        throw new Error("x.txt has no video track");
      },
      () => `u${++made}`,
    );
    await expect(failing).rejects.toThrow("no video track");
    expect(made).toBe(0);
  });
});

describe("join clip helpers", () => {
  test("disposeJoinClips revokes every preview URL", async () => {
    const clips = await Promise.all(["a", "b"].map((n) => createJoinClip(file(n), async () => 1, () => `blob:${n}`)));
    const revoked: string[] = [];
    disposeJoinClips(clips, (u) => revoked.push(u));
    expect(revoked).toEqual(["blob:a", "blob:b"]);
  });

  test("moveItem reorders and clamps", () => {
    expect(moveItem([1, 2, 3], 0, 1)).toEqual([2, 1, 3]);
    expect(moveItem([1, 2, 3], 2, -1)).toEqual([1, 3, 2]);
    expect(moveItem([1, 2, 3], 0, -1)).toEqual([1, 2, 3]);
    expect(moveItem([1, 2, 3], 2, 5)).toEqual([1, 2, 3]);
    expect(moveItem([1, 2, 3], 9, 1)).toEqual([1, 2, 3]);
  });

  test("toJoinClip carries file and trim only", async () => {
    const clip = await createJoinClip(file("a"), async () => 5, () => "u");
    clip.trimStartMs = 1000;
    clip.trimEndMs = 4000;
    expect(toJoinClip(clip)).toEqual({ file: clip.file, trimStartMs: 1000, trimEndMs: 4000 });
  });

  test("isTrimmed", async () => {
    const clip = await createJoinClip(file("a"), async () => 5, () => "u");
    expect(isTrimmed(clip)).toBe(false);
    expect(isTrimmed({ ...clip, trimEndMs: 5000 })).toBe(false);
    expect(isTrimmed({ ...clip, trimEndMs: 4000 })).toBe(true);
    expect(isTrimmed({ ...clip, trimStartMs: 10 })).toBe(true);
  });
});
