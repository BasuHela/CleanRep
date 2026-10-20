import { expect, test } from "vitest";
import catalogJson from "../../../packages/contracts/catalog.public.json";
import { frameChecks } from "../src/pose/checks";
import { angle, hipOffset, trunkLean } from "../src/pose/geometry";
import { PosePipeline } from "../src/pose/pipeline";
import type { Catalog, Frame, Point } from "../src/pose/types";
const catalog = catalogJson as Catalog;
const px = (x: number, y: number): Point => ({ x, y, z: 0, visibility: 1 });
type Pixels = Record<number, [number, number]>;
// MediaPipe divides x by the image width and y by the image height.
function frame(pixels: Pixels, width: number, height: number): Frame {
  const landmarks = Array.from({ length: 33 }, () => ({
    x: 0.5,
    y: 0.5,
    z: 0,
    visibility: 0.3,
  }));
  for (const [i, [x, y]] of Object.entries(pixels))
    landmarks[Number(i)] = { x: x / width, y: y / height, z: 0, visibility: 1 };
  return { timestamp: 1000, landmarks, luma: 128, aspect: width / height };
}
function metrics(pixels: Pixels, width: number, height: number) {
  const pipeline = new PosePipeline(catalog, "squat", "s_geo", () => {});
  return pipeline.push(frame(pixels, width, height))!.observation.metrics;
}
// True values come from the same functions applied to square pixel coordinates.
function truth(pixels: Pixels) {
  const [s, e, w, h, k, a] = [11, 13, 15, 23, 25, 27].map((i) =>
    px(...pixels[i]),
  );
  return {
    knee: angle(h, k, a)!,
    elbow: angle(s, e, w)!,
    hip: angle(s, h, k)!,
    lean: trunkLean(h, s),
    offset: hipOffset(s, h, a),
  };
}
const landscape: Pixels = {
  11: [700, 250],
  13: [760, 330],
  15: [840, 300],
  23: [560, 400],
  25: [700, 420],
  27: [650, 600],
};
const portrait: Pixels = {
  11: [350, 500],
  13: [410, 580],
  15: [490, 550],
  23: [210, 650],
  25: [350, 670],
  27: [300, 850],
};

test("W-GEO-1 joint angles from a 16:9 landscape frame match the true angles", () => {
  const m = metrics(landscape, 1280, 720),
    t = truth(landscape);
  expect(m.knee_min_deg).toBeCloseTo(t.knee, 6);
  expect(m.elbow_min_deg).toBeCloseTo(t.elbow, 6);
  expect(m.hip_ext_max_deg).toBeCloseTo(t.hip, 6);
});

test("W-GEO-2 trunk lean and hip offset from a 16:9 frame match the true values", () => {
  const m = metrics(landscape, 1280, 720),
    t = truth(landscape);
  expect(m.trunk_lean_deg).toBeCloseTo(t.lean, 6);
  expect(m.hip_offset).toBeCloseTo(t.offset, 6);
});

test("W-GEO-3 a portrait frame gives the true angles too", () => {
  const m = metrics(portrait, 720, 1280),
    t = truth(portrait);
  expect(m.knee_min_deg).toBeCloseTo(t.knee, 6);
  expect(m.trunk_lean_deg).toBeCloseTo(t.lean, 6);
});

test("W-GEO-4 side-view check compares true shoulder separation with true torso length", () => {
  const three_quarter = frame(
    {
      11: [600, 200],
      12: [700, 200],
      23: [600, 400],
      24: [700, 400],
      25: [600, 550],
      27: [600, 680],
    },
    1280,
    720,
  );
  // Shoulders 100 px apart over a 200 px torso: ratio 0.5, above side_ratio 0.35.
  expect(
    frameChecks(
      three_quarter.landmarks,
      "squat",
      0,
      128,
      catalog.checks,
      16 / 9,
    ).side_view,
  ).toBe(false);
  three_quarter.landmarks[12].x = 620 / 1280;
  three_quarter.landmarks[24].x = 620 / 1280;
  expect(
    frameChecks(
      three_quarter.landmarks,
      "squat",
      0,
      128,
      catalog.checks,
      16 / 9,
    ).side_view,
  ).toBe(true);
});
