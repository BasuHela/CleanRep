package org.cleanrep.phone;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import java.net.URL;

/** Pure-Java unit and TLS handshake test suite for CleanRep phone security policy and pinning. */
public final class PhonePolicyTest {
 private static final String VALID_FP = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
 private static int passed = 0;

 private static void check(boolean condition, String detail) {
  if (!condition) {
   throw new AssertionError("Assertion failed: " + detail);
  }
 }

 private static void run(String id, Runnable test) {
  test.run();
  passed++;
  System.out.println("PASS " + id);
 }

 // P-APP-1: Allowed private IPv4 and loopback hosts and scheme selection rules.
 private static void testPApp1AllowedHostsAndSchemes() {
  String[] privateHosts = {
   "10.0.0.1", "10.255.255.254",
   "172.16.0.1", "172.24.10.5", "172.31.255.254",
   "192.168.0.1", "192.168.1.42", "192.168.255.254"
  };
  for (String host : privateHosts) {
   PhonePolicy.ServerConfig pinned = PhonePolicy.ServerConfig.validate(host, 8443, VALID_FP);
   check(pinned != null, "Expected private host with pin to be allowed: " + host);
   check(pinned.isPinned(), "Expected pinned=true for " + host);
   check(("https://" + host + ":8443").equals(pinned.origin()), "Unexpected origin: " + pinned.origin());

   PhonePolicy.ServerConfig unpinned = PhonePolicy.ServerConfig.validate(host, 8443, "");
   check(unpinned == null, "Expected private host without pin to be rejected: " + host);
   check(PhonePolicy.ServerConfig.validate(host, 8443, null) == null, "Expected null pin on private host to be rejected: " + host);
  }

  String[] loopbackHosts = {"localhost", "127.0.0.1", "127.1.2.3", "127.255.255.254"};
  for (String host : loopbackHosts) {
   PhonePolicy.ServerConfig plain = PhonePolicy.ServerConfig.validate(host, 8105, "");
   check(plain != null, "Expected loopback without pin to be allowed: " + host);
   check(!plain.isPinned(), "Expected pinned=false for unpinned loopback: " + host);
   check(("http://" + host + ":8105").equals(plain.origin()), "Unexpected loopback origin: " + plain.origin());

   PhonePolicy.ServerConfig pinnedLoop = PhonePolicy.ServerConfig.validate(host, 8443, VALID_FP);
   check(pinnedLoop != null && pinnedLoop.isPinned(), "Expected pinned loopback to be allowed: " + host);
   check(("https://" + host + ":8443").equals(pinnedLoop.origin()), "Unexpected pinned loopback origin: " + pinnedLoop.origin());
  }
 }

 // P-APP-2: Rejected hosts (172.32.0.1, 192.169.0.1, 0.0.0.0, 8.8.8.8, DNS names, IPv6) and port boundaries (0, 65536).
 private static void testPApp2RejectedHostsAndPorts() {
  String[] rejectedHosts = {
   "172.15.255.254", "172.32.0.1",
   "192.167.1.1", "192.169.0.1",
   "0.0.0.0", "8.8.8.8", "1.1.1.1", "169.254.1.1", "255.255.255.255",
   "cleanrep.local", "example.com", "tv.local", "LOCALHOST", "localhost.",
   "::1", "[::1]", "fe80::1", "[fe80::1]", "2001:db8::1",
   "192.168.1", "192.168.1.1.1", "192.168.01.1", "192.168.1.256", "2130706433", "", null
  };
  for (String host : rejectedHosts) {
   check(!PhonePolicy.isAllowedHost(host), "Host should be rejected: " + host);
   check(PhonePolicy.ServerConfig.validate(host, 8443, VALID_FP) == null, "ServerConfig should reject host: " + host);
   check(PhonePolicy.ServerConfig.validate(host, 8105, "") == null, "Unpinned ServerConfig should reject host: " + host);
  }

  check(PhonePolicy.ServerConfig.validate("192.168.1.10", 1, VALID_FP) != null, "Port 1 should be valid");
  check(PhonePolicy.ServerConfig.validate("192.168.1.10", 65535, VALID_FP) != null, "Port 65535 should be valid");
  check(PhonePolicy.ServerConfig.validate("192.168.1.10", 0, VALID_FP) == null, "Port 0 must be rejected");
  check(PhonePolicy.ServerConfig.validate("192.168.1.10", -1, VALID_FP) == null, "Negative port must be rejected");
  check(PhonePolicy.ServerConfig.validate("192.168.1.10", 65536, VALID_FP) == null, "Port 65536 must be rejected");
 }

