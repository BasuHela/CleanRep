import { afterEach, beforeEach, expect, test, vi } from "vitest";
import {
  cameraError,
  linkedCode,
  linkedPair,
  pairError,
  pairIntent,
  pairTarget,
} from "../src/phone/policy";
import { connectTv, LINK_REJECTED } from "../src/phone/server";
import { serverPlace } from "../src/shared/place";
import { cameraPrivacy, phoneLink, phoneUrl } from "../src/tv/labels";

const FP = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOP-_"
  .slice(0, 43)
  .padEnd(43, "A");
const jpeg = (size: number) => {
  const bytes = new Uint8Array(size);
  bytes.set([0xff, 0xd8, 0xff].slice(0, size));
  return bytes;
};
const base64 = (bytes: Uint8Array) => Buffer.from(bytes).toString("base64");

type MirrorCall = { id: string; path: string; jpeg: string; token: string };
let win: EventTarget & { CleanRepNative?: Record<string, unknown> };
let calls: MirrorCall[];
let reply: ((call: MirrorCall) => string | null) | null;

/** A bridge that records `requestMirror` calls and answers like the phone app: later, through the window event. */
function answer(id: string, payload: string) {
  win.dispatchEvent(
    new CustomEvent("cleanrep-native-response", { detail: { id, payload } }),
  );
}
const envelope = (status: string, message = "") =>
  JSON.stringify({
    request_id: "native",
    status,
    state_version: null,
    data: {},
    evidence: [],
    error: message ? { message } : undefined,
  });
async function modules() {
  vi.resetModules();
  const api = await import("../src/shared/api");
  const mirror = await import("../src/phone/mirror");
  return { api, mirror };
}

