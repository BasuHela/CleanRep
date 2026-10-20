import { expect, test, vi } from "vitest";
import catalogJson from "../../../packages/contracts/catalog.public.json";
import { RepEngine } from "../src/pose/engine";
import { PartialNotice } from "../src/phone/partial";
import { PosePipeline } from "../src/pose/pipeline";
import type { Catalog, Frame, Observation } from "../src/pose/types";
import type { RepEvent } from "../../../packages/contracts/generated";

const catalog = catalogJson as Catalog;
const squats = Object.entries(catalog.exercises)
  .filter(([, exercise]) => exercise.family === "squat")
  .map(([id]) => id);
const attempt = [175, 149, 140, 135, 130, 125, 125, 130, 140, 150, 160, 175];
const observation = (
  angle: number,
  timestamp: number,
  depth = -0.5,
): Observation => ({
  angle,
  timestamp,
  metrics: { depth_ratio: depth },
  confidence: 0.98,
  full_body: true,
  side_view: true,
  light: true,
});
function run(engine: RepEngine, depth: number, start = 0, angles = attempt) {
  return angles.flatMap((angle, i) => {
    const event = engine.push(observation(angle, start + i * 100, depth));
    return event ? [event] : [];
  });
}
function engine(id: string, partial = vi.fn()) {
  return new RepEngine(
    id,
    "s_accuracy",
    catalog.exercises[id],
    catalog.checks,
    partial,
  );
}

test.each(squats)(
  "W-REP-9 %s rejects barely-bent attempts and preserves half squats",
  (id) => {
    const partial = vi.fn(),
      e = engine(id, partial);
    expect(run(e, -0.8)).toHaveLength(0);
    expect(partial).toHaveBeenCalledTimes(1);
    const counted = run(e, -0.5, 2000);
    expect(counted).toHaveLength(1);
    expect(counted[0]).toMatchObject({
      rep_index: 0,
      rated: id !== "squat_jump",
    });
    expect(counted[0].metrics.depth_ratio).toBe(-0.5);
    expect(counted[0].metrics.depth_ratio).toBeLessThan(
      catalogJson.faults.shallow_depth.threshold,
    );
    expect(partial).toHaveBeenCalledTimes(1);
  },
);

test("W-REP-10 depth boundary, missing samples and knee guard fail closed", () => {
  for (const [depth, count] of [
    [-0.651, 0],
    [-0.65001, 0],
    [-0.65, 1],
    [-0.649, 1],
  ])
    expect(run(engine("squat"), depth)).toHaveLength(count);
  for (const depth of [NaN, Infinity, -Infinity])
    expect(run(engine("squat"), depth)).toHaveLength(0);
  const e = engine("squat");
  const events = attempt.flatMap((angle, i) => {
    if (i === 5)
      e.push({ ...observation(0, i * 100 - 1), angle: null, metrics: {} });
    const event = e.push(observation(angle, i * 100, -0.8));
    return event ? [event] : [];
  });
  expect(events).toHaveLength(0);
  expect(
    run(
      engine("squat"),
      -0.5,
      0,
      attempt.map((a) => Math.max(a, 141)),
    ),
  ).toHaveLength(0);
  const missing = engine("squat");
  expect(
    attempt.flatMap((angle, i) => {
      const event = missing.push({
        ...observation(angle, i * 100),
        metrics: {},
      });
      return event ? [event] : [];
    }),
  ).toHaveLength(0);
});

test("W-REP-11 standing jitter and a brief landmark spike cannot count", () => {
  const partial = vi.fn(),
    e = engine("squat", partial);
  expect(
    run(
      e,
      -0.95,
      0,
      Array.from({ length: 300 }, (_, i) => 175 + (i % 2 ? 3 : -3)),
    ),
  ).toHaveLength(0);
  expect(partial).not.toHaveBeenCalled();
  expect(run(e, -0.5, 31000, [175, 125, 175])).toHaveLength(0);
});

