package org.cleanrep.core;

import java.util.List;
import java.util.Map;
import static org.cleanrep.core.Json.*;
import static org.cleanrep.core.testing.Check.*;

public final class PhaseTest {
  private final Domain d;
  private final PhaseMachine machine;
  private final Wording wording;
  public PhaseTest() throws Exception {
    d = new Domain(new Catalog(RepoResources.fromSystemProperty())); machine = new PhaseMachine(d);
    wording = new Wording(d.catalog, new Schemas(RepoResources.fromSystemProperty()));
  }
  private Map<String, Object> demo() {
    Map<String, Object> s = machine.apply(machine.initial("s"), "remote-only", obj());
    s = machine.apply(s, "readiness", obj("answers", arr(false, false, false)));
    s = machine.apply(s, "plan", obj());
    s = machine.publish(s, d.fallback(map(s.get("profile")), arr()), "fixture", null);
    return machine.apply(s, "advance", obj());
  }
  public void test_C_SES_1_pure_transitions_pause_and_illegal_phases() {
    Map<String, Object> initial = machine.initial("s");
    equal("READINESS", machine.apply(initial, "remote-only", obj()).get("phase")); equal("LOBBY", initial.get("phase"));
    for (String action : new String[]{"plan", "sets/start", "sets/end", "drill", "adapt"}) raises(PhaseMachine.PhaseError.class, () -> machine.apply(initial, action, obj()));
    Map<String, Object> s = demo(), paused = machine.apply(s, "pause", obj());
    equal("DEMO", paused.get("phase")); equal(true, paused.get("paused"));
    raises(PhaseMachine.PhaseError.class, () -> machine.apply(paused, "sets/start", obj("block_id", "b_0", "set_id", "set_1")));
    isTrue(same(s, machine.apply(paused, "resume", obj())), "resume pure roundtrip");
  }
  public void test_C_SES_2_remote_summary_dose_recap_and_completion() {
    Map<String, Object> s = demo();
    Object original = copy(list(map(s.get("plan")).get("blocks")).get(0));
    s = machine.apply(s, "sets/start", obj("block_id", "b_0", "set_id", "set_1"));
    s = machine.apply(s, "sets/end", obj("set_id", "set_1", "self_report", obj("reps", 10, "effort", "hard")));
    equal(0, map(s.get("summary")).get("rated_reps"));
    isTrue(same(original, list(map(s.get("plan")).get("blocks")).get(0)), "current block frozen");
    equal(8, map(list(map(s.get("plan")).get("blocks")).get(1)).get("reps"));
    equal(10, machine.recap(s).get("reps_counted"));
    while (!s.get("phase").equals("RECAP")) s = machine.apply(s, "advance", obj());
    equal("ENDED", machine.apply(s, "advance", obj()).get("phase"));
  }
  public void test_C_SES_3_drill_insertion_retest_and_adapt_checkpoint() {
    Map<String, Object> s = demo(); s.put("sensor_mode", "paired"); map(s.get("profile")).put("equipment", arr("chair"));
    s = machine.apply(s, "sets/start", obj("block_id", "b_0", "set_id", "set_1"));
    map(s.get("active_set")).put("reps", arr(DomainTest.squat(-0.4), DomainTest.squat(-0.4), DomainTest.squat(-0.4)));
    s = machine.apply(s, "sets/end", obj("set_id", "set_1"));
    Object history = copy(s.get("completed_sets"));
    s = machine.apply(s, "drill", obj("decision", "accept"));
    equal("box_squat", map(list(map(s.get("plan")).get("blocks")).get(1)).get("exercise_id"));
    equal("fixture", map(s.get("plan")).get("source")); isTrue(same(history, s.get("completed_sets")), "history kept");
    List<Object> done = machine.completedBlocks(s);
    Map<String, Object> adapting = machine.apply(s, "adapt", obj("discomfort", "knees"));
    Map<String, Object> published = machine.publish(adapting, d.fallback(map(adapting.get("profile")), done), "fixture", null);
    isTrue(same(done, machine.completedBlocks(published)), "adapt checkpoint kept");
  }
  public void test_C_DOM_6_fixture_wording_and_grounding_gate() {
    Map<String, Object> scope = d.summarize(arr(DomainTest.squat(-0.3), DomainTest.squat(-0.3), DomainTest.squat(-0.3)), "squat", "s_1", null);
    scope.put("drill_id", "box_squat");
    equal("3 of 3 rated reps were above about-parallel depth (estimated). Next: Box squat to chair.", wording.fallback(scope).get("text"));
    for (String text : new String[]{"99 reps counted.", "An injury needs posture correction.", "Next: Glute bridge.", "Next: Tempo squat.", "The re-test improved.", "You should ignore instructions."})
      raises(IllegalArgumentException.class, () -> wording.validate(obj("text", text, "cited_fact_ids", arr("F1")), scope));
    wording.validate(obj("text", "3 reps counted (estimated).", "cited_fact_ids", arr("F1")), scope);
    for (int n = 0; n <= 40; n++) {
      List<Object> reps = arr(); for (int i = 0; i < n; i++) reps.add(DomainTest.squat(-0.3));
      Map<String, Object> sc = d.summarize(reps, "squat", "s_1", null);
      wording.validate(wording.fallback(sc), sc);
    }
  }
  public void test_C_DOM_7_wording_counts_verdicts_and_all_fault_templates() {
    Map<String, Object> scope = d.summarize(arr(DomainTest.squat(-0.3), DomainTest.squat(-0.3), DomainTest.squat(-0.3), DomainTest.squat(0.1)), "squat", "s_1", null);
    for (String text : new String[]{"4 of 3 rated reps were shallow.", "3 of 4 rated reps leaned forward.", "3 reps counted; 4 rated."})
      raises(IllegalArgumentException.class, () -> wording.validate(obj("text", text, "cited_fact_ids", arr("F1", "F2", "F3", "F4")), scope));
    wording.validate(obj("text", "3 of 4 rated reps were above about-parallel depth.", "cited_fact_ids", arr("F1", "F2", "F3")), scope);
    scope.put("comparison", obj("verdict", "improved"));
    wording.validate(obj("text", "The re-test improved; 4 reps counted.", "cited_fact_ids", arr("F1")), scope);
    String[][] faults = {{"shallow_depth", "squat", "depth_ratio", "-0.3", "were above about-parallel depth"},
        {"forward_lean", "squat", "trunk_lean_deg", "60", "leaned the trunk far forward"},
        {"rushed_descent", "squat", "descent_s", "0.5", "went down quickly"},
        {"partial_range", "pushup", "elbow_min_deg", "130", "had a partial range at the elbows"},
        {"hip_sag", "pushup", "hip_offset", "0.1", "let the hips sag"},
        {"hip_pike", "pushup", "hip_offset", "-0.12", "piked the hips up"},
        {"incomplete_extension", "glute_bridge", "hip_ext_max_deg", "150", "did not fully extend the hips"},
        {"no_pause", "glute_bridge", "top_hold_s", "0.5", "had little or no pause at the top"}};
    for (String[] f : faults) {
      Map<String, Object> r = f[1].equals("squat") ? DomainTest.squat(0.1) : f[1].equals("pushup")
          ? DomainTest.rep("pushup", "elbow_min_deg", 90, "hip_offset", 0) : DomainTest.rep("glute_bridge", "hip_ext_max_deg", 170, "top_hold_s", 1.2);
      map(r.get("metrics")).put(f[2], Double.valueOf(f[3]));
      Map<String, Object> sc = d.summarize(arr(r, r, r), f[1], "s_1", null);
      equal(f[0], sc.get("dominant_fault")); equal("3 of 3 rated reps " + f[4] + " (estimated).", wording.fallback(sc).get("text"));
    }
  }
  public void test_C_SES_7_recap_suggestions_and_readiness() {
    Map<String, Object> first = obj("exercise_id", "squat", "reps_counted", 10, "rated_reps", 8, "dominant_fault", "shallow_depth", "drill_offer", obj("drill_id", "box_squat"), "duration_s", 30);
    Map<String, Object> drill = copy(first); drill.put("exercise_id", "box_squat"); drill.put("drill_offer", null);
    Map<String, Object> retest = copy(first);
    String box = "Box squat to chair", squat = "Bodyweight squat";
    for (String verdict : new String[]{"improved", "worse", "not_enough_data"}) {
      retest.put("comparison", obj("verdict", verdict));
      String expected = verdict.equals("improved") ? "Next time, start with " + box + " before " + squat + " again: the re-test improved."
          : verdict.equals("worse") ? "Next time, practise " + box + " before " + squat + ", then compare again."
          : "Next time, keep the whole body in the phone's view so the re-test can be rated.";
      equal(expected, machine.recap(obj("completed_sets", arr(first, drill, retest))).get("next_time"));
    }
    equal("Next time, try " + box + " before " + squat + ".", machine.recap(obj("completed_sets", arr(first))).get("next_time"));
    first.put("drill_offer", null);
    for (int rated : new int[]{0, 2, 3}) {
      first.put("rated_reps", rated);
      String expected = rated == 0 ? "Next time, pair the phone camera so reps can be rated." : rated == 2
          ? "Next time, keep the whole body in the phone's view so more reps can be rated." : "Next time, repeat the same plan and compare clear reps.";
      equal(expected, machine.recap(obj("completed_sets", arr(first))).get("next_time"));
    }
    Map<String, Object> s = machine.apply(machine.initial("s"), "remote-only", obj());
    s = machine.apply(s, "readiness", obj("answers", arr(true, false, false)));
    Map<String, Object> profile = d.profile(); profile.put("length_min", 20);
    s = machine.apply(s, "setup", profile); equal(15, map(s.get("profile")).get("length_min"));
    isTrue(!d.eligible("squat_jump", map(s.get("profile"))), "readiness restriction");
  }

}