 // P-APP-3: Certificate fingerprint format validation (43 base64url characters).
 private static void testPApp3FingerprintValidation() {
  check(PhonePolicy.isValidFingerprint(VALID_FP), "43-char base64url should be valid");
  check(PhonePolicy.isValidFingerprint("0123456789abcdefghijklmnopqrstuvwxyzABCDE-_"), "Mixed base64url chars should be valid");
  check(!PhonePolicy.isValidFingerprint(null), "Null fingerprint must be rejected");
  check(!PhonePolicy.isValidFingerprint(""), "Empty fingerprint must be rejected");
  check(!PhonePolicy.isValidFingerprint(VALID_FP.substring(0, 42)), "42-char fingerprint must be rejected");
  check(!PhonePolicy.isValidFingerprint(VALID_FP + "A"), "44-char fingerprint must be rejected");
  check(!PhonePolicy.isValidFingerprint(VALID_FP.substring(0, 42) + "="), "Padded fingerprint must be rejected");
  check(!PhonePolicy.isValidFingerprint(VALID_FP.substring(0, 42) + "+"), "Standard base64 '+' must be rejected");
  check(!PhonePolicy.isValidFingerprint(VALID_FP.substring(0, 42) + "/"), "Standard base64 '/' must be rejected");
 }

 // P-APP-4: Pair-link parsing for valid links, unique ?pair=<n> startUrl, extra parameters ignored, and check/launcher intents.
 private static void testPApp4PairLinkValidAndExtraParams() {
  String url = "cleanrep://pair?host=192.168.1.25&port=8443&code=654321&fp=" + VALID_FP;
  PhonePolicy.PairLink link = PhonePolicy.PairLink.parse(url);
  check(link != null, "Expected valid pair link to parse");
  check("192.168.1.25".equals(link.server().host()), "Unexpected host");
  check(link.server().port() == 8443, "Unexpected port");
  check("654321".equals(link.code()), "Unexpected code");
  check(VALID_FP.equals(link.server().fingerprint()), "Unexpected fingerprint");
  check("https://192.168.1.25:8443".equals(link.server().origin()), "Unexpected origin");

  String withExtras = "cleanrep://pair?utm=qr&host=localhost&port=8443&code=001234&fp=" + VALID_FP + "&extra=ignored";
  PhonePolicy.IntentTarget target1 = PhonePolicy.IntentTarget.parse(withExtras);
  PhonePolicy.IntentTarget target2 = PhonePolicy.IntentTarget.parse(withExtras);
  check(target1.kind() == PhonePolicy.IntentTarget.Kind.PAIR, "Expected PAIR target with extra params");
  check("localhost".equals(target1.server().host()), "Expected localhost");
  check("001234".equals(target1.code()), "Expected 001234 code");
  check(target1.startUrl().matches("https://cleanrep\\.phone/phone\\?pair=[0-9]+#code=001234"), "Unexpected startUrl: " + target1.startUrl());
  check(!target1.startUrl().equals(target2.startUrl()), "Consecutive pair intents must produce distinct ?pair= startUrls");

  PhonePolicy.IntentTarget launcherTarget = PhonePolicy.IntentTarget.parse(null);
  check(launcherTarget.kind() == PhonePolicy.IntentTarget.Kind.PHONE, "Expected PHONE target for launcher");
  check("https://cleanrep.phone/phone".equals(launcherTarget.startUrl()), "Unexpected launcher startUrl");

  PhonePolicy.IntentTarget checkTarget = PhonePolicy.IntentTarget.parse("cleanrep://check");
  check(checkTarget.kind() == PhonePolicy.IntentTarget.Kind.CHECK, "Expected CHECK target");
  check("https://cleanrep.phone/check.html".equals(checkTarget.startUrl()), "Unexpected check startUrl");
 }

