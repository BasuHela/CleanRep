# Testing CleanRep: instructions for judges

CleanRep needs a Fire OS device and, for the camera, an Android phone on the same Wi-Fi. With no Android phone, you can still run a full session with the remote alone (path B). There is also a 3-minute desktop path with no devices at all (path D).

**Status, honestly:** these steps follow the code and its automated tests. **Path A has not yet run on a real Fire TV.** On 21 Oct, the developer reports that a real Android phone paired from the QR code over Wi-Fi with the TV app on the emulator, and that it worked as expected (test 3b in [device-tests.md](device-tests.md)). The earlier version, with its server on a laptop, ran one live set with a phone and the Fire OS app on the emulator on 19 Oct. If something here fails, path B and path D still show the session.

## What you need

| | Requirement |
|---|---|
| TV | A Fire TV with **Fire OS** (6, 7 or 8, for example the Fire TV Stick 4K Max). Vega OS devices can't install APKs. Or the Android TV emulator (path C). |
| Phone | Android 9 or later, on the **same Wi-Fi** as the TV. A guest network that keeps devices apart won't work. iPhones have no CleanRep app: use path B. |
| Files | From the repository's **Releases** page: `cleanrep-tv-0.1.0-debug.apk`. The phone app is inside it, and the TV hands it to the phone. Both are debug builds. |
| Space | About 3 m in front of the TV, and something to prop the phone up to your side, at hip height. |

## A. Fire TV and an Android phone (the full experience)

### 1. Install the TV app

1. **Turn on developer options.** On the Fire TV, open **Settings → My Fire TV → About**, then select the device name seven times until it says you're a developer.
2. **Turn on ADB debugging** in **Settings → My Fire TV → Developer options**.
3. **Find the TV's address** under **Settings → My Fire TV → About → Network**.
4. **Install from a computer** with the Android platform tools, accepting the prompt that appears on the TV:

   ```sh
   adb connect <tv-ip>:5555
   adb -s <tv-ip>:5555 install -r cleanrep-tv-0.1.0-debug.apk
   ```

   Without a computer, use the Downloader app with the APK's Releases link. Turn on **Install unknown apps** for Downloader in Developer options first.
5. **Open CleanRep** from **Your apps & channels**. The session server starts inside the app. If the TV says *CleanRep's server on this TV isn't answering*, close CleanRep and open it again.

### 2. Pair the phone (the first time takes two scans)

1. **On the TV**, select **Start a session**. A QR code, an address and a 6-digit code appear. The code lasts 5 minutes.
2. **Scan the QR code** with the phone's camera. Chrome opens a page titled **Open CleanRep on your phone**.
3. **Get the app (first time only).** Tap **Open in the CleanRep app**. With no app installed yet, it downloads the phone app from the TV (or use **Download the app**). When Android asks, allow Chrome to install apps, then install CleanRep.
4. **Scan the QR code again**, and tap **Open in the CleanRep app**. The app opens with the code filled in and *TV at \<tv-ip\>*.
5. **Pair.** Tap **Pair with TV**. Both screens show the same 4-digit number. On the TV, select **Approve phone**.

### 3. Run a session with the remote

Use the D-pad and Select only.

1. **Set up.** Answer the three readiness questions, select **Continue**, pick your equipment (for example **Chair**), then **Make plan** and **Start**.
2. **Place the phone.** On the phone, tap **Enable camera** and allow it. Prop the phone to your side, 2.5 to 3 m away, lens at hip height, with your whole body in view. The TV ticks off its camera checks and shows the phone's view with your posture overlay. *Stop showing on TV* turns that view off: then only numbers leave the phone.
3. **Do a set.** Select **Continue**, then **Start set**, and after 3-2-1 do squats. Make a few of them shallow on purpose. The TV counts each rep live, marks each rated rep with a ✓ or a fault, and briefly says *Not counted: bend deeper* when an attempt doesn't count.
4. **Read the summary.** After the set, the TV shows what was measured, one plain sentence about it, a rest timer and a drill for the main fault, such as **Try Box squat to chair**.
5. **Drill and re-test.** Accept the drill and do it, then the re-test. The TV says whether that fault became less frequent: improved, no change, or not enough data.
6. **Finish.** Continue to the recap, then select **End and delete**, which removes the session from the TV.

