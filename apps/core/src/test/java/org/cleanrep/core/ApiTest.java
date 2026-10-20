package org.cleanrep.core;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import static org.cleanrep.core.Json.*;
import static org.cleanrep.core.testing.Check.*;

public final class ApiTest {
  public void test_C_API_1_pair_roles_views_expiry_and_tokens() throws Exception {
    try (Harness h = new Harness()) {
      h.host(); equal(43, h.tv.length()); isTrue(h.tv.matches("[A-Za-z0-9_-]{43}"), "base64url");
      h.pair();
      Map<String, Object> phone = h.data(h.request("GET", "/v1/sessions/" + h.sid, null, h.phone));
      equal(new java.util.HashSet<>(arr("session_id", "phase", "paused", "sensor_mode", "active_set", "catalog")), phone.keySet());
      equal(403, h.request("POST", "/v1/sessions/" + h.sid + "/pause", obj("expected_version", 2, "idempotency_key", "abcdefgh"), h.phone).status);
      equal(401, h.request("GET", "/v1/sessions/" + h.sid, null, "bad").status);
      h.clock.tick(7200); equal(410, h.request("GET", "/v1/sessions/" + h.sid, null, h.tv).status);
      equal(404, h.request("GET", "/v1/sessions/" + h.sid, null, h.tv).status);
    }
  }
  public void test_C_API_2_idempotency_binding_restart_delete() throws Exception {
    try (Harness h = new Harness()) {
      h.host(); Map<String, Object> p = obj("expected_version", 0, "idempotency_key", "retry_key");
      ApiResponse first = h.request("POST", "/v1/sessions/" + h.sid + "/remote-only", p, h.tv); h.ok(first);
      h.restart();
      equal(first.text(), h.request("POST", "/v1/sessions/" + h.sid + "/remote-only", p, h.tv).text());
      p.put("expected_version", 1);
      equal(409, h.request("POST", "/v1/sessions/" + h.sid + "/remote-only", p, h.tv).status);
      h.ok(h.request("DELETE", "/v1/sessions/" + h.sid, p, h.tv));
      equal(404, h.request("GET", "/v1/sessions/" + h.sid, null, h.tv).status);
    }
  }
  public void test_C_API_3_policy_precedes_routing_and_credentials() throws Exception {
    try (Harness h = new Harness()) {
      equal(415, h.api.handle(new ApiRequest("POST", "/v1/no", "", Collections.emptyMap(), "{}".getBytes(StandardCharsets.UTF_8), "testclient")).status);
      equal(422, h.request("POST", "/v1/no", obj("nested", arr(obj("baſe64", 1))), null).status);
      equal(404, h.request("POST", "/v1/no", obj(), null).status);
      equal(405, h.request("PUT", "/v1/bootstrap", null, null).status);
      equal(401, h.request("POST", "/v1/sessions", arr(), null).status);
      equal(422, h.request("POST", "/v1/sessions", arr(), "bad").status);
    }
  }
}
