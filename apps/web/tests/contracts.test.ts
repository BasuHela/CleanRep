import Ajv from "ajv";
import { expect, test } from "vitest";
import schema from "../../../packages/contracts/RepEvent.json";
import type { RepEvent } from "../../../packages/contracts/generated";
test("W-CON-1 generated types match runtime schema", () => {
  const event: RepEvent = {
    set_id: "s_test",
    rep_index: 0,
    exercise_id: "squat",
    rated: false,
    confidence: 0.5,
    metrics: {
      knee_min_deg: 90,
      depth_ratio: 0.1,
      descent_s: 1,
      trunk_lean_deg: 20,
    },
    duration_s: 3,
    client_ts_ms: 100,
  };
  const validate = new Ajv({ strict: false }).compile(schema);
  expect(validate(event)).toBe(true);
  expect(validate({ ...event, frame: "x" })).toBe(false);
  expect(validate({ ...event, confidence: 1.1 })).toBe(false);
});
