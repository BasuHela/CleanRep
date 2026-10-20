package org.cleanrep.core;

import java.util.List;
import java.util.Map;
import static org.cleanrep.core.Json.*;
import static org.cleanrep.core.testing.Check.*;

public final class DomainTest {
  private final Domain d;
  public DomainTest() throws Exception { d = new Domain(new Catalog(RepoResources.fromSystemProperty())); }
  static Map<String, Object> rep(String exercise, Object... metrics) {
    return obj("set_id", "s_1", "rep_index", 0, "exercise_id", exercise, "rated", true, "confidence", 0.9,
        "metrics", obj(metrics), "duration_s", 3.0, "client_ts_ms", 1000);
  }
  static Map<String, Object> squat(double depth) {
    return rep("squat", "knee_min_deg", 95.0, "depth_ratio", depth, "descent_s", 1.2, "trunk_lean_deg", 30.0);
  }
  public void test_C_DOM_1_catalog_references_and_public_defaults() throws Exception {
    equal(11, d.catalog.exercises.size());
    Map<String, Object> publicCatalog = d.catalog.publicView("pushup");
    equal(1, map(publicCatalog.get("exercises")).size());
    Map<String, Object> e = map(map(publicCatalog.get("exercises")).get("pushup"));
    isTrue(e.containsKey("alignment"), "pydantic default dict");
    isTrue(map(e.get("machine")).containsKey("min_depth_ratio"), "pydantic nullable default");
    Map<String, Object> bad = copy(d.catalog.data);
    list(map(map(bad.get("exercises")).get("squat")).get("metrics")).add("unknown");
    raises(IllegalArgumentException.class, () -> new Catalog(bad));
  }
  public void test_C_DOM_2_fault_boundaries_borderline_and_unrated() {
    for (String fault : d.catalog.faults.keySet()) {
      Map<String, Object> rule = map(d.catalog.faults.get(fault));
      String exercise = arr("partial_range", "hip_sag", "hip_pike").contains(fault) ? "pushup"
          : arr("incomplete_extension", "no_pause").contains(fault) ? "glute_bridge" : "squat";
      Map<String, Object> r = exercise.equals("squat") ? squat(0.1) : exercise.equals("pushup")
          ? rep(exercise, "elbow_min_deg", 90.0, "hip_offset", 0.0) : rep(exercise, "hip_ext_max_deg", 170.0, "top_hold_s", 1.2);
      for (double offset : new double[]{-0.0001, 0, 0.0001}) {
        map(r.get("metrics")).put(str(rule, "metric"), num(rule.get("threshold")) + offset);
        equal("lt".equals(rule.get("comparator")) ? offset < 0 : offset > 0, d.classify(r).get(0).contains(fault));
      }
    }
    equal(arr("shallow_depth"), d.classify(squat(-0.1)).get(1));
    Map<String, Object> r = squat(-0.5); r.put("rated", false); equal(arr(), d.classify(r).get(0));
    equal("shallow_depth", d.dominant(obj("forward_lean", 3, "shallow_depth", 3), 6));
    equal(null, d.dominant(obj("shallow_depth", 3), 7));
  }
  public void test_C_DOM_3_fallback_matrix_constraints_checkpoint() {
    for (int length : new int[]{10, 15, 20}) for (boolean floor : new boolean[]{true, false})
      for (boolean chair : new boolean[]{true, false}) for (boolean restricted : new boolean[]{true, false})
        for (boolean easy : new boolean[]{true, false}) {
          Map<String, Object> p = d.profile(); p.putAll(obj("length_min", length, "no_floor", !floor,
              "equipment", chair ? arr("chair") : arr(), "readiness_restricted", restricted,
              "go_easy", easy ? arr("knees", "wrists", "lower_back") : arr()));
          List<Object> blocks = d.fallback(p, arr());
          equal(arr(), d.validate(blocks, p, arr()));
          List<Object> done = arr(blocks.get(0));
          isTrue(same(done.get(0), d.fallback(p, done).get(0)), "immutable checkpoint");
        }
    List<Object> invalid = arr(Domain.block("squat_jump", 8, 50, null, 1), Domain.block("squat_jump", 8, 50, null, 1));
    Map<String, Object> p = d.profile(); p.put("readiness_restricted", true);
    List<Object> errors = d.validate(invalid, p, arr());
    for (String code : new String[]{"0:excluded", "0:sets_range", "0:reps_range", "0:rest_range", "time_budget", "readiness_length"}) isTrue(errors.contains(code), code);
  }
  public void test_C_DOM_4_selection_comparison_and_summary() {
    Map<String, Object> p = d.profile(); p.put("equipment", arr("chair"));
    equal("box_squat", d.select("shallow_depth", "squat", p).get("drill_id"));
    equal("drill_unavailable", d.select("shallow_depth", "squat", d.profile()).get("reason"));
    Map<String, Object> a = d.summarize(arr(squat(-0.3), squat(-0.3), squat(-0.3)), "squat", "s_1", null);
    Map<String, Object> b = d.summarize(arr(squat(0.1), squat(0.1), squat(0.1)), "squat", "s_2", null);
    equal("improved", d.compare(a, b, "shallow_depth").get("verdict"));
    b.put("rated_reps", 2); equal("not_enough_data", d.compare(a, b, "shallow_depth").get("verdict"));
    equal(arr("fault", "fault", "fault"), a.get("rep_marks"));
  }
  public void test_C_DOM_5_server_rep_and_hold_validation() {
    d.validateRep(squat(-0.65));
    raises(IllegalArgumentException.class, () -> d.validateRep(squat(-0.65001)));
    Map<String, Object> bad = squat(0.1); map(bad.get("metrics")).put("knee_min_deg", 141);
    raises(IllegalArgumentException.class, () -> d.validateRep(bad));
    map(bad.get("metrics")).remove("knee_min_deg"); raises(IllegalArgumentException.class, () -> d.validateRep(bad));
    raises(IllegalArgumentException.class, () -> d.validateHold(obj("exercise_id", "plank_hold", "aligned_s", 21, "total_s", 20)));
  }
}
