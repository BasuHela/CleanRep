package org.cleanrep.core;

import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.DoubleSupplier;
import static org.cleanrep.core.Json.*;
import static org.cleanrep.core.testing.Check.*;

final class Harness implements AutoCloseable {
  static final class Clock implements DoubleSupplier {
    volatile double now = 1700000000; public double getAsDouble() { return now; } void tick(double seconds) { now += seconds; }
  }
  final File dir;
  final Clock clock = new Clock();
  final CoreConfig config;
  CleanRepApi api;
  String tv, phone, sid, code;
  int key;
  Harness() throws Exception {
    dir = Files.createTempDirectory("cleanrep-api-").toFile();
    config = CoreConfig.builder(dir, RepoResources.fromSystemProperty()).clock(clock).random(new Random(1234)).allowReplay(true).build();
    api = new CleanRepApi(config);
  }
  ApiResponse request(String method, String path, Object body, String token) {
    Map<String, String> headers = new LinkedHashMap<>();
    if (token != null) headers.put("authorization", "Bearer " + token);
    if (body != null) headers.put("content-type", "application/json");
    return api.handle(new ApiRequest(method, path, "", headers, body == null ? new byte[0] : stringify(body).getBytes(StandardCharsets.UTF_8), "testclient"));
  }
  Map<String, Object> ok(ApiResponse r) { equal(200, r.status); return map(Json.parse(r.body)); }
  Map<String, Object> data(ApiResponse r) { return map(ok(r).get("data")); }
  void host() {
    tv = str(data(request("POST", "/v1/bootstrap", obj(), null)), "tv_token");
    Map<String, Object> view = data(request("POST", "/v1/sessions", obj(), tv)); sid = str(view, "session_id"); code = str(view, "pairing_code");
  }
  Map<String, Object> get() { return ok(request("GET", "/v1/sessions/" + sid, null, tv)); }
  Map<String, Object> view() { return map(get().get("data")); }
  ApiResponse mutate(String action, Map<String, Object> payload) {
    Map<String, Object> p = obj("expected_version", get().get("version"), "idempotency_key", "test_key_" + (++key)); p.putAll(payload);
    return request("POST", "/v1/sessions/" + sid + "/" + action, p, tv);
  }
  void pair() {
    Map<String, Object> r = data(request("POST", "/v1/pair/request", obj("pairing_code", code), null));
    data(mutate("pair/approve", obj("request_id", r.get("request_id"))));
    phone = str(data(request("POST", "/v1/pair/claim", obj("request_id", r.get("request_id"), "claim_token", r.get("claim_token")), null)), "phone_token");
  }
  void ready() throws Exception {
    host(); pair(); data(mutate("readiness", obj("answers", arr(false, false, false))));
    data(mutate("setup", obj("equipment", arr("chair", "counter"), "quiet", true, "no_floor", false, "length_min", 15, "go_easy", arr())));
    String job = str(data(mutate("plan", obj())), "job_id"); waitJob(job);
  }
  void waitJob(String job) throws Exception {
    for (int i = 0; i < 200; i++) {
      if ("done".equals(data(request("GET", "/v1/sessions/" + sid + "/jobs/" + job, null, tv)).get("state"))) return;
      Thread.sleep(5);
    }
    throw new AssertionError("job did not finish");
  }
  Map<String, Object> active() throws Exception {
    ready(); data(mutate("advance", obj()));
    Map<String, Object> started = data(mutate("sets/start", obj("block_id", "b_0")));
    Map<String, Object> event = DomainTest.squat(0.1); event.put("set_id", map(started.get("active_set")).get("set_id")); return event;
  }
  void restart() { api.close(); api = new CleanRepApi(config); api.recover(); }
  @Override public void close() throws java.io.IOException { api.close(); TestFiles.remove(dir); }
}