beforeEach(() => {
  calls = [];
  reply = null;
  win = Object.assign(new EventTarget(), {
    CleanRepNative: {
      requestMirror(
        id: string,
        path: string,
        jpegBase64: string,
        token: string,
      ) {
        const call = { id, path, jpeg: jpegBase64, token };
        calls.push(call);
        const payload = reply?.(call);
        if (payload) queueMicrotask(() => answer(id, payload));
      },
    },
  });
  vi.stubGlobal("window", win);
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

test("W-HOME-1 a frame goes through the phone app's bridge as base64 with a fresh id, within its size limit", async () => {
  const { mirror } = await modules();
  reply = () => envelope("ok");
  const frame = jpeg(300);
  await mirror.postMirrorFrame(
    "s_1",
    "tok",
    new Blob([frame]),
    new AbortController().signal,
  );
  await mirror.postMirrorFrame(
    "s_1",
    "tok",
    new Blob([frame]),
    new AbortController().signal,
  );
  expect(calls.length).toBe(2);
  expect(calls[0]).toMatchObject({
    path: "/v1/sessions/s_1/mirror",
    jpeg: base64(frame),
    token: "tok",
  });
  expect(calls[0].id).toMatch(/^[A-Za-z0-9_-]{1,128}$/);
  expect(calls[1].id).not.toBe(calls[0].id);

  // The largest frame the sender allows is exactly what the app accepts: 204,800 base64 characters.
  calls.length = 0;
  await mirror.postMirrorFrame(
    "s_1",
    "tok",
    new Blob([jpeg(mirror.MIRROR_MAX_BYTES)]),
    new AbortController().signal,
  );
  expect(calls.length).toBe(1);
  expect(calls[0].jpeg.length).toBe(204800);
  // Over the limit, or too small to be a JPEG, never reaches the app.
  for (const size of [mirror.MIRROR_MAX_BYTES + 1, 3, 0]) {
    await mirror.postMirrorFrame(
      "s_1",
      "tok",
      new Blob([jpeg(size)]),
      new AbortController().signal,
    );
  }
  expect(calls.length).toBe(1);
});

test("W-HOME-2 through the bridge MirrorSender still sends one frame at a time and keeps going after busy, unavailable and rejected answers", async () => {
  const { mirror } = await modules();
  const frame = new Blob([jpeg(300)]);
  const sender = new mirror.MirrorSender(
    async () => frame,
    (blob, signal) => mirror.postMirrorFrame("s_1", "tok", blob, signal),
  );
  sender.sync(
    { phase: "IN_SET", paused: false, sensor_mode: "paired" },
    { enabled: true, sensing: true, hidden: false },
  );
  const settle = async () => {
    for (let i = 0; i < 6; i++) await Promise.resolve();
  };
  // No answer yet: later frames are dropped, not queued.
  expect(sender.frame(0)).toBe(true);
  await settle();
  expect(sender.frame(150)).toBe(false);
  expect(sender.frame(300)).toBe(false);
  expect(calls.length).toBe(1);
  answer(calls[0].id, envelope("ok"));
  await settle();
  // Each failure envelope is a dropped frame; the next one is sent on schedule.
  let now = 300;
  for (const [status, message] of [
    ["unavailable", "The TV is busy reconnecting. Retry when ready."],
    [
      "unavailable",
      "Can't reach your TV. Check that the CleanRep TV app is open.",
    ],
    ["rejected", "The TV rejected an invalid scoped request."],
  ]) {
    expect(sender.frame(now)).toBe(true);
    await settle();
    answer(calls[calls.length - 1].id, envelope(status, message));
    await settle();
    now += 150;
  }
  expect(calls.length).toBe(4);
  expect(sender.frame(now)).toBe(true);
});

test("W-HOME-3 aborting a frame forgets it at once, and a late answer or a missing one never throws", async () => {
  vi.useFakeTimers();
  const { mirror } = await modules();
  const controller = new AbortController();
  const done = vi.fn();
  void mirror
    .postMirrorFrame("s_1", "tok", new Blob([jpeg(300)]), controller.signal)
    .then(done);
  await vi.advanceTimersByTimeAsync(0);
  expect(calls.length).toBe(1);
  expect(vi.getTimerCount()).toBe(1);
  controller.abort();
  await vi.advanceTimersByTimeAsync(0);
  // Settled and cleaned up without waiting for the timeout.
  expect(done).toHaveBeenCalledTimes(1);
  expect(vi.getTimerCount()).toBe(0);
  expect(() => answer(calls[0].id, envelope("ok"))).not.toThrow();

  // Already aborted before it starts: nothing is sent.
  await mirror.postMirrorFrame(
    "s_1",
    "tok",
    new Blob([jpeg(300)]),
    controller.signal,
  );
  expect(calls.length).toBe(1);

  // No answer at all: the frame is lost after MIRROR_BRIDGE_TIMEOUT_MS, which outlasts the app's own 3 s connect plus 3 s read.
  const lost = vi.fn();
  void mirror
    .postMirrorFrame(
      "s_1",
      "tok",
      new Blob([jpeg(300)]),
      new AbortController().signal,
    )
    .then(lost);
  expect(mirror.MIRROR_BRIDGE_TIMEOUT_MS).toBeGreaterThan(6000);
  await vi.advanceTimersByTimeAsync(mirror.MIRROR_BRIDGE_TIMEOUT_MS - 100);
  expect(lost).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(200);
  expect(lost).toHaveBeenCalledTimes(1);

  // An unreadable answer is also just a dropped frame.
  reply = () => "not json";
  await mirror.postMirrorFrame(
    "s_1",
    "tok",
    new Blob([jpeg(300)]),
    new AbortController().signal,
  );
});

test("W-HOME-4 without requestMirror a frame is posted with fetch exactly as before, and request() shares the bridge's answer map", async () => {
  const { api, mirror } = await modules();
  const fetchMock = vi.fn(async () => new Response("{}"));
  vi.stubGlobal("fetch", fetchMock);
  win.CleanRepNative = {
    requestAsync(id: string) {
      queueMicrotask(() => answer(id, envelope("ok")));
    },
  };
  const blob = new Blob([jpeg(300)]);
  const signal = new AbortController().signal;
  await mirror.postMirrorFrame("s_1", "tok", blob, signal);
  expect(fetchMock).toHaveBeenCalledWith("/v1/sessions/s_1/mirror", {
    method: "POST",
    headers: { "Content-Type": "image/jpeg", Authorization: "Bearer tok" },
    body: blob,
    cache: "no-store",
    signal,
  });
  // The shared helper still serves request(): an answer resolves it, an app error keeps its message.
  await expect(api.request("/v1/pair/request", {})).resolves.toMatchObject({
    status: "ok",
  });
  win.CleanRepNative = {
    requestAsync(id: string) {
      queueMicrotask(() =>
        answer(
          id,
          envelope("unavailable", "Scan the QR code on your TV first."),
        ),
      );
    },
  };
  await expect(api.request("/v1/pair/request", {})).rejects.toMatchObject({
    code: "unavailable",
    message: "Scan the QR code on your TV first.",
  });
  // No bridge at all: a page fetch, as in the browser.
  win.CleanRepNative = undefined;
  fetchMock.mockResolvedValueOnce(
    new Response(envelope("ok"), { status: 200 }),
  );
  await api.request("/v1/healthz");
  expect(fetchMock).toHaveBeenCalledTimes(2);
});

test('W-HOME-5 a server place "tv": the TV app says so, and the phone app reaches its TV over HTTPS', () => {
  const phoneApp = { setServer: () => true };
  expect(serverPlace("https://192.168.1.20:8443", phoneApp)).toBe("tv");
  // The app's own packaged origin, before a TV is set, is not "online".
  expect(serverPlace("https://cleanrep.phone", phoneApp)).toBe("tv");
  expect(
    serverPlace("http://127.0.0.1:8105", { serverPlace: () => "tv" }),
  ).toBe("tv");
  // Everything else reads as before.
  expect(serverPlace("http://localhost:8105", phoneApp)).toBe("home");
  expect(serverPlace("http://127.0.0.1:8105", { serverPlace: () => "" })).toBe(
    "home",
  );
  expect(serverPlace("http://127.0.0.1:8105", {})).toBe("home");
  expect(serverPlace("https://192.168.1.20:8443")).toBe("home");
  expect(serverPlace("https://cleanrep.example.com", {})).toBe("online");
  expect(serverPlace("not an origin", { setServer: () => true })).toBe(
    "online",
  );

  expect(pairError("network", "tv")).toBe(
    "Can't reach your TV. Check that the CleanRep TV app is open and this phone is on the same Wi-Fi.",
  );
  const about = cameraPrivacy("tv");
  expect(about).toContain("directly over your home network");
  expect(about).toContain("never stored and never sent to AWS");
  expect(about).not.toContain("laptop");
  expect(about).toContain("only numbers leave the phone");
});

test("W-HOME-6 the phone app's own messages are shown as they are, the page's own timeout is not, and a blocked camera points to Android Settings", async () => {
  const message =
    "This TV's security key has changed. Scan the QR code on your TV again.";
  expect(pairError("unavailable", "tv", message)).toBe(message);
  expect(pairError("rejected", "tv", message)).not.toBe(message);
  // Without a message, or for a server code, the usual wording stays.
  expect(pairError("unavailable", "tv")).toBe(pairError("nonsense", "tv"));
  expect(pairError("rate_limited", "tv", message)).toContain("Too many tries");
  const blocked = Object.assign(new Error("denied"), {
    name: "NotAllowedError",
  });
  expect(cameraError(blocked, true)).toBe(
    "Camera blocked. Allow Camera for CleanRep in Android Settings, then tap Enable camera.",
  );
  expect(cameraError(blocked)).toContain("icon left of the address");
  expect(cameraError(blocked, false)).toBe(cameraError(blocked));

  // An app envelope's message is kept; the page's 13 s timeout reads as a lost connection, with no Retry text.
  vi.useFakeTimers();
  vi.resetModules();
  const api = await import("../src/shared/api");
  const policy = await import("../src/phone/policy");
  win.CleanRepNative = {
    requestAsync(id: string) {
      queueMicrotask(() => answer(id, envelope("unavailable", message)));
    },
  };
  const fromApp = await api.request("/v1/pair/request", {}).catch((e) => e);
  expect(policy.requestFailure(fromApp)).toEqual({
    code: "unavailable",
    message,
  });
  win.CleanRepNative = { requestAsync() {} };
  const timedOut = api.request("/v1/pair/request", {}).catch((e) => e);
  await vi.advanceTimersByTimeAsync(13000);
  const timeout = await timedOut;
  expect(timeout.message).toBe(api.TIMEOUT_MESSAGE);
  expect(policy.requestFailure(timeout)).toEqual({ code: "network" });
  const failure = policy.requestFailure(timeout);
  expect(policy.pairError(failure.code, "tv", failure.message)).toBe(
    policy.pairError("network", "tv"),
  );
  // Anything that is not an ApiError is a lost connection too.
  expect(policy.requestFailure(new TypeError("Failed to fetch"))).toEqual({
    code: "network",
  });
  // A server error keeps its code.
  expect(
    policy.requestFailure(new api.ApiError("pairing_expired", "No TV.")),
  ).toEqual({ code: "pairing_expired", message: "No TV." });
});

test("W-HOME-7 the QR link carries the TV certificate's fingerprint when there is one, and the phone reads code and fingerprint strictly", () => {
  const url = phoneUrl("http://192.168.1.20:8105");
  expect(phoneLink(url, "123456", FP)).toBe(
    `http://192.168.1.20:8105/phone#code=123456&fp=${FP}`,
  );
  expect(phoneLink(url, "123456", "")).toBe(
    "http://192.168.1.20:8105/phone#code=123456",
  );
  expect(phoneLink(url, "123456")).toBe(phoneLink(url, "123456", ""));

  const hash = new URL(phoneLink(url, "123456", FP)).hash;
  expect(linkedPair(hash)).toEqual({ code: "123456", fp: FP });
  expect(linkedCode(hash)).toBe("123456");
  expect(linkedPair("#code=123456")).toEqual({ code: "123456", fp: "" });
  for (const bad of [
    "",
    "#code=123456&fp=",
    `#code=123456&fp=${FP.slice(1)}`,
    `#code=123456&fp=${FP}A`,
    `#code=123456&fp=${FP.slice(1)}=`,
    `#code=123456&fp=${FP.slice(1)}+`,
    `#fp=${FP}&code=123456`,
    `#code=12345&fp=${FP}`,
    `#code=123456&fp=${FP}&x=1`,
    `#code=123456&x=1`,
  ]) {
    expect(linkedPair(bad), bad).toBeNull();
    expect(linkedCode(bad), bad).toBeNull();
  }
});

test("W-HOME-9 the phone app accepts the TV's QR link, a cleanrep:// link or a USB address, and nothing else", () => {
  // The TV's QR link: the API is on 8443, whatever port the landing page used.
  expect(
    pairTarget(`http://192.168.1.20:8105/phone#code=123456&fp=${FP}`),
  ).toEqual({ host: "192.168.1.20", port: 8443, fp: FP, code: "123456" });
  expect(
    pairTarget(`  http://10.0.0.7:8105/phone#code=000001&fp=${FP}\n`),
  ).toEqual({ host: "10.0.0.7", port: 8443, fp: FP, code: "000001" });
  // A cleanrep:// link keeps its port.
  expect(
    pairTarget(
      `cleanrep://pair?host=192.168.1.20&port=9443&code=123456&fp=${FP}`,
    ),
  ).toEqual({ host: "192.168.1.20", port: 9443, fp: FP, code: "123456" });
  expect(
    pairTarget(`cleanrep://pair?host=localhost&port=8443&code=123456&fp=${FP}`),
  ).toEqual({ host: "localhost", port: 8443, fp: FP, code: "123456" });
  expect(
    pairTarget("cleanrep://pair?host=127.0.0.1&port=8105&code=123456"),
  ).toEqual({ host: "127.0.0.1", port: 8105, fp: "", code: "123456" });
  // USB testing: loopback, no fingerprint, port kept.
  expect(pairTarget("localhost:8105")).toEqual({
    host: "localhost",
    port: 8105,
    fp: "",
    code: "",
  });
  expect(pairTarget("http://localhost:8105/phone")).toEqual({
    host: "localhost",
    port: 8105,
    fp: "",
    code: "",
  });
  expect(pairTarget("http://localhost:8105/phone#code=123456")).toEqual({
    host: "localhost",
    port: 8105,
    fp: "",
    code: "123456",
  });

  for (const bad of [
    "",
    "hello",
    `http://8.8.8.8:8105/phone#code=123456&fp=${FP}`,
    `http://cleanrep.example.com:8105/phone#code=123456&fp=${FP}`,
    `http://192.168.1.20.nip.io:8105/phone#code=123456&fp=${FP}`,
    `http://172.32.0.1:8105/phone#code=123456&fp=${FP}`,
    `http://192.168.1.256:8105/phone#code=123456&fp=${FP}`,
    `https://192.168.1.20:8443/phone#code=123456&fp=${FP}`,
    "http://192.168.1.20:8105/phone#code=123456",
    `http://192.168.1.20:8105/phone#code=12345&fp=${FP}`,
    `http://192.168.1.20:8105/phone#code=123456&fp=${FP.slice(1)}`,
    `http://192.168.1.20:8105/other#code=123456&fp=${FP}`,
    `http://192.168.1.20:8105/phone?x=1#code=123456&fp=${FP}`,
    `http://user:pw@192.168.1.20:8105/phone#code=123456&fp=${FP}`,
    `http://192.168.1.20:8105/phone#code=123456&fp=${FP}&x=1`,
    "cleanrep://pair?host=192.168.1.20&port=8443&code=123456",
    `cleanrep://pair?host=8.8.8.8&port=8443&code=123456&fp=${FP}`,
    `cleanrep://pair?host=example.com&port=8443&code=123456&fp=${FP}`,
    `cleanrep://pair?host=192.168.1.20&port=0&code=123456&fp=${FP}`,
    `cleanrep://pair?host=192.168.1.20&port=65536&code=123456&fp=${FP}`,
    `cleanrep://pair?host=192.168.1.20&port=8443&code=<code>&fp=${FP}`,
    `cleanrep://pair?host=192.168.1.20&port=8443&code=123456&fp=short`,
    `cleanrep://pair?host=192.168.1.20&port=8443&code=123456&fp=${FP}&extra=1`,
    `cleanrep://pair?host=192.168.1.20&host=10.0.0.1&port=8443&code=123456&fp=${FP}`,
    `cleanrep://other?host=192.168.1.20&port=8443&code=123456&fp=${FP}`,
    `cleanrep://pair?host=192.168.1.20&port=8443&code=123456&fp=${FP}#x`,
    `javascript:alert(1)`,
    "localhost",
    "localhost:0",
    "localhost:70000",
    "localhost:8105/phone",
    "example.com:8105",
    "192.168.1.20:8105",
    `http://localhost:8105/phone#code=123456&fp=${FP}`,
    "http://localhost/phone",
    "http://localhost:8105/phone#code=12345",
    "http://localhost:8105/other",
    "x".repeat(600),
    // Leading zeros: the phone app refuses them, and URL parsing would quietly rewrite them.
    "cleanrep://pair?host=192.168.001.5&port=8443&code=123456&fp=" + FP,
    `http://192.168.001.5:8105/phone#code=123456&fp=${FP}`,
    `http://010.0.0.7:8105/phone#code=123456&fp=${FP}`,
    "cleanrep://pair?host=010.0.0.7&port=8443&code=123456&fp=" + FP,
    "http://127.1:8105/phone",
    "127.00.0.1:8105",
  ])
    expect(pairTarget(bad), bad).toBeNull();
});

test("W-HOME-10 the Chrome landing link opens the phone app with the TV's address, code and fingerprint, and falls back to the TV's APK", () => {
  const apkUrl = "http://192.168.1.20:8105/cleanrep-phone.apk";
  const link = pairIntent({
    host: "192.168.1.20",
    code: "123456",
    fp: FP,
    apkUrl,
  });
  expect(link).toBe(
    `intent://pair?host=192.168.1.20&port=8443&code=123456&fp=${FP}` +
      "#Intent;scheme=cleanrep;package=org.cleanrep.phone;" +
      "S.browser_fallback_url=http%3A%2F%2F192.168.1.20%3A8105%2Fcleanrep-phone.apk;end",
  );
});

test("W-HOME-11 Connect sets the TV only for a link the app would accept, and says so when it does not", () => {
  const setServer = vi.fn(() => true);
  expect(
    connectTv(`http://192.168.1.20:8105/phone#code=123456&fp=${FP}`, {
      setServer,
    }),
  ).toEqual({ ok: true, code: "123456" });
  expect(setServer).toHaveBeenCalledWith("192.168.1.20", 8443, FP);
  expect(connectTv("localhost:8105", { setServer })).toEqual({
    ok: true,
    code: "",
  });
  expect(setServer).toHaveBeenLastCalledWith("localhost", 8105, "");

  // Not a CleanRep link: the app is never asked.
  setServer.mockClear();
  expect(connectTv("http://8.8.8.8:8105/phone", { setServer })).toEqual({
    ok: false,
  });
  expect(setServer).not.toHaveBeenCalled();
  // The app refuses, throws, or there is no app.
  const link = `cleanrep://pair?host=10.0.0.7&port=8443&code=123456&fp=${FP}`;
  expect(connectTv(link, { setServer: () => false })).toEqual({ ok: false });
  expect(
    connectTv(link, {
      setServer: () => {
        throw new Error("bridge");
      },
    }),
  ).toEqual({ ok: false });
  expect(connectTv(link, undefined)).toEqual({ ok: false });
  expect(LINK_REJECTED).toBe(
    "That link isn't from a CleanRep TV on this network.",
  );
});
