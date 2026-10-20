import { LocalCamera } from "./camera";
import { FrameStats, debugText, sensorOptions } from "./debug";
import {
  MIRROR_INTERVAL_MS,
  MirrorControls,
  MirrorSender,
  compositeMirrorFrame,
  drawSkeleton,
  encodeMirrorCanvas,
  mirrorFrameSize,
  postMirrorFrame,
  readMirrorPreference,
  writeMirrorPreference,
} from "./mirror";
import {
  CLAIM_POLL_MS,
  StatusLine,
  cameraError,
  canStart,
  claimWaiting,
  keepSensing,
  linkedPair,
  pairError,
  requestFailure,
  phoneTitle,
} from "./policy";
import { RepTransport, sendFailure } from "./transport";
import { PartialNotice } from "./partial";
import { CoachSender } from "./coach";
import { PosePipeline } from "../pose/pipeline";
import type { Frame, Point } from "../pose/types";
import {
  ApiError,
  clearSession,
  escape as e,
  readSession,
  request,
  saveSession,
} from "../shared/api";
import type { View } from "../shared/views";
import { serverPlace } from "../shared/place";
import { landing } from "./landing";
import { serverEntry } from "./server";
const CHECKS = [
  ["full_body", "Whole body"],
  ["side_view", "Side view"],
  ["light", "Light"],
] as const;
const hostOf = (origin: string): string => {
  try {
    return new URL(origin).hostname;
  } catch {
    return "";
  }
};
const PAIR_TITLE = "Pair with your TV",
  PAIR_HINT = "Type the code shown on your TV.";
