package org.cleanrep.core;

import java.io.IOException;
import java.util.Map;
import static org.cleanrep.core.Json.*;

/** Loaded once from domain/catalog.json, with the same public defaults and reference checks as catalog.py. */
public final class Catalog {
  final Map<String, Object> data;
  final Map<String, Object> exercises;
  final Map<String, Object> faults;
  final Map<String, Object> metrics;
  final Map<String, Object> checks;
  public Catalog(Resources resources) throws IOException { this(map(Json.parse(resources.read("domain/catalog.json")))); }
  Catalog(Map<String, Object> raw) {
    data = copy(raw);
    fields(data, "metrics", "faults", "exercises", "exclusions", "checks", "smoothing");
    exercises = map(data.get("exercises")); faults = map(data.get("faults"));
    metrics = map(data.get("metrics")); checks = map(data.get("checks"));
    double visibility = num(checks.get("calibration_visibility"));
    require(visibility >= 0 && visibility <= 1);
    for (Object value : metrics.values()) {
      Map<String, Object> m = map(value); fields(m, "minimum", "maximum");
      m.put("minimum", num(m.get("minimum"))); m.put("maximum", num(m.get("maximum")));
    }
    for (Object value : faults.values()) {
      Map<String, Object> f = map(value);
      fields(f, "metric", "comparator", "threshold", "priority", "borderline_upper", "drills");
      f.putIfAbsent("borderline_upper", null);
      require(metrics.containsKey(f.get("metric")) && arr("lt", "gt").contains(f.get("comparator")) && integer(f.get("priority")) >= 1);
      f.put("threshold", num(f.get("threshold")));
      if (f.get("borderline_upper") != null) f.put("borderline_upper", num(f.get("borderline_upper")));
    }
    for (Object value : exercises.values()) {
      Map<String, Object> e = map(value);
      fields(e, "name", "cue", "family", "metrics", "faults", "requires", "loads", "rep_seconds", "machine", "rated", "drill", "alignment");
      e.putIfAbsent("machine", null); e.putIfAbsent("rated", true); e.putIfAbsent("drill", false); e.putIfAbsent("alignment", obj());
      require(arr("squat", "pushup", "bridge", "hold").contains(e.get("family")) && num(e.get("rep_seconds")) > 0);
      e.put("rep_seconds", num(e.get("rep_seconds")));
      for (Object m : list(e.get("metrics"))) require(metrics.containsKey(m));
      for (Object f : list(e.get("faults"))) require(faults.containsKey(f) && list(e.get("metrics")).contains(map(faults.get(f)).get("metric")));
      for (Object r : list(e.get("requires"))) require(arr("floor", "chair", "counter", "chair_or_counter", "plank_support").contains(r));
      for (Object load : list(e.get("loads"))) require(arr("knees", "hips", "wrists", "shoulders", "lower_back", "ankles").contains(load));
      if (e.get("machine") != null) {
        Map<String, Object> m = map(e.get("machine"));
        fields(m, "top", "enter", "minimum", "reversal", "min_s", "max_s", "min_depth_ratio");
        m.putIfAbsent("reversal", 5.0); m.putIfAbsent("min_s", 0.6); m.putIfAbsent("max_s", 8.0); m.putIfAbsent("min_depth_ratio", null);
        for (String k : m.keySet()) if (m.get(k) != null) m.put(k, num(m.get(k)));
        if (m.get("min_depth_ratio") != null) require(num(m.get("min_depth_ratio")) >= -2 && num(m.get("min_depth_ratio")) <= 2);
      }
      if ("squat".equals(e.get("family"))) {
        require(e.get("machine") != null && list(e.get("metrics")).containsAll(arr("depth_ratio", "knee_min_deg")));
        Map<String, Object> m = map(e.get("machine"));
        require(m.get("min_depth_ratio") != null && faults.get("shallow_depth") != null
            && num(m.get("min_depth_ratio")) < num(map(faults.get("shallow_depth")).get("threshold")));
      }
    }
    for (Object value : faults.values()) for (Object drill : list(map(value).get("drills")))
      require(exercises.containsKey(drill) && yes(map(exercises.get(drill)).get("drill")));
    for (Object names : map(data.get("exclusions")).values()) for (Object name : list(names)) require(exercises.containsKey(name));
  }
  private static void fields(Map<String, Object> v, String... allowed) {
    for (String k : v.keySet()) require(java.util.Arrays.asList(allowed).contains(k));
  }
  private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("invalid catalog"); }
  Map<String, Object> exercise(String name) {
    Object value = exercises.get(name);
    if (value == null) throw new IllegalArgumentException("unknown exercise");
    return map(value);
  }
  public Map<String, Object> publicView(String exercise) {
    Map<String, Object> out = copy(data);
    if (exercise != null) out.put("exercises", obj(exercise, copy(exercise(exercise))));
    return out;
  }
}
