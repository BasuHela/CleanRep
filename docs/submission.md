# Devpost submission draft

The text to paste into Devpost for the Amazon "Build, Ship, Shape" hackathon, **Fire TV track**. The deadline is 23 Oct 2026, 12:00 PDT (24 Oct, 00:30 IST).

**Before submitting, the developer still has to:**
- [ ] Create the GitHub repository and push this one. Either make it public (the MIT `LICENSE` is at the root), or keep it private and add the reviewers `chris-trag`, `knmeiss`, `giolaq`, `anishamalde`, `mosesroth` and `emersonsklar`.
- [ ] Attach `cleanrep-tv-0.1.0-debug.apk` and `cleanrep-phone-0.1.0-debug.apk` to a GitHub release, built with `make tv-build` from the tagged commit. [Judge instructions](judge-instructions.md) use these names.
- [ ] Run the [device tests](device-tests.md). Then update the status lines below, the README table and [LIMITATIONS.md](../LIMITATIONS.md) with what actually ran.
- [ ] Record the video, under 3 minutes, showing the Fire OS app with the CleanRep name. Upload it publicly to YouTube or Vimeo. If the TV is the emulator, caption it "Fire OS app on the Android TV emulator".
- [ ] Re-read the official rules and FAQ in a browser. These notes come from a 14–15 Oct reading.

Lines marked **(optional)** can go if space is short. Keep every claim at its status: nothing here may say more than the repository shows.

---

## Project name

CleanRep

## Tagline

Your phone watches your form. Your Fire TV coaches you, and nothing leaves your home.

## Inspiration

Fitness apps on Fire TV play videos at you. None of them can see you, and Fire TV has no camera API for apps. Form apps on phones can see you, but they coach on a 6-inch screen you can't read mid-squat. Most home workouts count reps; finishing your reps is not the same as doing them well.

We wanted the big screen to coach from what actually happened: see a fault, give one drill for it, then re-test and say honestly whether it changed. We also wanted no account and no cloud, with video that never leaves the home.

## What it does

CleanRep is a short bodyweight-strength session for the living room, run with the Fire TV remote.

- **Pairing:** scan the QR code on the TV with your Android phone, and approve it on the TV when both screens show the same 4-digit number.
- **The plan:** three safety questions and the equipment you have, then a short plan from a catalog of 11 movements: squats and their drills, squat jumps, push-ups, glute bridges, a plank and a bridge hold.
- **Each set:** propped to your side, the phone runs MediaPipe pose tracking on the device. The TV shows:
  - a live rep count;
  - a ✓ or a fault marker after each rated rep;
  - a brief *Not counted: bend deeper* when an attempt didn't count.
- **The camera view:** the phone's view with your posture overlay appears on the TV, over your Wi-Fi only. It is kept in the TV's memory for at most 2 seconds and never stored. Turn it off and only numbers leave the phone.
- **After each set:** what was measured, one plain sentence about it, a rest timer, and a drill for the main fault, such as *Box squat to chair* for shallow squats.
- **The re-test:** the same movement again, and an honest comparison: improved, no change, or not enough data.
- **When the view is unclear,** CleanRep abstains rather than guessing: a rep it can't see well is counted but not rated.
- **Without a phone:** remote-only mode counts reps with − and + and keeps the whole session.
- **Recovery:** if the phone leaves, the TV keeps the history. If the TV app closes, reopening it resumes the session.

## How we built it

- **The Fire TV app is the server.** The session server is plain Java 8 with no dependencies, so it runs on Fire OS's Android 7.1 and up, inside the TV app. It covers:
  - pairing, roles and tokens;
  - plans, sets, faults, drills and re-tests;
  - a JSON store with compare-and-swap;
  - HTTP and TLS listeners hardened against slow, oversized and malformed clients.

  It was ported from our earlier Python reference server. The port was checked by replaying 419 recorded requests from 8 journeys and comparing every response.
- **The TV screens** are TypeScript in a WebView, designed for 1920×1080 and driven by the D-pad only.
- **The phone app** is a small Android app that loads the phone pages from its own packaged `https://` origin. That gives the camera a secure context with no internet certificate. Its Java code makes every request to the TV over HTTPS, pinned to the TV's own self-signed certificate, whose fingerprint arrives in the QR code.
- **The TV hands the phone its app.** The TV APK carries the phone APK, and the first QR scan offers it for download, so no store or laptop is needed.
- **Pose tracking:** MediaPipe Pose Landmarker Lite runs in a worker on the phone. Rep and fault rules are deterministic: a four-state machine per rep (top, descending, bottom, ascending), with abstention when the view is unclear.
- **Tests:**
  - 98 Java tests for the server, including real TLS handshakes against the phone app's own pinning code;
  - 11 Java tests for the phone app;
  - 149 Vitest tests;
  - 10 Playwright journeys in Chrome against the Java server, which drive the TV with D-pad keys only.
