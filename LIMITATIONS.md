# Limitations

## Not yet run on a device

- **CleanRep has not run on a device yet.** Everything in this repository is verified on the JVM, in desktop Chrome, and by building both APKs with Android Lint. The device tests are in [docs/device-tests.md](docs/device-tests.md), and all are NOT RUN:
  - whether MediaPipe loads inside the phone app's WebView;
  - pairing through the app;
  - the TV app running its server on the emulator, and its memory with the camera view on;
  - a physical Fire TV on Wi-Fi.
- **What did run, before the move.** The earlier version ran its server on a laptop. One live 10-squat set with a OnePlus Nord 4 (in Chrome) and the Fire OS app on the emulator worked end to end on 19 Oct (developer-reported).
- **No physical Fire TV.** The TV app was walked with the D-pad only on an Android TV API 30 emulator, which stands in for Fire OS 8 ([friction log](docs/friction-log.md) FL-003). Amazon WebView, real overscan and readability at 3 m are unverified. The target stick is the Fire TV Stick 4K Max (2 GB). Sticks with less memory are untested and not a target ([FL-005](docs/friction-log.md)).
- **Android-only paths that no test reaches:**
  - Android's regex engine (the server falls back from a flag Android refuses);
  - static files served from the APK's assets;
  - the Keystore-sealed certificate password;
  - Conscrypt's TLS on Android 7.1, which has TLS 1.2 only.

## Accuracy and evidence

- **No accuracy measured on real people.** Rep counting, unclear-view abstention and fault rules are tested on synthetic landmarks, not on people, clothing, lighting or camera placement. Thresholds are starting hypotheses. Push-up, bridge and hold logic has unit tests only. The model is MediaPipe's Lite pose model.
- **No user study.** No usability or benefit claims are made.

## Design limits

- **Built-in planner only.** The plan and the one-sentence feedback come from authored rules. The development version could also ask Claude on Amazon Bedrock, but CleanRep runs only on the two devices and includes no AWS code.
- **The server lives only while the TV app is open.** That is enough for a session in the room. Sessions are saved, so reopening the app resumes them. Back on a top-level TV screen leaves the app and stops the server.
- **Its store is one JSON file, owned by one process.** Each write rewrites it, up to about 330 KB in a long session. Session documents are capped at 24 KB, so extreme sessions can reach the cap before 60 sets; the server refuses more rather than dropping evidence silently.
- **Coach notices are best effort.** "Not counted" notices live 4 seconds in memory, with no retry. The TV sees them only through its 600 ms polling.
- **Pairing needs the phone app, a sideloaded APK.** Chrome alone cannot use the camera on a page served over plain HTTP from the TV. The first scan's landing page and APK download are not protected against an attacker already on the home network ([SECURITY.md](SECURITY.md)).
- **The emulator needs relays.** Its own network is not the home Wi-Fi, so testing it with a phone needs `adb forward` and `adb reverse` ([device tests](docs/device-tests.md)).
- **Debug builds only.** A release build is refused for now: the server's loopback HTTP needs cleartext, and debug builds log the pairing link.
- **Style debt.** The ported server code is dense, with lines over 120 characters. A reformat is left for after the hackathon.
- **QR codes.** The pairing QR encoder matched reference encoders module for module, but no phone camera has yet scanned it from a TV screen.
