package org.cleanrep.phone;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Pure-Java security policy, pair-link parser, and bridge request validators (no Android imports). */
public final class PhonePolicy {
 public static final String PACKAGED_ORIGIN = "https://cleanrep.phone";
 public static final String CSP =
  "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; " +
  "img-src 'self' data: blob:; media-src 'self' blob:; worker-src 'self' blob:; connect-src 'self'; " +
  "frame-ancestors 'none'; base-uri 'self'; object-src 'none'";

 public static final String MSG_NO_SERVER = "Scan the QR code on your TV first.";
 public static final String MSG_NETWORK =
  "Can't reach your TV. Check that the CleanRep TV app is open and this phone is on the same Wi-Fi.";
 public static final String MSG_PIN_MISMATCH =
  "This TV's security key has changed. Scan the QR code on your TV again.";
 public static final String MSG_INVALID_REQUEST = "The TV rejected an invalid scoped request.";
 public static final String MSG_BUSY = "The TV is busy reconnecting. Retry when ready.";

 public static final int API_MAX_BODY_CHARS = 8192;
 public static final int MAX_TOKEN_CHARS = 200;
 public static final int API_MAX_RESPONSE_BYTES = 262144;
 public static final int MIRROR_MAX_BASE64_CHARS = 204800;
 public static final int MIRROR_MAX_BYTES = 153600;

 private PhonePolicy() {}

 /** Parses a strict dotted-quad IPv4 literal without DNS lookups; returns null if not a valid IPv4 literal. */
 static int[] parseIpv4(String host) {
  if (host == null || host.length() < 7 || host.length() > 15) return null;
  int[] octets = new int[4];
  int part = 0;
  int current = 0;
  int digits = 0;
  for (int i = 0; i < host.length(); i++) {
   char c = host.charAt(i);
   if (c == '.') {
    if (digits == 0 || part >= 3) return null;
    octets[part++] = current;
    current = 0;
    digits = 0;
   } else if (c >= '0' && c <= '9') {
    if (digits == 1 && current == 0) return null; // Reject leading zeros such as 01
    current = current * 10 + (c - '0');
    digits++;
    if (digits > 3 || current > 255) return null;
   } else {
    return null;
   }
  }
  if (part != 3 || digits == 0) return null;
  octets[3] = current;
  return octets;
 }

 public static boolean isLoopbackHost(String host) {
  if ("localhost".equals(host)) return true;
  int[] ip = parseIpv4(host);
  return ip != null && ip[0] == 127;
 }

 public static boolean isPrivateNonLoopbackHost(String host) {
  int[] ip = parseIpv4(host);
  if (ip == null) return false;
  if (ip[0] == 10) return true;
  if (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31) return true;
  return ip[0] == 192 && ip[1] == 168;
 }

 public static boolean isAllowedHost(String host) {
  return isLoopbackHost(host) || isPrivateNonLoopbackHost(host);
 }

 public static boolean isValidPort(int port) {
  return port >= 1 && port <= 65535;
 }

 /** SHA-256 of DER leaf cert encoded as unpadded base64url: exactly 43 chars in [A-Za-z0-9_-]. */
 public static boolean isValidFingerprint(String fp) {
  if (fp == null || fp.length() != 43) return false;
  for (int i = 0; i < 43; i++) {
   char c = fp.charAt(i);
   boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
   if (!ok) return false;
  }
  try {
   byte[] decoded = Base64.getUrlDecoder().decode(fp);
   return decoded.length == 32;
  } catch (IllegalArgumentException e) {
   return false;
  }
 }

 public static final class ServerConfig {
  private final String host;
  private final int port;
  private final String fingerprint;
  private final boolean https;

  private ServerConfig(String host, int port, String fingerprint, boolean https) {
   this.host = host;
   this.port = port;
   this.fingerprint = fingerprint;
   this.https = https;
  }

  public static ServerConfig validate(String host, int port, String fingerprint) {
   if (!isAllowedHost(host) || !isValidPort(port)) return null;
   String fp = fingerprint == null ? "" : fingerprint;
   if (!fp.isEmpty()) {
    if (!isValidFingerprint(fp)) return null;
    return new ServerConfig(host, port, fp, true);
   }
   if (!isLoopbackHost(host)) return null;
   return new ServerConfig(host, port, "", false);
  }

  public String host() { return host; }
  public int port() { return port; }
  public String fingerprint() { return fingerprint; }
  public boolean isPinned() { return https && !fingerprint.isEmpty(); }
  public String origin() { return (https ? "https://" : "http://") + host + ":" + port; }

