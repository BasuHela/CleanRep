import { describe, expect, test } from "vitest";
import catalogJson from "../../../packages/contracts/catalog.public.json";
import {
  angle,
  depthRatio,
  hipOffset,
  NearSide,
  trunkLean,
} from "../src/pose/geometry";
import { OneEuro } from "../src/pose/filter";
import { frameChecks, CheckWindow, rated } from "../src/pose/checks";
import { RepEngine, HoldEngine } from "../src/pose/engine";
import type { Catalog, Observation, Point } from "../src/pose/types";
const catalog = catalogJson as Catalog;
const p = (x: number, y: number, visibility = 1): Point => ({
  x,
  y,
  z: 0,
  visibility,
});
const observation = (
  angle: number,
  timestamp: number,
  metrics: Record<string, number> = {},
): Observation => ({
  angle,
  timestamp,
  metrics: { depth_ratio: Math.cos((angle * Math.PI) / 180), ...metrics },
  confidence: 0.95,
  full_body: true,
  side_view: true,
  light: true,
});
export function cycle(
  engine: RepEngine,
  values: number[],
  start = 0,
  dt = 100,
  metrics: Record<string, number> = {},
) {
  return values.flatMap((a, i) => {
    const event = engine.push(observation(a, start + i * dt, metrics));
    return event ? [event] : [];
  });
}
const squat = [
  170, 165, 149, 140, 130, 120, 110, 100, 90, 85, 85, 90, 100, 110, 120, 130,
  140, 150, 160, 170,
];
function engine(name = "squat") {
  return new RepEngine(name, "s_test", catalog.exercises[name], catalog.checks);
}
test("W-ANG-1 right angle", () =>
  expect(angle(p(0, 1), p(0, 0), p(1, 0))).toBeCloseTo(90));
test("W-ANG-2 straight angle", () =>
  expect(angle(p(0, 1), p(0, 0), p(0, -1))).toBeCloseTo(180));
test("W-ANG-3 degenerate segment", () =>
  expect(angle(p(0, 0), p(0, 0), p(0, -1))).toBeNull());
test("W-EUR-1 jitter is reduced", () => {
  const f = new OneEuro();
  const input = Array.from(
    { length: 120 },
    (_, i) => 1 + (i % 2 ? 0.01 : -0.01),
  );
  const output = input.map((v, i) => f.push(v, i * 33));
  const spread = (xs: number[]) =>
    Math.max(...xs.slice(30)) - Math.min(...xs.slice(30));
  expect(spread(output)).toBeLessThan(spread(input) / 3);
});
test("W-EUR-2 step lag bounded", () => {
  const f = new OneEuro();
  f.push(0, 0);
  let y = 0;
  for (let i = 1; i <= 20; i++) y = f.push(1, i * 33);
  expect(y).toBeGreaterThan(0.95);
});
test("W-SIDE-1 selects near side and locks after ten frames", () => {
  const near = new NearSide(),
    points = Array.from({ length: 33 }, () => p(0.5, 0.5, 0.5));
  [12, 24, 26, 28].forEach((i) => (points[i].visibility = 0.9));
  for (let i = 0; i < 10; i++) expect(near.choose(points)).toBe(1);
  [11, 23, 25, 27].forEach((i) => (points[i].visibility = 1));
  expect(near.choose(points)).toBe(1);
});
test("W-REP-1 ten clean squats", () => {
  const e = engine();
  const reps = Array.from({ length: 10 }, (_, i) =>
    cycle(e, squat, i * 2500),
  ).flat();
  expect(reps).toHaveLength(10);
  expect(reps.map((r) => r.rep_index)).toEqual(
    Array.from({ length: 10 }, (_, i) => i),
  );
});
test("W-REP-2 partial movement does not count", () => {
  expect(
    cycle(engine(), [170, 149, 146, 143, 145, 150, 160, 170]),
  ).toHaveLength(0);
});
test("W-REP-3 too fast discarded", () =>
  expect(cycle(engine(), squat, 0, 20)).toHaveLength(0));
