import { expect, test, vi } from "vitest";
import {
  allowedPayload,
  RepTransport,
  sendFailure,
} from "../src/phone/transport";
import { ApiError } from "../src/shared/api";
import type { RepEvent } from "../../../packages/contracts/generated";
const event: RepEvent = {
  set_id: "s_test",
  rep_index: 0,
  exercise_id: "squat",
  rated: true,
  confidence: 0.9,
  metrics: {
    knee_min_deg: 90,
    depth_ratio: 0,
    descent_s: 1,
    trunk_lean_deg: 30,
  },
  duration_s: 3,
  client_ts_ms: 0,
};
test("W-NET-1 wire payload only contains allowlisted fields", () => {
  const raw = { ...event, landmarks: [1, 2, 3], image: "secret" };
  expect(Object.keys(allowedPayload(raw)).sort()).toEqual(
    Object.keys(event).sort(),
  );
  expect(JSON.stringify(allowedPayload(raw))).not.toMatch(/landmarks|image/);
});
test("W-NET-2 bounded queue and retry keeps natural idempotency key", async () => {
  vi.useFakeTimers();
  const calls: unknown[] = [];
  let attempt = 0;
  const t = new RepTransport(async (e) => {
    calls.push(structuredClone(e));
    if (attempt++ === 0) throw new Error("offline");
  });
  t.enqueue(event);
  await Promise.resolve();
  await Promise.resolve();
  for (let i = 1; i < 30; i++)
    expect(t.enqueue({ ...event, rep_index: i })).toBe(true);
  expect(t.pending).toBeLessThanOrEqual(30);
  await vi.advanceTimersByTimeAsync(1500);
  expect(calls[0]).toEqual(calls[1]);
  t.stop();
  expect(t.pending).toBe(0);
  vi.useRealTimers();
});
test("transport clears a failed-upload notice after retry succeeds", async () => {
  vi.useFakeTimers();
  const notice = vi.fn();
  const send = vi
    .fn()
    .mockRejectedValueOnce(new Error("rate limited"))
    .mockResolvedValue(undefined);
  const t = new RepTransport(send, notice);
  try {
    t.enqueue(event);
    await vi.advanceTimersByTimeAsync(0);
    expect(notice).toHaveBeenLastCalledWith(
      "Reps couldn't be sent yet. They're kept on this phone and sent again shortly.",
    );
    await vi.advanceTimersByTimeAsync(1000);
    expect(t.pending).toBe(0);
    expect(notice).toHaveBeenLastCalledWith(
      "Connected again. Saved reps are being sent.",
    );
    expect(send.mock.calls[1][0]).toEqual(send.mock.calls[0][0]);
  } finally {
    t.stop();
    vi.useRealTimers();
  }
});
test("stopped transport ignores an in-flight failure from the previous set", async () => {
  let reject!: (reason: Error) => void;
  const notice = vi.fn();
  const t = new RepTransport(
    () =>
      new Promise<void>((_, fail) => {
        reject = fail;
      }),
    notice,
  );
  t.enqueue(event);
  t.stop();
  reject(new Error("old request"));
  await Promise.resolve();
  await Promise.resolve();
  expect(notice).not.toHaveBeenCalled();
  expect(t.pending).toBe(0);
});

test.each(["invalid_request", "invalid_metric", "private_payload"])(
  "W-NET-3 permanent rejection %s discards only the head and notifies once",
  async (code) => {
    vi.useFakeTimers();
    const notice = vi.fn();
    const send = vi
      .fn()
      .mockRejectedValueOnce(new ApiError(code, "Invalid measurement"))
      .mockResolvedValue(undefined);
    const transport = new RepTransport(send, notice);
    try {
      expect(sendFailure(code)).toBe("discard");
      transport.enqueue(event);
      transport.enqueue({ ...event, rep_index: 1 });
      await vi.advanceTimersByTimeAsync(0);
      expect(transport.pending).toBe(1);
      expect(notice).toHaveBeenCalledExactlyOnceWith(
        "One rep couldn't be saved and was skipped.",
      );
      await vi.advanceTimersByTimeAsync(260);
      expect(send.mock.calls.map(([sent]) => sent.rep_index)).toEqual([0, 1]);
      expect(transport.pending).toBe(0);
      await vi.advanceTimersByTimeAsync(10000);
      expect(send).toHaveBeenCalledTimes(2);
      expect(notice).toHaveBeenCalledTimes(1);
    } finally {
      transport.stop();
      vi.useRealTimers();
    }
  },
);

test.each([
  new Error("offline"),
  new ApiError("internal_error", "Server unavailable"),
  new ApiError("rate_limited", "Try later"),
  new ApiError("unexpected_code", "Unknown failure"),
])(
  "W-NET-4 transient and unknown failures retain the head for retry: %s",
  async (error) => {
    vi.useFakeTimers();
    const notice = vi.fn();
    const send = vi
      .fn()
      .mockRejectedValueOnce(error)
      .mockResolvedValue(undefined);
    const transport = new RepTransport(send, notice);
    try {
      if (error instanceof ApiError)
        expect(sendFailure(error.code)).toBe("retry");
      transport.enqueue(event);
      transport.enqueue({ ...event, rep_index: 1 });
      await vi.advanceTimersByTimeAsync(0);
      expect(transport.pending).toBe(2);
      await vi.advanceTimersByTimeAsync(1000);
      expect(send.mock.calls.map(([sent]) => sent.rep_index)).toEqual([0, 0]);
      await vi.advanceTimersByTimeAsync(260);
      expect(send.mock.calls.map(([sent]) => sent.rep_index)).toEqual([
        0, 0, 1,
      ]);
      expect(transport.pending).toBe(0);
      expect(notice).not.toHaveBeenCalledWith(
        "One rep couldn't be saved and was skipped.",
      );
    } finally {
      transport.stop();
      vi.useRealTimers();
    }
  },
);