// Synthetic side-view landmarks, with yaw foreshortening and image-aspect changes.
function frames(bottoms: number[], aspect = 1, yaw = 1): Frame[] {
  const angles = Array(45).fill(175);
  for (const bottom of bottoms) {
    for (let i = 0; i < 50; i++) angles.push(175 - ((175 - bottom) * i) / 49);
    angles.push(...Array(9).fill(bottom));
    for (let i = 0; i < 40; i++)
      angles.push(bottom + ((175 - bottom) * i) / 39);
    angles.push(...Array(18).fill(175));
  }
  return angles.map((angle, i) => {
    const k = (angle * Math.PI) / 180;
    const hip = {
      x: 0.53 - 0.24 * Math.sin(k) * yaw,
      y: 0.64 + 0.24 * Math.cos(k),
    };
    const landmarks = Array.from({ length: 33 }, () => ({
      x: 0.5,
      y: 0.5,
      z: 0,
      visibility: 0.98,
    }));
    for (const side of [0, 1]) {
      landmarks[11 + side] = {
        ...landmarks[0],
        x: hip.x + 0.11 + side * 0.01,
        y: hip.y - 0.245,
      };
      landmarks[23 + side] = {
        ...landmarks[0],
        x: hip.x + side * 0.01,
        y: hip.y,
      };
      landmarks[25 + side] = {
        ...landmarks[0],
        x: 0.53 + side * 0.01,
        y: 0.64,
      };
      landmarks[27 + side] = { ...landmarks[0], x: 0.53 + side * 0.01, y: 0.9 };
    }
    return {
      timestamp: Math.round((i * 1000) / 30),
      luma: 128,
      aspect,
      landmarks: landmarks.map((p) => ({
        ...p,
        x: 0.5 + (p.x - 0.5) / aspect,
      })),
    };
  });
}

test.each([1, 16 / 9, 9 / 16])(
  "W-REP-12 pipeline separates partials and emitted reps at aspect %s",
  (aspect) => {
    for (const yaw of [1, 0.8]) {
      const events: RepEvent[] = [],
        partial = vi.fn();
      const pipeline = new PosePipeline(
        catalog,
        "squat",
        "s_accuracy",
        (r) => events.push(r),
        partial,
      );
      frames([138, 119, 138, 78], aspect, yaw).forEach((f) => pipeline.push(f));
      expect(partial).toHaveBeenCalledTimes(2);
      expect(events.map((r) => r.rep_index)).toEqual([0, 1]);
      expect(events[0].metrics.depth_ratio).toBeLessThan(-0.15);
      expect(events[1].metrics.depth_ratio).toBeGreaterThan(-0.05);
    }
  },
);

test("W-REP-13 missing standing calibration cannot manufacture squat depth", () => {
  const events: RepEvent[] = [];
  const pipeline = new PosePipeline(catalog, "squat", "s_accuracy", (r) =>
    events.push(r),
  );
  const input = frames([119]);
  // Starting mid-descent gives no standing thigh calibration until the first return to TOP.
  input.filter((f) => f.timestamp >= 2000).forEach((f) => pipeline.push(f));
  expect(events).toHaveLength(0);
});

test("W-REP-14 push-up and bridge small attempts fail their existing range guards", () => {
  expect(
    run(
      engine("pushup"),
      0,
      0,
      attempt.map((a) => Math.max(a, 130)),
    ),
  ).toHaveLength(0);
  expect(
    run(
      engine("pushup"),
      0,
      0,
      attempt.map((a) => Math.max(a, 120)),
    ),
  ).toHaveLength(1);
  expect(
    run(
      engine("glute_bridge"),
      0,
      0,
      [120, 141, 145, 150, 150, 148, 145, 139, 135, 130],
    ),
  ).toHaveLength(0);
  expect(
    run(
      engine("glute_bridge"),
      0,
      0,
      [120, 141, 150, 155, 157, 157, 150, 139, 135, 130],
    ),
  ).toHaveLength(1);
});