test("W-REP-4 too slow discarded and reset", () => {
  const e = engine();
  expect(cycle(e, squat, 0, 600)).toHaveLength(0);
  expect(cycle(e, squat, 13000)).toHaveLength(1);
});
test("W-REP-5 noise cannot double count", () => {
  const e = engine();
  const noisy = squat.flatMap((a) => [a - 3, a + 3, a]);
  expect(cycle(e, noisy, 0, 40)).toHaveLength(1);
});
test("W-REP-6 push-up machine", () => {
  const values = [
    170, 150, 139, 130, 120, 110, 100, 95, 95, 100, 110, 120, 130, 140, 150,
    170,
  ];
  expect(cycle(engine("pushup"), values)).toHaveLength(1);
});
test("W-REP-7 bridge machine", () => {
  const values = [
    120, 130, 141, 150, 160, 170, 170, 170, 165, 150, 139, 130, 120,
  ];
  const reps = cycle(engine("glute_bridge"), values);
  expect(reps).toHaveLength(1);
  expect(reps[0].metrics.hip_ext_max_deg).toBe(170);
});
test("W-REP-8 hold timer and aligned seconds", () => {
  const e = new HoldEngine(
    "plank_hold",
    "s_test",
    catalog.checks,
    catalog.exercises.plank_hold.alignment,
  );
  for (let i = 0; i <= 200; i++)
    e.push(observation(180, i * 100, { hip_offset: i < 100 ? 0.01 : 0.2 }));
  const event = e.finish();
  expect(event.total_s).toBeCloseTo(20);
  expect(event.aligned_s).toBeCloseTo(9.9);
});
test("W-MET-1 depth ratio y convention", () => {
  expect(depthRatio(p(0.3, 0.6), p(0.5, 0.5), 0.2)).toBeCloseTo(0.5);
});
test("W-MET-2 descent to local minimum", () => {
  const r = cycle(engine(), squat)[0];
  expect(r.metrics.descent_s).toBeCloseTo(0.7);
});
test("W-MET-3 trunk lean", () => {
  expect(trunkLean(p(0.5, 0.5), p(0.5, 0.2))).toBe(0);
  expect(trunkLean(p(0.5, 0.5), p(0.8, 0.2))).toBeCloseTo(45);
});
test("W-MET-4 signed hip offset invariant to mirroring", () => {
  expect(hipOffset(p(0.2, 0.5), p(0.5, 0.56), p(0.8, 0.5))).toBeCloseTo(0.1);
  expect(hipOffset(p(0.8, 0.5), p(0.5, 0.56), p(0.2, 0.5))).toBeCloseTo(0.1);
});
function pose() {
  const points = Array.from({ length: 33 }, () => p(0.5, 0.5));
  points[11] = p(0.4, 0.2);
  points[12] = p(0.42, 0.2);
  points[23] = p(0.4, 0.5);
  points[24] = p(0.42, 0.5);
  points[25] = p(0.4, 0.7);
  points[27] = p(0.4, 0.9);
  return points;
}
test("W-CHK-1 full body rolling thirty frame window", () => {
  const w = new CheckWindow(catalog.checks),
    good = frameChecks(pose(), "squat", 0, 128, catalog.checks);
  for (let i = 0; i < 29; i++) expect(w.push(good).full_body).toBe(false);
  expect(w.push(good).full_body).toBe(true);
  for (let i = 0; i < 4; i++) w.push({ ...good, full_body: false });
  expect(w.push(good).full_body).toBe(false);
});
test("W-CHK-2 side view and light window", () => {
  const points = pose();
  expect(frameChecks(points, "squat", 0, 40, catalog.checks).light).toBe(true);
  points[12].x = 0.8;
  expect(frameChecks(points, "squat", 0, 221, catalog.checks)).toMatchObject({
    side_view: false,
    light: false,
  });
  const w = new CheckWindow(catalog.checks);
  let last;
  for (let i = 0; i < 30; i++)
    last = w.push({
      full_body: true,
      confidence: 1,
      side_view: i >= 6,
      light: i >= 6,
    });
  expect(last).toMatchObject({ side_view: true, light: true });
});
test("W-CHK-3 rating requires confidence, view and framing", () => {
  const good = {
    full_body: true,
    side_view: true,
    light: true,
    confidence: 0.8,
  };
  expect(rated([good], catalog.checks)).toBe(true);
  for (const bad of [
    { confidence: 0.64 },
    { side_view: false },
    { full_body: false },
  ])
    expect(rated([{ ...good, ...bad }], catalog.checks)).toBe(false);
});
describe("abstention", () => {
  test("low confidence reps still count", () => {
    const e = engine();
    const out = squat.flatMap((a, i) => {
      const r = e.push({ ...observation(a, i * 100), confidence: 0.2 });
      return r ? [r] : [];
    });
    expect(out).toHaveLength(1);
    expect(out[0].rated).toBe(false);
  });
});

test("missing pose samples reduce rep rating instead of disappearing", () => {
  const e = engine();
  const out = [];
  for (let i = 0; i < squat.length; i++) {
    if (i >= 3 && i <= 14)
      e.push({
        ...observation(0, i * 100 - 1),
        angle: null,
        confidence: 0,
        full_body: false,
        side_view: false,
      });
    const rep = e.push(observation(squat[i], i * 100));
    if (rep) out.push(rep);
  }
  expect(out).toHaveLength(1);
  expect(out[0].rated).toBe(false);
});
