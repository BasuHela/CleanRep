package org.cleanrep.core;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import static org.cleanrep.core.Json.*;

/** Home-mode fixture API. Policy order and error envelopes match services/api/app.py (MIT). */
public final class CleanRepApi implements Api {
  private final CoreConfig config;
  private final Schemas schemas;
  private final Domain domain;
  final Sessions sessions;
  private static final Map<String, String> MUTATIONS = new LinkedHashMap<>();
  private static final Map<String, String> SENSORS = new LinkedHashMap<>();
  static {
    String[] actions = {"pair/approve", "PairApprove", "remote-only", "Mutation", "readiness", "Readiness", "setup", "Setup", "plan", "Mutation", "advance", "Mutation", "sets/start", "StartSet", "sets/end", "EndSet", "explain", "Explain", "drill", "Drill", "adapt", "Adapt", "pause", "Mutation", "resume", "Mutation"};
    for (int i = 0; i < actions.length; i += 2) MUTATIONS.put(actions[i], actions[i + 1]);
    actions = new String[]{"reps", "RepEvent", "holds", "HoldEvent", "camera-check", "CameraCheck", "heartbeat", "Empty", "coach", "CoachEvent"};
    for (int i = 0; i < actions.length; i += 2) SENSORS.put(actions[i], actions[i + 1]);
  }
  public CleanRepApi(CoreConfig config) {
    this.config = config;
    try { schemas = new Schemas(config.resources); domain = new Domain(new Catalog(config.resources)); }
    catch (IOException e) { throw new IllegalStateException("cannot load core resources", e); }
    sessions = new Sessions(config, domain, schemas);
  }
  @Override public ApiResponse handle(ApiRequest request) {
    ApiResponse result;
    try { result = route(request, policy(request)); }
    catch (PolicyError e) { result = reject(e.code, e.status); }
    catch (DomainError e) {
      result = json(e.http, obj("status", e.http == 409 ? "conflict" : e.http >= 500 ? "unavailable" : "rejected", "version", e.version, "data", obj(), "error", obj("code", e.code, "message", e.getMessage())));
    } catch (StoreConflict e) { result = reject("stale_version", 409); }
    catch (RuntimeException e) {
      // Match Starlette for unhandled failures.
      result = new ApiResponse(500, "text/plain; charset=utf-8", "Internal Server Error".getBytes(java.nio.charset.StandardCharsets.UTF_8), Collections.emptyMap());
    }
    if (request.method.equals("HEAD")) return new ApiResponse(result.status, result.contentType, new byte[0], result.headers);
    return result;
  }
  private static final class PolicyError extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final String code; final int status;
    PolicyError(String code, int status) { this.code = code; this.status = status; }
  }
  private static void fail(String code, int status) { throw new PolicyError(code, status); }
  private static ApiResponse json(int status, Object value) { return ApiResponse.json(status, stringify(value)); }
  private static ApiResponse reject(String code, int status) {
    return json(status, obj("status", "rejected", "version", null, "data", obj(), "error", obj("code", code, "message", code.replace('_', ' '))));
  }
  private Object policy(ApiRequest r) {
    if (!r.path.startsWith("/v1/")) return null;
    String origin = r.header("origin");
    if (origin != null && !origin.isEmpty() && !config.origins().contains(origin)) fail("origin_rejected", 403);
    boolean mirror = r.method.equals("POST") && r.path.startsWith("/v1/sessions/") && r.path.endsWith("/mirror") && r.path.chars().filter(c -> c == '/').count() == 4;
    if (!mirror && !r.method.equals("POST") && !r.method.equals("DELETE")) return null;
    int limit = mirror ? 150 * 1024 : 8192;
    if (!mirror) for (String suffix : SENSORS.keySet()) if (r.path.endsWith("/" + suffix)) limit = 2048;
    String length = r.header("content-length");
    BigInteger declared = pythonInt(length == null ? "0" : Json.strip(length, true));
    if (declared == null) fail("invalid_length", 400);
    if (declared.compareTo(BigInteger.valueOf(limit)) > 0) fail("payload_too_large", 413);
    String type = r.header("content-type"); type = type == null ? "" : type.split(";", -1)[0];
    if (mirror ? !Json.strip(type, false).toLowerCase(Locale.ROOT).equals("image/jpeg") : !type.equals("application/json")) fail(mirror ? "jpeg_required" : "json_required", 415);
    if (r.body.length > limit) fail("payload_too_large", 413);
    if (mirror) {
      if (r.body.length < 4 || r.body[0] != (byte) 255 || r.body[1] != (byte) 216 || r.body[2] != (byte) 255) fail("invalid_jpeg", 422);
      return null;
    }
    Object value;
    try { value = Json.parse(r.body); } catch (IllegalArgumentException e) { throw new PolicyError("invalid_request", 422); }
    if (forbidden(value)) fail("private_payload", 422);
    return value;
  }
  /** Python's int() of stripped text: an optional sign, then Unicode decimal digits with single underscores between
   * them, at most 4,300 digits; null when Python raises ValueError. No regex, so Android and the JVM agree. */
  static BigInteger pythonInt(String text) {
    StringBuilder digits = new StringBuilder();
    int i = 0;
    if (!text.isEmpty() && (text.charAt(0) == '+' || text.charAt(0) == '-')) digits.append(text.charAt(i++));
    boolean afterDigit = false;
    while (i < text.length()) {
      int c = text.codePointAt(i);
      i += Character.charCount(c);
      if (c == '_' && afterDigit && i < text.length() && Character.isDigit(text.codePointAt(i))) { afterDigit = false; continue; }
      if (!Character.isDigit(c)) return null;
      digits.append((char) ('0' + Character.digit(c, 10)));
      afterDigit = true;
    }
    int count = digits.length() - (digits.length() > 0 && !Character.isDigit(digits.charAt(0)) ? 1 : 0);
    return count == 0 || count > 4300 ? null : new BigInteger(digits.toString());
  }
  private boolean forbidden(Object value) {
    if (value instanceof Map) for (Map.Entry<String, Object> e : map(value).entrySet()) {
      String key = Wording.fold(e.getKey());
      for (String word : new String[]{"image", "frame", "landmark", "pixel", "video", "base64"}) if (key.contains(word)) return true;
      if (forbidden(e.getValue())) return true;
    }
    if (value instanceof java.util.List) for (Object item : list(value)) if (forbidden(item)) return true;
    return false;
  }
  private String credential(ApiRequest r) {
    String auth = r.header("authorization");
    if (auth == null || !auth.startsWith("Bearer ") || auth.codePointCount(0, auth.length()) > 200) throw new DomainError("unauthorized", 401);
    return auth.substring(7);
  }
  private Map<String, Object> validate(String schema, Object body) {
    try {
      Map<String, Object> value = schemas.validate(schema, body);
      if (schema.equals("RepEvent")) domain.validateRep(value);
      if (schema.equals("HoldEvent")) domain.validateHold(value);
      return value;
    } catch (IllegalArgumentException e) { throw new PolicyError("invalid_request", 422); }
  }
  private String methods(String path) {
    if (path.equals("/v1/healthz")) return "GET";
    if (arr("/v1/bootstrap", "/v1/sessions", "/v1/pair/request", "/v1/pair/claim").contains(path)) return "POST";
    String[] parts = path.split("/", -1);
    if (parts.length == 4 && parts[1].equals("v1") && parts[2].equals("replay") && !parts[3].isEmpty()) return "GET";
    if (parts.length < 4 || !parts[1].equals("v1") || !parts[2].equals("sessions") || parts[3].isEmpty()) return null;
    if (parts.length == 4) return "GET, DELETE";
    String action = path.substring(("/v1/sessions/" + parts[3] + "/").length());
    if (MUTATIONS.containsKey(action) || SENSORS.containsKey(action)) return "POST";
    if (action.equals("mirror")) return "POST, GET";
    if (parts.length == 6 && parts[4].equals("jobs") && !parts[5].isEmpty()) return "GET";
    return null;
  }
  private ApiResponse route(ApiRequest r, Object body) {
    String allowed = methods(r.path);
    if (allowed == null) {
      String alternate = r.path.endsWith("/") ? r.path.replaceAll("/+$", "") : r.path + "/";
      if (methods(alternate) != null) return new ApiResponse(307, null, new byte[0], Collections.singletonMap("Location", alternate + (r.query.isEmpty() ? "" : "?" + r.query)));
      return json(404, obj("detail", "Not Found"));
    }
    if (!java.util.Arrays.asList(allowed.split(", ")).contains(r.method))
      return new ApiResponse(405, "application/json", stringify(obj("detail", "Method Not Allowed")).getBytes(java.nio.charset.StandardCharsets.UTF_8), Collections.singletonMap("Allow", allowed.split(", ")[0]));
    sessions.sweep();
    if (r.path.equals("/v1/healthz")) return json(200, Sessions.envelope(null, obj("mode", "fixture", "store", "json", "model_source", "fixture")));
    if (r.path.equals("/v1/bootstrap")) { validate("Empty", body); return json(200, sessions.bootstrap(r.peer == null ? "unknown" : r.peer)); }
    if (r.path.equals("/v1/sessions")) { String token = credential(r); validate("Empty", body); return json(200, sessions.create(token)); }
    if (r.path.equals("/v1/pair/request")) {
      Map<String, Object> p = validate("PairRequest", body); return json(200, sessions.pair(str(p, "pairing_code"), r.peer == null ? "unknown" : r.peer));
    }
    if (r.path.equals("/v1/pair/claim")) {
      Map<String, Object> p = validate("PairClaim", body); return json(200, sessions.claim(str(p, "request_id"), str(p, "claim_token"), r.peer == null ? "unknown" : r.peer));
    }
    if (r.path.startsWith("/v1/replay/")) {
      String name = r.path.substring("/v1/replay/".length());
      if (!config.allowReplay || name.replace("_", "").isEmpty() || !name.matches("[\\p{L}\\p{N}_]+")) throw new DomainError("not_found", 404);
      try { return new ApiResponse(200, "application/json", config.resources.read("fixtures/landmarks/" + name + ".json"), Collections.emptyMap()); }
      catch (IOException e) { throw new DomainError("not_found", 404); }
    }
    String token = credential(r); String[] parts = r.path.split("/", -1); String sid = parts[3];
    if (parts.length == 4) return json(200, r.method.equals("GET") ? sessions.get(sid, token) : sessions.delete(sid, token, validate("Mutation", body)));
    String action = r.path.substring(("/v1/sessions/" + sid + "/").length());
    if (action.equals("mirror")) {
      if (r.method.equals("POST")) return json(200, sessions.putMirror(sid, token, r.body));
      if (!r.query.isEmpty()) throw new DomainError("unauthorized", 401);
      byte[] frame = sessions.getMirror(sid, token);
      return new ApiResponse(frame == null ? 204 : 200, frame == null ? null : "image/jpeg", frame == null ? new byte[0] : frame, Collections.emptyMap());
    }
    if (parts.length == 6 && parts[4].equals("jobs")) return json(200, sessions.job(sid, token, parts[5]));
    if (MUTATIONS.containsKey(action)) return json(200, sessions.mutate(sid, token, action, validate(MUTATIONS.get(action), body)));
    return json(200, sessions.sensor(sid, token, action, validate(SENSORS.get(action), body)));
  }
  public void recover() { sessions.recover(); }
  public void close() { sessions.close(); }
  CoreConfig config() { return config; }
}