 // P-APP-5: Pair-link scheme/fingerprint rules (loopback without fp accepted as http, private LAN without fp rejected, loopback with malformed fp rejected) and invalid parameter cases.
 private static void testPApp5PairLinkInvalidCases() {
  // Loopback without fp (or with empty fp) is allowed as plain HTTP for adb reverse
  PhonePolicy.IntentTarget loopNoFp = PhonePolicy.IntentTarget.parse("cleanrep://pair?host=localhost&port=8105&code=123456");
  check(loopNoFp.kind() == PhonePolicy.IntentTarget.Kind.PAIR, "Expected localhost without fp to be accepted");
  check(!loopNoFp.server().isPinned(), "Expected localhost without fp to be unpinned");
  check("http://localhost:8105".equals(loopNoFp.server().origin()), "Expected http://localhost:8105 origin");

  PhonePolicy.IntentTarget ipv4LoopNoFp = PhonePolicy.IntentTarget.parse("cleanrep://pair?host=127.0.0.1&port=8105&code=123456&fp=");
  check(ipv4LoopNoFp.kind() == PhonePolicy.IntentTarget.Kind.PAIR, "Expected 127.0.0.1 with empty fp to be accepted");
  check("http://127.0.0.1:8105".equals(ipv4LoopNoFp.server().origin()), "Expected http://127.0.0.1:8105 origin");

  String[] invalidLinks = {
   "cleanrep://pair?port=8443&code=123456&fp=" + VALID_FP, // missing host
   "cleanrep://pair?host=8.8.8.8&port=8443&code=123456&fp=" + VALID_FP, // disallowed host
   "cleanrep://pair?host=172.32.0.1&port=8443&code=123456&fp=" + VALID_FP, // disallowed 172.32
   "cleanrep://pair?host=192.168.1.10&code=123456&fp=" + VALID_FP, // missing port
   "cleanrep://pair?host=192.168.1.10&port=0&code=123456&fp=" + VALID_FP, // port 0
   "cleanrep://pair?host=192.168.1.10&port=65536&code=123456&fp=" + VALID_FP, // port 65536
   "cleanrep://pair?host=192.168.1.10&port=abc&code=123456&fp=" + VALID_FP, // non-numeric port
   "cleanrep://pair?host=192.168.1.10&port=8443&fp=" + VALID_FP, // missing code
   "cleanrep://pair?host=192.168.1.10&port=8443&code=12345&fp=" + VALID_FP, // 5-digit code
   "cleanrep://pair?host=192.168.1.10&port=8443&code=1234567&fp=" + VALID_FP, // 7-digit code
   "cleanrep://pair?host=192.168.1.10&port=8443&code=12345a&fp=" + VALID_FP, // non-digit code
   "cleanrep://pair?host=192.168.1.10&port=8443&code=123456", // private LAN missing fp -> rejected
   "cleanrep://pair?host=10.0.0.2&port=8443&code=123456&fp=", // private LAN empty fp -> rejected
   "cleanrep://pair?host=localhost&port=8443&code=123456&fp=short", // loopback with malformed fp -> rejected
   "cleanrep://pair?host=127.0.0.1&port=8443&code=123456&fp=bad_fp!", // 127.0.0.1 with malformed fp -> rejected
   "cleanrep://pair?host=192.168.1.10&port=8443&code=123456&fp=short", // private LAN with malformed fp -> rejected
   "cleanrep://pair?host=192.168.1.10&host=192.168.1.11&port=8443&code=123456&fp=" + VALID_FP, // duplicate host
   "https://cleanrep.phone/pair?host=192.168.1.10&port=8443&code=123456&fp=" + VALID_FP, // wrong scheme
   "cleanrep://unknown?host=192.168.1.10&port=8443&code=123456&fp=" + VALID_FP // unknown host
  };
  for (String link : invalidLinks) {
   check(PhonePolicy.PairLink.parse(link) == null, "Expected PairLink.parse to reject: " + link);
   PhonePolicy.IntentTarget t = PhonePolicy.IntentTarget.parse(link);
   check(t.kind() == PhonePolicy.IntentTarget.Kind.INVALID, "Expected INVALID target for: " + link);
   check("https://cleanrep.phone/phone".equals(t.startUrl()), "Invalid link must fall back to /phone without code");
   check(t.reason() != null && !t.reason().isEmpty(), "Expected diagnostic reason for: " + link);
  }
 }

