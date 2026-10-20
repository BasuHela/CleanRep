import { expect, test, vi } from "vitest";
import {
  MirrorControls,
  MirrorSender,
  canMirror,
  mirrorFrameSize,
  readMirrorPreference,
  writeMirrorPreference,
} from "../src/phone/mirror";
import {
  mirrorPanel,
  mirrorShape,
  repFeedback,
  showsLiveMirror,
} from "../src/tv/labels";

const view = (
  phase = "IN_SET",
  paused = false,
  sensor_mode: "paired" | "remote_only" = "paired",
) => ({
  phase,
  paused,
  sensor_mode,
});

test("W-MIR-1 the mirror sender caps frame rate at 10 fps and keeps at most one request in flight", async () => {
  const sent: number[] = [];
  let inFlight = 0,
    maxInFlight = 0,
    resolveCurrent: (() => void) | null = null;
  const sender = new MirrorSender(
    async () => new Blob([new Uint8Array([0xff, 0xd8, 0xff, 0xd9])]),
    async () => {
      inFlight++;
      maxInFlight = Math.max(maxInFlight, inFlight);
      sent.push(inFlight);
      await new Promise<void>((r) => {
        resolveCurrent = r;
      });
      inFlight--;
    },
  );
  sender.sync(view("IN_SET"), { enabled: true, sensing: true, hidden: false });

  // Frame at t=0 starts sending; frames while in flight or under 100 ms are dropped.
  expect(sender.frame(0)).toBe(true);
  expect(sender.frame(33)).toBe(false);
  expect(sender.frame(120)).toBe(false);
  expect(sender.frame(250)).toBe(false);
  await Promise.resolve();
  expect(sent.length).toBe(1);
  expect(maxInFlight).toBe(1);

  resolveCurrent!();
  await Promise.resolve();
  await Promise.resolve();

  // After in-flight completes, rate limit (100 ms since t=0) allows t=300, then blocks t=350.
  expect(sender.frame(300)).toBe(true);
  expect(sender.frame(350)).toBe(false);
  await Promise.resolve();
  resolveCurrent!();
  await Promise.resolve();
  await Promise.resolve();
  expect(sent.length).toBe(2);
  expect(maxInFlight).toBe(1);
});

test("W-MIR-2 mirror policy and sender stop on toggle off, Stop, hidden page, pause and phase change", async () => {
  expect(
    canMirror(view("READY"), { enabled: true, sensing: true, hidden: false }),
  ).toBe(true);
  expect(
    canMirror(view("DEMO"), { enabled: true, sensing: true, hidden: false }),
  ).toBe(true);
  expect(
    canMirror(view("IN_SET"), { enabled: true, sensing: true, hidden: false }),
  ).toBe(true);

  // Toggle off
  expect(
    canMirror(view("IN_SET"), { enabled: false, sensing: true, hidden: false }),
  ).toBe(false);
  // Stop (camera/replay stopped)
  expect(
    canMirror(view("IN_SET"), { enabled: true, sensing: false, hidden: false }),
  ).toBe(false);
  // Hidden page
  expect(
    canMirror(view("IN_SET"), { enabled: true, sensing: true, hidden: true }),
  ).toBe(false);
  // Paused on TV
  expect(
    canMirror(view("IN_SET", true), {
      enabled: true,
      sensing: true,
      hidden: false,
    }),
  ).toBe(false);
  // Phase outside READY, DEMO, IN_SET
  for (const p of [
    "LOBBY",
    "READINESS",
    "SETUP",
    "PLANNING",
    "SET_SUMMARY",
    "DRILL_OFFER",
    "ADAPTING",
    "RECAP",
    "ENDED",
  ]) {
    expect(
      canMirror(view(p), { enabled: true, sensing: true, hidden: false }),
    ).toBe(false);
  }
  // Remote-only or missing session
  expect(
    canMirror(view("IN_SET", false, "remote_only"), {
      enabled: true,
      sensing: true,
      hidden: false,
    }),
  ).toBe(false);
  expect(canMirror(null, { enabled: true, sensing: true, hidden: false })).toBe(
    false,
  );

  // Sender aborts in-flight request and refuses frames when stopped by any policy transition.
  let aborted = false;
  const send = vi.fn(async (_blob: Blob, signal: AbortSignal) => {
    signal.addEventListener("abort", () => {
      aborted = true;
    });
    await new Promise(() => {});
  });
  const sender = new MirrorSender(
    async () => new Blob([new Uint8Array([0xff, 0xd8, 0xff, 0xd9])]),
    send,
  );
  sender.sync(view("IN_SET"), { enabled: true, sensing: true, hidden: false });
  expect(sender.frame(0)).toBe(true);
  await Promise.resolve();
  expect(send).toHaveBeenCalledTimes(1);

  sender.sync(view("IN_SET", true), {
    enabled: true,
    sensing: true,
    hidden: false,
  });
  expect(aborted).toBe(true);
  expect(sender.frame(200)).toBe(false);

  // MirrorControls writes the DOM only when mirrorEnabled changes, not on every frame.
  const writes: [boolean, string][] = [];
  const controls = new MirrorControls((enabled, label) => {
    writes.push([enabled, label]);
  });
  controls.update(false);
  controls.update(false);
  controls.update(true);
  controls.update(true);
  controls.update(false);
  expect(writes).toEqual([
    [false, "Show camera on TV"],
    [true, "Stop showing on TV"],
    [false, "Show camera on TV"],
  ]);
});

