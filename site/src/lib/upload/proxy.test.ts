import { afterEach, describe, expect, test } from "bun:test";
import { uploadFile } from "./proxy";

/**
 * The upload path retries transient failures. These tests drive it with a fake
 * XMLHttpRequest so the classification rules (which statuses retry, which give
 * up immediately) are pinned down without any network.
 */

type Scripted =
  | { kind: "status"; status: number; body: string }
  | { kind: "network" }
  | { kind: "abort" };

interface FakeXhrLog {
  /** One entry per attempt actually made */
  attempts: number;
  aborted: number;
}

/** Install a fake XMLHttpRequest that replays `script` one entry per attempt. */
function installFakeXhr(script: Scripted[]): FakeXhrLog {
  const log: FakeXhrLog = { attempts: 0, aborted: 0 };

  class FakeXhr {
    status = 0;
    responseText = "";
    upload = { addEventListener: () => {} };
    private handlers: Record<string, Array<() => void>> = {};
    private step: Scripted;

    constructor() {
      // Clamp so a script shorter than MAX_ATTEMPTS keeps replaying its last entry
      this.step = script[Math.min(log.attempts, script.length - 1)]!;
      log.attempts++;
    }
    addEventListener(type: string, fn: () => void) {
      (this.handlers[type] ??= []).push(fn);
    }
    open() {}
    abort() {
      log.aborted++;
      queueMicrotask(() => this.fire("abort"));
    }
    send() {
      queueMicrotask(() => {
        if (this.step.kind === "network") return this.fire("error");
        if (this.step.kind === "abort") return this.fire("abort");
        this.status = this.step.status;
        this.responseText = this.step.body;
        this.fire("load");
      });
    }
    private fire(type: string) {
      for (const fn of this.handlers[type] ?? []) fn();
    }
  }

  (globalThis as { XMLHttpRequest?: unknown }).XMLHttpRequest = FakeXhr;
  return log;
}

const file = () => new File(["hello"], "a.png", { type: "image/png" });

/**
 * Await a promise that is expected to reject and return its message.
 * Used instead of `expect(...).rejects` because bun types that as returning
 * void, which makes `await` on it a type hint.
 */
async function rejectionMessage(promise: Promise<unknown>): Promise<string> {
  try {
    await promise;
  } catch (err) {
    return err instanceof Error ? err.message : String(err);
  }
  throw new Error("expected the upload to reject, but it resolved");
}

afterEach(() => {
  delete (globalThis as { XMLHttpRequest?: unknown }).XMLHttpRequest;
});

describe("uploadFile — success", () => {
  test("resolves with the hosted URL on first try", async () => {
    const log = installFakeXhr([
      { kind: "status", status: 200, body: JSON.stringify({ url: "https://0x0.st/abc" }) },
    ]);
    const result = await uploadFile(file(), "0x0.st").result;
    expect(result.url).toBe("https://0x0.st/abc");
    expect(result.host).toBe("0x0.st");
    expect(log.attempts).toBe(1);
  });

  test("a 2xx with no url in the body is treated as a failure, not a success", async () => {
    installFakeXhr([{ kind: "status", status: 200, body: JSON.stringify({ ok: true }) }]);
    expect(await rejectionMessage(uploadFile(file(), "0x0.st").result)).toMatch(/invalid response/i);
  });
});

describe("uploadFile — retry classification", () => {
  test("retries a 5xx and succeeds on the second attempt", async () => {
    const log = installFakeXhr([
      { kind: "status", status: 502, body: JSON.stringify({ error: "upstream boom" }) },
      { kind: "status", status: 200, body: JSON.stringify({ url: "https://0x0.st/ok" }) },
    ]);
    const seen: Array<[number, number]> = [];
    const result = await uploadFile(file(), "0x0.st", undefined, (a, t) => seen.push([a, t])).result;
    expect(result.url).toBe("https://0x0.st/ok");
    expect(log.attempts).toBe(2);
    expect(seen).toEqual([[2, 3]]);
  }, 15000);

  test("retries a network error and gives up after three attempts", async () => {
    const log = installFakeXhr([{ kind: "network" }]);
    expect(await rejectionMessage(uploadFile(file(), "0x0.st").result)).toMatch(/after 3 attempts/);
    expect(log.attempts).toBe(3);
  }, 15000);

  test("does NOT retry a 4xx — the request itself is wrong", async () => {
    const log = installFakeXhr([
      { kind: "status", status: 413, body: JSON.stringify({ error: "File is 300 MB but the limit is 95 MB" }) },
    ]);
    expect(await rejectionMessage(uploadFile(file(), "0x0.st").result)).toMatch(/300 MB/);
    expect(log.attempts).toBe(1);
  });

  test("surfaces the relay's error message rather than raw JSON", async () => {
    installFakeXhr([
      { kind: "status", status: 400, body: JSON.stringify({ error: "Unsupported host: nope" }) },
    ]);
    expect(await rejectionMessage(uploadFile(file(), "0x0.st").result)).toBe("Unsupported host: nope");
  });

  test("falls back to the status code when the body is not JSON", async () => {
    installFakeXhr([{ kind: "status", status: 403, body: "<html>nope</html>" }]);
    expect(await rejectionMessage(uploadFile(file(), "0x0.st").result)).toMatch(/HTTP 403/);
  });
});

describe("uploadFile — cancellation", () => {
  test("an aborted attempt is never retried", async () => {
    const log = installFakeXhr([{ kind: "abort" }]);
    expect(await rejectionMessage(uploadFile(file(), "0x0.st").result)).toMatch(/cancelled/i);
    expect(log.attempts).toBe(1);
  });

  test("cancel() aborts the in-flight request", async () => {
    const log = installFakeXhr([{ kind: "status", status: 502, body: "{}" }]);
    const handle = uploadFile(file(), "0x0.st");
    handle.cancel();
    expect(await rejectionMessage(handle.result)).toMatch(/cancelled/i);
    expect(log.aborted).toBeGreaterThan(0);
  }, 15000);
});