 // P-APP-6: Scoped API request validation (id, method, path, body size, token size).
 private static void testPApp6ApiRequestValidation() {
  check(PhonePolicy.isValidRequestId("req_01-abc"), "Valid id rejected");
  check(!PhonePolicy.isValidRequestId(""), "Empty id must be rejected");
  check(!PhonePolicy.isValidRequestId(null), "Null id must be rejected");
  check(!PhonePolicy.isValidRequestId("a".repeat(129)), "129-char id must be rejected");
  check(!PhonePolicy.isValidRequestId("bad id"), "Space in id must be rejected");

  check(PhonePolicy.isValidApiRequest("GET", "/v1/sessions/s_1", "", "tok_1"), "Valid GET failed");
  check(PhonePolicy.isValidApiRequest("POST", "/v1/pair/request", "{\"pairing_code\":\"123456\"}", ""), "Valid POST failed");
  check(PhonePolicy.isValidApiRequest("DELETE", "/v1/sessions/s_1", "", "tok_1"), "Valid DELETE failed");
  check(PhonePolicy.isValidApiRequest("POST", "/v1/sessions/s_1/reps", "x".repeat(8192), "t".repeat(200)), "Boundary lengths failed");

  check(!PhonePolicy.isValidApiRequest("PUT", "/v1/sessions/s_1", "", ""), "PUT must be rejected");
  check(!PhonePolicy.isValidApiRequest("GET", "/v1/sessions/../s_1", "", ""), "Dot in path must be rejected");
  check(!PhonePolicy.isValidApiRequest("GET", "/phone", "", ""), "Non-/v1/ path must be rejected");
  check(!PhonePolicy.isValidApiRequest("POST", "/v1/sessions/s_1", "x".repeat(8193), ""), "Body > 8192 must be rejected");
  check(!PhonePolicy.isValidApiRequest("GET", "/v1/sessions/s_1", "", "t".repeat(201)), "Token > 200 must be rejected");
 }

 // P-APP-7: Mirror frame request validation (path pattern, base64 cap, byte cap, FF D8 FF header).
 private static void testPApp7MirrorRequestValidation() {
  byte[] validJpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, (byte) 0xFF, (byte) 0xD9};
  String validB64 = Base64.getEncoder().encodeToString(validJpeg);

  byte[] decoded = PhonePolicy.decodeAndValidateMirror("/v1/sessions/sess_123-abc/mirror", validB64, "token_1");
  check(decoded != null && Arrays.equals(validJpeg, decoded), "Valid mirror frame was rejected");

  // Exact max byte size (153,600 bytes)
  byte[] maxJpeg = new byte[PhonePolicy.MIRROR_MAX_BYTES];
  maxJpeg[0] = (byte) 0xFF;
  maxJpeg[1] = (byte) 0xD8;
  maxJpeg[2] = (byte) 0xFF;
  String maxB64 = Base64.getEncoder().encodeToString(maxJpeg);
  check(maxB64.length() <= PhonePolicy.MIRROR_MAX_BASE64_CHARS, "150 KB base64 should fit within 204,800 chars");
  check(PhonePolicy.decodeAndValidateMirror("/v1/sessions/sess_1/mirror", maxB64, "tok") != null, "150 KB JPEG should be valid");

  // 153,601 bytes -> rejected
  byte[] overJpeg = new byte[PhonePolicy.MIRROR_MAX_BYTES + 1];
  overJpeg[0] = (byte) 0xFF;
  overJpeg[1] = (byte) 0xD8;
  overJpeg[2] = (byte) 0xFF;
  String overB64 = Base64.getEncoder().encodeToString(overJpeg);
  check(PhonePolicy.decodeAndValidateMirror("/v1/sessions/sess_1/mirror", overB64, "tok") == null, "153,601-byte JPEG must be rejected");

