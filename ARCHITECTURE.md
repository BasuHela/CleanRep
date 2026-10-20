# Architecture

CleanRep has two apps and no server of its own. The Fire TV app runs the session server, and the phone app is the camera.

```
 Phone: org.cleanrep.phone                              Fire TV: org.cleanrep.tv
 ┌─────────────────────────────────┐                   ┌─────────────────────────────────────────┐
 │ WebView, https://cleanrep.phone │  HTTPS :8443      │ HomeServer: CleanRepApi + two listeners │
 │  phone pages + MediaPipe pose   │  TV cert pinned   │   :8443 HTTPS, /v1 only (phones)        │
 │ Java bridge makes every request │ ────────────────▶ │   :8105 HTTP: pages, phone APK; /v1 for │
 │  (PhonePolicy, PinnedTls)       │                   │         loopback only (the TV itself)   │
 └─────────────────────────────────┘                   │ WebView, https://cleanrep.local          │
                                                       │  TV pages ──▶ http://127.0.0.1:8105      │
                                                       └─────────────────────────────────────────┘
```

## The TV app (`apps/tv/android`)

- **A WebView shell.** It serves the TV pages from its own assets at the packaged origin `https://cleanrep.local`, with a strict CSP, and blocks every other URL.
- **The `CleanRepNative` bridge.** Java makes every API request, to `http://127.0.0.1:8105`, which is the app's own server over loopback. The bridge also keeps the TV's session token under an Android Keystore AES-GCM key.
- **D-pad.** Remote keys become keyboard events: arrows, Enter, Back (Escape) and Menu.
- **Start-up.** `HomeServer.start()` runs once per process, off the UI thread, before the page loads. It stops only when the activity finishes, so the `recreate()` after a WebView renderer crash keeps the server.
- **The certificate.** `HomeServer` keeps the PKCS#12 password under its own Android Keystore key, and serves static files from the APK's assets.
- **The QR code.** It names `http://<the TV's Wi-Fi address>:8105/phone#code=<code>&fp=<certificate pin>`.
- **What the build packs.** `scripts/tv-build.sh` builds the web pages and the phone APK, then packs the phone APK, the exercise catalog and the JSON schemas under `assets/cleanrep-home/`. The WebView refuses those paths; only the server reads them.

## The server (`apps/core`)

Plain Java 8 with no dependencies, because the TV app's minimum is Android 7.1 (API 25), the oldest Fire OS 6 level. `scripts/core.sh` refuses Java APIs that Android 7.1 lacks, and the Android Lint check confirms it.

- **`CleanRepApi`:** the session API under `/v1`.
  - **Pairing.** A 6-digit code on the TV; the phone claims it; both screens show a 4-digit match number; the TV approves.
  - **Roles and tokens.** Separate TV and phone tokens, with a 2-hour expiry.
  - **The session.** Readiness questions, then a plan from authored rules.
  - **Sets and faults.** A camera check, sets of reps or timed holds, fault rules, one plain sentence from authored wording, a drill for the main fault, then a re-test with a comparison, and a recap.
  - **Safety.** "Remote only" without a phone, and discomfort handling.
  - **Integrity.** Idempotent writes, request limits and the coach notices.
  - **The camera view.** In-memory frames of at most 150 KB, each kept for at most 2 seconds and never stored.
- **`JsonStore`:** one JSON file per server with compare-and-swap versions and atomic writes, owned by one process. Sessions survive an app restart. Documents are capped at 24 KB.
- **`org.cleanrep.core.http`:** a small HTTP/1.1 server, one per port.
  - Thread-per-connection workers with a deadline reaper, strict parsing and keep-alive.
  - `Router`: the per-port rules (below).
  - `TlsIdentity`: a self-signed EC P-256 certificate made in plain Java, kept as PKCS#12. TLS 1.2 or 1.3 with ECDHE-ECDSA AES-GCM only.
  - `Listeners` binds both ports before the store opens, then serves.
- **`Main`:** the same server on a computer, for `make demo`, `make server` and the browser journeys.

| Port | Protocol | Serves | `/v1` API |
|---|---|---|---|
| 8105 | HTTP | the web pages, the Chrome landing page, `cleanrep-phone.apk` | loopback peers only (the TV's own bridge); others get 403 `https_required` |
| 8443 | HTTPS, the TV's certificate | `/v1` only | anyone who passes pairing and tokens |

## The phone app (`apps/phone/android`)

- **A WebView shell** on the TV shell's pattern. The phone pages come from its own packaged origin `https://cleanrep.phone`, so the page is a secure context and can open the camera without a certificate install. The camera is granted only to that origin.
- **The bridge** keeps the TV's method names and adds `setServer` and `requestMirror`. Java does all networking, and only to private IPv4 addresses or loopback. A private address needs HTTPS pinned to the TV certificate's SHA-256 (`PinnedTls`, which replaces the CA chain and host-name checks); plain HTTP is allowed only to loopback (`adb reverse`).
- **Pairing links.** A `cleanrep://pair?host&port&code&fp` link sets the server and opens the pairing page with the code filled in. `cleanrep://check` opens a standalone camera and pose check.
- **Tested on the JVM.** The policy and pin code use no Android classes.

## The pages (`apps/web`)

- **One Vite build** serves both apps: `/tv` for the TV and `/phone` for the phone.
- **Pose tracking.** The phone page runs MediaPipe PoseLandmarker Lite in a worker. The model, wasm and bundle are copied into the build and checksum-checked, so nothing loads from a CDN.
- **Rep logic.** Each rep becomes numbers: depth angle, tempo and confidence. The page abstains when the view is unclear, and sends only those numbers.
- **The camera view** is optional and goes through the bridge's `requestMirror`.
- **The TV pages** are a 1920×1080 layout driven by the D-pad, with focus management, overscan-safe margins and large type.
- **The Chrome landing page.** When the TV's QR code is opened in Chrome, the page makes no API call. It shows **Open in the CleanRep app**, a Chrome intent link whose fallback downloads the phone APK from the TV.

## Pairing a phone, end to end

1. **TV:** **Start a session** shows the code and the QR code.
2. **Phone camera app:** scanning opens Chrome on `http://<tv-ip>:8105/phone#code=…&fp=…`. The fragment is never sent over the network.
3. **Chrome:** the landing page offers **Open in the CleanRep app**.
   - With the app installed, it opens on the pairing page with the code filled in, and pins `fp`.
   - Without it, Chrome downloads `cleanrep-phone.apk` from the TV. After installing it, scan again.
4. **Phone:** **Pair with TV**. Both screens show the same 4-digit number, and the TV approves.

## Where the code came from

CleanRep was developed as FormLab, whose server first ran on a laptop in Python (FastAPI, pydantic). For CleanRep that server was ported to Java so it could run inside the TV app. Golden tests replay 8 journeys (419 requests) recorded from the Python server and compare every response, and 2,395 schema cases recorded from its pydantic models check the JSON validation. Comments that name `services/…` or `packages/domain/*.py` refer to that reference.

The Python server also had an optional AWS mode: Claude on Amazon Bedrock wrote the plan and the feedback sentence, with Guardrails. CleanRep does not include it. Its plan and wording come from the authored rules, which were the fallback there.
