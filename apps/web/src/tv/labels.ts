/* Viewer-facing TV labels. Raw ids stay in data attributes and the API. */
import type { RepMark } from "../shared/views";
import type { ServerPlace } from "../shared/place";
const FAULT_LABELS: Record<string, string> = {
  shallow_depth: "Shallow depth",
  forward_lean: "Forward lean",
  rushed_descent: "Rushed descent",
  partial_range: "Partial range",
  hip_sag: "Hips sagging",
  hip_pike: "Hips piked",
  incomplete_extension: "Hips not fully extended",
  no_pause: "No pause at the top",
};
const SOURCE_LABELS: Record<string, string> = {
  fixture: "Built-in plan",
  fallback: "Built-in plan (safe fallback)",
  model: "AI plan · Amazon Bedrock",
};
const EXPLANATION_SOURCE_LABELS: Record<string, string> = {
  fixture: "Built-in wording",
  fallback: "Built-in wording (safe fallback)",
  model: "AI wording · Amazon Bedrock",
};
export const capitalize = (text: string): string =>
  text.charAt(0).toUpperCase() + text.slice(1);
export const faultLabel = (id: string): string =>
  FAULT_LABELS[id] ?? capitalize(id.replaceAll("_", " "));
/** Fault counts worth showing on the summary: those that occurred, most frequent first. */
export const faultStats = (
  faults: Record<string, number>,
): [string, number][] =>
  Object.entries(faults)
    .filter(([, n]) => n > 0)
    .sort((a, b) => b[1] - a[1]);
/** 3-2-1 at the start of a set: the phone learns the new set within about a second. */
export const countdownLabel = (elapsedMs: number): string =>
  elapsedMs < 3000
    ? String(3 - Math.floor(elapsedMs / 1000))
    : elapsedMs < 4000
      ? "Go"
      : "";
/** The block's rest, counted down on the summary; Continue stays available throughout. */
export const restLabel = (
  restS: number | undefined,
  elapsedMs: number,
): string => {
  if (!restS) return "";
  const left = Math.ceil(restS - elapsedMs / 1000);
  return left > 0
    ? `Rest ${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")}`
    : "Rest done: Continue when ready";
};
export const sourceLabel = (
  source: string | undefined | null,
  explanation = false,
): string =>
  (explanation ? EXPLANATION_SOURCE_LABELS : SOURCE_LABELS)[source ?? ""] ??
  capitalize(String(source ?? ""));
type HoldResult = { rated: boolean; aligned_s: number; total_s: number };
/** Heading for a set summary: the dominant fault, a clean set, or why there is no single focus. */
export function summaryHeading(s: {
  dominant_fault?: string | null;
  rated_reps: number;
  faults: Record<string, number>;
  hold?: HoldResult | null;
}): string {
  if (s.hold)
    return s.hold.rated
      ? "Hold complete"
      : "Hold timed; alignment not rated (unclear view)";
  if (s.dominant_fault)
    return "Main focus: " + faultLabel(s.dominant_fault).toLowerCase();
  if (s.rated_reps < 3) return "Not enough clear reps to rate";
  return Object.values(s.faults).some((n) => n > 0)
    ? "No single pattern stood out"
    : "Clean set: no faults flagged";
}
/** Line under the summary heading: aligned seconds for a hold, counts for a rep set. */
export const summarySubtitle = (s: {
  reps_counted: number;
  rated_reps: number;
  hold?: HoldResult | null;
}): string =>
  s.hold
    ? `${Math.round(s.hold.aligned_s)} of ${Math.round(s.hold.total_s)} seconds aligned (estimated)`
    : `${s.reps_counted} reps counted · ${s.rated_reps} rated (estimated)`;
/** Big number on the live set screen: the hold target, or the reps counted so far. */
export const liveTarget = (a: {
  kind: string;
  target: number;
  reps_counted: number;
}): { value: number; unit: string } =>
  a.kind === "hold"
    ? { value: a.target, unit: "second hold" }
    : { value: a.reps_counted, unit: "reps counted" };
/** About & privacy: where the camera view travels depends on where the server runs (shared/place.ts). */
export function cameraPrivacy(place: ServerPlace): string {
  const route =
    place === "tv"
      ? "directly over your home network. It is never stored and never sent to AWS."
      : place === "home"
        ? "over your home network, through the CleanRep server on your computer. It is never stored or sent to AWS."
        : "through the CleanRep server online. It is kept in memory for at most 2 seconds and never stored.";
  return `The phone's camera view reaches this TV ${route} Tap Stop showing on TV and only numbers leave the phone.`;
}
/** Phone address for the pairing screen: the address the server names (the TV app names its Wi-Fi address), else from the TV's API origin. 10.0.2.2 is the emulator's name for the host, which a phone reaches as localhost. */
export const phoneUrl = (apiOrigin: string, served?: string | null): string =>
  served ?? apiOrigin.replace("//10.0.2.2:", "//localhost:") + "/phone";
/** What the pairing QR opens: the phone page with the code in the fragment, which never reaches the server.
 * Home mode adds the TV certificate's fingerprint (`fp`), which the phone app pins. */
export const phoneLink = (url: string, code: string, fp = ""): string =>
  fp ? `${url}#code=${code}&fp=${fp}` : `${url}#code=${code}`;
