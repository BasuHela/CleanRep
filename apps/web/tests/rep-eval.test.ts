import { expect, test } from "vitest";
import { labelForEvent, replay } from "../../../scripts/rep-replay";
import catalogJson from "../../../packages/contracts/catalog.public.json";
import barely from "../../../evals/fixtures/squat_barely_bent.json";
import half from "../../../evals/fixtures/squat_half.json";
import type { Catalog } from "../src/pose/types";
import { deviceSummary, parseDeviceCsv } from "../../../scripts/device-eval";

const labels = [
  { start_ms: 0, end_ms: 999, counted: false, affected: false, faults: [] },
  {
    start_ms: 1000,
    end_ms: 1999,
    counted: true,
    affected: true,
    faults: ["shallow_depth"],
  },
];
test("W-REP-16 labels follow attempt time even when a partial was wrongly counted", () => {
  expect(labelForEvent(labels, 900)).toBe(labels[0]);
  expect(labelForEvent(labels, 1500)).toBe(labels[1]);
  expect(labelForEvent(labels, 999)).toBe(labels[0]);
  expect(labelForEvent(labels, 1000)).toBe(labels[1]);
  expect(labelForEvent(labels, 2000)).toBeNull();
});
test("W-DEV-2 recording replay uses the same range rule and scores zero hand counts", () => {
  const rows = parseDeviceCsv(
    "set_index,lighting,hand_count,recording_file,notes\n1,synthetic,0,negative.json,logic test\n2,synthetic,5,half.json,logic test",
  );
  expect(rows.map((r) => r.hand_count)).toEqual([0, 5]);
  const results = [barely, half].map((fixture) =>
    replay(catalogJson as Catalog, "squat", fixture),
  );
  const summary = deviceSummary(
    rows.map((row, i) => ({
      hand_count: row.hand_count,
      events: results[i].events,
    })),
  );
  expect(summary).toMatchObject({
    sets: 2,
    reps_counted: 5,
    exact_rate: 1,
    mean_absolute_error: 0,
  });
  expect(results[0].partials).toBe(5);
  expect(
    results[1].events.every((event) => event.metrics.depth_ratio < -0.15),
  ).toBe(true);
});
