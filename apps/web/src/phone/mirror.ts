/* LAN camera mirror sender and pure policy. On by default; the phone can turn it off. */
import type { Point } from "../pose/types";
import { awaitNative, key } from "../shared/api";

export const MIRROR_INTERVAL_MS = 100;
export const MIRROR_MAX_BYTES = 150 * 1024;
export const MIRROR_QUALITY = 0.6;
/** The app gives a frame 3 s to connect and then 3 s to answer, with the upload between, so it can hold the frame for
 * over 6 s and answers "busy" to any other until then. Giving up earlier only makes it encode and send frames to be refused. */
export const MIRROR_BRIDGE_TIMEOUT_MS = 7000;
const MIRROR_PHASES = ["READY", "DEMO", "IN_SET"];
const MIRROR_PREFERENCE_KEY = "cleanrep.mirror";

type PreferenceStore = Pick<Storage, "getItem" | "setItem" | "removeItem">;

/** On unless this phone turned it off. Storage that is missing or blocked keeps the default. */
export function readMirrorPreference(store: () => PreferenceStore): boolean {
  try {
    return store().getItem(MIRROR_PREFERENCE_KEY) !== "off";
  } catch {
    return true;
  }
}

/** Remembers only the "off" choice, so turning the view back on leaves nothing stored. */
export function writeMirrorPreference(
  store: () => PreferenceStore,
  enabled: boolean,
): void {
  try {
    if (enabled) store().removeItem(MIRROR_PREFERENCE_KEY);
    else store().setItem(MIRROR_PREFERENCE_KEY, "off");
  } catch {
    /* Without storage the choice lasts until the page reloads. */
  }
}

export const SKELETON_EDGES: readonly [number, number][] = [
  [11, 13],
  [13, 15],
  [11, 23],
  [23, 25],
  [25, 27],
  [12, 14],
  [14, 16],
  [12, 24],
  [24, 26],
  [26, 28],
];

type MirrorView = {
  phase: string;
  paused: boolean;
  sensor_mode: string;
};

export type MirrorState = {
  enabled: boolean;
  sensing: boolean;
  hidden?: boolean;
};

/** Pure policy: frames may be sent only when opted in, sensing on a visible page, and in an active unpaused paired phase. */
export function canMirror(
  view: MirrorView | null,
  state: MirrorState,
): boolean {
  if (!state.enabled || !state.sensing || state.hidden || !view) return false;
  if (view.sensor_mode !== "paired" || view.paused) return false;
  return MIRROR_PHASES.includes(view.phase);
}

/** Frames keep the camera's own shape (an upright phone sends portrait), long side 640 px; 640×360 until the camera reports its size. */
export function mirrorFrameSize(
  videoWidth: number,
  videoHeight: number,
): { width: number; height: number } {
  if (!(videoWidth > 0 && videoHeight > 0)) return { width: 640, height: 360 };
  const scale = 640 / Math.max(videoWidth, videoHeight);
  return {
    width: Math.round(videoWidth * scale),
    height: Math.round(videoHeight * scale),
  };
}

export function drawSkeleton(
  ctx: CanvasRenderingContext2D,
  points: Point[] | null,
): void {
  if (!points) return;
  const { width, height } = ctx.canvas;
  ctx.strokeStyle = "#b6ff71";
  ctx.lineWidth = 3;
  for (const [a, b] of SKELETON_EDGES) {
    if (
      !points[a] ||
      !points[b] ||
      points[a].visibility < 0.5 ||
      points[b].visibility < 0.5
    )
      continue;
    ctx.beginPath();
    ctx.moveTo(points[a].x * width, points[a].y * height);
    ctx.lineTo(points[b].x * width, points[b].y * height);
    ctx.stroke();
  }
}

export function compositeMirrorFrame(
  ctx: CanvasRenderingContext2D,
  options: {
    video: HTMLVideoElement;
    points: Point[] | null;
    replay: boolean;
  },
): void {
  const { width, height } = ctx.canvas;
  ctx.fillStyle = "#193d30";
  ctx.fillRect(0, 0, width, height);
  if (!options.replay && options.video.readyState >= 2) {
    try {
      ctx.drawImage(options.video, 0, 0, width, height);
    } catch {
      /* Keep dark background if video frame is not ready. */
    }
  }
  drawSkeleton(ctx, options.points);
  if (options.replay) {
    ctx.fillStyle = "#d2f49a";
    ctx.font = "bold 18px Arial, sans-serif";
    ctx.fillText("REPLAY", 16, 28);
  }
}