**Things worth trying:**
- Close the app on the phone mid-set: the TV offers **Use remote only** and keeps your history.
- Quit CleanRep on the TV and reopen it: the session resumes where it was. During a session, Back pauses, and Back on the app's home screen quits.

## B. Fire TV alone (no Android phone)

Install the TV app as in A.1. On the pairing screen, select **Use remote only**. You count reps with the − and + buttons and rate the effort. The plan, rest timers, summaries and recap work the same. There is no form feedback, because there is no camera.

## C. The Android TV emulator instead of a Fire TV

The emulator is a stand-in, since Amazon offers no Fire OS emulator ([friction log](friction-log.md) FL-003). We use an Android TV API 30 image with 2 GB of RAM. Its network is private, so the phone reaches it through adb relays on the computer. Path A needs none of these relays: a real Fire TV is on the home Wi-Fi itself. Its Google Cast service holds port 8443, which the TV app needs, so the first two lines below turn Cast off on the emulator ([FL-007](friction-log.md)). Fire OS has no Google Cast, so a real Fire TV doesn't need this. That is test 3 in [device-tests.md](device-tests.md), with every command. In short:

```sh
adb -s emulator-5554 shell pm disable-user --user 0 com.google.android.apps.mediashell
adb -s emulator-5554 shell am force-stop com.google.android.apps.mediashell
adb -s emulator-5554 install -r cleanrep-tv-0.1.0-debug.apk
adb -s emulator-5554 logcat -s CleanRep                 # prints the phone link, with the pin
adb -s emulator-5554 forward tcp:8443 tcp:8443
adb -s <phone-serial> reverse tcp:8443 tcp:8443
adb -s <phone-serial> install -r cleanrep-phone-0.1.0-debug.apk
adb -s <phone-serial> shell am start -a android.intent.action.VIEW -d "'cleanrep://pair?host=localhost&port=8443&code=<code>&fp=<pin>'" org.cleanrep.phone
```

The phone APK for this path is on the Releases page too, because the emulator's QR code names an address the phone can't reach.

**With no debugging on the phone:** from a checkout of this repository, `make lan-relay` puts the emulator's TV on the computer's Wi-Fi. The TV app, told the computer's address at launch, puts that address in its QR code. The phone then pairs from the QR code alone, as in A.2. This is test 3b in [device-tests.md](device-tests.md). It needs a firewall rule on the computer, and a Wi-Fi network that lets devices reach each other.

## D. Desktop browser only (about 3 minutes, no devices)

This runs the same Java server on a computer, with recorded squats standing in for the camera. You need JDK 17 or later, Node 22 and Chrome. Then follow [the README's steps](../README.md#try-it-in-a-desktop-browser-no-devices-about-3-minutes):

```sh
make setup
make demo
```

`make browser-test` runs the same journey automatically, using D-pad keys only, along with nine more journeys.

## If something goes wrong

| What you see | What to do |
|---|---|
| The phone says *Check that this phone is on the same Wi-Fi as the CleanRep server* | Put both devices on the same network, not a guest network |
| *This TV's security key has changed. Scan the QR code on your TV again.* | The TV made a new certificate (for example, after reinstalling). Scan again |
| The pairing code has expired (it lasts 5 minutes) | On the TV, select **Start over**, then **Start a session** |
| The phone's camera stays black | Check that Android granted CleanRep the camera, then tap **Enable camera** again |
| Nothing happens on the TV | CleanRep is controlled by the D-pad and Select. Back pauses the session; Menu shows session details |
| On the emulator, the TV says *CleanRep's server on this TV isn't answering*, and `adb logcat -s CleanRep` shows `EADDRINUSE` | Google Cast holds port 8443. Run the two `mediashell` lines in path C, then close and reopen CleanRep |

Privacy and network details: [SECURITY.md](../SECURITY.md). Known limits: [LIMITATIONS.md](../LIMITATIONS.md).
