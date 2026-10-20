# Friction log

Problems we hit with Amazon tools and guidance while building CleanRep for Fire TV, recorded when they happened.

**Read this first**
- **The old name stays in the entries.** CleanRep was called FormLab until 20 Oct 2026. Entries quote package IDs, paths and logs as they were at the time (`org.formlab.tv`, `/tmp/amazon-devpost-formlab`), because exact error text is the point of a friction log.
- **Some evidence paths are in the development repo.** Paths such as `docs/implementation-report.md`, `temp/…` and `infra/host.py` are there, not here.
- **One entry is left out.** FL-001 (a Bedrock Guardrails test stub) concerns code CleanRep does not include.
- **Ratings:** `Blocker` means we could not proceed; `High`, a workaround that cost more than an hour; then `Medium` and `Low`.

## Summary

| ID | Tool | Severity | One line |
|---|---|---|---|
| FL-002 | Fire OS shell build (Android Gradle Plugin) | Low | Two preference-folder variables disagree, and the error appears only with `--stacktrace` |
| FL-003 | Fire OS 8 testing without hardware | Medium | There is no Fire OS 8 emulator, and the Android TV stand-in's WebView drifts with Google's releases |
| FL-004 | Fire TV hybrid app layout | Medium | A WebView lays out at 960×540 CSS px, while Amazon's web-app guide says 1920×1080 |
| FL-005 | Fire OS hybrid app with a live camera view | Medium | The TV app died during a set; a renderer-crash handler was added; the cause is unconfirmed |
| FL-006 | AWS hosting for a small companion API | Medium | There was no simple AWS home with HTTPS and a role, which is why the server moved into the TV app |

## Entries

### FL-002 · Android preference variables disagree during Fire OS shell build
- **Date / reporter:** 2026-10-15 · FormLab implementation agent
- **Tool and version:** Android Gradle Plugin 8.7.3, Gradle 8.11.1, Android SDK platform 35; Android toolchain used to package the Fire OS shell (not an Amazon service failure).
- **Task attempted:** Build the debug APK while keeping Gradle/Android preference writes inside the FormLab task cache.
- **Steps taken:** 1. Set ANDROID_USER_HOME to `/tmp/amazon-devpost-formlab/android`. 2. Add ANDROID_SDK_HOME `/tmp/amazon-devpost-formlab` after an analytics preference write attempted the read-only home directory. 3. Run `make tv-build`, then Gradle `assembleDebug --stacktrace`.
- **Expected result:** The build would use the isolated writable preference directory.
- **Actual result:** `Could not create provider for value source AndroidLocationsBuildService.AndroidDirectoryCreator.` The stack trace explains: `com.android.prefs.AndroidLocationsException: Several environment variables and/or system properties contain different paths to the Android Preferences folder. Please correct and use only one way to inject the preference location.` It lists the two variables above and recommends ANDROID_USER_HOME.
- **Severity:** Low.
- **Workaround used:** Make the locations consistent: ANDROID_USER_HOME is `$TASK_CACHE/.android`, matching the directory derived from legacy ANDROID_SDK_HOME. The debug keystore follows ANDROID_USER_HOME. Final build outcome is recorded in the implementation report.
- **Actionable suggestion:** Surface the conflicting preference paths in the top-level Android plugin error, without requiring `--stacktrace`; ensure analytics respects the same preference variable as the build plugin.
- **Evidence:** `scripts/tv-build.sh` (the same variables, renamed `CLEANREP_*`); commands and exact root cause in the development repo's `docs/implementation-report.md`.

