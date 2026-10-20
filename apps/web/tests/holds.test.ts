import { expect, test } from "vitest";
import { sendFailure } from "../src/phone/transport";
import { liveTarget, summaryHeading, summarySubtitle } from "../src/tv/labels";

const hold = { rated: true, aligned_s: 17.6, total_s: 20.1 };

test("W-HLD-1 a hold summary is about seconds, not reps", () => {
  const s = { dominant_fault: null, rated_reps: 0, faults: {}, hold };
  expect(summaryHeading(s)).toBe("Hold complete");
  expect(summarySubtitle({ ...s, reps_counted: 0 })).toBe(
    "18 of 20 seconds aligned (estimated)",
  );
  expect(summaryHeading({ ...s, hold: { ...hold, rated: false } })).toBe(
    "Hold timed; alignment not rated (unclear view)",
  );
  expect(summarySubtitle({ rated_reps: 8, reps_counted: 10 })).toBe(
    "10 reps counted · 8 rated (estimated)",
  );
});

test("W-HLD-2 the live set shows the hold target instead of a rep count", () => {
  expect(liveTarget({ kind: "hold", target: 20, reps_counted: 0 })).toEqual({
    value: 20,
    unit: "second hold",
  });
  expect(liveTarget({ kind: "reps", target: 10, reps_counted: 4 })).toEqual({
    value: 4,
    unit: "reps counted",
  });
});

test("W-HLD-3 a set that ended on the server drops queued events but keeps the camera", () => {
  expect(sendFailure("set_not_active")).toBe("drop");
  for (const code of ["unauthorized", "not_found", "session_expired"])
    expect(sendFailure(code)).toBe("end");
  expect(sendFailure("unavailable")).toBe("retry");
});
