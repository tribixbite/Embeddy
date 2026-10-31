import { describe, expect, test } from "bun:test";
import { isChunkLoadError, recoverFromChunkError, type ReloadEnv } from "./chunk-reload";

function env(): ReloadEnv & { reloads: number; store: Map<string, string> } {
  const store = new Map<string, string>();
  const e = {
    reloads: 0,
    store,
    storage: {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
    },
    reload: () => {
      e.reloads++;
    },
  };
  return e;
}

describe("isChunkLoadError", () => {
  test("recognises each browser's dynamic-import failure", () => {
    expect(isChunkLoadError(new TypeError("Failed to fetch dynamically imported module: https://x/_astro/a.js"))).toBe(true);
    expect(isChunkLoadError(new TypeError("error loading dynamically imported module"))).toBe(true);
    expect(isChunkLoadError(new TypeError("Importing a module script failed."))).toBe(true);
    expect(isChunkLoadError(new Error("Encoder worker failed to load"))).toBe(true);
  });

  test("ignores ordinary errors and non-errors", () => {
    expect(isChunkLoadError(new Error("GIF contains no frames"))).toBe(false);
    expect(isChunkLoadError(undefined)).toBe(false);
    expect(isChunkLoadError({ message: 42 })).toBe(false);
  });
});

describe("recoverFromChunkError", () => {
  test("reloads once per build", () => {
    const e = env();
    const err = new TypeError("Failed to fetch dynamically imported module");
    expect(recoverFromChunkError(err, "b1", e)).toBe(true);
    expect(recoverFromChunkError(err, "b1", e)).toBe(false);
    expect(e.reloads).toBe(1);
    // A newer build gets its own one-shot
    expect(recoverFromChunkError(err, "b2", e)).toBe(true);
    expect(e.reloads).toBe(2);
  });

  test("does nothing for unrelated errors", () => {
    const e = env();
    expect(recoverFromChunkError(new Error("nope"), "b1", e)).toBe(false);
    expect(e.reloads).toBe(0);
  });

  test("blocked storage refuses rather than risking a reload loop", () => {
    const e = env();
    e.storage.getItem = () => {
      throw new Error("SecurityError");
    };
    expect(recoverFromChunkError(new TypeError("Importing a module script failed."), "b", e)).toBe(false);
    expect(e.reloads).toBe(0);
  });
});
