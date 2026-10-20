import { expect, test } from "vitest";
import { FrameStats, debugText, sensorOptions } from "../src/phone/debug";

test("W-DBG-1 query string turns on the readout and forces the CPU delegate", () => {
  expect(sensorOptions("")).toEqual({ debug: false, delegate: "GPU" });
  expect(sensorOptions("?debug=1&delegate=cpu")).toEqual({
    debug: true,
    delegate: "CPU",
  });
  expect(sensorOptions("?delegate=CPU").delegate).toBe("CPU");
  expect(sensorOptions("?debug=yes&delegate=npu")).toEqual({
    debug: false,
    delegate: "GPU",
  });
});

test("W-DBG-2 fps comes from processed frame times, not inference time", () => {
  const stats = new FrameStats(2000);
  expect(stats.snapshot().fps).toBe(0);
  // 40 ms between processed frames (25 fps) although inference takes only 12 ms.
  for (let i = 0; i < 26; i++) stats.record(1000 + i * 40, 12, "GPU");
  const s = stats.snapshot();
  expect(s.fps).toBeCloseTo(25, 5);
  expect(s.inference_ms).toBeCloseTo(12, 5);
  expect(s.delegate).toBe("GPU");
  expect(s.frames).toBe(26);
});

test("W-DBG-3 frames older than the window stop counting toward fps", () => {
  const stats = new FrameStats(1000);
  for (let i = 0; i < 10; i++) stats.record(i * 100, 50, "CPU");
  stats.record(5000, 20, "CPU");
  stats.record(5050, 30, "CPU");
  const s = stats.snapshot();
  expect(s.fps).toBeCloseTo(20, 5);
  expect(s.inference_ms).toBeCloseTo(25, 5);
  expect(s.frames).toBe(12);
});

test("W-DBG-4 readout names the delegate used and flags a GPU fallback", () => {
  const snapshot = {
    fps: 24.64,
    inference_ms: 11.2,
    delegate: "CPU",
    frames: 1234,
  } as const;
  expect(
    debugText(snapshot, { requested: "GPU", width: 1280, height: 720 }),
  ).toBe(
    "Lite model · CPU (GPU unavailable) · 24.6 fps · 11 ms inference · 1280×720 · 1234 frames",
  );
  expect(debugText(snapshot, { requested: "CPU" })).toBe(
    "Lite model · CPU (forced) · 24.6 fps · 11 ms inference · 1234 frames",
  );
  expect(
    debugText({ ...snapshot, delegate: "GPU" }, { requested: "GPU" }),
  ).toBe("Lite model · GPU · 24.6 fps · 11 ms inference · 1234 frames");
  expect(debugText(snapshot, { requested: "GPU", replay: true })).toBe(
    "REPLAY · no pose model · 24.6 fps · 1234 frames",
  );
});