  // Non-JPEG header (PNG)
  byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
  check(PhonePolicy.decodeAndValidateMirror("/v1/sessions/sess_1/mirror", Base64.getEncoder().encodeToString(png), "tok") == null, "PNG must be rejected");

  // Too short (< 4 bytes)
  byte[] shortJpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
  check(PhonePolicy.decodeAndValidateMirror("/v1/sessions/sess_1/mirror", Base64.getEncoder().encodeToString(shortJpeg), "tok") == null, "3-byte payload must be rejected");

  // Bad paths and oversized base64 / token
  check(PhonePolicy.decodeAndValidateMirror("/v1/sessions/sess_1/reps", validB64, "tok") == null, "Non-mirror path must be rejected");
  check(PhonePolicy.decodeAndValidateMirror("/v1/sessions/a/b/mirror", validB64, "tok") == null, "Nested session path must be rejected");
  check(PhonePolicy.decodeAndValidateMirror("/v1/sessions/sess_1/mirror", "A".repeat(204801), "tok") == null, "Base64 > 204,800 chars must be rejected");
  check(PhonePolicy.decodeAndValidateMirror("/v1/sessions/sess_1/mirror", "!!!not_base64!!!", "tok") == null, "Malformed base64 must be rejected");
  check(PhonePolicy.decodeAndValidateMirror("/v1/sessions/sess_1/mirror", validB64, "t".repeat(201)) == null, "Token > 200 chars must be rejected");
 }

 // P-APP-8: Packaged origin, asset path resolution, and MIME types (.wasm, .task, .json).
 private static void testPApp8PackagedOriginAndAssets() {
  check(PhonePolicy.isPackagedUrl("https://cleanrep.phone"), "Root packaged URL should be accepted");
  check(PhonePolicy.isPackagedUrl("https://cleanrep.phone/phone"), "/phone should be accepted");
  check(PhonePolicy.isPackagedUrl("https://cleanrep.phone/check.html"), "/check.html should be accepted");
  check(!PhonePolicy.isPackagedUrl("http://cleanrep.phone/phone"), "http:// must be rejected");
  check(!PhonePolicy.isPackagedUrl("https://cleanrep.phone.evil/phone"), "Prefix-spoofed domain must be rejected");

  check("index.html".equals(PhonePolicy.resolveAssetPath("/")), "/ should map to index.html");
  check("index.html".equals(PhonePolicy.resolveAssetPath("/phone")), "/phone should map to index.html");
  check("check.html".equals(PhonePolicy.resolveAssetPath("/check.html")), "/check.html should map to check.html");
  check("check.js".equals(PhonePolicy.resolveAssetPath("/check.js")), "/check.js should map to check.js");
  check("vision/wasm/vision_wasm_internal.wasm".equals(PhonePolicy.resolveAssetPath("/vision/wasm/vision_wasm_internal.wasm")), "WASM path failed");
  check(PhonePolicy.resolveAssetPath("/../secret") == null, "Path traversal '..' must be rejected");
  check(PhonePolicy.resolveAssetPath("//double") == null, "Double leading slash must be rejected");

  check("application/wasm".equals(PhonePolicy.mimeType("vision_wasm_internal.wasm")), ".wasm MIME mismatch");
  check("application/octet-stream".equals(PhonePolicy.mimeType("pose_landmarker_lite.task")), ".task MIME mismatch");
  check("application/json".equals(PhonePolicy.mimeType("catalog.json")), ".json MIME mismatch");
  check("text/javascript".equals(PhonePolicy.mimeType("vision_bundle.mjs")), ".mjs MIME mismatch");
  check("text/javascript".equals(PhonePolicy.mimeType("check.js")), ".js MIME mismatch");
  check("text/html".equals(PhonePolicy.mimeType("check.html")), ".html MIME mismatch");
 }

 private static KeyStore loadPkcs12(String path, char[] password) throws Exception {
  KeyStore ks = KeyStore.getInstance("PKCS12");
  try (FileInputStream in = new FileInputStream(path)) {
   ks.load(in, password);
  }
  return ks;
 }

 private static SSLServerSocket createServerSocketOnHighPort(KeyStore ks, char[] password) throws Exception {
  KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
  kmf.init(ks, password);
  SSLContext serverCtx = SSLContext.getInstance("TLS");
  serverCtx.init(kmf.getKeyManagers(), null, new SecureRandom());
  SSLServerSocketFactory factory = serverCtx.getServerSocketFactory();
  InetAddress loopback = InetAddress.getByName("127.0.0.1");
  for (int port = 24443; port < 29000; port++) {
   try {
    SSLServerSocket server = (SSLServerSocket) factory.createServerSocket();
    server.setReuseAddress(true);
    server.bind(new InetSocketAddress(loopback, port), 4);
    return server;
   } catch (Exception ignored) {
    // Try next port above 20000
   }
  }
  throw new IllegalStateException("Could not bind SSLServerSocket on any port in 24443..28999");
 }

 // P-APP-9: Real TLS handshake succeeds when client is pinned to the server certificate's SHA-256 fingerprint.
 private static void testPApp9PinnedTlsHandshakeSuccess(String keystore1Path, char[] password) throws Exception {
  KeyStore ks1 = loadPkcs12(keystore1Path, password);
  String alias = ks1.aliases().nextElement();
  X509Certificate cert1 = (X509Certificate) ks1.getCertificate(alias);
  String fp1 = PinnedTls.fingerprintOf(cert1);
  check(PhonePolicy.isValidFingerprint(fp1), "Computed cert1 fingerprint should be 43-char base64url: " + fp1);

  try (SSLServerSocket server = createServerSocketOnHighPort(ks1, password)) {
   int port = server.getLocalPort();
   check(port > 20000, "Server port must be > 20000, got: " + port);
   AtomicReference<Throwable> serverError = new AtomicReference<>();
   CountDownLatch done = new CountDownLatch(1);

   Thread serverThread = new Thread(() -> {
    try (SSLSocket client = (SSLSocket) server.accept()) {
     client.setSoTimeout(5000);
     BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
     String line = reader.readLine();
     if (!"PING".equals(line)) throw new AssertionError("Unexpected client line: " + line);
     OutputStream out = client.getOutputStream();
     out.write("PONG\n".getBytes(StandardCharsets.UTF_8));
     out.flush();
    } catch (Throwable t) {
     serverError.set(t);
    } finally {
     done.countDown();
    }
   });
   serverThread.setDaemon(true);
   serverThread.start();

   PinnedTls pinned = new PinnedTls("127.0.0.1", fp1);
   try (SSLSocket socket = (SSLSocket) pinned.socketFactory().createSocket("127.0.0.1", port)) {
    socket.setSoTimeout(5000);
    socket.startHandshake();
    check(pinned.hostnameVerifier().verify("127.0.0.1", socket.getSession()), "HostnameVerifier should accept configured host");
    check(!pinned.hostnameVerifier().verify("192.168.1.10", socket.getSession()), "HostnameVerifier must reject other hosts");
    OutputStream out = socket.getOutputStream();
    out.write("PING\n".getBytes(StandardCharsets.UTF_8));
    out.flush();
    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    String reply = reader.readLine();
    check("PONG".equals(reply), "Expected PONG over pinned TLS, got: " + reply);
   }
   check(done.await(5, TimeUnit.SECONDS), "Server thread timed out");
   if (serverError.get() != null) {
    throw new AssertionError("Server error during valid handshake", serverError.get());
   }
  }
 }

 // P-APP-10: Real TLS handshake fails when client is pinned to a different certificate's fingerprint.
 private static void testPApp10PinnedTlsHandshakeRejectsWrongPin(String keystore1Path, String keystore2Path, char[] password) throws Exception {
  KeyStore ks1 = loadPkcs12(keystore1Path, password);
  KeyStore ks2 = loadPkcs12(keystore2Path, password);
  X509Certificate cert1 = (X509Certificate) ks1.getCertificate(ks1.aliases().nextElement());
  X509Certificate cert2 = (X509Certificate) ks2.getCertificate(ks2.aliases().nextElement());
  String fp1 = PinnedTls.fingerprintOf(cert1);
  String fp2 = PinnedTls.fingerprintOf(cert2);
  check(!fp1.equals(fp2), "Two generated keystores must have distinct certificate fingerprints");

  try (SSLServerSocket server = createServerSocketOnHighPort(ks1, password)) {
   int port = server.getLocalPort();
   check(port > 20000, "Server port must be > 20000, got: " + port);
   CountDownLatch done = new CountDownLatch(1);

   Thread serverThread = new Thread(() -> {
    try (SSLSocket client = (SSLSocket) server.accept()) {
     client.setSoTimeout(5000);
     client.startHandshake();
     client.getInputStream().read();
    } catch (Throwable ignored) {
     // Expected: client aborts handshake due to certificate pin mismatch
    } finally {
     done.countDown();
    }
   });
   serverThread.setDaemon(true);
   serverThread.start();

   PinnedTls wrongPin = new PinnedTls("127.0.0.1", fp2);
   boolean threwHandshake = false;
   try (SSLSocket socket = (SSLSocket) wrongPin.socketFactory().createSocket("127.0.0.1", port)) {
    socket.setSoTimeout(5000);
    socket.startHandshake();
   } catch (SSLHandshakeException expected) {
    threwHandshake = true;
    check(PinnedTls.isPinMismatch(expected), "Expected PinnedTls.isPinMismatch(expected) to be true");
   }
   check(threwHandshake, "Expected SSLHandshakeException when server certificate does not match pinned fingerprint");
   check(done.await(5, TimeUnit.SECONDS), "Server thread timed out");
  }
 }

 // P-APP-11: HttpsURLConnection end-to-end pinning, hostname verification, and error envelopes.
 private static void testPApp11HttpsUrlConnectionPinningAndEnvelopes(String keystore1Path, String keystore2Path, char[] password) throws Exception {
  KeyStore ks1 = loadPkcs12(keystore1Path, password);
  KeyStore ks2 = loadPkcs12(keystore2Path, password);
  String fp1 = PinnedTls.fingerprintOf((X509Certificate) ks1.getCertificate(ks1.aliases().nextElement()));
  String fp2 = PinnedTls.fingerprintOf((X509Certificate) ks2.getCertificate(ks2.aliases().nextElement()));

  try (SSLServerSocket server = createServerSocketOnHighPort(ks1, password)) {
   int port = server.getLocalPort();
   Thread serverThread = new Thread(() -> {
    for (int i = 0; i < 3; i++) {
     try (SSLSocket client = (SSLSocket) server.accept()) {
      client.setSoTimeout(3000);
      BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
      String line;
      while ((line = in.readLine()) != null && !line.isEmpty()) {
       // Read request headers
      }
      String body = "{\"status\":\"ok\",\"data\":{}}";
      String resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body;
      OutputStream out = client.getOutputStream();
      out.write(resp.getBytes(StandardCharsets.UTF_8));
      out.flush();
     } catch (Throwable ignored) {
      // Expected on rejected handshakes
     }
    }
   });
   serverThread.setDaemon(true);
   serverThread.start();

   // 1. Matching pin + matching host -> 200 OK
   PinnedTls okTls = new PinnedTls("127.0.0.1", fp1);
   HttpsURLConnection okConn = (HttpsURLConnection) new URL("https://127.0.0.1:" + port + "/v1/sessions/s1").openConnection();
   okConn.setSSLSocketFactory(okTls.socketFactory());
   okConn.setHostnameVerifier(okTls.hostnameVerifier());
   okConn.setConnectTimeout(3000);
   okConn.setReadTimeout(3000);
   check(okConn.getResponseCode() == 200, "Expected HTTP 200 for matching pin");
   okConn.disconnect();

   // 2. Mismatched pin -> throws and isPinMismatch is true
   PinnedTls badPinTls = new PinnedTls("127.0.0.1", fp2);
   HttpsURLConnection badPinConn = (HttpsURLConnection) new URL("https://127.0.0.1:" + port + "/v1/sessions/s1").openConnection();
   badPinConn.setSSLSocketFactory(badPinTls.socketFactory());
   badPinConn.setHostnameVerifier(badPinTls.hostnameVerifier());
   badPinConn.setConnectTimeout(3000);
   badPinConn.setReadTimeout(3000);
   boolean caughtBadPin = false;
   try {
    badPinConn.getResponseCode();
   } catch (Exception e) {
    caughtBadPin = PinnedTls.isPinMismatch(e);
   } finally {
    badPinConn.disconnect();
   }
   check(caughtBadPin, "Expected HttpsURLConnection with wrong pin to fail with isPinMismatch=true");

   // 3. Matching pin but wrong host configured in PinnedTls -> HostnameVerifier rejects
   PinnedTls badHostTls = new PinnedTls("192.168.1.50", fp1);
   HttpsURLConnection badHostConn = (HttpsURLConnection) new URL("https://127.0.0.1:" + port + "/v1/sessions/s1").openConnection();
   badHostConn.setSSLSocketFactory(badHostTls.socketFactory());
   badHostConn.setHostnameVerifier(badHostTls.hostnameVerifier());
   badHostConn.setConnectTimeout(3000);
   badHostConn.setReadTimeout(3000);
   boolean caughtBadHost = false;
   try {
    badHostConn.getResponseCode();
   } catch (Exception e) {
    caughtBadHost = PinnedTls.isPinMismatch(e);
   } finally {
    badHostConn.disconnect();
   }
   check(caughtBadHost, "Expected HttpsURLConnection with mismatched host to fail with isPinMismatch=true");
  }

  String noServerEnv = PhonePolicy.errorEnvelope("native-unconfigured", "unavailable", null, PhonePolicy.MSG_NO_SERVER);
  check(noServerEnv.contains("\"status\":\"unavailable\"") && noServerEnv.contains("Scan the QR code on your TV first."), "Unexpected unconfigured envelope");
  String pinEnv = PhonePolicy.errorEnvelope("native-network", "unavailable", 0, PhonePolicy.MSG_PIN_MISMATCH);
  check(pinEnv.contains("This TV's security key has changed. Scan the QR code on your TV again."), "Unexpected pin mismatch envelope");
 }

 public static void main(String[] args) throws Exception {
  if (args.length != 3) {
   throw new IllegalArgumentException("Usage: PhonePolicyTest <keystore1.p12> <keystore2.p12> <password>");
  }
  final String ks1 = args[0];
  final String ks2 = args[1];
  final char[] password = args[2].toCharArray();

  run("P-APP-1", PhonePolicyTest::testPApp1AllowedHostsAndSchemes);
  run("P-APP-2", PhonePolicyTest::testPApp2RejectedHostsAndPorts);
  run("P-APP-3", PhonePolicyTest::testPApp3FingerprintValidation);
  run("P-APP-4", PhonePolicyTest::testPApp4PairLinkValidAndExtraParams);
  run("P-APP-5", PhonePolicyTest::testPApp5PairLinkInvalidCases);
  run("P-APP-6", PhonePolicyTest::testPApp6ApiRequestValidation);
  run("P-APP-7", PhonePolicyTest::testPApp7MirrorRequestValidation);
  run("P-APP-8", PhonePolicyTest::testPApp8PackagedOriginAndAssets);
  run("P-APP-9", () -> {
   try {
    testPApp9PinnedTlsHandshakeSuccess(ks1, password);
   } catch (Exception e) {
    throw new RuntimeException(e);
   }
  });
  run("P-APP-10", () -> {
   try {
    testPApp10PinnedTlsHandshakeRejectsWrongPin(ks1, ks2, password);
   } catch (Exception e) {
    throw new RuntimeException(e);
   }
  });
  run("P-APP-11", () -> {
   try {
    testPApp11HttpsUrlConnectionPinningAndEnvelopes(ks1, ks2, password);
   } catch (Exception e) {
    throw new RuntimeException(e);
   }
  });

  System.out.println("ALL PASSED (" + passed + " tests)");
 }
}