  @Override public boolean equals(Object o) {
   if (this == o) return true;
   if (!(o instanceof ServerConfig)) return false;
   ServerConfig that = (ServerConfig) o;
   return port == that.port && https == that.https && Objects.equals(host, that.host) && Objects.equals(fingerprint, that.fingerprint);
  }

  @Override public int hashCode() {
   return Objects.hash(host, port, fingerprint, https);
  }
 }

 public static final class PairLink {
  private final ServerConfig server;
  private final String code;

  private PairLink(ServerConfig server, String code) {
   this.server = server;
   this.code = code;
  }

  public ServerConfig server() { return server; }
  public String code() { return code; }

  public static PairLink parse(String uri) {
   IntentTarget target = IntentTarget.parse(uri);
   return target.kind() == IntentTarget.Kind.PAIR ? new PairLink(target.server(), target.code()) : null;
  }
 }

 public static final class IntentTarget {
  public enum Kind { PHONE, CHECK, PAIR, INVALID }

  private static final AtomicLong PAIR_COUNTER = new AtomicLong(0);

  private final Kind kind;
  private final ServerConfig server;
  private final String code;
  private final String reason;
  private final long pairSeq;

  private IntentTarget(Kind kind, ServerConfig server, String code, String reason) {
   this.kind = kind;
   this.server = server;
   this.code = code;
   this.reason = reason;
   this.pairSeq = kind == Kind.PAIR ? PAIR_COUNTER.incrementAndGet() : 0L;
  }

  public Kind kind() { return kind; }
  public ServerConfig server() { return server; }
  public String code() { return code; }
  public String reason() { return reason; }

  public String startUrl() {
   if (kind == Kind.CHECK) return PACKAGED_ORIGIN + "/check.html";
   if (kind == Kind.PAIR) return PACKAGED_ORIGIN + "/phone?pair=" + pairSeq + "#code=" + code;
   return PACKAGED_ORIGIN + "/phone";
  }

