import { readFileSync } from "node:fs";
import { afterEach, expect, test, vi } from "vitest";
import catalogJson from "../../../packages/contracts/catalog.public.json";
import { CoachSender } from "../src/phone/coach";
import { PartialNotice } from "../src/phone/partial";
import { RepTransport } from "../src/phone/transport";
import { PosePipeline } from "../src/pose/pipeline";
import type { Catalog, Frame } from "../src/pose/types";
import { CoachBanner, coachLabel } from "../src/tv/coach";

const active = () => ({
  phase: "IN_SET",
  paused: false,
  active_set: { set_id: "s_coach" },
});
const settled = async () => {
  await Promise.resolve();
  await Promise.resolve();
};
afterEach(() => vi.useRealTimers());

test("W-COACH-1 sender coalesces to the latest kind with one request in flight", async () => {
  let release!: () => void,
    now = 0;
  const send = vi
    .fn()
    .mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          release = resolve;
        }),
    )
    .mockResolvedValue(undefined);
  const coach = new CoachSender(send, active, () => now);
  coach.show("shallow");
  coach.show("uncalibrated");
  now = 2000;
  coach.show("shallow");
  expect(send).toHaveBeenCalledTimes(1);
  release();
  await settled();
  expect(send.mock.calls.map(([body]) => body)).toEqual([
    { set_id: "s_coach", kind: "shallow", client_ts_ms: 0 },
    { set_id: "s_coach", kind: "shallow", client_ts_ms: 2000 },
  ]);
});

test("W-COACH-2 failure is silent, never retried and never enters RepTransport", async () => {
  vi.useFakeTimers();
  const enqueue = vi.spyOn(RepTransport.prototype, "enqueue");
  const send = vi.fn().mockRejectedValue(new Error("offline"));
  const coach = new CoachSender(send, active);
  try {
    coach.show("shallow");
    await vi.advanceTimersByTimeAsync(10000);
    expect(send).toHaveBeenCalledTimes(1);
    expect(enqueue).not.toHaveBeenCalled();
    coach.show("uncalibrated");
    await settled();
    expect(send).toHaveBeenCalledTimes(2);
  } finally {
    enqueue.mockRestore();
  }
});

test("W-COACH-3 dedupe is per kind and set at the 1.5 s boundary", async () => {
  let now = 0,
    view = active();
  const send = vi.fn().mockResolvedValue(undefined);
  const coach = new CoachSender(
    send,
    () => view,
    () => now,
  );
  coach.show("shallow");
  await settled();
  coach.show("uncalibrated");
  await settled();
  now = 1499;
  coach.show("shallow");
  await settled();
  expect(send).toHaveBeenCalledTimes(2);
  now = 1500;
  coach.show("shallow");
  await settled();
  expect(send).toHaveBeenCalledTimes(3);
  view = { ...view, active_set: { set_id: "s_next" } };
  coach.show("shallow");
  await settled();
  expect(send).toHaveBeenCalledTimes(4);
});

test.each([
  { ...active(), phase: "DEMO" },
  { ...active(), paused: true },
  { ...active(), active_set: null },
  null,
])("W-COACH-4 inactive or paused views cannot send: %j", async (inactive) => {
  let view: ReturnType<typeof active> | typeof inactive = inactive;
  let release!: () => void;
  const send = vi.fn(
    () =>
      new Promise<void>((resolve) => {
        release = resolve;
      }),
  );
  const coach = new CoachSender(send, () => view);
  coach.show("shallow");
  expect(send).not.toHaveBeenCalled();
  view = active();
  coach.show("shallow");
  coach.show("uncalibrated");
  view = inactive;
  release();
  await settled();
  expect(send).toHaveBeenCalledTimes(1);
});

