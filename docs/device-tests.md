# Device tests

These are the manual checks that no automated test can make. Run them in order: each one narrows down where a failure can be. Record each result where it says **Result**, with the date and the exact versions.

**Setup.** The commands assume:
- the Android SDK in `~/Android/Sdk` (`ADB=~/Android/Sdk/platform-tools/adb` below);
- a phone with Android 9 or later, the phone app's minimum, on adb (USB or wireless debugging);
- the `firetv_api30` emulator (Android TV API 30 with 2 GB RAM, `emulator -avd firetv_api30 -memory 2048`), which stands in for a Fire TV ([friction log](friction-log.md) FL-003).

With both devices on adb, pass serials: `adb devices` lists them.

## 1. The phone app's camera and pose model

**What it checks:** can MediaPipe load its worker, wasm and model inside the phone app's WebView, and open the camera from the app's packaged `https://cleanrep.phone` origin?

```bash
make phone-build
CLEANREP_ADB_SERIAL=<phone-serial> make phone-install
$ADB -s <phone-serial> shell am start -a android.intent.action.VIEW -d cleanrep://check org.cleanrep.phone
$ADB -s <phone-serial> logcat -s CleanRep:D chromium:I        # in another terminal
```

Allow the camera when Android asks. That first request is itself a check: the app must not lose the camera while the permission dialog is open.

**What to check:**
- **The page:** Origin `https://cleanrep.phone` and isSecureContext `true`.
- **The camera** starts on the first try, right after allowing it.
- **The model runs:** the status turns to `Running (GPU)` or `Running (CPU)`, with Landmarks `33`, a non-zero Results / sec, and an inference time.
- **The assets load through the app:** the log has `intercept GET` lines for `/pose-worker.js`, `/vision/vision_bundle.mjs`, `/vision/wasm/…` and `/models/pose_landmarker_lite.task`.
- **Background and back:** press Home, wait a second and reopen CleanRep. The visibilityState row reads `native-pause:… -> visibilitychange:hidden -> native-resume:… -> visibilitychange:visible`.

**Result:** NOT RUN

## 2. The server on a computer, the phone over Wi-Fi

**What it checks:** the whole phone path except the TV app (pinned pairing, a set, the camera view, a restart), with the TV app's own server running on a computer on the phone's Wi-Fi. Do test 1 first.

1. **Start the server.** In terminal 1:

   ```bash
   make server
   ```

   The ready line ends with `phone link cleanrep://pair?host=<computer-wifi-ip>&port=8443&code=<code>&fp=<pin>`. The store is `var/home` and the certificate is `var/home-tls`.
2. **Start a session.** In the computer's browser, open `http://127.0.0.1:8105/tv`, select **Start a session** and note the code. This page's QR code carries no pin, because only the TV app knows it, so use the printed link.
3. **Pair the phone.** Open the link with the code put in:

   ```bash
   $ADB -s <phone-serial> shell am start -a android.intent.action.VIEW -d "'cleanrep://pair?host=<computer-wifi-ip>&port=8443&code=<code>&fp=<pin>'" org.cleanrep.phone
   ```

   Without adb, paste the link into the app's **Or paste the link from your TV** field and tap **Connect**.

**What to check:**
- **Pairing and a set:** the app opens on the pairing page with the code filled in and `TV at <computer-wifi-ip>`. Select **Pair with TV**, approve on the TV with the same match number, then run a short set with the camera view on. Reps count on the TV, and the live camera view shows the phone's view.
- **The landing page:** in the phone's Chrome, open `http://<computer-wifi-ip>:8105/phone#code=<code>&fp=<pin>`. It shows **Open in the CleanRep app**, which opens the app. (**Download the app** is a 404 here: only the TV build packs the APK.)
- **A restart:** Ctrl-C the server and run `make server` again. The TV page and the phone carry on with the same session, and the pin is the same.
- **The API stays off plain HTTP:** from the phone's Chrome, `http://<computer-wifi-ip>:8105/v1/healthz` answers 403 `https_required`.

**Result:** NOT RUN

## 3. The TV app on the Fire OS emulator

**What it checks:** the TV app running the session itself. Do test 2 first.

**Why the relays:** the emulator has its own private network. The address in its QR code (`10.0.2.x`) is not reachable from the phone, so the computer relays the HTTPS port, and the phone app uses `https://localhost:8443`, still pinned.

1. **Build and install.** `tv-build` builds the phone APK too and packs it inside:

   ```bash
   make tv-build
   $ADB -s emulator-5554 shell cat /proc/meminfo | grep MemTotal     # about 2,000,000 kB
   CLEANREP_ADB_SERIAL=emulator-5554 make tv-install
   $ADB -s emulator-5554 logcat -s CleanRep                         # in another terminal
   ```

   Expect `CleanRep: home server ready, phone link cleanrep://pair?host=<ip>&port=8443&code=<code>&fp=<pin>`. A `home server could not start` line gives the reason.
2. **Relay the HTTPS port**, emulator to computer, then computer to phone:

   ```bash
   $ADB -s emulator-5554 forward tcp:8443 tcp:8443
   $ADB -s <phone-serial> reverse tcp:8443 tcp:8443
   ```

3. **Pair.** On the TV, select **Start a session** and note the code. Then, with the pin from step 1:

   ```bash
   $ADB -s <phone-serial> shell am start -a android.intent.action.VIEW -d "'cleanrep://pair?host=localhost&port=8443&code=<code>&fp=<pin>'" org.cleanrep.phone
   ```

**What to check:**
- **Pairing and a set:** the phone shows `TV at localhost`. Pair, approve on the TV, then run a set with the camera view on: reps on the TV, and the live camera view.
- **Memory during that set,** with no low-memory kill in logcat:

  ```bash
  $ADB -s emulator-5554 shell dumpsys meminfo org.cleanrep.tv | grep -E "TOTAL|Java Heap|Native Heap"
  ```

- **Leaving and reopening:** Back on the TV's home screen leaves the app, which stops the server. Reopen CleanRep: the TV rejoins the same session, and the logged pin is the same.
- **The landing page and APK from the TV:** run `$ADB -s emulator-5554 forward tcp:8105 tcp:8105`, then open `http://127.0.0.1:8105/phone` in the computer's browser and download `/cleanrep-phone.apk`.

**Result:** NOT RUN

## 4. A Fire TV on home Wi-Fi (the real path)

**What it checks:** the path a household or a judge takes, from the TV's QR code alone ([judge instructions](judge-instructions.md)). It needs a Fire OS device, such as a Fire TV Stick 4K Max. Vega OS sticks can't sideload APKs.

**What to check:**
- **The QR code** names `http://<tv-wifi-ip>:8105/phone`.
- **The landing page:** the phone's camera app opens it in Chrome.
- **The APK download:** **Download the app** fetches the phone APK from the TV.
- **Pairing:** after installing, a second scan and **Open in the CleanRep app** pair the phone.
- **A full set** runs with the camera view on.
- **Readability:** from 3 m, the text, focus ring and coach banner can be read, and nothing is cut off by overscan.

**Result:** NOT RUN (no Fire TV hardware yet)

## Clean up

```bash
$ADB reverse --remove-all
$ADB forward --remove-all
# terminal 1: Ctrl-C stops `make server`
```
