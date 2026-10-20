package org.cleanrep.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import static org.cleanrep.core.Json.*;
import static org.cleanrep.core.testing.Check.*;

public final class EdgeTest {
  public void test_C_API_4_concurrent_mutations_and_sensor_retries() throws Exception {
    try (Harness h = new Harness()) {
      h.host(); Object version = h.get().get("version");
      List<ApiResponse> results = Collections.synchronizedList(new ArrayList<>());
      concurrent(12, i -> results.add(h.request("POST", "/v1/sessions/" + h.sid + "/remote-only", obj("expected_version", version, "idempotency_key", "race_key_" + i), h.tv)));
      equal(1L, results.stream().filter(r -> r.status == 200).count()); equal(11L, results.stream().filter(r -> r.status == 409).count());
    }
    try (Harness h = new Harness()) {
      Map<String, Object> rep = h.active(); List<ApiResponse> results = Collections.synchronizedList(new ArrayList<>());
      concurrent(12, i -> results.add(h.request("POST", "/v1/sessions/" + h.sid + "/reps", rep, h.phone)));
      for (ApiResponse r : results) { equal(200, r.status); equal(results.get(0).text(), r.text()); }
      equal(1L, map(h.view().get("active_set")).get("reps_counted"));
    }
  }
  public void test_C_STO_4_size_limit_rollback_and_crash_temp() throws Exception {
    try (Harness h = new Harness()) {
      h.host(); Map<String, Object> old = h.api.sessions.load(h.sid), huge = copy(old);
      char[] chars = new char[25000]; java.util.Arrays.fill(chars, 'x'); huge.put("unused", new String(chars));
      DomainError error = raises(DomainError.class, () -> h.api.sessions.save(old, huge, "receipt:too_big", obj("revision", 0)));
      equal("session_size_limit", error.code); isTrue(same(old, h.api.sessions.load(h.sid)), "size failure must not write state");
      equal(null, h.api.sessions.store.get("receipt:too_big"));
      h.api.close();
      java.nio.file.Files.write(new java.io.File(h.dir, "sessions.json.tmp").toPath(), "broken transaction".getBytes(StandardCharsets.UTF_8));
      h.api = new CleanRepApi(h.config); equal("LOBBY", h.view().get("phase"));
      isTrue(!new java.io.File(h.dir, "sessions.json.tmp").exists(), "abandoned temp removed");
    }
  }
  public void test_C_API_5_replay_gate_exact_bytes_bad_names_and_origins() throws Exception {
    try (Harness h = new Harness()) {
      String path = "/v1/replay/squat_good_10";
      ApiResponse r = h.request("GET", path, null, null); equal(200, r.status);
      equalBytes(h.config.resources.read("fixtures/landmarks/squat_good_10.json"), r.body);
      for (String name : new String[]{"missing", "__", "..", "squat-good-10"}) equal(404, h.request("GET", "/v1/replay/" + name, null, null).status);
      Map<String, String> hdr = new LinkedHashMap<>(); hdr.put("origin", "https://bad.example");
      equal(403, h.api.handle(new ApiRequest("GET", path, "", hdr, new byte[0], "testclient")).status);
      h.api.close(); h.api = new CleanRepApi(CoreConfig.builder(h.dir, h.config.resources).clock(h.clock).build());
      equal(404, h.request("GET", path, null, null).status);
    }
  }
  public void test_C_API_6_loopback_exemption_peer_limits_and_pairing_cap() throws Exception {
    try (Harness h = new Harness()) {
      Map<String, String> headers = new LinkedHashMap<>(); headers.put("content-type", "application/json");
      for (int i = 0; i < 13; i++) equal(200, h.api.handle(new ApiRequest("POST", "/v1/bootstrap", "", headers, new byte[]{'{', '}'}, "127.0.0.1")).status);
      h.host();
      for (int i = 0; i < 8; i++) h.ok(h.request("POST", "/v1/pair/request", obj("pairing_code", h.code), null));
      ApiResponse cap = h.request("POST", "/v1/pair/request", obj("pairing_code", h.code), null); equal(429, cap.status);
      equal("pairing_limit", map(map(Json.parse(cap.body)).get("error")).get("code"));
      for (int i = 0; i < 12; i++) {
        headers.put("x-forwarded-for", "pretend-client-" + i);
        equal(200, h.api.handle(new ApiRequest("POST", "/v1/bootstrap", "", headers, new byte[]{'{', '}'}, "192.168.1.2")).status);
      }
      headers.put("x-forwarded-for", "different-client");
      equal(429, h.api.handle(new ApiRequest("POST", "/v1/bootstrap", "", headers, new byte[]{'{', '}'}, "192.168.1.2")).status);
      equal(200, h.api.handle(new ApiRequest("POST", "/v1/bootstrap", "", headers, new byte[]{'{', '}'}, "192.168.1.3")).status);
    }
  }
  public void test_C_MIR_2_exact_body_limits_and_deep_policy() throws Exception {
    try (Harness h = new Harness()) {
      Map<String, String> headers = new LinkedHashMap<>(); headers.put("content-type", "image/jpeg");
      byte[] bytes = new byte[150 * 1024]; bytes[0] = (byte) 255; bytes[1] = (byte) 216; bytes[2] = (byte) 255;
      equal(401, h.api.handle(new ApiRequest("POST", "/v1/sessions/no/mirror", "", headers, bytes, "testclient")).status);
      equal(413, h.api.handle(new ApiRequest("POST", "/v1/sessions/no/mirror", "", headers, java.util.Arrays.copyOf(bytes, bytes.length + 1), "testclient")).status);
      headers.put("content-type", "application/json");
      for (String path : new String[]{"/v1/no", "/v1/sessions/no/reps"}) {
        int size = path.endsWith("reps") ? 2048 : 8192;
        byte[] json = new byte[size]; java.util.Arrays.fill(json, (byte) ' '); json[0] = '{'; json[1] = '}';
        equal(path.endsWith("reps") ? 401 : 404, h.api.handle(new ApiRequest("POST", path, "", headers, json, "testclient")).status);
        equal(413, h.api.handle(new ApiRequest("POST", path, "", headers, java.util.Arrays.copyOf(json, size + 1), "testclient")).status);
      }
    }
  }
  public void test_C_API_7_second_phone_approval_is_a_conflict() throws Exception {
    try (Harness h = new Harness()) {
      h.host();
      Map<String, Object> first = h.data(h.request("POST", "/v1/pair/request", obj("pairing_code", h.code), null));
      Map<String, Object> second = h.data(h.request("POST", "/v1/pair/request", obj("pairing_code", h.code), null));
      h.data(h.mutate("pair/approve", obj("request_id", first.get("request_id"))));
      Object version = h.get().get("version");
      ApiResponse r = h.mutate("pair/approve", obj("request_id", second.get("request_id")));
      equal(409, r.status); equal("application/json", r.contentType);
      Map<String, Object> body = map(Json.parse(r.body));
      equal("conflict", body.get("status")); equal("phase_invalid", map(body.get("error")).get("code"));
      isTrue(same(version, body.get("version")), "the conflict carries the current version");
      equal("READINESS", h.view().get("phase"));
    }
  }
  public void test_C_API_8_unicode_regexes_and_content_length_without_android_only_flags() {
    // Python's int(): sign, Unicode digits, single underscores between digits.
    equal(new java.math.BigInteger("20"), CleanRepApi.pythonInt("2_0"));
    equal(new java.math.BigInteger("2"), CleanRepApi.pythonInt("+0002"));
    equal(new java.math.BigInteger("-1"), CleanRepApi.pythonInt("-1"));
    equal(new java.math.BigInteger("12"), CleanRepApi.pythonInt("１２"));
    for (String bad : new String[]{"", "+", "_1", "1_", "1__0", "bad", "1.0", "0x10"}) equal(null, CleanRepApi.pythonInt(bad));
    // The helper keeps Python's Unicode classes on the JVM (Android's ICU has them by default).
    isTrue(Wording.regex("^\\d{6}\\z").matcher("１２３４５６").find(), "Unicode digits");
    isTrue(Wording.regex("\\bpain\\b").matcher("no pain.").find(), "word boundary");
  }
  private interface Indexed { void run(int index) throws Exception; }
  private void concurrent(int count, Indexed action) throws Exception {
    CountDownLatch gate = new CountDownLatch(1); List<Throwable> errors = Collections.synchronizedList(new ArrayList<>()); Thread[] threads = new Thread[count];
    for (int i = 0; i < count; i++) {
      final int index = i; threads[i] = new Thread(() -> { try { gate.await(); action.run(index); } catch (Throwable t) { errors.add(t); } }); threads[i].start();
    }
    gate.countDown(); for (Thread t : threads) t.join();
    if (!errors.isEmpty()) throw new AssertionError("worker failed", errors.get(0));
  }
}
