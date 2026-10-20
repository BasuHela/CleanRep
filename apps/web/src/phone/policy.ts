/* When the phone may sense or poll. Pure, so the rules are testable without a camera. */
import { ApiError, TIMEOUT_MESSAGE } from "../shared/api";
import { lanIPv4, type ServerPlace } from "../shared/place";

/** Claim polling interval while the TV approves; the server allows 40 claims per minute per address. */
export const CLAIM_POLL_MS = 2000;
/** Claim errors that mean "keep waiting for the TV" rather than a failure to show. */
export const claimWaiting = (code: string): boolean =>
  code === "not_approved" || code === "rate_limited";
type Phase = { phase: string; paused: boolean; sensor_mode: string };
const SET_PHASES = ["READY", "DEMO", "IN_SET"];
// Between sets of a paired session; an enabled camera runs through these so nobody walks back to the phone.
const BETWEEN_SETS = ["SET_SUMMARY", "DRILL_OFFER", "ADAPTING", "IN_SET"];

/** Enable camera (or a replay) is allowed only where a set can run. */
export function canStart(v: Phase): boolean {
  return SET_PHASES.includes(v.phase) && !v.paused;
}

/** Whether a running sensor keeps running; `enabled` means the user turned the camera on and has not stopped it. */
export function keepSensing(v: Phase, enabled: boolean): boolean {
  if (v.sensor_mode !== "paired") return false;
  return canStart(v) || (enabled && BETWEEN_SETS.includes(v.phase));
}

const BEFORE_CAMERA = ["LOBBY", "READINESS", "SETUP", "PLANNING"];
const FINISHED = ["RECAP", "ENDED"];

/** Phone status line for the session state, in sentences; only the TV can pause or resume. */
export function phoneStatus(
  v: Phase,
  sensing: boolean,
  replay = false,
): string {
  if (v.paused)
    return sensing
      ? "Paused on the TV. The camera stays on; select Resume on the TV."
      : "Paused on the TV. Select Resume on the TV to continue.";
  if (canStart(v))
    return sensing
      ? replay
        ? "Replay running with synthetic landmarks."
        : "Tracking on this phone."
      : replay
        ? "Ready to run the replay."
        : "Ready. Tap Enable camera, then stand the phone up.";
  if (sensing)
    return "The camera stays on between sets. Tap Stop camera to turn it off.";
  if (BEFORE_CAMERA.includes(v.phase))
    return "Finish setup on the TV. The camera starts at the camera check.";
  if (FINISHED.includes(v.phase))
    return "Session finished. You can close this page.";
  return "Camera off. Turn it on again when the next set starts.";
}

/** The phone's heading: where the person is, in two or three words. */
export function phoneTitle(v: Phase, sensing: boolean): string {
  if (v.paused) return "Paused on the TV";
  if (sensing) return "Camera on";
  if (canStart(v)) return "Turn on the camera";
  if (FINISHED.includes(v.phase)) return "All done";
  if (BEFORE_CAMERA.includes(v.phase)) return "Paired with your TV";
  return "Camera off";
}

/** A failed request as the phone shows it: the page's own timeout is a lost connection; the app's message stays. */
export function requestFailure(err: unknown): {
  code: string;
  message?: string;
} {
  if (!(err instanceof ApiError)) return { code: "network" };
  if (err.code === "unavailable" && err.message === TIMEOUT_MESSAGE)
    return { code: "network" };
  return { code: err.code, message: err.message };
}

/** Pairing failures in words a person can act on; the server's codes come from apps/core (CleanRepApi). */
export function pairError(
  code: string,
  place: ServerPlace = "home",
  message?: string,
): string {
  // The phone app words its own failures (no TV set, can't reach the TV, changed security key).
  if (code === "unavailable" && message) return message;
  if (code === "pairing_expired")
    return "No TV is showing that code. Check the six digits on the TV, or start a new session there.";
  if (code === "rate_limited" || code === "pairing_limit")
    return "Too many tries. Wait a minute, then try again.";
  if (code === "claim_used")
    return "This phone request was already used. Enter the code again.";
  if (code === "network")
    return place === "tv"
      ? "Can't reach your TV. Check that the CleanRep TV app is open and this phone is on the same Wi-Fi."
      : place === "home"
        ? "Can't reach CleanRep. Check that this phone is on the same Wi-Fi as the CleanRep server."
        : "Can't reach CleanRep. Check this phone's internet connection.";
  return "Pairing didn't work. Enter the code again.";
}

/** Camera failures (getUserMedia errors by name) in words a person can act on. */
export function cameraError(err: unknown, inApp = false): string {
  const name = err instanceof Error ? err.name : "";
  if (name === "NotAllowedError" && inApp)
    return "Camera blocked. Allow Camera for CleanRep in Android Settings, then tap Enable camera.";
  if (name === "NotAllowedError")
    return "Camera blocked. Tap the icon left of the address, allow Camera, then tap Enable camera.";
  if (name === "NotFoundError" || name === "OverconstrainedError")
    return "No rear camera found. Pick another lens, or use the remote only on the TV.";
  if (name === "NotReadableError")
    return "Another app is using the camera. Close it, then tap Enable camera.";
  return err instanceof Error ? err.message : String(err);
}