  public static IntentTarget parse(String uri) {
   if (uri == null || uri.isEmpty()) return new IntentTarget(Kind.PHONE, null, null, null);
   if (!uri.startsWith("cleanrep://")) return new IntentTarget(Kind.INVALID, null, null, "Unsupported scheme");
   String rest = uri.substring("cleanrep://".length());
   int hashIdx = rest.indexOf('#');
   if (hashIdx >= 0) rest = rest.substring(0, hashIdx);
   String hier = rest;
   String query = null;
   int qIdx = rest.indexOf('?');
   if (qIdx >= 0) {
    hier = rest.substring(0, qIdx);
    query = rest.substring(qIdx + 1);
   }
   String hostPart = hier;
   String pathPart = "";
   int slashIdx = hier.indexOf('/');
   if (slashIdx >= 0) {
    hostPart = hier.substring(0, slashIdx);
    pathPart = hier.substring(slashIdx);
   }
   if (!pathPart.isEmpty() && !"/".equals(pathPart)) {
    return new IntentTarget(Kind.INVALID, null, null, "Unexpected path in intent URI");
   }
   if ("check".equals(hostPart)) {
    return new IntentTarget(Kind.CHECK, null, null, null);
   }
   if (!"pair".equals(hostPart)) {
    return new IntentTarget(Kind.INVALID, null, null, "Unknown intent host");
   }
   if (query == null || query.isEmpty()) {
    return new IntentTarget(Kind.INVALID, null, null, "Missing query parameters");
   }
   String host = null;
   String portStr = null;
   String code = null;
   String fp = null;
   String[] pairs = query.split("&");
   for (String pair : pairs) {
    if (pair.isEmpty()) continue;
    int eq = pair.indexOf('=');
    if (eq <= 0) continue;
    String k;
    String v;
    try {
     k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8.name());
     v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8.name());
    } catch (Exception e) {
     return new IntentTarget(Kind.INVALID, null, null, "Malformed percent-encoding");
    }
    switch (k) {
     case "host":
      if (host != null) return new IntentTarget(Kind.INVALID, null, null, "Duplicate host parameter");
      host = v;
      break;
     case "port":
      if (portStr != null) return new IntentTarget(Kind.INVALID, null, null, "Duplicate port parameter");
      portStr = v;
      break;
     case "code":
      if (code != null) return new IntentTarget(Kind.INVALID, null, null, "Duplicate code parameter");
      code = v;
      break;
     case "fp":
      if (fp != null) return new IntentTarget(Kind.INVALID, null, null, "Duplicate fp parameter");
      fp = v;
      break;
     default:
      break;
    }
   }
   if (host == null || host.isEmpty()) return new IntentTarget(Kind.INVALID, null, null, "Missing host");
   if (portStr == null || !portStr.matches("[0-9]{1,5}")) return new IntentTarget(Kind.INVALID, null, null, "Invalid port");
   int port;
   try {
    port = Integer.parseInt(portStr);
   } catch (NumberFormatException e) {
    return new IntentTarget(Kind.INVALID, null, null, "Port out of range");
   }
   if (code == null || !code.matches("[0-9]{6}")) return new IntentTarget(Kind.INVALID, null, null, "Invalid 6-digit code");
   ServerConfig server = ServerConfig.validate(host, port, fp);
   if (server == null) return new IntentTarget(Kind.INVALID, null, null, "Disallowed host, port, or fingerprint");
   return new IntentTarget(Kind.PAIR, server, code, null);
  }
 }

 public static boolean isPackagedUrl(String url) {
  return url != null && (url.equals(PACKAGED_ORIGIN) || url.startsWith(PACKAGED_ORIGIN + "/"));
 }

 public static boolean isPackagedOrigin(String origin) {
  return origin != null && (origin.equals(PACKAGED_ORIGIN) || origin.equals(PACKAGED_ORIGIN + "/"));
 }

 public static String resolveAssetPath(String path) {
  if (path == null || path.contains("..") || path.contains("\\") || path.startsWith("//")) return null;
  if (path.isEmpty() || "/".equals(path) || "/phone".equals(path)) return "index.html";
  if (!path.startsWith("/")) return null;
  String rel = path.substring(1);
  if (rel.isEmpty() || rel.startsWith("/")) return null;
  return rel;
 }

 public static String mimeType(String asset) {
  if (asset == null) return "application/octet-stream";
  if (asset.endsWith(".js") || asset.endsWith(".mjs")) return "text/javascript";
  if (asset.endsWith(".css")) return "text/css";
  if (asset.endsWith(".mp4")) return "video/mp4";
  if (asset.endsWith(".png")) return "image/png";
  if (asset.endsWith(".jpg") || asset.endsWith(".jpeg")) return "image/jpeg";
  if (asset.endsWith(".svg")) return "image/svg+xml";
  if (asset.endsWith(".wasm")) return "application/wasm";
  if (asset.endsWith(".woff2")) return "font/woff2";
  if (asset.endsWith(".html")) return "text/html";
  if (asset.endsWith(".json")) return "application/json";
  if (asset.endsWith(".task")) return "application/octet-stream";
  return "application/octet-stream";
 }

 public static boolean isValidRequestId(String id) {
  return id != null && id.matches("[A-Za-z0-9_-]{1,128}");
 }

 public static boolean isValidApiRequest(String method, String path, String body, String token) {
  if (method == null || path == null || body == null || token == null) return false;
  if (!"GET".equals(method) && !"POST".equals(method) && !"DELETE".equals(method)) return false;
  if (!path.matches("/v1/[A-Za-z0-9_/-]+")) return false;
  return body.length() <= API_MAX_BODY_CHARS && token.length() <= MAX_TOKEN_CHARS;
 }

 public static boolean isValidMirrorPath(String path) {
  return path != null && path.matches("/v1/sessions/[A-Za-z0-9_-]+/mirror");
 }

 public static boolean isValidMirrorInput(String path, String jpegBase64, String token) {
  if (!isValidMirrorPath(path) || jpegBase64 == null || token == null) return false;
  if (jpegBase64.isEmpty() || jpegBase64.length() > MIRROR_MAX_BASE64_CHARS) return false;
  return token.length() <= MAX_TOKEN_CHARS;
 }

 public static boolean isValidMirrorJpeg(byte[] bytes) {
  if (bytes == null || bytes.length < 4 || bytes.length > MIRROR_MAX_BYTES) return false;
  return (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
 }

 /** Pure-Java helper for JVM tests to validate and decode a base64 mirror request end-to-end. */
 public static byte[] decodeAndValidateMirror(String path, String jpegBase64, String token) {
  if (!isValidMirrorInput(path, jpegBase64, token)) return null;
  try {
   byte[] decoded = Base64.getMimeDecoder().decode(jpegBase64);
   return isValidMirrorJpeg(decoded) ? decoded : null;
  } catch (IllegalArgumentException e) {
   return null;
  }
 }

 public static String errorEnvelope(String requestId, String status, Integer stateVersion, String message) {
  String ver = stateVersion == null ? "null" : stateVersion.toString();
  String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
  return "{\"request_id\":\"" + requestId + "\",\"status\":\"" + status + "\",\"state_version\":" + ver +
   ",\"data\":{},\"evidence\":[],\"error\":{\"message\":\"" + escaped + "\"}}";
 }
}