export function phone(root: HTMLElement): () => void {
  const native = window.CleanRepNative,
    inApp = Boolean(native?.setServer),
    scanned = linkedPair(location.hash);
  // A home-mode QR scanned in Chrome: the app does the pairing, so this page makes no call and opens no camera.
  if (!native && scanned?.fp) {
    landing(root, scanned);
    return () => {};
  }
  // The phone app before any TV is set: nothing to call yet.
  if (inApp && !native!.apiOrigin()) {
    serverEntry(root);
    return () => {};
  }
  const params = new URLSearchParams(location.search),
    replay = params.get("source") === "replay",
    options = sensorOptions(location.search),
    place = serverPlace(native?.apiOrigin() || location.origin, native),
    tvAddress = inApp ? hostOf(native!.apiOrigin()) : "";
  let saved = readSession("phone"),
    view: View | null = null,
    claim: {
      request_id: string;
      claim_token: string;
      verification_code: string;
    } | null = null,
    pairing = false;
  let pipeline: PosePipeline | null = null,
    transport: RepTransport | null = null,
    activeId = "",
    camera: LocalCamera | null = null,
    lastCheck = 0,
    lastClaim = -Infinity,
    sensing = false,
    armed = false,
    replaying = false,
    cancelReplay = 0,
    mirrorEnabled = readMirrorPreference(() => localStorage),
    lastPoints: Point[] | null = null;
  let recorded: Frame[] = [],
    recording = false,
    holdStarted: number | null = null,
    holdSent = false,
    stats = new FrameStats(),
    lastDebug = -Infinity;
  const sources = replay
    ? '<select id="fixture" aria-label="Replay fixture"><option value="squat_shallow_10">Shallow squat, 10 reps</option><option value="squat_good_10">Clear squat, 10 reps</option></select>'
    : '<details class="lens"><summary>Lens</summary><div class="options"><button id="wide">Wide lens</button><select id="lens" aria-label="Rear camera"><option value="">Default rear camera</option></select></div></details>';
  root.innerHTML = `<header class="bar"><a class="brand" href="/phone">Clean<span>Rep</span></a><span id="link" class="link">${replay ? "Replay" : "Not paired"}</span></header><main class="phone"><h1 id="title">${saved ? "Reconnecting" : PAIR_TITLE}</h1><p id="status" role="status">${saved ? "Reconnecting…" : PAIR_HINT}</p><form id="pairing" class="stage" novalidate><label class="code" for="code"><span class="visually-hidden">TV pairing code</span><span class="code-box"><input id="code" inputmode="numeric" pattern="[0-9]*" maxlength="6" autocomplete="one-time-code" enterkeyhint="go" spellcheck="false"><span class="cells" aria-hidden="true">${"<i></i>".repeat(6)}</span></span><span class="small">No code yet? On the TV, select <b>Start a session</b>.</span></label>${tvAddress ? `<p class="small tv-at">TV at ${e(tvAddress)}</p><button id="other-tv" type="button" class="quiet">Use another TV</button>` : ""}<div id="verify" class="verify" hidden><p id="verification" class="match"></p><p class="small">If the TV shows the same number, select <b>Approve phone</b> there. This page carries on by itself.</p></div><div class="dock"><button id="pair" type="submit" class="primary" disabled>Pair with TV</button><button id="again" type="button" hidden>Use a different code</button></div></form><div id="sensor" class="stage" hidden><div class="preview off"><img class="placement" src="/placement.svg" alt="Top view: phone to your side, 2.5 to 3 metres away, lens at hip height"><video id="camera" muted playsinline></video><canvas id="skeleton"></canvas><p id="mirror-indicator" class="on-tv" hidden>Camera visible on your TV</p><p id="partial" role="status"></p><p id="count" class="count" hidden><strong>0</strong><span>reps</span><small></small></p></div><ul id="checks" class="checks" aria-label="Camera checks" hidden>${CHECKS.map(([key, label]) => `<li data-check="${key}">${label}</li>`).join("")}</ul><div class="options"><button id="mirror">Show camera on TV</button>${sources}</div>${options.debug ? '<p id="debug" class="small">Debug · waiting for frames</p>' : ""}${params.get("record") === "1" ? '<div class="options"><button id="record">Record locally</button><button id="export">Export local recording</button></div>' : ""}<p class="small">The camera stays on between sets. Leaving this page turns it off.</p><div class="dock"><button id="start" class="primary">${replay ? "Run replay" : "Enable camera"}</button><button id="stop" hidden>Stop camera</button></div></div></main>`;
  const el = <T extends HTMLElement>(id: string) =>
    root.querySelector<T>("#" + id)!;
  // Text nodes are rewritten only on a change; syncControls runs on every processed frame.
  const setText = (node: HTMLElement, text: string) => {
    if (node.textContent !== text) node.textContent = text;
  };
  const status = (message: string) => {
    el("status").textContent = message;
  };
  const line = new StatusLine(status);
  const partial = new PartialNotice(
    (text) => (el("partial").textContent = text),
  );
  const coach = new CoachSender(
    async (event) => {
      if (saved)
        await request(
          "/v1/sessions/" + saved.session_id + "/coach",
          event,
          saved.token,
        );
    },
    () => view,
  );
  const video = el<HTMLVideoElement>("camera"),
    canvas = el<HTMLCanvasElement>("skeleton"),
    mirrorCanvas = document.createElement("canvas"),
    code = el<HTMLInputElement>("code"),
    cells = [...root.querySelectorAll<HTMLElement>(".cells i")],
    count = el("count");
  canvas.width = mirrorCanvas.width = 640;
  canvas.height = mirrorCanvas.height = 360;
  // Preview box, overlay and TV frames follow the camera's shape; resizing a canvas clears it, so only on a change.
  const preview = root.querySelector<HTMLElement>(".preview")!;
  function fitCanvases() {
    const { width, height } = replay
      ? mirrorFrameSize(0, 0)
      : mirrorFrameSize(video.videoWidth, video.videoHeight);
    if (
      [canvas, mirrorCanvas].every(
        (c) => c.width === width && c.height === height,
      )
    )
      return;
    for (const c of [canvas, mirrorCanvas]) {
      c.width = width;
      c.height = height;
    }
    preview.style.aspectRatio = `${width} / ${height}`;
    preview.classList.toggle("portrait", height > width);
  }
  const mirrorSender = new MirrorSender(
    async () => {
      const mctx = mirrorCanvas.getContext("2d");
      if (!mctx) return null;
      fitCanvases();
      compositeMirrorFrame(mctx, { video, points: lastPoints, replay });
      return encodeMirrorCanvas(mirrorCanvas);
    },
    async (blob, signal) => {
      if (!saved) return;
      await postMirrorFrame(saved.session_id, saved.token, blob, signal);
    },
  );
  const mirrorControls = new MirrorControls((enabled, label) => {
    const btn = el<HTMLButtonElement>("mirror");
    btn.textContent = label;
    btn.classList.toggle("selected", enabled);
  });
  /** One stage at a time: the code entry before pairing, the camera after. */
  function showStage(paired: boolean) {
    el("pairing").hidden = paired;
    el("sensor").hidden = !paired;
  }
  /** Back to an empty code, e.g. after a wrong code or an ended session. */
  function resetPairing() {
    claim = null;
    code.value = "";
    el("verify").hidden = true;
    el("again").hidden = true;
    root.querySelector<HTMLElement>(".code")!.hidden = false;
    el("pair").hidden = false;
    setText(el("title"), PAIR_TITLE);
    syncCode();
  }
  function syncCode() {
    const digits = code.value;
    cells.forEach((cell, i) => {
      cell.textContent = digits[i] ?? "";
      cell.classList.toggle("next", i === Math.min(digits.length, 5));
    });
    el<HTMLButtonElement>("pair").disabled = pairing || digits.length !== 6;
  }
  function syncControls() {
    // On from Enable camera until Stop camera, including the gap after a replay's last frame.
    const on = sensing || armed;
    el("start").hidden = sensing;
    el<HTMLButtonElement>("start").disabled = !(view && canStart(view));
    el("stop").hidden = !on;
    // The placement figure only while the camera is off, so it never sits under the count or the TV chip.
    preview.classList.toggle("off", !on);
    el("checks").hidden = !on;
    count.hidden = !on || view?.phase !== "IN_SET";
    setText(
      el("link"),
      replay
        ? "Replay"
        : saved
          ? view
            ? "Paired"
            : "Reconnecting"
          : "Not paired",
    );
    el("link").classList.toggle("on", Boolean(saved && view));
    if (saved && view) setText(el("title"), phoneTitle(view, on));
  }
  function syncMirror() {
    mirrorControls.update(mirrorEnabled);
    // The indicator follows what is actually sent, not just the toggle.
    el("mirror-indicator").hidden = !mirrorSender.sync(view, {
      enabled: mirrorEnabled,
      sensing: sensing || (replay && armed),
      hidden: document.visibilityState === "hidden",
    });
    syncControls();
  }
  function stop() {
    partial.clear();
    coach.clear();
    sensing = false;
    armed = false;
    cancelReplay++;
    replaying = false;
    lastPoints = null;
    syncMirror();
    camera?.stop();
    status("Camera off.");
  }
  function draw(points: Point[] | null) {
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    fitCanvases();
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    drawSkeleton(ctx, points);
  }
  async function cameraCheck(
    full_body: boolean,
    side_view: boolean,
    light: boolean,
    fps: number,
    delegate: "GPU" | "CPU",
  ) {
    if (!saved || performance.now() - lastCheck < 1100) return;
    lastCheck = performance.now();
    try {
      await request(
        "/v1/sessions/" + saved.session_id + "/camera-check",
        { full_body, side_view, light, fps: Math.min(240, fps), delegate },
        saved.token,
      );
    } catch {
      /* The next check can retry; camera data is never included. */
    }
  }
  function resetStats() {
    stats = new FrameStats();
    lastDebug = -Infinity;
  }
  function showDebug(now: number) {
    if (!options.debug || now - lastDebug < 500) return;
    lastDebug = now;
    el("debug").textContent =
      "Debug · " +
      debugText(stats.snapshot(), {
        requested: options.delegate,
        width: video.videoWidth,
        height: video.videoHeight,
        replay,
      });
  }
  function showCount(reps: number, note: string) {
    setText(count.querySelector("strong")!, String(reps));
    setText(count.querySelector("span")!, reps === 1 ? "rep" : "reps");
    setText(count.querySelector("small")!, note);
  }
  function receive(
    frame: Frame,
    delegate: "GPU" | "CPU" = "CPU",
    inferenceMs = 33,
  ) {
    if (!pipeline) return;
    stats.record(frame.timestamp, inferenceMs, delegate);
    showDebug(frame.timestamp);
    if (recording && recorded.length < 18000)
      recorded.push(structuredClone(frame));
    const output = pipeline.push(frame);
    if (!output) return;
    lastPoints = output.points;
    draw(output.points);
    syncMirror();
    mirrorSender.frame();
    const c = output.checks;
    for (const chip of el("checks").children)
      chip.classList.toggle(
        "ok",
        c[(chip as HTMLElement).dataset.check as keyof typeof c] === true,
      );
    void cameraCheck(
      c.full_body,
      c.side_view,
      c.light,
      stats.snapshot().fps,
      delegate,
    );
    if (view?.active_set?.kind === "hold") {
      holdStarted ??= frame.timestamp;
      if (
        !holdSent &&
        (frame.timestamp - holdStarted) / 1000 >= view.active_set.target
      ) {
        holdSent = true;
        transport?.enqueue(pipeline.finishHold());
      }
    }
  }
  camera = new LocalCamera(video, receive, status, options.delegate, stop);
  function resetPipeline() {
    if (!view?.catalog) return;
    const active = view.active_set,
      id = active?.set_id ?? "camera_check";
    if (id === activeId && pipeline) return;
    transport?.stop();
    activeId = id;
    partial.clear();
    coach.clear();
    showCount(0, "");
    holdStarted = null;
    holdSent = false;
    if (saved) {
      const current: RepTransport = new RepTransport(async (event) => {
        if (!saved) return;
        try {
          await request(
            "/v1/sessions/" +
              saved.session_id +
              ("rep_index" in event ? "/reps" : "/holds"),
            event,
            saved.token,
          );
        } catch (err) {
          const failure =
            err instanceof ApiError ? sendFailure(err.code) : "retry";
          // A set that ended on the server only drops this transport's queue; the camera keeps running.
          if (failure === "drop") current.stop();
          else if (failure === "end") stop();
          else throw err;
        }
      }, status);
      transport = current;
    }
    pipeline = new PosePipeline(
      view.catalog,
      active?.exercise_id ?? "squat",
      id,
      (event) => {
        if (view?.phase === "IN_SET" && !view.paused && active) {
          partial.clear();
          coach.clear();
          transport?.enqueue(event);
          showCount(
            event.rep_index + 1,
            event.rated
              ? "Last rep rated (estimated)"
              : "Last rep not rated: unclear view",
          );
        }
      },
      (reason) => {
        if (view?.phase === "IN_SET" && !view.paused && active) {
          partial.show(reason);
          coach.show(reason);
        }
      },
    );
  }
  async function refresh() {
    if (!saved) {
      if (claim && performance.now() - lastClaim >= CLAIM_POLL_MS) {
        lastClaim = performance.now();
        try {
          const r = await request<{ phone_token: string; session_id: string }>(
            "/v1/pair/claim",
            { request_id: claim.request_id, claim_token: claim.claim_token },
          );
          saved = {
            session_id: r.data.session_id,
            token: r.data.phone_token,
            role: "phone",
          };
          saveSession(saved);
          claim = null;
        } catch (err) {
          const { code: reason, message } = requestFailure(err);
          if (!claimWaiting(reason)) {
            status(pairError(reason, place, message));
            // The app being unable to reach the TV is a pause in the wait, not the end of this request.
            if (reason !== "network" && reason !== "unavailable")
              resetPairing();
          }
        }
      }
      return;
    }
    try {
      const r = await request<View>(
        "/v1/sessions/" + saved.session_id,
        undefined,
        saved.token,
      );
      view = r.data;
      showStage(true);
      if ((sensing || armed) && !keepSensing(view, armed)) stop();
      syncMirror();
      line.update(view, sensing, replay);
      resetPipeline();
    } catch (err) {
      const failure = requestFailure(err);
      if (
        err instanceof ApiError &&
        ["unauthorized", "not_found", "session_expired"].includes(err.code)
      ) {
        stop();
        transport?.stop();
        clearSession();
        saved = null;
        view = null;
        showStage(false);
        resetPairing();
        syncControls();
        status(
          "This session ended, or the TV removed this phone. Pair again to continue.",
        );
      } else
        status(
          failure.code === "unavailable" && failure.message
            ? failure.message
            : "Connection lost. Reconnecting…",
        );
      line.forget();
    }
  }
  code.oninput = () => {
    code.value = code.value.replace(/\D/g, "").slice(0, 6);
    syncCode();
  };
  code.onfocus = () => root.querySelector(".code")!.classList.add("focus");
  code.onblur = () => root.querySelector(".code")!.classList.remove("focus");
  el("pairing").onsubmit = async (event) => {
    event.preventDefault();
    if (pairing || code.value.length !== 6) return;
    pairing = true;
    syncCode();
    try {
      claim = (
        await request<typeof claim>("/v1/pair/request", {
          pairing_code: code.value,
        })
      ).data;
      if (claim) {
        el("verification").textContent = claim.verification_code;
        el("verify").hidden = false;
        el("again").hidden = false;
        root.querySelector<HTMLElement>(".code")!.hidden = true;
        el("pair").hidden = true;
        setText(el("title"), "Check the TV");
        status("Compare this number with the one on the TV.");
      }
    } catch (err) {
      const { code: reason, message } = requestFailure(err);
      status(pairError(reason, place, message));
    } finally {
      pairing = false;
      syncCode();
    }
  };
  el("again").onclick = () => {
    resetPairing();
    status(PAIR_HINT);
    code.focus();
  };
  el("start").onclick = async () => {
    if (!view || !canStart(view)) return;
    if (replay) {
      if (replaying) return;
      resetStats();
      const generation = ++cancelReplay;
      replaying = true;
      sensing = true;
      armed = true;
      syncMirror();
      status("Replay running with synthetic landmarks.");
      try {
        const filename = el<HTMLSelectElement>("fixture").value;
        const result = await fetch(
          "/v1/replay/" + encodeURIComponent(filename),
        );
        if (!result.ok) throw new Error("Replay is disabled on this server.");
        const fixture = (await result.json()) as { frames: Frame[] };
        for (let i = 0; i < fixture.frames.length; i++) {
          if (cancelReplay !== generation) break;
          receive(fixture.frames[i]);
          if (i % 30 === 0) await new Promise((r) => setTimeout(r, 5));
        }
      } catch (err) {
        status(String(err));
      } finally {
        // A replay is a finite feed: once its frames run out, nothing is sensing. Stop() already reset a cancelled one.
        if (cancelReplay === generation) {
          replaying = sensing = false;
          syncMirror();
        }
      }
    } else {
      resetStats();
      try {
        await camera!.start();
        sensing = true;
        armed = true;
        syncMirror();
        const devices = await camera!.cameras();
        el<HTMLSelectElement>("lens").innerHTML =
          '<option value="">Default rear camera</option>' +
          devices
            .map(
              (d) => `<option value="${e(d.deviceId)}">${e(d.label)}</option>`,
            )
            .join("");
        el<HTMLSelectElement>("lens").value = camera!.lens ?? "";
      } catch (err) {
        status(cameraError(err, inApp));
      }
    }
  };
  el("mirror").onclick = () => {
    mirrorEnabled = !mirrorEnabled;
    writeMirrorPreference(() => localStorage, mirrorEnabled);
    if (replay && mirrorEnabled && view && canStart(view)) armed = true;
    syncMirror();
    if (mirrorSender.active) mirrorSender.frame();
  };
  el("stop").onclick = () => {
    stop();
    transport?.stop();
    activeId = "";
    pipeline = null;
  };
  if (!replay) {
    // A lens change restarts a running camera only; it never opens a stopped one.
    el("wide").onclick = () => {
      if (armed) void camera!.wide().catch(() => {});
    };
    el<HTMLSelectElement>("lens").onchange = () => {
      if (!armed) return;
      resetStats();
      void camera!
        .start(el<HTMLSelectElement>("lens").value)
        .catch((err) => status(cameraError(err, inApp)));
    };
  }
  const fixture = el<HTMLSelectElement>("fixture");
  if (fixture && params.get("fixture")) fixture.value = params.get("fixture")!;
  if (params.get("record") === "1") {
    el("record").onclick = () => {
      recording = !recording;
      if (recording) recorded = [];
      el("record").textContent = recording
        ? "Stop local recording"
        : "Record locally";
    };
    el("export").onclick = () => {
      const url = URL.createObjectURL(
        new Blob(
          [
            JSON.stringify({
              source: "manual-device-recording",
              frames: recorded,
            }),
          ],
          { type: "application/json" },
        ),
      );
      const a = document.createElement("a");
      a.href = url;
      a.download = "cleanrep-local-recording.json";
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    };
  }
  const timer = setInterval(() => void refresh(), 1000),
    mirrorTimer = setInterval(() => {
      if (replay && mirrorSender.active) mirrorSender.frame();
    }, MIRROR_INTERVAL_MS),
    heartbeat = setInterval(() => {
      if (saved)
        void request(
          "/v1/sessions/" + saved.session_id + "/heartbeat",
          {},
          saved.token,
        ).catch(() => {});
    }, 5000);
  const hidden = () => {
    if (document.visibilityState === "hidden") stop();
  };
  document.addEventListener("visibilitychange", hidden);
  // The phone app going to the background: same as a hidden page, and the camera stays off on return.
  document.addEventListener("cleanrep-native-pause", stop);
  // A saved session reconnects straight into the camera stage; a stale one falls back to the code.
  showStage(Boolean(saved));
  // Opened from the TV's QR code: the code rides in the fragment, which never reaches the server. Fill it
  // in, and drop it from the address so a later reload does not offer an expired code.
  if (scanned) {
    history.replaceState(null, "", location.pathname + location.search);
    if (!saved) {
      code.value = scanned.code;
      status("Code filled in from your TV. Tap Pair with TV.");
    }
  }
  syncCode();
  syncControls();
  void refresh();
  const dispose = () => {
    clearInterval(timer);
    clearInterval(mirrorTimer);
    clearInterval(heartbeat);
    stop();
    transport?.stop();
    camera?.dispose();
    document.removeEventListener("visibilitychange", hidden);
    document.removeEventListener("cleanrep-native-pause", stop);
  };
  if (tvAddress)
    el("other-tv").onclick = () => {
      dispose();
      serverEntry(root);
    };
  return dispose;
}
