import catalogData from "../../../../packages/contracts/catalog.public.json";
import type { Catalog } from "../pose/types";
import type { Profile } from "../../../../packages/contracts/generated";
import {
  ApiError,
  clearSession,
  escape as e,
  key,
  readSession,
  request,
  saveSession,
} from "../shared/api";
import type { View } from "../shared/views";
import { installRemoteNavigation } from "./focus";
import { CoachBanner } from "./coach";
import { backAction, footerHint } from "./remote";
import { serverPlace } from "../shared/place";
import { qrSvg } from "../shared/qr";
import {
  cameraPrivacy,
  capitalize,
  comparisonLabel,
  comparisonNote,
  countdownLabel,
  faultLabel,
  faultStats,
  movementMinutes,
  restLabel,
  sourceLabel,
  summaryHeading,
  summarySubtitle,
  liveTarget,
  mirrorPanel as cameraPanel,
  mirrorShape,
  MIRROR_START_GRACE_MS,
  phoneCamera,
  phoneLink,
  phoneUrl,
  repFeedback,
  sessionProgress,
  showsLiveMirror,
  tallySlots,
} from "./labels";
import type { RepMark } from "../shared/views";
// Home illustration: the same lime skeleton the phone draws, at the depth it checks. Each line draws in once.
const SQUAT_FIGURE = `<svg class="figure" viewBox="0 0 640 560" role="img" aria-label="A side-on squat with the hip at knee height, drawn as the phone's skeleton lines">
<line class="guide" x1="120" y1="365" x2="620" y2="365"/><text class="note" x="620" y="345" text-anchor="end">hip depth</text>
<path class="guide arc" d="M392.2 401.1 A48 48 0 0 1 372 363.8"/><text class="note" x="452" y="440">knee angle</text>
<line class="floor" x1="80" y1="500" x2="620" y2="500"/>
<g class="bones"><path pathLength="1" d="M528 226 L425 226 L318 192 L262 368 L420 362 L332 486"/><path pathLength="1" d="M310 494 L405 494"/></g>
<circle class="head" cx="346" cy="140" r="34"/>
<g class="joints"><circle cx="318" cy="192" r="11"/><circle cx="425" cy="226" r="11"/><circle cx="528" cy="226" r="11"/><circle cx="262" cy="368" r="11"/><circle cx="420" cy="362" r="11"/><circle cx="332" cy="486" r="11"/></g>
</svg>`;
const catalog = catalogData as Catalog;
export function tv(root: HTMLElement): () => void {
  const coachElement = document.createElement("p");
  const coach = new CoachBanner(coachElement);
  root.after(coachElement);
  let saved = readSession("tv"),
    view: View | null = null,
    version = 0,
    error = "",
    busy = false,
    cameraCheck = false,
    drawer = false,
    about = false;
  let answers = [false, false, false],
    profile: Required<Profile> = {
      equipment: [],
      quiet: true,
      no_floor: false,
      length_min: 15,
      go_easy: [],
      readiness_restricted: false,
    },
    selfReps = 10,
    effort: "easy" | "ok" | "hard" = "ok";
  let retry: {
    action: string;
    payload: Record<string, unknown>;
    method: string;
  } | null = null;
  // Focus memory survives re-renders where the focused button was briefly disabled (busy).
  let focusedAction: string | undefined,
    screenKey = "";
  // When the current set or summary first appeared, for the start countdown and the rest timer.
  let shownKey = "",
    shownAt = 0;
  // Persistent mirror panel and rep feedback badge survive innerHTML re-renders without flicker.
  const mirrorPanel = document.createElement("figure"),
    mirrorFrame = document.createElement("div"),
    mirrorImg = document.createElement("img"),
    mirrorCaption = document.createElement("figcaption"),
    repBadge = document.createElement("div");
  mirrorPanel.className = "mirror-panel";
  mirrorPanel.dataset.mirrorPanel = "live";
  mirrorFrame.className = "mirror-frame";
  mirrorImg.alt = "Live camera from your phone";
  mirrorCaption.textContent = "Live camera from your phone";
  mirrorFrame.appendChild(mirrorImg);
  mirrorPanel.append(mirrorFrame, mirrorCaption);
  repBadge.className = "rep-feedback";
  repBadge.dataset.repFeedback = "";
  repBadge.hidden = true;
  // The last frame's shape, kept so the hint that stands in for the feed has the same size.
  let shape: { ratio: string; portrait: boolean } | null = null;
  const applyShape = (panel: Element) => {
    panel.classList.toggle("portrait", Boolean(shape?.portrait));
    const frame = panel.querySelector<HTMLElement>(".mirror-frame");
    if (frame) frame.style.aspectRatio = shape?.ratio ?? "";
  };
  mirrorImg.addEventListener("load", () => {
    const next = mirrorShape(mirrorImg.naturalWidth, mirrorImg.naturalHeight);
    if (!next || next.ratio === shape?.ratio) return;
    shape = next;
    applyShape(mirrorPanel);
  });
  let mirrorFetching = false,
    mirrorObjectUrl: string | null = null,
    lastRepSetId = "",
    lastRepCount: number | null = null,
    feedbackTimer: ReturnType<typeof setTimeout> | null = null; // When the camera area last appeared, so a feed that has not resumed yet reads "starting", not "off".
  let mirrorSince: number | null = null,
    mirrorStartTimer: ReturnType<typeof setTimeout> | null = null;
  const liveMirror = () => showsLiveMirror(view, { about, cameraCheck });
  const mirrorStarting = () =>
    mirrorSince !== null && Date.now() - mirrorSince < MIRROR_START_GRACE_MS;
  function clearMirrorUrl() {
    if (mirrorObjectUrl) {
      URL.revokeObjectURL(mirrorObjectUrl);
      mirrorObjectUrl = null;
    }
    mirrorImg.removeAttribute("src");
  }
  function clearRepFeedback() {
    if (feedbackTimer) {
      clearTimeout(feedbackTimer);
      feedbackTimer = null;
    }
    repBadge.hidden = true;
    repBadge.dataset.repFeedback = "";
  }
  async function pollMirror() {
    if (mirrorFetching || !saved || !liveMirror()) return;
    mirrorFetching = true;
    try {
      const res = await fetch("/v1/sessions/" + saved.session_id + "/mirror", {
        headers: { Authorization: "Bearer " + saved.token },
        cache: "no-store",
      });
      if (!liveMirror()) return;
      if (res.status === 200) {
        const blob = await res.blob();
        if (!liveMirror()) return;
        const nextUrl = URL.createObjectURL(blob),
          prevUrl = mirrorObjectUrl;
        mirrorObjectUrl = nextUrl;
        mirrorImg.src = nextUrl;
        if (prevUrl) URL.revokeObjectURL(prevUrl);
      }
    } catch {
      /* Transient mirror fetch errors wait for the next poll tick. */
    } finally {
      mirrorFetching = false;
    }
  }
  root.addEventListener("focusin", (event) => {
    const action = (event.target as HTMLElement).dataset?.action;
    if (action) focusedAction = action;
  });
  const button = (id: string, label: string, style = "") =>
    `<button data-action="${id}" class="${style}" ${busy ? "disabled" : ""}>${label}</button>`;
  const name = (id: string) => catalog.exercises[id]?.name ?? id;
  // One slot per target rep, filled in order with each counted rep's result.
  const tally = (marks: RepMark[] | null | undefined, target: number) =>
    `<ol class="tally" data-tally aria-label="${marks?.length ?? 0} of ${target} reps counted">${tallySlots(
      marks,
      target,
    )
      .map((m) => `<li class="${m}"></li>`)
      .join("")}</ol>`;
  const cameraLine = (v: View) => {
    const c = phoneCamera(v);
    return c
      ? `<p class="confidence ${c.on ? "rated" : ""}" data-phone-camera="${c.on ? "on" : "off"}">● ${c.text}</p>`
      : "";
  };
  // The live panel is attached after render; the hint keeps the same box so the space is never empty.
  const cameraArea = (v: View) => {
    const p = cameraPanel(v, mirrorStarting());
    if (!p) return "";
    if (p.kind === "live") return "<div data-mirror-slot></div>";
    return `<figure class="mirror-panel" data-mirror-hint><div class="mirror-frame mirror-hint"><p>${e(p.text)}</p></div><figcaption>Camera view</figcaption></figure>`;
  };
  async function refresh() {
    if (!saved) return;
    try {
      const r = await request<View>(
        "/v1/sessions/" + saved.session_id,
        undefined,
        saved.token,
      );
      version = r.version;
      coach.update(
        r.data.phase === "IN_SET" && !r.data.paused
          ? (r.data.coach_notice ?? null)
          : null,
      );
      const active = r.data.active_set;
      if (!active || r.data.phase !== "IN_SET" || r.data.paused) {
        lastRepSetId = "";
        lastRepCount = null;
        clearRepFeedback();
      } else {
        if (active.set_id !== lastRepSetId) {
          lastRepSetId = active.set_id;
          lastRepCount = null;
          clearRepFeedback();
        }
        const fb = repFeedback(
          lastRepCount,
          active.reps_counted,
          r.data.last_rep,
        );
        lastRepCount = active.reps_counted;
        if (fb) {
          repBadge.className = "rep-feedback " + fb.kind;
          repBadge.dataset.repFeedback = fb.kind;
          repBadge.innerHTML = `<span class="rep-symbol">${e(fb.symbol)}</span><span class="rep-label">${e(fb.label)}</span>`;
          repBadge.hidden = false;
          if (feedbackTimer) clearTimeout(feedbackTimer);
          feedbackTimer = setTimeout(() => {
            repBadge.hidden = true;
            repBadge.dataset.repFeedback = "";
            feedbackTimer = null;
          }, 1500);
        }
      }
      // Coach notices update the banner in place; they never force a full render.
      const screen = (v: View | null) =>
        JSON.stringify(v, (key, value) =>
          key === "coach_notice" ? undefined : value,
        );
      const changed = screen(view) !== screen(r.data);
      view = r.data;
      if (changed) {
        render();
      }
    } catch (err) {
      if (
        err instanceof ApiError &&
        ["not_found", "session_expired", "unauthorized"].includes(err.code)
      ) {
        // A session saved by an earlier run that no longer exists is not news; one that ends on screen is.
        const wasShowing = view !== null;
        saved = null;
        view = null;
        clearSession();
        clearMirrorUrl();
        clearRepFeedback();
        error = wasShowing ? "This session has ended. Start a new one." : "";
        render();
        return;
      }
      error = err instanceof Error ? err.message : "Connection interrupted";
      render();
    }
  }
  async function mutate(
    action: string,
    data: Record<string, unknown> = {},
    method = "POST",
  ) {
    if (!saved || busy) return;
    busy = true;
    error = "";
    const payload =
      retry?.action === action && retry.method === method
        ? retry.payload
        : { ...data, expected_version: version, idempotency_key: key() };
    retry = { action, payload, method };
    render();
    try {
      await request(
        "/v1/sessions/" + saved.session_id + (action ? "/" + action : ""),
        payload,
        saved.token,
        method,
      );
      retry = null;
      if (method === "DELETE") {
        clearSession();
        saved = null;
        view = null;
        cameraCheck = false;
        clearMirrorUrl();
        clearRepFeedback();
      }
      await refresh();
    } catch (err) {
      if (err instanceof ApiError) {
        retry = null;
        await refresh();
      }
      error =
        err instanceof Error
          ? err.message
          : "Connection interrupted. Retry the action.";
    } finally {
      busy = false;
      render();
    }
  }
  function render() {
    coach.update(
      view?.phase === "IN_SET" && !view.paused
        ? (view.coach_notice ?? null)
        : null,
    );
    let title = "A little practice. A clearer picture.",
      subtitle = "Your phone is the camera. Your TV keeps count.",
      content = "";
    if (!view || !cameraPanel(view)) mirrorSince = null;
    else if (mirrorSince === null) mirrorSince = Date.now();
    const key = `${view?.phase}|${view?.active_set?.set_id ?? view?.summary?.set_id ?? ""}`;
    if (key !== shownKey) [shownKey, shownAt] = [key, Date.now()];
    const elapsed = Date.now() - shownAt;
    if (!view) {
      content = `<div class="actions">${button("start", "Start a session", "primary")}${button("about", "About & privacy")}</div>${SQUAT_FIGURE}`;
    } else if (view.paused) {
      title = "Paused";
      subtitle = "Completed sets are saved.";
      // Choosing an area replans the rest of the session around it, as on the setup screen.
      content = `${button("resume", "Resume", "primary")}<h2>Go easy on</h2><div class="row">${button("knees", "Knees")}${button("wrists", "Wrists")}${button("lower_back", "Lower back")}</div><h2>Session</h2><div class="row">${button("quiet", view.profile.quiet ? "✓ Quiet" : "Quiet", view.profile.quiet ? "selected" : "")}${button("floor", view.profile.no_floor ? "✓ No floor work" : "No floor work", view.profile.no_floor ? "selected" : "")}${view.sensor_mode === "remote_only" ? "" : button("remote", "Use remote only")}${button("delete", "End and delete")}</div>`;
    } else
      switch (view.phase) {
        case "LOBBY": {
          title = "Pair your phone";
          subtitle = "";
          // A TV sign-in screen: a QR code on the left, the code large on the right. The QR carries the
          // code too, so a scan opens the phone page ready to pair; the address is the fallback.
          const url = phoneUrl(
            window.CleanRepNative?.apiOrigin() ?? location.origin,
            view.phone_url,
          );
          // The code is issued once per session and lasts 5 minutes; in the lobby an empty code has expired, and
          // Back here leaves the app, so Start over (End and delete) is the way to a new one.
          content = view.pairing_code
            ? `<div class="pairing"><div class="join">${qrSvg(phoneLink(url, view.pairing_code, window.CleanRepNative?.tlsFingerprint?.() ?? ""), "QR code that opens the CleanRep phone page with this code")}<div><p class="lead">Scan with your phone's camera</p><p class="alt">Or open this address</p><p class="url">${e(url)}</p></div></div><div class="pair-code">${Array.from(view.pairing_code, (c) => `<span>${e(c)}</span>`).join("")}</div></div>${view.pairing_requests.map((r) => `<div class="card approve">Verify both screens show <strong>${e(r.verification_code)}</strong>${button("approve:" + r.request_id, "Approve phone", "primary")}</div>`).join("")}${button("remote", "Use remote only")}`
            : `<p class="lead">This pairing code has expired. Start over for a new one.</p>${button("delete", "Start over", "primary")}${button("remote", "Use remote only")}`;
          break;
        }
        case "READINESS":
          title = "Before we start";
          subtitle = "Any yes gives a gentler session, up to 15 minutes.";
          content =
            [
              "Has a qualified professional advised limiting physical activity?",
              "Is there a current reason to avoid your usual exercise?",
              "Would a gentler session be appropriate today?",
            ]
              .map(
                (q, i) =>
                  `<div class="question"><p>${q}</p>${button("answer:" + i, answers[i] ? "Yes" : "No", answers[i] ? "selected" : "")}</div>`,
              )
              .join("") +
            // The advice appears once it applies, not as standing small print.
            `${answers.some(Boolean) ? `<p class="small">With any yes, talk to a qualified professional about suitable activity.</p>` : ""}${button("readiness", "Continue", "primary")}`;
          break;
        case "SETUP":
          title = "Today's session";
          subtitle = "";
          content = `<h2>Equipment</h2><div class="row">${(["chair", "mat", "counter"] as const).map((x) => button("equipment:" + x, (profile.equipment.includes(x) ? "✓ " : "") + capitalize(x), profile.equipment.includes(x) ? "selected" : "")).join("")}</div><h2>Options</h2><div class="row">${button("setupquiet", profile.quiet ? "✓ Quiet" : "Quiet", profile.quiet ? "selected" : "")}${button("setupfloor", profile.no_floor ? "✓ No floor work" : "No floor work", profile.no_floor ? "selected" : "")}${button("length", "Length: " + profile.length_min + " min")}</div><h2>Go easy on</h2><div class="row">${(["knees", "wrists", "lower_back"] as const).map((x) => button("area:" + x, (profile.go_easy.includes(x) ? "✓ " : "") + capitalize(x.replace("_", " ")), profile.go_easy.includes(x) ? "selected" : "")).join("")}</div>${button("plan", "Make plan", "primary")}`;
          break;
        case "PLANNING":
        case "ADAPTING":
          title =
            view.phase === "ADAPTING"
              ? "Adjusting your session"
              : "Planning your session";
          subtitle = "";
          content = '<div class="pulse">One moment…</div>';
          break;
        case "READY":
          title = cameraCheck ? "Find a side view" : "Your plan";
          subtitle = cameraCheck
            ? "Phone at hip height, 2.5–3 m to your side."
            : "";
          content = cameraCheck
            ? `<div class="camera-check"><div><div class="checks">${(
                [
                  ["full_body", "Whole body visible"],
                  ["side_view", "Side view"],
                  ["light", "Steady light"],
                ] as const
              )
                .map(
                  ([x, label]) =>
                    `<p class="check ${view!.camera_check?.[x] ? "on" : ""}"><span>${view!.camera_check?.[x] ? "✓" : ""}</span>${label}</p>`,
                )
                .join(
                  "",
                )}</div>${button("begin", "Continue", "primary")}${button("remote", "Use remote only")}</div>${view.mirror_live ? "<div data-mirror-slot></div>" : '<img class="placement" src="/placement.svg" alt="Top view: phone to your side, 2.5 to 3 metres away, lens at hip height">'}</div>`
            : `<span class="badge" data-source="${e(view.plan?.source)}">${e(sourceLabel(view.plan?.source))}</span><ol class="plan">${view.plan?.blocks.map((b) => `<li><strong>${e(name(b.exercise_id))}</strong><span><b>${b.sets} × ${b.reps ?? b.hold_s}</b> ${b.hold_s ? "seconds" : "reps"}</span></li>`).join("")}</ol>${button("camera", "Start", "primary")}${button("regenerate", "Regenerate")}${button("backsetup", "Back to setup")}`;
          break;
        case "DEMO": {
          const block = view.plan!.blocks[view.block_index];
          title = name(block.exercise_id);
          subtitle = catalog.exercises[block.exercise_id].cue;
          content = `<div class="exercise-grid"><div class="demo-media"><video class="demo" autoplay loop muted playsinline disableremoteplayback controlslist="noremoteplayback nodownload nofullscreen" poster="/demos/${block.exercise_id}.jpg" src="/demos/${block.exercise_id}.mp4"></video>${cameraArea(view)}</div><div><div class="big-number">${block.reps ?? block.hold_s}<small>${block.hold_s ? "seconds" : "reps"}</small></div><p>Set ${view.set_index + 1} of ${block.sets}</p>${cameraLine(view)}${button("startset", "Start set", "primary")}${button("advance", "Skip exercise")}</div></div>`;
          break;
        }
        case "IN_SET": {
          const live = liveTarget(view.active_set!),
            holding =
              view.active_set!.kind === "hold" && view.sensor_mode === "paired";
          title = name(view.active_set!.exercise_id);
          // Kept short but never empty: it holds the coach banner's space above the count.
          subtitle = holding
            ? "Hold steady. The phone times it."
            : view.sensor_mode === "paired"
              ? "Move at your own pace."
              : "Remote only: no form rating.";
          content = `<div class="live-grid"><div><p class="countdown" data-countdown>${countdownLabel(elapsed)}</p><div class="big-number" data-rep-count>${live.value}${view.active_set!.kind === "reps" && view.sensor_mode === "paired" ? `<span class="of">/ ${view.active_set!.target}</span>` : ""}<small>${live.unit}</small></div>${view.sensor_mode === "paired" && !holding ? tally(view.rep_marks, view.active_set!.target) : ""}${view.sensor_mode === "paired" && !holding ? `<p class="confidence ${view.active_set!.rated ? "rated" : ""}">● ${view.active_set!.reps_counted === 0 ? "Waiting for the first rep" : view.active_set!.rated ? "Last rep rated (estimated)" : "Last rep not rated: unclear view"}</p>` : ""}${view.sensor_mode === "remote_only" ? `<div class="row">${button("less", "−")}${button("count", selfReps + " reps done")}${button("more", "+")}</div>${button("effort", "Effort: " + { easy: "too easy", ok: "about right", hard: "too hard" }[effort])}` : ""}${cameraLine(view)}${button("endset", "End set", "primary")}${view.sensor_mode === "paired" && !view.phone_connected ? button("remote", "Use remote only") : ""}</div><div class="live-side">${cameraArea(view)}<div data-rep-feedback-slot></div></div></div>`;
          break;
        }
        case "SET_SUMMARY":
        case "DRILL_OFFER": {
          const s = view.summary!,
            rest = view.plan?.blocks[view.block_index]?.rest_s;
          title = s.comparison ? "Re-test complete" : "Set complete";
          subtitle = summarySubtitle(s);
          const setTally = s.rep_marks?.length
            ? `<div class="set-tally">${tally(s.rep_marks, 0)}<p class="legend"><span class="clean">Clean</span><span class="fault">Flagged</span><span class="unrated">Not rated</span></p></div>`
            : "";
          content = `${setTally}<div class="summary card"><h2 data-fault="${e(s.dominant_fault ?? "")}">${e(summaryHeading(s))}</h2><div class="stats">${faultStats(
            s.faults,
          )
            .map(
              ([f, n]) =>
                `<p><strong>${n}</strong> ${e(faultLabel(f).toLowerCase())}</p>`,
            )
            .join(
              "",
            )}</div><div class="explain">${view.explanation ? `<p data-explanation>${e(view.explanation.text)}</p><span class="badge">${e(sourceLabel(view.explanation.source, true))}</span>` : `<p class="confidence" data-explanation-pending>Writing feedback…</p>`}${s.comparison ? `<p data-comparison data-verdict="${e(s.comparison.verdict)}">${e(comparisonLabel(s.comparison))}</p><p>${comparisonNote(s.comparison.verdict)}</p>` : ""}</div></div><div class="next"><p class="rest" data-rest="${rest ?? ""}">${restLabel(rest, elapsed)}</p>${s.drill_offer?.drill_id ? button("drill", "Try " + e(name(s.drill_offer.drill_id)), "primary") : ""}${button("advance", "Continue", s.drill_offer?.drill_id ? "" : "primary")}</div>`;
          break;
        }
        case "RECAP":
        case "ENDED":
          title = "Practice, noticed.";
          subtitle = "";
          content = `<div class="stats recap"><p><strong>${view.recap?.sets_done ?? 0}</strong> sets done</p><p><strong>${view.recap?.reps_counted ?? 0}</strong> reps counted</p><p><strong>${view.recap?.rated_reps ?? 0}</strong> rated reps</p><p><strong>${view.recap?.estimated_movement_min ?? 0}</strong> ${e(movementMinutes(view.recap?.estimated_movement_min ?? 0).replace(/^\S+ /, ""))}</p></div>${view.recap?.comparisons.map((c) => (c ? `<p class="verdict" data-verdict="${e(c.verdict)}">${e(comparisonLabel(c))}</p>` : "")).join("")}<p class="card next-time">${e(view.recap?.next_time)}</p>${view.phase === "RECAP" ? button("advance", "Finish", "primary") : ""}${button("delete", "End and delete")}`;
          break;
      }
    if (about) {
      title = "Private by design";
      subtitle = "No accounts. No names. No video uploads.";
      content = `<p>${e(cameraPrivacy(serverPlace(window.CleanRepNative?.apiOrigin() ?? location.origin, window.CleanRepNative)))}</p><p>Sessions expire after two hours. Feedback is estimated; this is a practice tool.</p>${button("about", "Back", "primary")}`;
    }
    const progress = view && !about ? sessionProgress(view) : null,
      rail = progress
        ? `<div class="rail"><ol>${Array.from({ length: progress.total }, (_, i) => `<li class="${i < progress.current ? "done" : i === progress.current ? "now" : ""}"></li>`).join("")}</ol><span>Exercise ${progress.current + 1} of ${progress.total}</span></div>`
        : "",
      paired =
        view?.phase !== "LOBBY" &&
        view?.sensor_mode === "paired" &&
        view.phone_connected,
      mode = !view
        ? ""
        : view.phase === "LOBBY"
          ? "Pairing"
          : view.sensor_mode === "remote_only"
            ? "Remote only"
            : view.phone_connected
              ? "Phone paired"
              : "Phone disconnected";
    // Each sentence of the title is a span, so the home title can break between its two sentences.
    root.innerHTML = `<header><a class="brand" href="/tv" tabindex="-1">Clean<span>Rep</span></a>${rail}${mode ? `<span class="mode ${paired ? "on" : ""}">${mode}</span>` : ""}</header><section${!view && !about ? ' class="hero"' : ""}><h1>${title
      .split(". ")
      .map(
        (line, i, all) =>
          `<span>${e(line)}${i < all.length - 1 ? "." : ""}</span>`,
      )
      .join(
        " ",
      )}</h1>${subtitle ? `<p class="subtitle">${e(subtitle)}</p>` : ""}${error ? `<p class="error" role="alert">${e(error)}</p>` : ""}<div class="content">${content}</div></section><footer><span class="hints">${footerHint(
      { drawer, about, view },
    )
      .map((h) => `<span>${e(h)}</span>`)
      .join(
        "",
      )}</span>${view && !["LOBBY", "ENDED"].includes(view.phase) && !view.paused ? button("pause", "Pause") : ""}</footer>${drawer ? `<aside class="modal"><h2>Session details</h2><p>Version ${version} · ${e(view?.phase)}</p>${view?.traces?.length ? `<pre>${e(JSON.stringify(view.traces, null, 2))}</pre>` : `<p class="subtitle">No plan or explanation events yet.</p>`}${button("drawer", "Close details")}</aside>` : ""}`;
    // A hint that still says "starting" is re-checked once the grace period ends.
    if (mirrorStartTimer) clearTimeout(mirrorStartTimer);
    mirrorStartTimer = null;
    if (mirrorStarting() && root.querySelector("[data-mirror-hint]"))
      mirrorStartTimer = setTimeout(
        render,
        mirrorSince! + MIRROR_START_GRACE_MS - Date.now(),
      );
    root.querySelectorAll("[data-mirror-hint]").forEach(applyShape);
    if (liveMirror()) {
      const mirrorSlot = root.querySelector("[data-mirror-slot]");
      if (mirrorSlot) {
        if (view?.phase === "IN_SET") mirrorFrame.appendChild(repBadge);
        else if (repBadge.parentElement === mirrorFrame) repBadge.remove();
        mirrorSlot.replaceWith(mirrorPanel);
      }
      void pollMirror();
    } else {
      clearMirrorUrl();
      if (view?.phase === "IN_SET" && !view.paused && !about) {
        const hintFrame = root.querySelector(
            "[data-mirror-hint] .mirror-frame",
          ),
          feedbackSlot = root.querySelector("[data-rep-feedback-slot]");
        if (hintFrame) hintFrame.appendChild(repBadge);
        else if (feedbackSlot) feedbackSlot.replaceWith(repBadge);
      }
    }
    if (matchMedia("(prefers-reduced-motion: reduce)").matches)
      root.querySelectorAll("video").forEach((v) => v.pause());
    root.querySelectorAll("video").forEach((v) => {
      v.disableRemotePlayback = true;
    });
    const buttons = Array.from(
      root.querySelectorAll<HTMLButtonElement>("button"),
    );
    // On a new screen (or a new pairing request) focus its primary action; otherwise keep the user's focus.
    const nextKey = [
      view?.phase,
      view?.paused,
      view?.pairing_requests?.length,
      view?.summary?.set_id,
      view?.block_index,
      view?.set_index,
      cameraCheck,
      about,
      drawer,
    ].join("|");
    // Readiness starts on the first question so the safety questions are not skipped by default.
    // Session details is a modal: focus its Close button, not a control hidden behind it.
    const primary = drawer
      ? buttons.find((b) => b.dataset.action === "drawer")
      : ((view?.phase === "READINESS" && !view.paused
          ? buttons[0]
          : buttons.find((b) => b.classList.contains("primary"))) ??
        buttons[0]);
    // While busy every button is disabled and cannot take focus; decide only on the settled render, otherwise a
    // remembered action id (e.g. "advance" = Continue) could land on a different button of the next screen.
    if (busy) return;
    const target =
      nextKey !== screenKey
        ? primary
        : (buttons.find((b) => b.dataset.action === focusedAction) ?? primary);
    screenKey = nextKey;
    target?.focus();
  }
  async function click(id: string) {
    if (busy) return;
    if (id.startsWith("answer:")) {
      const i = Number(id.split(":")[1]);
      answers[i] = !answers[i];
      render();
      return;
    }
    if (id.startsWith("equipment:")) {
      const x = id.split(":")[1] as "chair";
      profile.equipment = profile.equipment.includes(x)
        ? profile.equipment.filter((y) => y !== x)
        : [...profile.equipment, x];
      render();
      return;
    }
    if (id.startsWith("area:")) {
      const x = id.split(":")[1] as "knees";
      profile.go_easy = profile.go_easy.includes(x)
        ? profile.go_easy.filter((y) => y !== x)
        : [...profile.go_easy, x];
      render();
      return;
    }
    if (id.startsWith("approve:")) {
      await mutate("pair/approve", { request_id: id.slice(8) });
      return;
    }
    switch (id) {
      case "start":
        busy = true;
        error = "";
        render();
        try {
          const b = await request<{ tv_token: string }>("/v1/bootstrap", {});
          const r = await request<View>("/v1/sessions", {}, b.data.tv_token);
          saved = {
            session_id: r.data.session_id,
            token: b.data.tv_token,
            role: "tv",
          };
          saveSession(saved);
          view = r.data;
          version = r.version;
        } catch (err) {
          error = err instanceof Error ? err.message : String(err);
        }
        busy = false;
        break;
      case "about":
        about = !about;
        break;
      case "remote":
        await mutate("remote-only");
        break;
      case "readiness":
        await mutate("readiness", { answers });
        break;
      case "setupquiet":
        profile.quiet = !profile.quiet;
        break;
      case "setupfloor":
        profile.no_floor = !profile.no_floor;
        break;
      case "length":
        profile.length_min =
          profile.length_min === 10 ? 15 : profile.length_min === 15 ? 20 : 10;
        break;
      case "plan":
        await mutate("setup", {
          equipment: profile.equipment,
          quiet: profile.quiet,
          no_floor: profile.no_floor,
          length_min: profile.length_min,
          go_easy: profile.go_easy,
        });
        if (!error) await mutate("plan");
        break;
      case "regenerate":
        await mutate("plan");
        break;
      case "backsetup":
        profile = view!.profile;
        await mutate("setup", {
          equipment: profile.equipment,
          quiet: profile.quiet,
          no_floor: profile.no_floor,
          length_min: profile.length_min,
          go_easy: profile.go_easy,
        });
        break;
      case "camera":
        cameraCheck = true;
        break;
      case "begin":
        cameraCheck = false;
        await mutate("advance");
        break;
      case "startset":
        selfReps = view!.plan!.blocks[view!.block_index].reps ?? 10;
        await mutate("sets/start", {
          block_id: view!.plan!.blocks[view!.block_index].block_id,
        });
        break;
      case "less":
        selfReps = Math.max(0, selfReps - 1);
        break;
      case "more":
        selfReps = Math.min(30, selfReps + 1);
        break;
      case "effort":
        effort = effort === "ok" ? "hard" : effort === "hard" ? "easy" : "ok";
        break;
      case "endset":
        await mutate("sets/end", {
          set_id: view!.active_set!.set_id,
          ...(view!.sensor_mode === "remote_only"
            ? { self_report: { reps: selfReps, effort } }
            : {}),
        });
        break;
      case "advance":
        await mutate("advance");
        break;
      case "drill":
        await mutate("drill", { decision: "accept" });
        break;
      case "pause":
      case "resume":
        await mutate(id);
        break;
      case "knees":
      case "wrists":
      case "lower_back":
        await mutate("adapt", { discomfort: id });
        break;
      case "quiet":
        await mutate("adapt", { quiet: !view!.profile.quiet });
        break;
      case "floor":
        await mutate("adapt", { no_floor: !view!.profile.no_floor });
        break;
      case "delete":
        await mutate("", {}, "DELETE");
        break;
      case "drawer":
        drawer = !drawer;
        break;
    }
    render();
  }
  root.addEventListener("click", (event) => {
    const target = (event.target as HTMLElement).closest<HTMLButtonElement>(
      "button[data-action]",
    );
    if (target) void click(target.dataset.action!);
  });
  installRemoteNavigation(() => {
    const action = backAction({ drawer, about, view });
    if (action === "close-drawer") {
      drawer = false;
      render();
    } else if (action === "close-about") {
      about = false;
      render();
    } else if (action === "exit") window.CleanRepNative?.exitApp?.();
    else void mutate(action);
  });
  const menu = (event: KeyboardEvent) => {
    if (view && ["ContextMenu", "F10", "Menu"].includes(event.key)) {
      event.preventDefault();
      drawer = !drawer;
      render();
    }
  };
  document.addEventListener("keydown", menu);
  render();
  void refresh();
  const timer = setInterval(() => {
      if (!busy) void refresh();
    }, 600),
    mirrorTimer = setInterval(() => {
      void pollMirror();
    }, 110);
  // Countdowns change only their own text: a full render every second would move focus.
  const ticker = setInterval(() => {
    const count = root.querySelector("[data-countdown]"),
      rest = root.querySelector<HTMLElement>("[data-rest]");
    if (count) count.textContent = countdownLabel(Date.now() - shownAt);
    if (rest)
      rest.textContent = restLabel(
        Number(rest.dataset.rest),
        Date.now() - shownAt,
      );
  }, 250);
  return () => {
    coachElement.remove();
    clearInterval(timer);
    clearInterval(mirrorTimer);
    clearInterval(ticker);
    if (mirrorStartTimer) clearTimeout(mirrorStartTimer);
    clearMirrorUrl();
    clearRepFeedback();
    document.removeEventListener("keydown", menu);
  };
}