- **(optional) How we worked:** coding agents (Claude Code, OpenAI Codex, and the Google Antigravity CLI), each in a sandbox with its own worktree. A coordinating Claude Code session reviewed, tested and merged each change, and the developer made the product decisions and ran the devices.

## Challenges we ran into

- **The camera needs a secure context, but a TV on home Wi-Fi has no trusted certificate.** Plain HTTP pages can't open the phone's camera. We first hosted the API on a laptop with a local CA, then looked at AWS hosting ([FL-006](friction-log.md)). Finally we put the server inside the TV app, and gave the phone a tiny app with its own `https://` origin and a pinned connection.
- **No Fire OS emulator.** We tested on an Android TV API 30 image as a stand-in, whose WebView changed version mid-week ([FL-003](friction-log.md)).
- **WebView layout:** a WebView lays out at 960×540 CSS px by default, while Amazon's web-app guide says 1920×1080 ([FL-004](friction-log.md)).
- **A crash with the camera view on:** the TV app died once during a set on a 1 GB emulator boot. We added a renderer-crash handler that rejoins the session ([FL-005](friction-log.md)); the crash has not recurred at 2 GB.
- **Android 7.1 limits for the server:** no `java.util.Base64`, `java.time` or `java.nio.file`. Before compiling, our build refuses those APIs.

## Accomplishments that we're proud of

- **Fault, drill, then an honest re-test,** on the big screen, all on two devices in the home.
- **A server inside a TV app,** checked response by response against the reference server it replaced.
- **Privacy you can check:** the server refuses any request field named like an image, frame or landmark, and sensor requests are capped at 2 KB. A Playwright journey inspects every phone request body.
- **No dead end without a camera:** remote-only mode is the same session.

## What we learned

- **Pose estimates can rank reps, not measure them clinically.** We show depth as estimated, and abstain when the view is unclear.
- **TV design leaves little room.** Every action must be reachable with the D-pad, and the coach banner must never cover the count.
- **A small, pinned app** was simpler and safer than any certificate scheme for a phone talking to a TV.

## What's next for CleanRep

- **Device runs:** a physical Fire TV, then measured rep accuracy on real people, with n.
- **A safer first scan:** derive the pairing number from the TV's certificate, so the first scan is protected too.
- **A release build** with no debug logging.
- **More exercises and a front view,** for knee tracking.

## Built with

`java` · `android` · `fire-os` · `webview` · `typescript` · `vite` · `mediapipe` · `playwright` · `vitest` · `gradle` · `tls`

## Testing instructions (Devpost field)

> Install `cleanrep-tv-0.1.0-debug.apk` from the GitHub release on a Fire OS device (adb or Downloader). Open CleanRep, select Start a session, and scan the QR code with an Android phone on the same Wi-Fi: the TV offers the phone app. With no Android phone, select **Use remote only**. With no Fire TV, `make setup && make demo` runs the same server in a desktop browser. Full steps: `docs/judge-instructions.md`.

## Product feedback (Devpost field)

> Our friction log, `docs/friction-log.md`, records six problems we hit with Amazon tools, each with versions, exact errors, workarounds and a suggestion:
> - FL-002: an Android Gradle Plugin preferences-folder conflict that shows only with `--stacktrace`;
> - FL-003: no Fire OS 8 emulator, and a stand-in whose WebView drifts;
> - FL-004: a WebView's 960×540 default viewport against the 1920×1080 web-app guidance;
> - FL-005: a hybrid-app crash with a live camera view;
> - FL-006: no simple AWS home for a small HTTPS API with a role after App Runner's closure to new customers, which is why the server moved into the TV app;
> - FL-007: the Android TV stand-in's Google Cast service holds port 8443, so the TV app's server could not start there.

## Category and challenges

- **Track:** Fire TV.
- **Mini-challenges:** none. CleanRep has no AWS code (developer's decision, 20 Oct).

## Status lines to keep honest

Update these once the device tests run:
- **Verified:** the server, the phone app's network code, the pages and 10 browser journeys (automated); both APKs build and pass Android Lint.
- **Ran once:** the earlier, laptop-server version did one live 10-squat set with a OnePlus Nord 4 and the Fire OS app on the emulator, on 19 Oct (developer-reported).
- **Worked, developer-reported (21 Oct):** CleanRep's TV app on the emulator with a real Android phone on Wi-Fi, no debugging on the phone, through the laptop relay (device test 3b). The phone model and checks are not recorded yet.
- **NOT RUN:** a physical Fire TV; accuracy on real people; any user study.