test("W-MIR-3 TV rep checkmark decision distinguishes clean, faulted, unrated and unchanged rep counts", () => {
  expect(repFeedback(0, 1, { index: 0, rated: true, faults: [] })).toEqual({
    kind: "clean",
    symbol: "✓",
    label: "Clean rep",
  });
  expect(
    repFeedback(1, 2, {
      index: 1,
      rated: true,
      faults: ["shallow_depth"],
    }),
  ).toEqual({
    kind: "fault",
    symbol: "!",
    fault: "shallow_depth",
    label: "Shallow depth",
  });
  // First observed count on a mid-set page load records baseline without showing a badge
  expect(
    repFeedback(null, 5, { index: 4, rated: true, faults: [] }),
  ).toBeNull();
  expect(repFeedback(5, 6, { index: 5, rated: true, faults: [] })).toEqual({
    kind: "clean",
    symbol: "✓",
    label: "Clean rep",
  });
  // No new rep
  expect(repFeedback(2, 2, { index: 1, rated: true, faults: [] })).toBeNull();
  expect(repFeedback(0, 0, null)).toBeNull();
  // Unrated rep never produces a checkmark or fault marker
  expect(repFeedback(2, 3, { index: 2, rated: false, faults: [] })).toBeNull();
});

test("W-MIR-4 the camera view is on by default, a turned-off choice survives a reload, and broken storage keeps the default", () => {
  const saved = new Map<string, string>();
  const storage = () => ({
    getItem: (key: string) => saved.get(key) ?? null,
    setItem: (key: string, value: string) => void saved.set(key, value),
    removeItem: (key: string) => void saved.delete(key),
  });
  expect(readMirrorPreference(storage)).toBe(true);
  writeMirrorPreference(storage, false);
  expect(readMirrorPreference(storage)).toBe(false);
  writeMirrorPreference(storage, true);
  expect(readMirrorPreference(storage)).toBe(true);
  expect(saved.size).toBe(0);

  const blocked = () => {
    throw new DOMException("Storage is disabled", "SecurityError");
  };
  expect(readMirrorPreference(blocked)).toBe(true);
  expect(() => writeMirrorPreference(blocked, false)).not.toThrow();
});

