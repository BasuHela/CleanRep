# Testing

Latest run (21 Oct 2026, on this repository; Gradle `lintDebug` last ran on 20 Oct):

| Command | Result |
|---|---|
| `make core-test` | **98 passed**: the session server on the JVM |
| `make phone-test` | **11 passed**: the phone app's network rules and TLS pinning |
| `make web-test` | **149 passed** in 23 files (Vitest) |
| `make lint`, `make typecheck` | Prettier and TypeScript: clean |
| `make browser-test` | **10 passed** in 1.1 min: Playwright journeys in Chrome against the Java server |
| `make tv-build`, Gradle `lintDebug` | TV debug APK 27,544,642 bytes from a clean build, with the 13,751,787-byte phone APK inside; Lint warnings only, none NewApi. Repeated incremental builds can leave the APK about 14 MB larger, with the same contents, so build a release candidate from clean |
| `make phone-build`, Gradle `lintDebug` | Phone debug APK; Lint warnings only, none NewApi |

Every test name starts with an ID, such as `C-HTTP-5` or `W-PHN-2`. The families are below.

## The server: `make core-test` (98)

`scripts/core.sh` compiles `apps/core` with `javac --release 8`, so only Java 8 APIs are available. Before compiling, it refuses the Java 8 APIs that Android 7.1 lacks (`java.util.Base64`, `java.time`, `java.nio.file`, and others), and the regex flag Android's engine rejects. It then runs every `*Test` class with a small runner, so there are no dependencies and no Gradle. `FILTER=<text>` runs a subset.

| IDs | What they cover |
|---|---|
| C-DOM-1..7 | The catalog, fault boundaries, the fallback matrix, selection and comparison, rep and hold validation, wording |
| C-SES-1..8 | Phase transitions, pause, drill insertion and re-test, recap, idle windows, burst limits |
| C-API-1..8 | Pairing, roles, tokens and expiry, idempotency, policy before routing, origins, concurrency, loopback rules |
| C-STO-1..4, C-REC-1..2 | The JSON store: compare-and-swap, durability, the single owner, size limits, crash leftovers, recovery |
| C-COA-1..2, C-MIR-1..3 | Coach notices and the camera view: rates, expiry, never stored |
| C-JSON-1..3, C-SCH-1..3 | JSON parsing and the schema checks, including 2,395 recorded cases from the reference server's models |
| C-GLD-1..8 | **Golden journeys:** 8 journeys, 419 requests recorded from the Python reference server, replayed against the Java server and compared response by response |
| C-HTTP-1..28 | The HTTP listeners over real sockets: parsing, keep-alive, deadlines, size limits, 411/413/414/431/400/503, HEAD, `Expect`, slow and non-reading clients, per-peer limits, the per-port rules, static files and the phone APK |
| C-TLS-1..15 | The certificate (DER, P-256, PKCS#12, stable across restarts, replaced when unreadable), protocols and suites, real handshakes with the phone app's own `PinnedTls`, a wrong pin refused, hostile clients |
| C-MAIN-1..3 | `Main`: flags, the real server behind real listeners across a restart, HTTPS with a stable pin |

The C-TLS tests compile the phone app's `PinnedTls` and `PhonePolicy` from `apps/phone` into the test classpath, so the TV and the phone are tested against each other's real code. Listener tests bind only to 127.0.0.1 on free ports.

## The phone app: `make phone-test` (11)

P-APP-1..11 run on the JVM without Android:
- which hosts, ports and pairing links are accepted: private IPv4 or loopback only, no DNS names or IPv6, and a pin required for private addresses;
- the request rules: paths, body and token sizes, and camera frames, with the JPEG header;
- the packaged origin's asset paths and MIME types;
- real TLS handshakes: a matching pin succeeds, another certificate's pin fails, and `HttpsURLConnection` pins end to end.

## The pages: `make web-test` (149)

Vitest covers:
- the TV pages: focus, labels, the coach banner, continuity, the QR encoder, checked against external decoders;
- the phone pages: pairing, camera errors, the transport queue, partial notices, the camera view;
- the pose pipeline: geometry, reps, holds, abstention, and replayed synthetic squat recordings;
- the app bridge: W-HOME-1..11;
- the emulator's Wi-Fi relay, `scripts/lan-relay.mjs` (W-RLY-1..6): every spelling of `/v1` refused on 8105 without reaching the TV, pages passed through, 8443 bytes passed through untouched, and a missing TV.

## The journeys: `make browser-test` (10)

Playwright starts `org.cleanrep.core.Main` on 8125 with an empty `var/browser-data`, and drives the TV with keyboard keys only (the D-pad) and the phone page with replayed recordings. Results go to `evals/browser-results.json`.

- J-1 paired shallow set, then drill, an improved re-test and the recap, TV D-pad only
- J-2 discomfort replans, keeps completed blocks and leaves out jumps
- J-3 the phone closes mid-set; remote-only keeps the history and completes
- J-4 a server restart (its own `Main` on 8126) resumes the same phase and version
- J-4b a saved session that no longer exists opens a clean home screen
- J-5 phone request bodies hold only allowed numbers, under 2 KB
- J-6 the camera view sends frames only to `/mirror`, shows the live panel and the rep ✓ on the TV, and stops when turned off
- J-6b the coach banner covers neither the camera panel, the rep count nor End set
- J-7 inside the phone app (a fake bridge), camera frames and every API call go through the bridge
- J-7b the TV's QR code opened in Chrome shows only the landing page: the intent link, no API call, no camera

## Not automated

The device tests in [docs/device-tests.md](docs/device-tests.md) are manual. Test 3b, the TV app on the emulator with a real phone on Wi-Fi, worked as expected on 21 Oct (developer-reported). The rest are NOT RUN: the phone app's camera check, pairing with the laptop server, the emulator over adb relays and its memory with the camera view on, and a physical Fire TV.
