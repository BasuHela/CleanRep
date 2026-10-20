import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { LocalCamera } from "../src/phone/camera";
let tracks: (EventTarget & { stop: ReturnType<typeof vi.fn> })[];
let win: EventTarget & { isSecureContext: boolean };
let doc: EventTarget & { visibilityState: string };
let camera: LocalCamera;
beforeEach(() => {
  tracks = [Object.assign(new EventTarget(), { stop: vi.fn() })];
  win = Object.assign(new EventTarget(), { isSecureContext: true });
  doc = Object.assign(new EventTarget(), { visibilityState: "visible" });
  vi.stubGlobal("window", win);
  vi.stubGlobal("document", doc);
  vi.stubGlobal("location", { origin: "http://localhost:8105" });
  vi.stubGlobal("navigator", {
    mediaDevices: {
      getUserMedia: vi.fn(async () => ({
        getTracks: () => tracks,
        getVideoTracks: () => tracks,
      })),
      enumerateDevices: vi.fn(async () => []),
    },
    wakeLock: {
      request: vi.fn(async () => ({ release: vi.fn(async () => {}) })),
    },
  });
  vi.stubGlobal(
    "Worker",
    class {
      postMessage() {}
      terminate() {}
    },
  );
  camera = new LocalCamera(
    { srcObject: null, play: async () => {} } as unknown as HTMLVideoElement,
    () => {},
    () => {},
  );
});
afterEach(() => {
  camera.dispose();
  vi.unstubAllGlobals();
});
test("W-CAM-1 stops every track on pagehide", async () => {
  await camera.start();
  win.dispatchEvent(new Event("pagehide"));
  expect(tracks[0].stop).toHaveBeenCalledTimes(1);
});
test("W-CAM-2 stops on visibility hidden", async () => {
  await camera.start();
  doc.visibilityState = "hidden";
  doc.dispatchEvent(new Event("visibilitychange"));
  expect(tracks[0].stop).toHaveBeenCalledTimes(1);
});
test("W-CAM-3 stops when track ends", async () => {
  await camera.start();
  tracks[0].dispatchEvent(new Event("ended"));
  expect(tracks[0].stop).toHaveBeenCalledTimes(1);
});
test("W-CAM-4 insecure context gives actionable message", async () => {
  win.isSecureContext = false;
  await expect(camera.start()).rejects.toThrow(
    /needs the CleanRep phone app\. Scan the QR code on your TV/,
  );
  expect(navigator.mediaDevices.getUserMedia).not.toHaveBeenCalled();
});
test("W-DBG-5 the requested delegate reaches the pose worker", async () => {
  const sent: unknown[] = [];
  vi.stubGlobal(
    "Worker",
    class {
      postMessage(message: unknown) {
        sent.push(message);
      }
      terminate() {}
    },
  );
  const forced = new LocalCamera(
    { srcObject: null, play: async () => {} } as unknown as HTMLVideoElement,
    () => {},
    () => {},
    "CPU",
  );
  await forced.start();
  forced.dispose();
  expect(sent).toEqual([
    { type: "init", origin: "http://localhost:8105", delegate: "CPU" },
  ]);
});
test("W-GEO-5 the processed bitmap's width/height reaches the frame", async () => {
  let worker!: { onmessage: (e: { data: unknown }) => void };
  vi.stubGlobal(
    "Worker",
    class {
      onmessage = () => {};
      constructor() {
        worker = this as unknown as typeof worker;
      }
      postMessage() {}
      terminate() {}
    },
  );
  const frames: unknown[] = [];
  const sensing = new LocalCamera(
    { srcObject: null, play: async () => {} } as unknown as HTMLVideoElement,
    (frame) => frames.push(frame),
    () => {},
  );
  await sensing.start();
  worker.onmessage({
    data: {
      type: "result",
      timestamp: 5,
      landmarks: [],
      luma: 100,
      delegate: "GPU",
      inference_ms: 10,
      width: 1280,
      height: 720,
    },
  });
  sensing.dispose();
  expect(frames).toEqual([
    { timestamp: 5, landmarks: [], luma: 100, aspect: 1280 / 720 },
  ]);
});
test("W-CNT-3 a reopened camera keeps the lens the user chose", async () => {
  const gum = navigator.mediaDevices.getUserMedia as ReturnType<typeof vi.fn>;
  const lens = (call: number) =>
    (gum.mock.calls[call][0] as { video: { deviceId?: unknown } }).video
      .deviceId;
  await camera.start("ultra-wide");
  camera.stop();
  await camera.start();
  expect(lens(1)).toEqual({ exact: "ultra-wide" });
  await camera.start("");
  expect(lens(2)).toBeUndefined();
});
test("permission resolving after stop never starts sensing", async () => {
  let resolve!: (s: unknown) => void;
  vi.mocked(navigator.mediaDevices.getUserMedia).mockImplementation(
    () =>
      new Promise((r) => {
        resolve = r as (s: unknown) => void;
      }),
  );
  const pending = camera.start();
  camera.stop();
  resolve({ getTracks: () => tracks, getVideoTracks: () => tracks });
  await pending;
  expect(tracks[0].stop).toHaveBeenCalledTimes(1);
});
test("W-HOME-8 the phone app going to the background stops the camera like a hidden page", async () => {
  await camera.start();
  doc.dispatchEvent(new Event("cleanrep-native-pause"));
  expect(tracks[0].stop).toHaveBeenCalledTimes(1);
  // Returning does not reopen it: the person taps Enable camera again.
  doc.dispatchEvent(new Event("cleanrep-native-resume"));
  expect(navigator.mediaDevices.getUserMedia).toHaveBeenCalledTimes(1);
  // A disposed camera no longer listens.
  await camera.start();
  camera.dispose();
  const stops = tracks[0].stop.mock.calls.length;
  doc.dispatchEvent(new Event("cleanrep-native-pause"));
  expect(tracks[0].stop).toHaveBeenCalledTimes(stops);
});