export function encodeMirrorCanvas(
  canvas: HTMLCanvasElement,
): Promise<Blob | null> {
  return new Promise((resolve) => {
    canvas.toBlob((blob) => resolve(blob), "image/jpeg", MIRROR_QUALITY);
  });
}

/** Standard base64 of a frame, in slices so a 150 KB frame never overflows `String.fromCharCode`. */
async function blobBase64(blob: Blob): Promise<string> {
  const bytes = new Uint8Array(await blob.arrayBuffer());
  let binary = "";
  for (let i = 0; i < bytes.length; i += 0x8000)
    binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(binary);
}

/** In the phone app a page `fetch` to the TV is blocked, so a frame goes through the bridge; any failure is a dropped frame. */
async function postMirrorViaBridge(
  bridge: NonNullable<Window["CleanRepNative"]>,
  path: string,
  token: string,
  blob: Blob,
  signal: AbortSignal,
): Promise<void> {
  if (signal.aborted || blob.size < 4 || blob.size > MIRROR_MAX_BYTES) return;
  const id = key();
  const answer = awaitNative(id, MIRROR_BRIDGE_TIMEOUT_MS, () => new Error());
  const onAbort = () => answer.forget();
  signal.addEventListener("abort", onAbort, { once: true });
  try {
    const jpeg = await blobBase64(blob);
    if (signal.aborted) return;
    bridge.requestMirror!(id, path, jpeg, token);
    await answer.promise;
  } catch {
    /* Busy, unavailable, rejected or timed out: the next frame tries on schedule. */
  } finally {
    answer.forget();
    signal.removeEventListener("abort", onAbort);
  }
}

export async function postMirrorFrame(
  sessionId: string,
  token: string,
  blob: Blob,
  signal: AbortSignal,
): Promise<void> {
  const path = "/v1/sessions/" + sessionId + "/mirror";
  const bridge = window.CleanRepNative;
  if (bridge?.requestMirror)
    return postMirrorViaBridge(bridge, path, token, blob, signal);
  await fetch(path, {
    method: "POST",
    headers: {
      "Content-Type": "image/jpeg",
      Authorization: "Bearer " + token,
    },
    body: blob,
    cache: "no-store",
    signal,
  });
}

/** At most 10 fps and at most one request in flight; drops frames instead of queueing. */
export class MirrorSender {
  private allowed = false;
  private inFlight = false;
  private lastSent = -Infinity;
  private generation = 0;
  private abort: AbortController | null = null;

  constructor(
    private readonly encode: () => Promise<Blob | null>,
    private readonly send: (blob: Blob, signal: AbortSignal) => Promise<void>,
    private readonly intervalMs = MIRROR_INTERVAL_MS,
  ) {}

  get active(): boolean {
    return this.allowed;
  }

  sync(view: MirrorView | null, state: MirrorState): boolean {
    const next = canMirror(view, state);
    if (!next && (this.allowed || this.inFlight)) {
      this.stop();
    }
    this.allowed = next;
    return next;
  }

  frame(now = performance.now()): boolean {
    if (
      !this.allowed ||
      this.inFlight ||
      now - this.lastSent < this.intervalMs
    ) {
      return false;
    }
    this.inFlight = true;
    this.lastSent = now;
    const gen = this.generation;
    const controller = new AbortController();
    this.abort = controller;
    void (async () => {
      try {
        const blob = await this.encode();
        if (
          !blob ||
          this.generation !== gen ||
          !this.allowed ||
          blob.size === 0 ||
          blob.size > MIRROR_MAX_BYTES
        ) {
          return;
        }
        await this.send(blob, controller.signal);
      } catch {
        /* Dropped frame; next frame tries on schedule. */
      } finally {
        if (this.generation === gen) {
          this.inFlight = false;
          this.abort = null;
        }
      }
    })();
    return true;
  }

  stop(): void {
    this.allowed = false;
    this.inFlight = false;
    this.generation++;
    this.abort?.abort();
    this.abort = null;
  }
}

/** Updates the phone mirror button and indicator DOM only when `enabled` changes. */
export class MirrorControls {
  private last: boolean | null = null;
  constructor(
    private readonly write: (enabled: boolean, label: string) => void,
  ) {}
  update(enabled: boolean): void {
    if (enabled === this.last) return;
    this.last = enabled;
    this.write(enabled, enabled ? "Stop showing on TV" : "Show camera on TV");
  }
}
