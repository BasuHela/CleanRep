# CleanRep

**Your phone watches your form. Your Fire TV coaches you, and nothing leaves your home.**

CleanRep is a short bodyweight-strength session for the living room. Prop your phone up to your side. It measures every rep on the phone with MediaPipe pose tracking. The Fire TV shows:
- the plan and a demonstration of each move;
- a live rep count, with a ✓ or a fault marker after each rated rep;
- a brief notice when an attempt didn't count, such as "Not counted: bend deeper";
- after each set, what was measured, one plain sentence about it, and a drill for the main fault;
- a re-test, and whether that fault became less frequent.

Finishing your reps is not the same as doing them well, and most home workouts only count.

**Everything runs on your two devices.** The session server runs inside the Fire TV app. The phone uses a small CleanRep app that opens the camera and talks to the TV over HTTPS, pinned to the TV's own certificate. There is no laptop, no cloud server and no account. Only numbers leave the phone. If you turn on *Show camera on TV*, the phone's view, with your posture overlay, goes straight to the TV over your Wi-Fi. It is kept in memory for at most 2 seconds and never stored. Fire TV has no camera API for apps, which is why the phone is the camera.

```
 Phone: CleanRep app                                  Fire TV: CleanRep app
 ┌───────────────────────────────┐   home Wi-Fi     ┌───────────────────────────────────────┐
 │ camera + MediaPipe, on device │   HTTPS :8443    │ session server (Java, inside the app) │
 │ per-rep numbers, optional view│ ───────────────▶ │ TV screens in a WebView, D-pad only   │
 │ pins the TV's certificate     │   TV cert pinned │ serves the phone app's APK on :8105   │
 └───────────────────────────────┘                  └───────────────────────────────────────┘
```

**Judges:** see [docs/judge-instructions.md](docs/judge-instructions.md). Install the TV app, then scan the QR code on the TV; the TV offers the phone app itself.

## Try it in a desktop browser (no devices, about 3 minutes)

You need JDK 17 or later, Node 22 and Chrome.

```sh
make setup      # npm ci, then the web build
make demo       # the TV app's server on this computer, with replay allowed
```

1. **TV:** open `http://127.0.0.1:8105/tv` and use only the arrow keys and Enter. Choose **Start a session**.
2. **Phone:** in a second window, open `http://127.0.0.1:8105/phone?source=replay&fixture=squat_shallow_10`. It is labelled REPLAY: recorded squats stand in for the camera. Enter the TV's 6-digit code. On the TV, choose **Approve phone** when both screens show the same 4 digits.
3. **TV:** answer the three readiness questions (No), then **Continue**, pick **Chair**, **Make plan**, **Start**, **Continue**, **Start set**.
4. **Phone:** after the TV's 3-2-1, choose **Run replay**. The TV summary shows 10 reps, 6 above about-parallel depth, a feedback sentence and a rest timer, and offers **Try Box squat to chair**.
5. Accept the drill. On the phone, select **Clear squat · 10**, run it for the drill, then **Continue**, **Start set** and run it again for the re-test. The TV shows **Improved** with the before and after counts.
6. Keep choosing **Continue** (or **Skip exercise**) to reach the recap, then **End and delete**.

This is the J-1 browser journey, which `make browser-test` runs with D-pad keys only.

## What is verified, and what is not

| Claim | Status |
|---|---|
| The session server (pairing, plan, sets, faults, drill and re-test, recovery, limits, camera view) | **Verified on the JVM:** 98 Java tests, including 8 journeys (419 requests) recorded from the reference server it was ported from, real TLS handshakes, and the entry point |
| The phone app's network rules and TLS pinning | **Verified on the JVM:** 11 Java tests, with real handshakes |
| TV and phone pages, rep logic, abstention when the view is unclear | **Verified:** 143 Vitest tests, on synthetic landmarks only |
| The whole journey in a browser: D-pad only, drill, re-test, recap, restart, phone loss, request privacy, live camera view | **Verified in desktop Chrome:** 10 Playwright journeys against the Java server |
| The TV and phone APKs | Debug builds succeed, and Android Lint finds no API above Fire OS's Android 7.1 (API 25) |
| CleanRep on the Fire OS emulator with the phone app on a real phone | **NOT RUN yet** ([device tests](docs/device-tests.md)) |
| A physical Fire TV stick on home Wi-Fi | **NOT RUN.** No hardware |
| Rep accuracy on real people, usability with real users | **NOT RUN.** No user study; no accuracy claim |

The previous version ran its server on a laptop. One live 10-squat set on a OnePlus Nord 4 with the Fire OS app on the emulator worked end to end on 19 Oct (developer-reported). CleanRep moves that server into the TV app; that combination has not run on a device yet. See [LIMITATIONS.md](LIMITATIONS.md).

## Build from source

You need JDK 17+, Node 22, and the Android SDK with `platforms;android-35` and `build-tools;35.0.0` (`ANDROID_HOME`, default `~/Android/Sdk`). The Gradle wrapper fetches Gradle 8.11.1 and the Android Gradle Plugin on first use.

| Command | What it does |
|---|---|
| `make setup` | `npm ci` and the web build |
| `make test` | The server's Java tests, the phone app's Java tests and Vitest (`make core-test`, `make phone-test`, `make web-test`) |
| `make lint` / `make typecheck` | Prettier and TypeScript checks |
| `make browser-test` | The ten Playwright journeys against the Java server (needs Chrome) |
| `make demo` | The server on this computer for the browser demo above |
| `make server` | The server on this computer's Wi-Fi, for a phone test without a TV ([device tests](docs/device-tests.md)) |
| `make tv-build` / `make tv-install` | The TV app's debug APK, with the phone APK packed inside; install with `CLEANREP_ADB_SERIAL=<serial>` |
| `make phone-build` / `make phone-install` | The phone app's debug APK on its own |

Build outputs: `apps/tv/android/app/build/outputs/apk/debug/app-debug.apk` (TV, about 28 MB, includes the phone APK) and `apps/phone/android/app/build/outputs/apk/debug/app-debug.apk` (phone, about 14 MB). Both are debug builds; a release build is refused for now ([LIMITATIONS.md](LIMITATIONS.md)).

## Repository

| Path | What it is |
|---|---|
| `apps/tv/android` | The Fire TV app: a WebView shell, the D-pad bridge, and the server's start and stop |
| `apps/core` | The session server in plain Java 8 with no dependencies: the API, the JSON store, the HTTP and TLS listeners, and `Main` for running it on a computer |
| `apps/phone/android` | The phone app: the phone pages from its own packaged `https://` origin, the camera, and pinned HTTPS to the TV |
| `apps/web` | The TV and phone pages (TypeScript, Vite), pose tracking and rep logic, and the Playwright journeys |
| `packages/contracts`, `packages/domain/catalog.json` | The JSON schemas, their TypeScript types, and the exercise catalog, shared by the server and the pages |
| `fixtures/landmarks`, `evals/fixtures` | Synthetic pose recordings for replay and tests |

More: [ARCHITECTURE.md](ARCHITECTURE.md), [SECURITY.md](SECURITY.md), [TESTING.md](TESTING.md), [LIMITATIONS.md](LIMITATIONS.md), [friction log](docs/friction-log.md), [licenses](docs/assets-license.md).

CleanRep was developed as FormLab. Its Java server was ported from that project's Python reference server, and comments that name `services/…` or `packages/domain/*.py` refer to that reference.

This app was not created or endorsed by Amazon. Fire TV is a trademark of Amazon.com, Inc. or its affiliates.
