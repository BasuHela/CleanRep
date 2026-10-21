# Security and privacy

CleanRep runs on two devices in one home: the Fire TV app holds the session, and the phone app is the camera. There is no cloud server, no account and no analytics. Nothing is sent to AWS or anywhere else outside the home network.

## What leaves the phone

- **Numbers, as JSON.** Per-rep and per-hold measurements, camera-check results, and coach notices that carry only a kind (`shallow` or `uncalibrated`). The server refuses, at any depth, JSON field names containing `image`, `frame`, `landmark`, `pixel`, `video` or `base64`. Sensor requests are capped at 2 KB and other JSON actions at 8 KB. Schemas reject extra fields, non-finite numbers and values outside the catalog's physical bounds.
- **The camera view, only while it is on.** *Show camera on TV* is on by default, and *Stop showing on TV* turns it off.
  - **What is sent:** JPEG frames with the posture overlay, long side at most 640 px, at most 150 KB each and checked for the JPEG marker. They go at up to about 10 a second to `POST /v1/sessions/{id}/mirror` with the phone token.
  - **When it is accepted:** only in the camera-check, demonstration and set phases, while unpaused.
  - **How long it is kept:** the TV keeps only the latest frame per session, in memory, and never writes it to the store, receipts or logs. A frame is dropped after 2 seconds, or at once on a pause, phone loss, set end, phase change, session end, deletion or restart.
  - **Who can read it:** only the TV token can fetch it, in a header and never in a URL, with `Cache-Control: no-store`.
- **No video is recorded.** The camera stops on Stop, toggle off, leaving the page, the app going to the background, pause, recap, session end and revoked access. A debug recording exists only behind an explicit URL flag, stays in the phone's memory, and is saved only by a tap.

## Sessions and pairing

- **Tokens.** TV and phone tokens are 32 random bytes; the TV's store keeps only their SHA-256 hashes and compares them in constant time. Roles are per session.
- **Pairing.** A pairing code lasts five minutes, needs a one-use claim and the TV's approval after both screens show the same 4-digit number, and is rate limited.
- **Writes.** Every TV action binds its idempotency key to the actor, action and payload. Rep and hold events bind to their set and index. Compare-and-swap versions stop conflicting writes.
- **What the phone sees.** Phone views carry no credentials, summaries, explanations or plan reasoning.
- **Expiry and deletion.** Sessions expire after two hours. **End and delete** removes the session, its credentials and its receipts from the store.

## The network inside the home

**Port 8105, plain HTTP (TV).** It serves the web pages, the Chrome landing page and the phone APK to anyone on the network. `/v1/*` is answered only for loopback peers, which means the TV's own bridge; any other peer gets 403 `https_required` before the API sees the request.

**Port 8443, HTTPS (TV).** It serves only `/v1/*`, protected by pairing and tokens.
- **The certificate:** a self-signed EC P-256 certificate, made on first start and kept in the app's private files as PKCS#12. Its random password is sealed under an Android Keystore AES-GCM key.
- **The protocol:** TLS 1.2 or 1.3, ECDHE-ECDSA with AES-GCM only, and no client certificates.
- **If the identity is lost:** a lost or unreadable identity is replaced, and phones must scan again.

**The phone app.**
- **Its pages** come only from its own APK, at `https://cleanrep.phone`, with a CSP (`default-src 'self'`, no inline script), `nosniff` and `no-store`. Every other URL is blocked, and file access, content access and cookies are off. The camera is granted only to that origin, after Android's runtime permission.
- **Where it connects:** Java makes every request, and only to private IPv4 addresses or loopback. Literal addresses are parsed without DNS.
- **How it trusts the TV:** a private address needs HTTPS pinned to the TV certificate's SHA-256, compared in constant time. The pin replaces both the CA chain and the host-name check, with no fallback to the system trust store. Plain HTTP is allowed only to loopback (`adb reverse` over USB).
- **Responses and requests:** redirects are not followed, and responses are capped at 256 KB. Request paths must be `/v1/…`, with bodies of at most 8 KB. Camera frames must be 4 bytes to 150 KB and start with the JPEG marker.
- **If the TV's key changes:** a certificate that no longer matches fails with "This TV's security key has changed. Scan the QR code on your TV again."
- **What it stores:** the session, kept under an Android Keystore AES-GCM key. The TV's address and pin are kept separately, and changing them clears the session. Backups are off.

**The TV app** exposes its bridge only to its packaged origin. It refuses outside navigation and the `cleanrep-home/` assets, disables redirects, file access and cookies, and keeps its session token under an Android Keystore AES-GCM key.

## What the TV server defends against

The C-HTTP and C-TLS tests cover each of these.
- **Slow clients:** deadlines on headers, body, idle keep-alive and every write, enforced by closing the socket.
- **Oversized requests:** a 16 KB header block, 100 headers, an 8 KB request line and 160 KB bodies, refused before they are read. No chunked bodies.
- **Malformed requests:** strict request-line, header and percent-decoding rules, and CR or LF in response headers.
- **Path tricks:** static names without dot, hidden or empty segments, resolved inside their folder.
- **Too many connections:** six workers and a queue of 16 per listener, then 503; and 12 connections per client address.
- **Wrong use of the TLS port:** plain HTTP, or a client that never speaks.

## Not protected

- **The first scan.** The landing page and the phone APK download are plain HTTP, so an attacker already active on the home network at that moment could swap the pin or the APK. That matches the trust any sideloaded APK already needs. A later fix would derive the 4-digit match number from the certificate.
- **Many addresses at once.** A flood from many devices on the home network, beyond the worker and queue limits, can deny service.
- **Debug builds only.** They allow loopback HTTP and log the pairing link, with the pin, to logcat.
- **The emulator relay (testing only).** On the emulator, and on no real device (it checks for the emulator's `ranchu` or `goldfish` board), a launch extra, `cleanrep.lan_host`, names a private IPv4 address that the TV puts in its QR code, its pairing link and its allowed phone origins. `scripts/lan-relay.mjs` then puts the TV's ports on the laptop's Wi-Fi. Through `adb forward`, every phone reaches the TV from loopback, which would let it use the API over plain HTTP on 8105. So the relay serves only GET and HEAD there, and refuses any path that decodes to `/v1/…`, as the TV's own rule does (W-RLY-3, W-RLY-4). 8443 is passed through untouched, so TLS stays pinned end to end. Loopback is also exempt from the bootstrap rate limit, so relayed phones share that exemption.

Device verification of all of the above is NOT RUN; see [LIMITATIONS.md](LIMITATIONS.md).
