import { expect, test } from "vitest";
import catalogJson from "../../../packages/contracts/catalog.public.json";
import {
  StatusLine,
  canStart,
  keepSensing,
  phoneStatus,
} from "../src/phone/policy";
import { PosePipeline } from "../src/pose/pipeline";
import type { Catalog } from "../src/pose/types";
import { phoneCamera } from "../src/tv/labels";

const at = (phase: string, paused = false, sensor_mode = "paired") => ({
  phase,
  paused,
  sensor_mode,
});

test("W-CNT-1 once enabled, the camera stays on between sets so nobody walks back to the phone", () => {
  for (const v of [
    at("SET_SUMMARY"),
    at("DRILL_OFFER"),
    at("ADAPTING"),
    at("IN_SET", true),
  ]) {
    expect(keepSensing(v, true)).toBe(true);
    expect(keepSensing(v, false)).toBe(false);
  }
  expect(keepSensing(at("DEMO"), false)).toBe(true);
});

test("W-CNT-2 sensing still stops when the session no longer uses the phone, and starts only where a set can run", () => {
  for (const v of [
    at("IN_SET", false, "remote_only"),
    at("RECAP"),
    at("ENDED"),
    at("LOBBY"),
  ])
    expect(keepSensing(v, true)).toBe(false);
  expect(["READY", "DEMO", "IN_SET"].map((p) => canStart(at(p)))).toEqual([
    true,
    true,
    true,
  ]);
  expect(canStart(at("SET_SUMMARY"))).toBe(false);
  expect(canStart(at("DEMO", true))).toBe(false);
});

test("W-CNT-6 the phone says when the TV has paused the session and where to resume", () => {
  // Before this, a pause in the demo read "Sensor stopped · demo", which looks like a failure.
  expect(phoneStatus(at("DEMO", true), false)).toBe(
    "Paused on the TV. Select Resume on the TV to continue.",
  );
  expect(phoneStatus(at("IN_SET", true), true)).toBe(
    "Paused on the TV. The camera stays on; select Resume on the TV.",
  );
  expect(phoneStatus(at("DEMO"), false)).toBe(
    "Ready. Tap Enable camera, then stand the phone up.",
  );
  expect(phoneStatus(at("DEMO"), false, true)).toBe("Ready to run the replay.");
  expect(phoneStatus(at("IN_SET"), true)).toBe("Tracking on this phone.");
  expect(phoneStatus(at("IN_SET"), true, true)).toBe(
    "Replay running with synthetic landmarks.",
  );
  expect(phoneStatus(at("SET_SUMMARY"), true)).toBe(
    "The camera stays on between sets. Tap Stop camera to turn it off.",
  );
  // Without the camera, each phase says what happens next instead of naming the phase.
  expect(phoneStatus(at("SETUP"), false)).toBe(
    "Finish setup on the TV. The camera starts at the camera check.",
  );
  expect(phoneStatus(at("SET_SUMMARY"), false)).toBe(
    "Camera off. Turn it on again when the next set starts.",
  );
  expect(phoneStatus(at("RECAP"), false)).toBe(
    "Session finished. You can close this page.",
  );
});

test("W-CNT-7 the status line follows phase, set and pause, and a recovered connection replaces the interrupted notice", () => {
  const shown: string[] = [];
  const line = new StatusLine((text) => shown.push(text));
  line.update(at("DEMO"), false);
  // Unchanged session state: camera notices such as "Keep the phone screen on" stay visible.
  line.update(at("DEMO"), true);
  line.update(at("DEMO", true), true);
  line.update(at("DEMO"), true);
  // A failed refresh showed "Connection interrupted; reconnecting…"; the next success rewrites it.
  line.forget();
  line.update(at("DEMO"), true);
  line.update({ ...at("IN_SET"), active_set: { set_id: "s1" } }, true);
  line.update({ ...at("IN_SET"), active_set: { set_id: "s2" } }, false);
  expect(shown).toEqual([
    "Ready. Tap Enable camera, then stand the phone up.",
    "Paused on the TV. The camera stays on; select Resume on the TV.",
    "Tracking on this phone.",
    "Tracking on this phone.",
    "Tracking on this phone.",
    "Ready. Tap Enable camera, then stand the phone up.",
  ]);
});

test("W-CNT-5 frames with nobody in view still report checks, so a running camera keeps reporting", () => {
  const pipeline = new PosePipeline(
    catalogJson as Catalog,
    "squat",
    "s_cnt",
    () => {},
  );
  const output = pipeline.push({
    timestamp: 1000,
    landmarks: [],
    luma: 128,
    aspect: 16 / 9,
  });
  expect(output?.points).toBeNull();
  expect(output?.checks).toMatchObject({
    full_body: false,
    side_view: false,
    light: false,
  });
});

test("W-CNT-4 the TV says whether the phone camera is actually sensing", () => {
  const paired = { sensor_mode: "paired", phone_connected: true };
  expect(phoneCamera({ ...paired, phone_sensing: true })).toEqual({
    on: true,
    text: "Phone camera on",
  });
  expect(phoneCamera({ ...paired, phone_sensing: false })).toEqual({
    on: false,
    text: "Phone camera off: tap Enable camera on the phone",
  });
  expect(
    phoneCamera({ ...paired, phone_connected: false, phone_sensing: false }),
  ).toEqual({ on: false, text: "Phone disconnected" });
  expect(
    phoneCamera({
      sensor_mode: "remote_only",
      phone_connected: false,
      phone_sensing: false,
    }),
  ).toBeNull();
});