test("W-MIR-5 the TV camera area shows the live feed, a hint that fits the phone's state, or nothing", () => {
  const tvView = (overrides: Record<string, unknown> = {}) => ({
    phase: "IN_SET",
    paused: false,
    sensor_mode: "paired",
    phone_connected: true,
    phone_sensing: true,
    mirror_live: true,
    ...overrides,
  });
  for (const phase of ["READY", "DEMO", "IN_SET"])
    expect(mirrorPanel(tvView({ phase }))).toEqual({ kind: "live" });
  expect(mirrorPanel(tvView({ mirror_live: false }))).toEqual({
    kind: "hint",
    text: "Camera view is off. On the phone, tap Show camera on TV",
  });
  // Right after a set the phone has not resumed frames yet: that is "starting", not "off".
  expect(mirrorPanel(tvView({ mirror_live: false }), true)).toEqual({
    kind: "hint",
    text: "Starting the camera view…",
  });
  expect(mirrorPanel(tvView(), true)).toEqual({ kind: "live" });
  for (const starting of [false, true])
    expect(
      mirrorPanel(
        tvView({ mirror_live: false, phone_sensing: false }),
        starting,
      ),
    ).toEqual({
      kind: "hint",
      text: "Your camera view appears here when the phone camera is on",
    });
  expect(
    mirrorPanel(
      tvView({
        mirror_live: false,
        phone_connected: false,
        phone_sensing: false,
      }),
    ),
  ).toEqual({
    kind: "hint",
    text: "Your camera view returns when the phone reconnects",
  });
  // Remote-only, paused and other phases keep their own layouts.
  expect(
    mirrorPanel(tvView({ sensor_mode: "remote_only", mirror_live: false })),
  ).toBeNull();
  expect(mirrorPanel(tvView({ paused: true }))).toBeNull();
  for (const phase of ["SETUP", "SET_SUMMARY", "RECAP"])
    expect(mirrorPanel(tvView({ phase }))).toBeNull();

  // Live frames: the camera check, demo and set show them; the plan screen has no room for them.
  const screen = { about: false, cameraCheck: false };
  expect(showsLiveMirror(tvView({ phase: "READY" }), screen)).toBe(false);
  expect(
    showsLiveMirror(tvView({ phase: "READY" }), {
      ...screen,
      cameraCheck: true,
    }),
  ).toBe(true);
  for (const phase of ["DEMO", "IN_SET"])
    expect(showsLiveMirror(tvView({ phase }), screen)).toBe(true);
  expect(showsLiveMirror(tvView(), { ...screen, about: true })).toBe(false);
  expect(showsLiveMirror(tvView({ paused: true }), screen)).toBe(false);
  expect(showsLiveMirror(tvView({ mirror_live: false }), screen)).toBe(false);
  expect(showsLiveMirror(tvView({ phase: "SET_SUMMARY" }), screen)).toBe(false);
  expect(showsLiveMirror(null, screen)).toBe(false);
});

test("W-MIR-6 frames and the TV panel keep the camera's shape, so an upright phone is not squashed into 16:9", () => {
  // An upright phone: Chrome reports a tall video, 3:4 or 9:16 depending on the lens mode.
  expect(mirrorFrameSize(480, 640)).toEqual({ width: 480, height: 640 });
  expect(mirrorFrameSize(1080, 1920)).toEqual({ width: 360, height: 640 });
  expect(mirrorFrameSize(1920, 1080)).toEqual({ width: 640, height: 360 });
  expect(mirrorFrameSize(1280, 960)).toEqual({ width: 640, height: 480 });
  // Before the camera reports a size, and in replay, the old 640×360 frame.
  expect(mirrorFrameSize(0, 0)).toEqual({ width: 640, height: 360 });

  expect(mirrorShape(360, 640)).toEqual({ ratio: "360 / 640", portrait: true });
  expect(mirrorShape(640, 360)).toEqual({
    ratio: "640 / 360",
    portrait: false,
  });
  expect(mirrorShape(0, 0)).toBeNull();
});