test.each(["shallow", "uncalibrated"] as const)(
  "W-COACH-5 synthetic pipeline rejection routes %s to the phone and TV kind only",
  async (kind) => {
    vi.useFakeTimers();
    const fixture = JSON.parse(
      readFileSync(
        new URL(
          "../../../evals/fixtures/squat_barely_bent.json",
          import.meta.url,
        ),
        "utf8",
      ),
    ) as { frames: Frame[] };
    const send = vi.fn().mockResolvedValue(undefined),
      counted = vi.fn(),
      write = vi.fn();
    const coach = new CoachSender(send, active, () => 5000);
    const partial = new PartialNotice(write);
    const pipeline = new PosePipeline(
      catalogJson as Catalog,
      "squat",
      "s_coach",
      counted,
      (reason) => {
        partial.show(reason);
        coach.show(reason);
      },
    );
    for (const frame of fixture.frames.filter((f) => f.timestamp < 5400)) {
      if (kind === "uncalibrated")
        for (const leg of [23, 24, 25, 26, 27, 28])
          frame.landmarks[leg].visibility = 0.49;
      pipeline.push(frame);
    }
    await settled();
    expect(counted).not.toHaveBeenCalled();
    expect(send).toHaveBeenCalledExactlyOnceWith({
      set_id: "s_coach",
      kind,
      client_ts_ms: 5000,
    });
    expect(write).toHaveBeenLastCalledWith(
      kind === "shallow"
        ? "Partial rep: not counted. Bend deeper."
        : "Stand tall, side-on and fully in view for a moment so CleanRep can measure your legs.",
    );
    partial.clear();
  },
);

test("W-COACH-6 TV owns the two coaching labels", () => {
  expect(coachLabel("shallow")).toBe("Not counted: bend deeper");
  expect(coachLabel("uncalibrated")).toBe(
    "Stand tall, side-on and fully in view so the phone can measure your legs",
  );
});

test("W-COACH-7 banner keeps its element and focus, changes only for new notices, and hides on null", () => {
  let text = "";
  const write = vi.fn((value: string) => {
    text = value;
  });
  const element = {
    hidden: false,
    className: "",
    setAttribute: vi.fn(),
    focus: vi.fn(),
    get textContent() {
      return text;
    },
    set textContent(value: string) {
      write(value);
    },
    set innerHTML(_: string) {
      throw new Error("Must not recreate markup");
    },
    replaceWith: vi.fn(),
    remove: vi.fn(),
  };
  const banner = new CoachBanner(element as unknown as HTMLElement);
  expect(element.hidden).toBe(true);
  expect(element.setAttribute).toHaveBeenCalledWith("role", "status");
  expect(element.setAttribute).toHaveBeenCalledWith("aria-live", "polite");
  banner.update({ kind: "shallow", seq: 1 });
  expect(element.hidden).toBe(false);
  expect(text).toBe(coachLabel("shallow"));
  const writes = write.mock.calls.length;
  for (let i = 0; i < 10; i++) banner.update({ kind: "shallow", seq: 1 });
  expect(write).toHaveBeenCalledTimes(writes);
  banner.update({ kind: "shallow", seq: 2 });
  expect(write).toHaveBeenCalledTimes(writes + 1);
  banner.update({ kind: "uncalibrated", seq: 3 });
  expect(text).toBe(coachLabel("uncalibrated"));
  banner.update(null);
  expect(element.hidden).toBe(true);
  expect(text).toBe("");
  expect(element.focus).not.toHaveBeenCalled();
  expect(element.replaceWith).not.toHaveBeenCalled();
  expect(element.remove).not.toHaveBeenCalled();
});

test("W-COACH-8 queued notices cannot cross sets or survive a clear", async () => {
  let view = active(),
    release!: () => void;
  const send = vi.fn(
    () =>
      new Promise<void>((resolve) => {
        release = resolve;
      }),
  );
  const coach = new CoachSender(send, () => view);
  coach.show("shallow");
  coach.show("uncalibrated");
  view = { ...view, active_set: { set_id: "s_next" } };
  release();
  await settled();
  expect(send).toHaveBeenCalledTimes(1);
  coach.show("shallow");
  coach.show("uncalibrated");
  coach.clear();
  release();
  await settled();
  expect(send).toHaveBeenCalledTimes(2);
});
