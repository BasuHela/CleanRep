package org.cleanrep.core;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.cleanrep.core.Json.*;
import static org.cleanrep.core.testing.Check.*;

public final class SensorTest {
  public void test_C_SES_4_reps_idempotency_downgrade_target_and_idle() throws Exception {
    try (Harness h = new Harness()) {
      Map<String, Object> r = h.active(); String path = "/v1/sessions/" + h.sid + "/reps";
      Object version = h.get().get("version"); r.put("confidence", 0.1);
      ApiResponse first = h.request("POST", path, r, h.phone); h.ok(first);
      equal(first.text(), h.request("POST", path, r, h.phone).text());
      equal(version, h.get().get("version")); equal(arr("unrated"), h.view().get("rep_marks"));
      Map<String, Object> bad = copy(r); bad.put("rated", false); equal(409, h.request("POST", path, bad, h.phone).status);
      for (int i = 1; i < 10; i++) { h.clock.tick(0.3); r.put("rep_index", i); h.ok(h.request("POST", path, r, h.phone)); }
      equal("SET_SUMMARY", h.view().get("phase")); equal(10L, map(h.view().get("summary")).get("reps_counted"));
      isTrue(h.view().get("explanation") != null, "wording on poll");
      h.data(h.mutate("advance", obj())); h.data(h.mutate("sets/start", obj("block_id", "b_0")));
      h.clock.tick(20); equal("SET_SUMMARY", h.view().get("phase"));
    }
  }
  public void test_C_SES_5_camera_check_rate_and_stable_version() throws Exception {
    try (Harness h = new Harness()) {
      h.ready(); Object version = h.get().get("version");
      Map<String, Object> check = obj("full_body", true, "side_view", false, "light", true, "fps", 30, "delegate", "GPU");
      String path = "/v1/sessions/" + h.sid + "/camera-check";
      h.ok(h.request("POST", path, check, h.phone)); equal(429, h.request("POST", path, check, h.phone).status);
      equal(version, h.get().get("version")); equal(obj("full_body", true, "side_view", false, "light", true), h.view().get("camera_check"));
      h.data(h.mutate("advance", obj())); equal(null, h.view().get("camera_check"));
      h.clock.tick(4); equal(false, h.view().get("phone_sensing")); equal(true, h.view().get("phone_connected"));
    }
  }
  public void test_C_MIR_1_role_ttl_rate_query_and_never_stored() throws Exception {
    try (Harness h = new Harness()) {
      h.ready(); String path = "/v1/sessions/" + h.sid + "/mirror";
      byte[] jpeg = {(byte) 255, (byte) 216, (byte) 255, 7};
      Map<String, String> headers = new LinkedHashMap<>(); headers.put("content-type", " Image/JPEG ;x=y"); headers.put("authorization", "Bearer " + h.phone);
      equal(204, h.request("GET", path, null, h.tv).status);
      for (int i = 0; i < 12; i++) h.ok(h.api.handle(new ApiRequest("POST", path, "", headers, jpeg, "testclient")));
      equal(429, h.api.handle(new ApiRequest("POST", path, "", headers, jpeg, "testclient")).status);
      equalBytes(jpeg, h.request("GET", path, null, h.tv).body); equal(true, h.view().get("mirror_live"));
      headers.put("authorization", "Bearer " + h.tv);
      equal(401, h.api.handle(new ApiRequest("GET", path, "anything", headers, new byte[0], "testclient")).status);
      equal(403, h.request("GET", path, null, h.phone).status);
      h.clock.tick(2); equal(200, h.request("GET", path, null, h.tv).status);
      h.clock.tick(0.001); equal(204, h.request("GET", path, null, h.tv).status);
      String disk = new String(java.nio.file.Files.readAllBytes(new java.io.File(h.dir, "sessions.json").toPath()), StandardCharsets.UTF_8);
      isTrue(!disk.contains("frame") && !disk.contains("mirror_rates"), "no frame storage");
    }
  }
  public void test_C_COA_1_notices_rate_sequence_expiry_clearing_and_no_storage() throws Exception {
    try (Harness h = new Harness()) {
      Map<String, Object> r = h.active(); String path = "/v1/sessions/" + h.sid + "/coach";
      Map<String, Object> notice = obj("set_id", r.get("set_id"), "kind", "shallow", "client_ts_ms", 999999999999999L);
      Object version = h.get().get("version");
      for (int i = 0; i < 4; i++) h.ok(h.request("POST", path, notice, h.phone));
      equal(429, h.request("POST", path, notice, h.phone).status);
      equal(4L, map(h.view().get("coach_notice")).get("seq")); equal(version, h.get().get("version"));
      h.clock.tick(4); equal(null, h.view().get("coach_notice"));
      h.ok(h.request("POST", path, notice, h.phone)); equal(5L, map(h.view().get("coach_notice")).get("seq"));
      h.ok(h.request("POST", "/v1/sessions/" + h.sid + "/reps", r, h.phone)); equal(null, h.view().get("coach_notice"));
      h.clock.tick(1); h.ok(h.request("POST", path, notice, h.phone));
      equal(null, h.data(h.mutate("pause", obj())).get("coach_notice"));
      h.data(h.mutate("resume", obj())); equal(null, h.view().get("coach_notice"));
      h.restart(); h.ok(h.request("POST", path, notice, h.phone)); equal(1L, map(h.view().get("coach_notice")).get("seq"));
    }
  }
  public void test_C_SES_8_rep_validation_does_not_consume_index_and_burst_limit() throws Exception {
    try (Harness h = new Harness()) {
      Map<String, Object> rep = h.active(); String path = "/v1/sessions/" + h.sid + "/reps";
      for (double depth : new double[]{-0.65001, -1.5}) for (boolean rated : new boolean[]{true, false}) {
        Map<String, Object> bad = copy(rep); map(bad.get("metrics")).put("depth_ratio", depth); bad.put("rated", rated);
        equal(422, h.request("POST", path, bad, h.phone).status);
      }
      equal(0L, map(h.view().get("active_set")).get("reps_counted"));
      for (int i = 0; i < 4; i++) { rep.put("rep_index", i); h.ok(h.request("POST", path, rep, h.phone)); }
      rep.put("rep_index", 4); equal(429, h.request("POST", path, rep, h.phone).status);
      h.clock.tick(1); h.ok(h.request("POST", path, rep, h.phone));
      h.data(h.mutate("sets/end", obj("set_id", rep.get("set_id"))));
      rep.put("rep_index", 5); equal(409, h.request("POST", path, rep, h.phone).status);
      rep.put("rep_index", 4); h.ok(h.request("POST", path, rep, h.phone));
    }
  }
  public void test_C_MIR_3_invalid_stored_rep_degrades_and_frame_lifecycle() throws Exception {
    try (Harness h = new Harness()) {
      Map<String, Object> rep = h.active(); h.ok(h.request("POST", "/v1/sessions/" + h.sid + "/reps", rep, h.phone));
      Map<String, Object> old = h.api.sessions.load(h.sid), corrupted = copy(old);
      map(map(list(map(corrupted.get("active_set")).get("reps")).get(0)).get("metrics")).put("knee_min_deg", 999);
      h.api.sessions.save(old, corrupted, null, null);
      equal(null, h.view().get("last_rep")); equal(arr("unrated"), h.view().get("rep_marks"));
      // Restore before set summarization; Python's summary validates persisted events again.
      Map<String, Object> repaired = copy(h.api.sessions.load(h.sid)); map(repaired.get("active_set")).put("reps", arr(rep));
      h.api.sessions.save(h.api.sessions.load(h.sid), repaired, null, null);
      Map<String, String> headers = new LinkedHashMap<>(); headers.put("content-type", "image/jpeg"); headers.put("authorization", "Bearer " + h.phone);
      byte[] jpeg = {(byte) 255, (byte) 216, (byte) 255, 7}; String path = "/v1/sessions/" + h.sid + "/mirror";
      h.ok(h.api.handle(new ApiRequest("POST", path, "", headers, jpeg, "testclient")));
      h.data(h.mutate("pause", obj())); equal(204, h.request("GET", path, null, h.tv).status);
      h.data(h.mutate("resume", obj())); h.clock.tick(1);
      h.ok(h.api.handle(new ApiRequest("POST", path, "", headers, jpeg, "testclient")));
      h.data(h.mutate("remote-only", obj())); equal(204, h.request("GET", path, null, h.tv).status);
      equal(401, h.api.handle(new ApiRequest("POST", path, "", headers, jpeg, "testclient")).status);
    }
  }

  public void test_C_COA_2_notice_is_not_persisted_or_an_inactivity_event() throws Exception {
    try (Harness h = new Harness()) {
      Map<String, Object> rep = h.active();
      java.nio.file.Path path = new java.io.File(h.dir, "sessions.json").toPath();
      byte[] before = java.nio.file.Files.readAllBytes(path);
      h.clock.tick(19);
      h.ok(h.request("POST", "/v1/sessions/" + h.sid + "/coach", obj("set_id", rep.get("set_id"), "kind", "uncalibrated", "client_ts_ms", 0), h.phone));
      h.view(); equalBytes(before, java.nio.file.Files.readAllBytes(path));
      h.clock.tick(1); equal("SET_SUMMARY", h.view().get("phase")); equal(null, h.view().get("coach_notice"));
      String disk = new String(java.nio.file.Files.readAllBytes(path), StandardCharsets.UTF_8);
      isTrue(!disk.contains("uncalibrated") && !disk.contains(h.tv) && !disk.contains(h.phone), "notices and bearer credentials never persisted");
    }
  }

}