/** Phone camera state on the demo and live set screens; null when the session does not use the phone. */
export function phoneCamera(v: {
  sensor_mode: string;
  phone_connected: boolean;
  phone_sensing: boolean;
}): { on: boolean; text: string } | null {
  if (v.sensor_mode !== "paired") return null;
  if (!v.phone_connected) return { on: false, text: "Phone disconnected" };
  return v.phone_sensing
    ? { on: true, text: "Phone camera on" }
    : { on: false, text: "Phone camera off: tap Enable camera on the phone" };
}
/** The phone resumes frames only after it sees the new phase, so a missing feed right after a set is not "off" yet. */
export const MIRROR_START_GRACE_MS = 5000;
/** Whether the TV fetches and shows live frames: the plan screen has no room for them, so only the camera check does in READY. */
export function showsLiveMirror(
  v: { phase: string; paused: boolean; mirror_live: boolean } | null,
  screen: { about: boolean; cameraCheck: boolean },
): boolean {
  return Boolean(
    v &&
    !screen.about &&
    !v.paused &&
    v.mirror_live &&
    (["DEMO", "IN_SET"].includes(v.phase) ||
      (v.phase === "READY" && screen.cameraCheck)),
  );
}
/** The camera panel takes the shape of the phone's frames; an upright phone gives a tall panel. */
export function mirrorShape(
  width: number,
  height: number,
): { ratio: string; portrait: boolean } | null {
  if (!(width > 0 && height > 0)) return null;
  return { ratio: `${width} / ${height}`, portrait: height > width };
}
/** What fills the camera area on the demo and live set: the phone's live view, a same-size hint, or nothing. */
export function mirrorPanel(
  v: {
    phase: string;
    paused: boolean;
    sensor_mode: string;
    phone_connected: boolean;
    phone_sensing: boolean;
    mirror_live: boolean;
  },
  starting = false,
): { kind: "live" } | { kind: "hint"; text: string } | null {
  if (
    v.sensor_mode !== "paired" ||
    v.paused ||
    !["READY", "DEMO", "IN_SET"].includes(v.phase)
  )
    return null;
  if (v.mirror_live) return { kind: "live" };
  if (!v.phone_connected)
    return {
      kind: "hint",
      text: "Your camera view returns when the phone reconnects",
    };
  if (!v.phone_sensing)
    return {
      kind: "hint",
      text: "Your camera view appears here when the phone camera is on",
    };
  return {
    kind: "hint",
    text: starting
      ? "Starting the camera view…"
      : "Camera view is off. On the phone, tap Show camera on TV",
  };
}
const VERDICT_LABELS: Record<string, string> = {
  improved: "Improved",
  same: "About the same",
  worse: "More reps flagged this time",
  not_enough_data: "Not enough clear reps to compare",
};
/** Line under the re-test sentence. */
export const comparisonNote = (verdict: string): string =>
  verdict === "not_enough_data"
    ? "A verdict needs at least 3 rated reps in each set."
    : verdict === "same"
      ? "No change yet."
      : "Estimated from the rated reps.";
/** Before → after line for a re-test, e.g. "Improved: flagged in 6 of 10 rated reps before, 0 of 10 after". */
export const comparisonLabel = (c: {
  verdict: string;
  before_count: number;
  before_rated: number;
  after_count: number;
  after_rated: number;
}): string =>
  `${VERDICT_LABELS[c.verdict] ?? capitalize(c.verdict)}: flagged in ${c.before_count} of ${c.before_rated} rated reps before, ${c.after_count} of ${c.after_rated} after`;
export const movementMinutes = (n: number): string =>
  `${n} movement minute${n === 1 ? "" : "s"} (estimated)`;

/** The tally's slots: one per rep the server reported, then empty slots up to the target. */
export const tallySlots = (
  marks: readonly RepMark[] | null | undefined,
  target: number,
): (RepMark | "todo")[] => {
  const done = marks ?? [];
  return [
    ...done,
    ...Array<"todo">(Math.max(0, target - done.length)).fill("todo"),
  ];
};
/** Where the session stands: the current exercise of the plan, for the header's progress rail. */
export function sessionProgress(v: {
  phase: string;
  block_index: number;
  plan: { blocks: unknown[] } | null;
}): { current: number; total: number } | null {
  if (
    !v.plan?.blocks.length ||
    !["DEMO", "IN_SET", "SET_SUMMARY", "DRILL_OFFER"].includes(v.phase)
  )
    return null;
  return {
    current: Math.min(v.block_index, v.plan.blocks.length - 1),
    total: v.plan.blocks.length,
  };
}
export type RepFeedback =
  | { kind: "clean"; symbol: "✓"; label: "Clean rep" }
  | { kind: "fault"; symbol: "!"; fault: string; label: string }
  | null;

/** Live-set rep feedback when the rep count rises after the set's first observed poll: green checkmark for a clean rated rep, amber marker for a faulted rated rep. */
export function repFeedback(
  previousCount: number | null,
  currentCount: number,
  lastRep:
    | { index: number; rated: boolean; faults: string[] }
    | null
    | undefined,
): RepFeedback {
  if (
    previousCount === null ||
    currentCount <= previousCount ||
    currentCount <= 0 ||
    !lastRep ||
    !lastRep.rated
  )
    return null;
  if (lastRep.faults.length === 0)
    return { kind: "clean", symbol: "✓", label: "Clean rep" };
  const fault = lastRep.faults[0];
  return { kind: "fault", symbol: "!", fault, label: faultLabel(fault) };
}