test("W-REP-17 a timed-out partial reports once on return and rearms", () => {
  for (const missing of [false, true]) {
    const partial = vi.fn(),
      e = engine("squat", partial);
    e.push(observation(149, 0, -0.8));
    e.push(observation(138, 500, -0.8));
    e.push({
      ...observation(138, 9000, -0.8),
      ...(missing ? { angle: null, metrics: {} } : {}),
    });
    expect(partial).not.toHaveBeenCalled();
    expect(e.push(observation(175, 9100, -0.98))).toBeNull();
    expect(e.push(observation(175, 9200, -0.98))).toBeNull();
    expect(partial).toHaveBeenCalledTimes(1);
    expect(run(e, -0.5, 10000)[0].rep_index).toBe(0);
  }
});

test.each([0.5, 0.1])(
  "W-REP-18 leg calibration survives unrelated occlusion at visibility %s",
  (upperVisibility) => {
    const events: RepEvent[] = [],
      notice = vi.fn();
    const pipeline = new PosePipeline(
      catalog,
      "squat",
      "s_accuracy",
      (r) => events.push(r),
      notice,
    );
    for (const frame of frames([78])) {
      frame.luma = 30;
      for (const i of [11, 12, 15, 16])
        frame.landmarks[i].visibility = upperVisibility;
      for (const i of [23, 24, 25, 26, 27, 28])
        frame.landmarks[i].visibility = 0.6;
      pipeline.push(frame);
    }
    expect(events).toHaveLength(1);
    expect(events[0].rated).toBe(false);
    expect(events[0].metrics.depth_ratio).toBeGreaterThan(-0.05);
    expect(notice).not.toHaveBeenCalled();
  },
);

test.each([23, 25, 27])(
  "W-REP-19 leg %s below calibration threshold gives calibration feedback",
  (leg) => {
    vi.useFakeTimers();
    const events: RepEvent[] = [],
      write = vi.fn(),
      rejection = vi.fn();
    const notice = new PartialNotice(write);
    const pipeline = new PosePipeline(
      catalog,
      "squat",
      "s_accuracy",
      (r) => events.push(r),
      (reason) => {
        rejection(reason);
        notice.show(reason);
      },
    );
    try {
      for (const frame of frames([78])) {
        frame.landmarks[leg].visibility = 0.499;
        // Keep this chain selected, rather than calibrating from the visible far leg.
        for (const i of [12, 24, 26, 28]) frame.landmarks[i].visibility = 0.1;
        pipeline.push(frame);
      }
      expect(events).toHaveLength(0);
      expect(rejection).toHaveBeenCalledExactlyOnceWith("uncalibrated");
      expect(write).toHaveBeenLastCalledWith(
        "Stand tall, side-on and fully in view for a moment so CleanRep can measure your legs.",
      );
      expect(
        write.mock.calls.some(([text]) => text.includes("Bend deeper")),
      ).toBe(false);
      checkCalibrationBoundary(leg);
    } finally {
      notice.clear();
      vi.useRealTimers();
    }
  },
);

function checkCalibrationBoundary(leg: number) {
  const events: RepEvent[] = [];
  const pipeline = new PosePipeline(catalog, "squat", "s_boundary", (r) =>
    events.push(r),
  );
  for (const frame of frames([78])) {
    frame.landmarks[leg].visibility = 0.5;
    for (const i of [12, 24, 26, 28]) frame.landmarks[i].visibility = 0.1;
    pipeline.push(frame);
  }
  expect(events).toHaveLength(1);
  expect(events[0].rated).toBe(false);
}

test("W-REP-20 uncalibrated timeout preserves its reason until the return to TOP", () => {
  const notice = vi.fn(),
    e = engine("squat", notice);
  for (const [angle, timestamp] of [
    [149, 0],
    [90, 500],
    [90, 9000],
    [175, 9100],
  ])
    expect(
      e.push({ ...observation(angle, timestamp), metrics: {} }),
    ).toBeNull();
  expect(notice).toHaveBeenCalledExactlyOnceWith("uncalibrated");
});
