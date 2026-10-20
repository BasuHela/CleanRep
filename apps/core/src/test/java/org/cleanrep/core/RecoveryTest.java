package org.cleanrep.core;

import java.util.Map;
import static org.cleanrep.core.Json.*;
import static org.cleanrep.core.testing.Check.*;

public final class RecoveryTest {
  public void test_C_REC_1_pending_job_recovery_and_expired_purge() throws Exception {
    try (Harness h = new Harness()) {
      h.host(); h.data(h.mutate("remote-only", obj())); h.data(h.mutate("readiness", obj("answers", arr(false, false, false))));
      Map<String, Object> old = h.api.sessions.load(h.sid), pending = copy(old);
      pending.putAll(obj("phase", "PLANNING", "pending_job", "job_recovery", "jobs", obj("job_recovery", obj("state", "pending", "source", null))));
      h.api.sessions.save(old, pending, null, null); h.restart();
      Map<String, Object> job = h.data(h.request("GET", "/v1/sessions/" + h.sid + "/jobs/job_recovery", null, h.tv));
      equal("done", job.get("state")); equal("fallback", job.get("source")); equal("restart_recovery", job.get("reason"));
      equal("READY", h.view().get("phase")); h.clock.tick(7200); h.restart();
      equal(404, h.request("GET", "/v1/sessions/" + h.sid, null, h.tv).status);
    }
  }
  public void test_C_SES_6_hold_idle_window_rounding_and_wording() throws Exception {
    try (Harness h = new Harness()) {
      h.ready(); h.data(h.mutate("advance", obj()));
      Map<String, Object> old = h.api.sessions.load(h.sid), holdPlan = copy(old);
      map(list(map(holdPlan.get("plan")).get("blocks")).get(0)).putAll(obj("exercise_id", "plank_hold", "reps", null, "hold_s", 40));
      h.api.sessions.save(old, holdPlan, null, null);
      String set = str(map(h.data(h.mutate("sets/start", obj("block_id", "b_0"))).get("active_set")), "set_id");
      h.clock.tick(41); equal("IN_SET", h.view().get("phase"));
      h.ok(h.request("POST", "/v1/sessions/" + h.sid + "/holds", obj("set_id", set, "exercise_id", "plank_hold", "rated", true, "confidence", 0.9, "aligned_s", 18.5, "total_s", 20.5), h.phone));
      equal("19 of 21 seconds aligned (estimated).", map(h.view().get("explanation")).get("text"));
      h.data(h.mutate("advance", obj())); h.data(h.mutate("sets/start", obj("block_id", "b_0")));
      h.clock.tick(55); equal("SET_SUMMARY", h.view().get("phase"));
    }
  }
  public void test_C_REC_2_job_retention_and_repeated_plan_receipt() throws Exception {
    try (Harness h = new Harness()) {
      h.ready(); String first = null, last = null;
      Map<String, Object> firstRequest = null, firstResponse = null;
      for (int i = 0; i < 10; i++) {
        Map<String, Object> p = obj("expected_version", h.get().get("version"), "idempotency_key", "job_cycle_" + i);
        Map<String, Object> response = h.ok(h.request("POST", "/v1/sessions/" + h.sid + "/plan", p, h.tv));
        last = str(map(response.get("data")), "job_id");
        if (i == 0) { first = last; firstRequest = p; firstResponse = response; }
        h.waitJob(last);
      }
      equal(8, map(h.api.sessions.load(h.sid).get("jobs")).size());
      equal(404, h.request("GET", "/v1/sessions/" + h.sid + "/jobs/" + first, null, h.tv).status);
      equal("done", h.data(h.request("GET", "/v1/sessions/" + h.sid + "/jobs/" + last, null, h.tv)).get("state"));
      isTrue(same(firstResponse, h.ok(h.request("POST", "/v1/sessions/" + h.sid + "/plan", firstRequest, h.tv))), "old idempotent response preserved even after job retention");
    }
  }

}