### FL-003 · No Fire OS 8 emulator; the Android TV API 30 stand-in has an old, non-updatable WebView
- **Date / reporter:** 2026-10-15 · FormLab coordinator (Claude Code) with the developer
- **Tool and version:** Amazon "Developing for Amazon Fire TV Devices Running Fire OS 8" page (https://www.developer.amazon.com/docs/fire-tv/fire-os-8.html); Android Emulator with the Android TV API 30 x86 system image (AVD `firetv_api30`, tv_1080p, density 320); WebView user agent "AOSP TV on x86", Chrome/90.0.4430.91.
- **Task attempted:** Test the FormLab hybrid (WebView) Fire TV app with D-pad only before a physical Fire TV is available.
- **Steps taken:** 1. Looked for an Amazon Fire OS 8 emulator or system image; the Fire OS 8 page only says "You can test your app's compatibility with Fire OS 8 by connecting to an actual device", and "How Fire TV Development Differs from Android TV Development" and the Smart TV FAQ say emulators are not reliable for Fire TV testing (the Vega virtual device does not run Fire OS Android apps). 2. Used Android TV API 30 (the Android level Fire OS 8 is based on) as the closest stand-in. 3. Installed the debug APK, attached Chrome DevTools to the WebView and read `navigator.userAgent`.
- **Expected result:** An Amazon-provided emulator, or a documented stand-in whose WebView engine matches Fire OS 8's Amazon WebView closely enough to trust layout and JavaScript results.
- **Actual result:** The stand-in runs the stock Android System WebView 90.0.4430.91 (April 2021) with no Play Store to update it. Amazon WebView, which the HTML5 Hybrid App FAQ says replaces the standard WebView only on Fire devices, cannot be tested at all without hardware. We had to lower the Vite build target to `chrome87` to be safe on the emulator, without knowing the real device engine version.
- **Severity:** Medium (emulator results cannot be trusted as Fire OS 8 evidence; the physical-device gate M-TV-1 stays open).
- **Workaround used:** Treat the emulator as a D-pad/layout smoke test only; build for an older Chromium target; keep the physical Fire TV run as a separate manual gate.
- **Actionable suggestion:** For developers without hardware (common in hackathons), publish a Fire OS 8 emulator image, or at least an official "closest Android TV image + known differences" table on the Fire OS 8 page, and state the Amazon WebView Chromium version per Fire OS release in the HTML5 Hybrid App FAQ (last updated Oct 29, 2020).
- **Evidence:** `apps/web/vite.config.ts` (`build.target`); emulator walk notes in the development repo's `evals/device/tv-emulator.md`.
- **Correction 2026-10-19 (evening):** on the same AVD the WebView is now `com.google.android.webview` 155.0.8059.43 (`dumpsys webviewupdate`; renderer log line `cr_WebViewApkApp: version=155.0.8059.43`), and the image is `google/sdk_google_atv_x86/generic_x86:11/RTU1.221129.002` with the Play Store service present (`Finsky` verified our APK at install). So "no Play Store to update it" above was wrong for this image: the WebView updated at some point after 15 Oct. That makes the problem worse, not better: the emulator's engine now drifts with Google's releases and is further still from whatever Amazon WebView a Fire OS 8 device runs, so emulator results are not reproducible over the hackathon week. The `chrome87` build target stays.
- **Update 2026-10-19 (recurred while planning the submission video):** the hackathon rules ask for the video to show the app on a Fire TV device or the Fire TV/Vega simulator, and the FAQ says an emulator is acceptable. The Vega Virtual Device does not run Fire OS (Android) apps, so without hardware the only option for a Fire OS entry is the same Android TV API 30 stand-in. Neither the rules nor the Fire OS 8 page says whether that counts. We will caption every TV shot "Fire OS app on the Android TV emulator". Further suggestion: state in the hackathon FAQ that the Android 11 TV emulator recommended in Amazon's "Use an Android TV emulator" guide is accepted for Fire OS entries.

### FL-004 · Hybrid app lays out at 960×540 CSS px on the TV emulator, while Amazon's web-app guide says 1920×1080
- **Date / reporter:** 2026-10-15 · FormLab coordinator (Claude Code) with the developer
- **Tool and version:** Android TV API 30 emulator (density 320, 1920×1080 display) with Android System WebView 90.0.4430.91; Amazon pages "Customizing Your Web App" (last updated Oct 29, 2020) and "Display and Layout".
- **Task attempted:** Show a 1920×1080-designed TV layout (overscan-safe footer, 32 px base text) in the app's WebView.
- **Steps taken:** 1. Loaded the packaged UI in a plain `WebView` (`setUseWideViewPort` unset, page viewport `width=device-width`). 2. Measured in DevTools: `innerWidth` 960, `innerHeight` 540, `devicePixelRatio` 2. 3. Compared with Amazon's guidance.
- **Expected result:** "Customizing Your Web App" states the Fire TV app resolution is "1080p (1920x1080)" and that a 720p app "fills only 2/3 of the display", implying a 1920-wide CSS layout.
- **Actual result:** The layout viewport was 960×540, so the 1920-wide design scrolled and the footer and Pause button fell outside the 54 px bottom overscan-safe area. ("Display and Layout" does describe the screen as 960×540 dp, so the two Amazon pages describe different units without reconciling them for WebView apps.)
- **Severity:** Medium (needed DevTools measurements on the emulator to diagnose; without the fix the UI ships clipped).
- **Workaround used:** `WebSettings.setUseWideViewPort(true)`, `setLoadWithOverviewMode(true)`, `setSupportZoom(false)` in `MainActivity`, plus `<meta name="viewport" content="width=1920, user-scalable=no">` on TV routes (`apps/web/src/tv/viewport.ts`). Result on the emulator: `innerWidth` 1920, `innerHeight` 1080, `visualViewport.scale` 0.5. Not yet confirmed on a physical Fire TV with Amazon WebView.
- **Actionable suggestion:** Add a "Hybrid/WebView layout" section to "Display and Layout" that gives the CSS viewport a WebView app gets by default on Fire OS 7/8 (dp vs px), and the recommended `WebSettings` + meta viewport for 1080p-designed UIs; update the 720p-only `initial-scale=1.5` advice.
- **Evidence:** `apps/tv/android/app/src/main/java/org/cleanrep/tv/MainActivity.java` (then `org/formlab/tv`), `apps/web/src/tv/viewport.ts`.

### FL-005 · The hybrid TV app crashed during a set while showing the live camera view
- **Date / reporter:** 2026-10-19 (evening) · the developer, written up by the FormLab coordinator (Claude Code)
- **Tool and version:** Fire OS app (debug APK `org.formlab.tv` 0.1.0, minSdk 25, targetSdk 35; WebView shell with a `shouldInterceptRequest` proxy for camera frames) on the Android TV API 30 x86 stand-in emulator (AVD `firetv_api30`, Emulator 37.1.3.0 build 15565763), Android System WebView 90.0.4430.91, software GPU rendering (SwiftShader). See FL-003 for why this stand-in is used.
- **Task attempted:** Do squats with the phone's camera view shown on the TV, which polls one 640×360 JPEG about every 110 ms through the app's native proxy.
- **Steps taken:** 1. `make dev-https`, emulator started, APK installed with `make tv-install`. 2. Paired the Nord 4, started a squat set with the camera view on. 3. During the reps the app crashed. 4. The developer then shut the emulator down.
- **Expected result:** The app keeps running, or reports an error on screen.
- **Actual result:** The app crashed (developer's report). Logcat's in-memory buffer was lost at shutdown. DropBox on the emulator's disk (read 19:58 local with `temp/collect-tv-crash.sh`) has **no** `data_app_crash`, `data_app_native_crash`, `data_app_anr`, `SYSTEM_TOMBSTONE` or `system_app_crash` entry for the boot that ran the APK (installed 14:08:28 emulator time; next boot 14:28, which ran `SYSTEM_FSCK`). So Android recorded no app exception, freeze or native crash: the process was most likely killed rather than crashing on its own. The emulator reports `MemTotal: 997544 kB` (`MemAvailable: 353696 kB` just after boot). It also reports `com.google.android.webview` 155.0.8059.43, not the 90.0.4430.91 recorded in FL-003. Exit reason **pending** (`dumpsys activity exit-info`, `temp/collect-tv-exit.sh`).
- **Update, third run (same evening):** with the renderer handler and the full 16:9 panel installed, a set with the camera view on ran smoothly, and a full logcat (`temp/tv-logcat.txt`, 14:39:30–14:43:27 emulator time) shows the app and its WebView renderer alive throughout: no crash, ANR, renderer death or low-memory kill. So the crash did not recur, and the handler was never triggered; the original cause remains unconfirmed (`dumpsys activity exit-info` not yet collected). The RAM setting in effect for that run was not recorded.
- **Update, 20 Oct:** the developer reports the emulator now runs with 2 GB of RAM, the amount in the Fire TV Stick 4K Max, the Fire OS stick FormLab targets. The crash happened on the boot that reported about 1 GB (above), and has not recurred since.
- **Severity:** Medium (did not recur after the fixes; cause unconfirmed).
- **Workaround used:** Defensive fix shipped before the cause was known: `MainActivity` now overrides `WebViewClient.onRenderProcessGone` (API 26+), logs `FormLab: WebView renderer gone: didCrash=… priorityAtExit=…`, destroys the dead WebView and recreates the activity, so the TV page rejoins its saved session instead of the whole app dying. Without that override, Android kills the app process when the WebView renderer dies, and a renderer under memory pressure from continuous image decoding with software rendering is the leading suspect. Not yet verified on the emulator.
- **Actionable suggestion:** Pending the log. If the renderer is confirmed, the suggestion will be for Amazon's hybrid (HTML5/WebView) app guidance to cover renderer-crash handling; we have not yet checked what that guidance currently says.
- **Evidence:** `MainActivity.java` (`onRenderProcessGone`); the logs and the run are in the development repo (`temp/tv-crash/`, not committed; `docs/runbook_smartphone.md` Step 11).

### FL-006 · No simple AWS home for a small HTTPS companion API that also calls Bedrock
- **Date / reporter:** 2026-10-20 · FormLab coordinator (Claude Code), for the developer
- **Tool and version:** AWS App Runner (status as of 20 Oct 2026), Amazon Lightsail container services (botocore 1.43.114 `lightsail` 2016-11-28 model), Amazon ECS Express Mode (documentation only).
- **Task attempted:** Move the FormLab API off the developer's laptop, so a judge can install the Fire TV APK and pair any phone. The host must have a publicly trusted HTTPS address (the phone page needs a secure context for the camera), keep one process (the live camera view and rate limits are in memory), cost little, and ideally hold an IAM role for Bedrock.
- **Steps taken:** 1. Looked for App Runner, the usual choice for one container with an HTTPS URL and an instance role. 2. Compared Lightsail container services and ECS Express Mode. 3. Read the Lightsail IAM and ECR private-repository guides and the botocore model for `CreateContainerService`.
- **Expected result:** One managed service giving an HTTPS address, a single always-on container and a role for Bedrock calls.
- **Actual result:** App Runner "will no longer accept new customers starting April 30, 2026" (AWS Support). AWS points to ECS Express Mode, which creates an Application Load Balancer that is billed per hour. Lightsail gives each container service an HTTPS endpoint with a managed certificate, but the Lightsail IAM guide says "Lightsail does not support service roles". Its only role is the ECR image puller, so a container calling Bedrock would need a long-lived IAM user key in its environment.
- **Severity:** Medium (no error, but the hosted mode is limited to fixture mode: the authored planner and explanations, no Bedrock).
- **Workaround used:** `make host` (`infra/host.py`) runs the API on one Lightsail nano node in fixture mode, which needs no AWS permissions, with the image in a private ECR repository pulled through the puller role. Bedrock (AWS mode) stays on the developer's machine. **Not yet deployed:** nothing in this entry was run against AWS.
- **Actionable suggestion:** Let a Lightsail container service assume an application IAM role, as it already does for ECR pulls. Or add a "small HTTPS container after App Runner" page that names the cheapest path with a role and no load balancer.
- **Evidence:** the development repo's `DEPLOYMENT.md` (Hosted section), `infra/host.py`, `tests/test_host.py`.
- **What happened next (20 Oct):** we dropped hosting altogether. The session server became a Java port that runs inside the Fire TV app, and the phone uses a small app pinned to the TV's own certificate. That is CleanRep's design (`ARCHITECTURE.md`). The hosted mode was never deployed and is not part of CleanRep.
