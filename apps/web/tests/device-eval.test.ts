import { expect, test } from "vitest";
import { deviceSummary, parseDeviceCsv } from "../../../scripts/device-eval";

const header =
  "set_index,lighting,hand_count,phone_count,rated_reps,hand_shallow,rated_shallow,recording_file,notes";

test("W-DEV-1 device replay reads hand counts and reports agreement and abstention", () => {
  expect(parseDeviceCsv(header + "\n")).toEqual([]);
  const rows = parseDeviceCsv(
    [
      header,
      "1,daylight,10,,,,,set1.json,steady, side-on",
      "2,lamp,,,,,,set2.json,not counted yet",
      "3,lamp,9,,,,,,no recording",
      "4,lamp,10,,,,,set4.json,",
    ].join("\n"),
  );
  expect(rows).toEqual([
    {
      set_index: "1",
      lighting: "daylight",
      hand_count: 10,
      recording_file: "set1.json",
      exercise_id: "squat",
    },
    {
      set_index: "4",
      lighting: "lamp",
      hand_count: 10,
      recording_file: "set4.json",
      exercise_id: "squat",
    },
  ]);
  const rep = (rated: boolean) => ({ rated });
  const summary = deviceSummary([
    {
      hand_count: 10,
      events: [...Array(8)]
        .map(() => rep(true))
        .concat([rep(false), rep(false)]),
    },
    { hand_count: 10, events: [...Array(9)].map(() => rep(true)) },
  ]);
  expect(summary).toEqual({
    sets: 2,
    hand_count: 20,
    reps_counted: 19,
    exact_rate: 0.5,
    mean_absolute_error: 0.5,
    abstention_rate: 0.105,
  });
});
