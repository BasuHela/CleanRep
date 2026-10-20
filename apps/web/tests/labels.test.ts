import { describe, expect, it, test } from "vitest";
import {
  comparisonLabel,
  comparisonNote,
  countdownLabel,
  faultLabel,
  faultStats,
  movementMinutes,
  restLabel,
  sessionProgress,
  sourceLabel,
  summaryHeading,
  tallySlots,
} from "../src/tv/labels";

describe("TV summary wording (found on the Android TV API 30 emulator)", () => {
  it("a set with zero faults is called clean, not 'a little more practice'", () => {
    expect(
      summaryHeading({
        dominant_fault: null,
        rated_reps: 10,
        faults: { shallow_depth: 0, forward_lean: 0, rushed_descent: 0 },
      }),
    ).toBe("Clean set: no faults flagged");
  });
  it("names the dominant fault in viewer language", () => {
    expect(
      summaryHeading({
        dominant_fault: "shallow_depth",
        rated_reps: 10,
        faults: { shallow_depth: 6 },
      }),
    ).toBe("Main focus: shallow depth");
  });
  it("says when there were too few clear reps", () => {
    expect(
      summaryHeading({ dominant_fault: null, rated_reps: 2, faults: {} }),
    ).toBe("Not enough clear reps to rate");
  });
  it("faults below the dominance threshold get a neutral heading", () => {
    expect(
      summaryHeading({
        dominant_fault: null,
        rated_reps: 10,
        faults: { forward_lean: 2 },
      }),
    ).toBe("No single pattern stood out");
  });
  it("maps ids and plan sources to readable labels", () => {
    expect(faultLabel("hip_sag")).toBe("Hips sagging");
    expect(faultLabel("new_fault")).toBe("New fault");
    expect(sourceLabel("fixture")).toBe("Built-in plan");
    expect(sourceLabel("model")).toBe("AI plan · Amazon Bedrock");
    expect(sourceLabel("fallback", true)).toBe(
      "Built-in wording (safe fallback)",
    );
  });
});
describe("re-test and recap wording (emulator finding)", () => {
  it("states the re-test change as a sentence instead of bare ratios", () => {
    expect(
      comparisonLabel({
        verdict: "improved",
        before_count: 6,
        before_rated: 10,
        after_count: 0,
        after_rated: 10,
      }),
    ).toBe("Improved: flagged in 6 of 10 rated reps before, 0 of 10 after");
    expect(
      comparisonLabel({
        verdict: "same",
        before_count: 3,
        before_rated: 10,
        after_count: 2,
        after_rated: 9,
      }),
    ).toMatch(/^About the same:/);
  });
  it("uses the singular for one movement minute", () => {
    expect(movementMinutes(1)).toBe("1 movement minute (estimated)");
    expect(movementMinutes(0)).toBe("0 movement minutes (estimated)");
    expect(movementMinutes(4)).toBe("4 movement minutes (estimated)");
  });
});
test("W-CMP-1 the TV withholds the verdict without three rated reps in each set", () => {
  const thin = {
    verdict: "not_enough_data",
    before_count: 1,
    before_rated: 1,
    after_count: 0,
    after_rated: 10,
  };
  expect(comparisonLabel(thin)).toBe(
    "Not enough clear reps to compare: flagged in 1 of 1 rated reps before, 0 of 10 after",
  );
  expect(comparisonNote(thin.verdict)).toBe(
    "A verdict needs at least 3 rated reps in each set.",
  );
  expect(comparisonNote("same")).toBe("No change yet.");
  expect(comparisonNote("improved")).toBe("Estimated from the rated reps.");
});
test("W-RST-1 a set opens with 3-2-1 and the summary counts the block's rest down", () => {
  expect([0, 999, 1000, 2999, 3000, 4000].map(countdownLabel)).toEqual([
    "3",
    "3",
    "2",
    "1",
    "Go",
    "",
  ]);
  expect(restLabel(45, 0)).toBe("Rest 0:45");
  expect(restLabel(45, 500)).toBe("Rest 0:45");
  expect(restLabel(90, 15_000)).toBe("Rest 1:15");
  expect(restLabel(45, 45_000)).toBe("Rest done: Continue when ready");
  expect(restLabel(undefined, 0)).toBe("");
});
test("W-SUM-1 the summary lists only faults that occurred, most frequent first", () => {
  expect(
    faultStats({ forward_lean: 0, shallow_depth: 6, rushed_descent: 2 }),
  ).toEqual([
    ["shallow_depth", 6],
    ["rushed_descent", 2],
  ]);
  expect(faultStats({ forward_lean: 0 })).toEqual([]);
});
test("W-TLY-1 the rep tally has a slot per target rep and the rail tracks the exercise", () => {
  expect(tallySlots(["clean", "fault", "unrated"], 5)).toEqual([
    "clean",
    "fault",
    "unrated",
    "todo",
    "todo",
  ]);
  expect(tallySlots(null, 2)).toEqual(["todo", "todo"]);
  // More reps than the target: every rep keeps its mark.
  expect(tallySlots(["clean", "clean", "fault"], 2)).toEqual([
    "clean",
    "clean",
    "fault",
  ]);
  const plan = { blocks: [{}, {}, {}, {}] };
  expect(sessionProgress({ phase: "DEMO", block_index: 1, plan })).toEqual({
    current: 1,
    total: 4,
  });
  expect(sessionProgress({ phase: "READY", block_index: 0, plan })).toBeNull();
  expect(
    sessionProgress({ phase: "IN_SET", block_index: 0, plan: null }),
  ).toBeNull();
});