const FINGERPRINT = "[A-Za-z0-9_-]{43}";

/** The pairing code from a scanned TV QR link (`/phone#code=123456`, or with `&fp=<fingerprint>`), or null. */
export function linkedCode(hash: string): string | null {
  return linkedPair(hash)?.code ?? null;
}

/** The code and the TV certificate fingerprint from a scanned link; `fp` is "" for a link from `make server`. Strict. */
export function linkedPair(hash: string): { code: string; fp: string } | null {
  const m = new RegExp(`^#code=(\\d{6})(?:&fp=(${FINGERPRINT}))?$`).exec(hash);
  return m ? { code: m[1], fp: m[2] ?? "" } : null;
}

export type PairTarget = {
  host: string;
  port: number;
  fp: string;
  code: string;
};

const octets = (host: string): number[] | null => {
  // A multi-digit octet may not start with 0, as in the phone app's own check.
  const m =
    /^(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})$/.exec(
      host,
    );
  const parts = m ? m.slice(1).map(Number) : [];
  return parts.length === 4 && parts.every((n) => n <= 255) ? parts : null;
};
const loopbackHost = (host: string): boolean =>
  host === "localhost" || octets(host)?.[0] === 127;
const privateHost = (host: string): boolean =>
  octets(host) !== null && lanIPv4(host);
const portOf = (text: string): number | null => {
  const port = /^\d{1,5}$/.test(text) ? Number(text) : 0;
  return port >= 1 && port <= 65535 ? port : null;
};

/**
 * What the phone app may connect to, from a pasted link: the TV's QR link (the app talks to the TV's HTTPS port 8443),
 * a `cleanrep://pair?host&port&code&fp` link, or, for USB testing, `localhost:<port>` and its `/phone` link without a
 * fingerprint. Private IPv4 and loopback hosts only; a private host needs its fingerprint. `code` is "" when absent.
 */
export function pairTarget(text: string): PairTarget | null {
  const raw = text.trim();
  if (raw.length > 512) return null;
  const bare = /^(localhost|127(?:\.\d{1,3}){3}):(\d+)$/.exec(raw);
  if (bare) {
    const port = portOf(bare[2]);
    return loopbackHost(bare[1]) && port
      ? { host: bare[1], port, fp: "", code: "" }
      : null;
  }
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return null;
  }
  if (url.username || url.password) return null;
  if (url.protocol === "cleanrep:") {
    if (url.hostname !== "pair" || url.hash || url.port) return null;
    const keys = [...url.searchParams.keys()].sort().join();
    if (keys !== "code,fp,host,port" && keys !== "code,host,port") return null;
    const q = (name: string) => url.searchParams.get(name) ?? "";
    const [host, fp, code, port] = [
      q("host"),
      q("fp"),
      q("code"),
      portOf(q("port")),
    ];
    if (!/^\d{6}$/.test(code) || !port) return null;
    if (fp && !new RegExp(`^${FINGERPRINT}$`).test(fp)) return null;
    if (privateHost(host) ? !fp : !loopbackHost(host)) return null;
    return { host, port, fp, code };
  }
  if (url.protocol !== "http:" || url.pathname !== "/phone" || url.search)
    return null;
  // URL parsing rewrites 010.0.0.7 or 127.1 into another address; the text must already be what the app will get.
  if (/^http:\/\/([^/?#:@]*)/i.exec(raw)?.[1].toLowerCase() !== url.hostname)
    return null;
  if (privateHost(url.hostname)) {
    const pair = linkedPair(url.hash);
    return pair?.fp
      ? { host: url.hostname, port: 8443, fp: pair.fp, code: pair.code }
      : null;
  }
  const port = portOf(url.port);
  if (!loopbackHost(url.hostname) || !port) return null;
  if (!url.hash) return { host: url.hostname, port, fp: "", code: "" };
  const pair = linkedPair(url.hash);
  return pair && !pair.fp
    ? { host: url.hostname, port, fp: "", code: pair.code }
    : null;
}

/** The Chrome intent link that opens the phone app with the TV's address, code and fingerprint, or fetches the APK from the TV. */
export function pairIntent(p: {
  host: string;
  code: string;
  fp: string;
  apkUrl: string;
}): string {
  return (
    `intent://pair?host=${p.host}&port=8443&code=${p.code}&fp=${p.fp}` +
    `#Intent;scheme=cleanrep;package=org.cleanrep.phone;` +
    `S.browser_fallback_url=${encodeURIComponent(p.apkUrl)};end`
  );
}

/** Rewrites the status line only when phase, set or pause changes, so camera and connection notices stay visible meanwhile. */
export class StatusLine {
  private key = "";
  constructor(private readonly write: (text: string) => void) {}
  update(
    v: Phase & { active_set?: { set_id: string } | null },
    sensing: boolean,
    replay = false,
  ): void {
    const key = `${v.phase}|${v.paused}|${v.active_set?.set_id ?? ""}`;
    if (key === this.key) return;
    this.key = key;
    this.write(phoneStatus(v, sensing, replay));
  }
  /** After a failed refresh: the next update rewrites the line, replacing the interrupted notice. */
  forget(): void {
    this.key = "";
  }
}
