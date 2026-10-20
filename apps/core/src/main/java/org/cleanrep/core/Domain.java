package org.cleanrep.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.cleanrep.core.Json.*;

/** Pure faults, selection and authored planning. Adapted from CleanRep/RoomQuest/ReplayLab (MIT). */
public final class Domain {
  final Catalog catalog;
  public Domain(Catalog catalog) { this.catalog = catalog; }
  public Map<String, Object> profile() {
    return obj("equipment", arr(), "quiet", true, "no_floor", false, "length_min", 15, "go_easy", arr(), "readiness_restricted", false);
  }
  public void validateRep(Map<String, Object> r) {
    Map<String, Object> e = catalog.exercise(str(r, "exercise_id")), metrics = map(r.get("metrics"));
    if ("hold".equals(e.get("family")) || !new HashSet<>(list(e.get("metrics"))).equals(metrics.keySet())) throw new IllegalArgumentException("invalid_metric");
    for (String k : metrics.keySet()) {
      Map<String, Object> limits = map(catalog.metrics.get(k)); double v = num(metrics.get(k));
      if (!(v >= num(limits.get("minimum")) && v <= num(limits.get("maximum")))) throw new IllegalArgumentException("invalid_metric");
    }
    if ("squat".equals(e.get("family"))) {
      Map<String, Object> machine = map(e.get("machine"));
      if (num(metrics.get("knee_min_deg")) > num(machine.get("minimum")) || num(metrics.get("depth_ratio")) < num(machine.get("min_depth_ratio")))
        throw new IllegalArgumentException("insufficient_range");
    }
  }
  public void validateHold(Map<String, Object> h) {
    if (!"hold".equals(catalog.exercise(str(h, "exercise_id")).get("family")) || num(h.get("aligned_s")) > num(h.get("total_s"))) throw new IllegalArgumentException("invalid_hold");
  }
  public List<List<Object>> classify(Map<String, Object> r) {
    List<Object> faults = arr(), borderline = arr();
    Map<String, Object> e = catalog.exercise(str(r, "exercise_id"));
    if (yes(r.get("rated")) && yes(e.get("rated"))) for (Object name : list(e.get("faults"))) {
      Map<String, Object> rule = map(catalog.faults.get(name));
      double value = num(map(r.get("metrics")).get(rule.get("metric"))), threshold = num(rule.get("threshold"));
      if ("lt".equals(rule.get("comparator")) ? value < threshold : value > threshold) faults.add(name);
      else if (rule.get("borderline_upper") != null && threshold <= value && value < num(rule.get("borderline_upper"))) borderline.add(name);
    }
    List<List<Object>> result = new ArrayList<>(); result.add(faults); result.add(borderline); return result;
  }
  public String repMark(Map<String, Object> r) {
    return !yes(r.get("rated")) || !yes(catalog.exercise(str(r, "exercise_id")).get("rated")) ? "unrated" : classify(r).get(0).isEmpty() ? "clean" : "fault";
  }
  public String dominant(Map<String, Object> counts, int rated) {
    if (rated < 3) return null;
    List<String> candidates = new ArrayList<>();
    for (String f : counts.keySet()) if (integer(counts.get(f)) >= 3 && num(counts.get(f)) / rated >= 0.5) candidates.add(f);
    Collections.sort(candidates, (a, b) -> {
      int order = Integer.compare(integer(counts.get(b)), integer(counts.get(a)));
      if (order == 0) order = Integer.compare(integer(map(catalog.faults.get(a)).get("priority")), integer(map(catalog.faults.get(b)).get("priority")));
      return order == 0 ? a.compareTo(b) : order;
    });
    return candidates.isEmpty() ? null : candidates.get(0);
  }
  public Map<String, Object> summarize(List<Object> reps, String exercise, String set, Integer selfCount) {
    Map<String, Object> e = catalog.exercise(exercise), counts = obj(), borderline = obj();
    List<Object> rated = arr(), marks = arr(), facts = arr();
    for (Object fault : list(e.get("faults"))) counts.put((String) fault, 0);
    for (Object value : reps) {
      Map<String, Object> r = map(value);
      if (yes(r.get("rated")) && yes(e.get("rated"))) rated.add(r);
      List<List<Object>> classified = classify(r);
      for (Object f : classified.get(0)) counts.put((String) f, integer(counts.getOrDefault(f, 0)) + 1);
      for (Object f : classified.get(1)) borderline.put((String) f, integer(borderline.getOrDefault(f, 0)) + 1);
      marks.add(repMark(r));
    }
    int count = selfCount == null ? reps.size() : selfCount;
    fact(facts, "reps_counted", count); fact(facts, "rated_reps", rated.size());
    for (String f : counts.keySet()) fact(facts, f, counts.get(f));
    for (Object metric : list(e.get("metrics"))) if (!rated.isEmpty()) {
      List<Double> values = new ArrayList<>();
      for (Object r : rated) values.add(num(map(map(r).get("metrics")).get(metric)));
      Collections.sort(values); int n = values.size();
      double median = n % 2 == 1 ? values.get(n / 2) : (values.get(n / 2 - 1) + values.get(n / 2)) / 2;
      fact(facts, "median_" + metric, round(median, 2));
    }
    return obj("set_id", set, "exercise_id", exercise, "reps_counted", count, "rated_reps", rated.size(), "faults", counts,
        "borderline", borderline, "dominant_fault", dominant(counts, rated.size()), "facts", facts, "drill_offer", null, "comparison", null, "rep_marks", marks);
  }
  static void fact(List<Object> facts, String name, Object value) { facts.add(obj("id", "F" + (facts.size() + 1), "name", name, "value", value)); }
  static double round(double n, int places) { return new BigDecimal(n).setScale(places, RoundingMode.HALF_EVEN).doubleValue(); }

  public boolean eligible(String id, Map<String, Object> p) {
    if (!catalog.exercises.containsKey(id)) return false;
    Map<String, Object> e = catalog.exercise(id);
    List<Object> flags = copy(list(p.get("go_easy"))), equipment = list(p.get("equipment"));
    if (yes(p.get("quiet"))) flags.add("quiet"); if (yes(p.get("no_floor"))) flags.add("no_floor"); if (yes(p.get("readiness_restricted"))) flags.add("readiness");
    for (Object f : flags) if (list(map(catalog.data.get("exclusions")).getOrDefault(f, arr())).contains(id)) return false;
    boolean support = equipment.contains("chair") || equipment.contains("counter");
    for (Object requirement : list(e.get("requires"))) {
      if (requirement.equals("floor") && yes(p.get("no_floor"))) return false;
      if (arr("chair", "counter").contains(requirement) && !equipment.contains(requirement)) return false;
      if (requirement.equals("chair_or_counter") && !support) return false;
      if (requirement.equals("plank_support") && yes(p.get("no_floor")) && (!support || flags.contains("wrists"))) return false;
    }
    return true;
  }
  public static Map<String, Object> block(String exercise, int sets, Integer reps, Integer hold, int rest) {
    return obj("exercise_id", exercise, "sets", sets, "reps", reps, "hold_s", hold, "rest_s", rest);
  }
  public double seconds(List<Object> blocks) {
    Set<String> seen = new HashSet<>(); double total = 0;
    for (Object value : blocks) {
      Map<String, Object> b = map(value); String id = str(b, "exercise_id");
      if (!catalog.exercises.containsKey(id)) continue;
      Map<String, Object> e = catalog.exercise(id);
      double dose = "hold".equals(e.get("family")) ? (b.get("hold_s") == null ? 0 : num(b.get("hold_s")))
          : (b.get("reps") == null ? 0 : num(b.get("reps"))) * num(e.get("rep_seconds"));
      total += num(b.get("sets")) * dose;
      total += num(b.get("sets")) * num(b.get("rest_s"));
      if (seen.add(id)) total += 30;
    }
    return total;
  }
  double target(Map<String, Object> p) { return (yes(p.get("readiness_restricted")) ? Math.min(15, num(p.get("length_min"))) : num(p.get("length_min"))) * 60; }
  private boolean capped(String id, Map<String, Object> p) {
    List<Object> areas = list(p.get("go_easy"));
    return areas.contains("knees") && "squat".equals(catalog.exercise(id).get("family")) || areas.contains("lower_back") && id.equals("glute_bridge");
  }
  public List<Object> validate(List<Object> blocks, Map<String, Object> p, List<Object> completed) {
    List<Object> errors = arr();
    if (blocks.size() < completed.size() || !same(blocks.subList(0, Math.min(completed.size(), blocks.size())), completed)) errors.add("completed_changed");
    for (int i = 0; i < blocks.size(); i++) {
      Map<String, Object> b = map(blocks.get(i)); String id = str(b, "exercise_id");
      if (!catalog.exercises.containsKey(id)) { errors.add(i + ":unknown_exercise"); continue; }
      if (i >= completed.size() && !eligible(id, p)) errors.add(i + ":excluded");
      if (!between(b.get("sets"), 1, 4)) errors.add(i + ":sets_range");
      if (!between(b.get("rest_s"), 30, 90)) errors.add(i + ":rest_range");
      if ("hold".equals(catalog.exercise(id).get("family"))) {
        if (b.get("reps") != null || !between(b.get("hold_s"), 10, 40)) errors.add(i + ":hold_range");
      } else if (b.get("hold_s") != null || !between(b.get("reps"), 5, 15)) errors.add(i + ":reps_range");
      if (i >= completed.size() && num(b.get("sets")) > 2 && capped(id, p)) errors.add(i + ":go_easy_sets");
    }
    double total = seconds(blocks), target = target(p);
    if (!(0.8 * target <= total && total <= 1.1 * target)) errors.add("time_budget");
    if (yes(p.get("readiness_restricted")) && total > 900) errors.add("readiness_length");
    return errors;
  }
  private boolean between(Object n, int min, int max) { return n instanceof Number && num(n) >= min && num(n) <= max; }
  public List<Object> fallback(Map<String, Object> p, List<Object> completed) {
    List<Object> blocks = copy(completed); double target = target(p);
    List<String> choices = new ArrayList<>();
    for (String id : new String[]{"squat", "incline_pushup", "glute_bridge", "squat_jump", "counterbalance_squat", "tempo_squat"}) if (eligible(id, p)) choices.add(id);
    if (choices.isEmpty()) throw new IllegalArgumentException("No eligible exercise");
    int index = 0;
    while (seconds(blocks) < 0.8 * target) blocks.add(block(choices.get(index++ % choices.size()), 2, 10, null, 60));
    while (seconds(blocks) > Math.min(1.1 * target, yes(p.get("readiness_restricted")) ? 900 : Double.POSITIVE_INFINITY) && blocks.size() > completed.size()) {
      Map<String, Object> last = map(blocks.get(blocks.size() - 1));
      if (integer(last.get("sets")) > 1) last.put("sets", integer(last.get("sets")) - 1);
      else if (integer(last.get("rest_s")) > 30) last.put("rest_s", integer(last.get("rest_s")) - 5);
      else if (last.get("reps") != null && integer(last.get("reps")) > 5) last.put("reps", integer(last.get("reps")) - 1);
      else blocks.remove(blocks.size() - 1);
    }
    if (!validate(blocks, p, completed).isEmpty()) throw new IllegalArgumentException("No valid remaining time budget");
    return blocks;
  }
  public Map<String, Object> select(String fault, String exercise, Map<String, Object> p) {
    if (fault == null) return null;
    for (Object name : list(map(catalog.faults.get(fault)).get("drills"))) if (eligible((String) name, p)) {
      boolean hold = "hold".equals(catalog.exercise((String) name).get("family"));
      return obj("drill_id", name, "retest_exercise_id", exercise, "blocks", arr(block((String) name, 1, hold ? null : 10, hold ? 20 : null, 30), block(exercise, 1, 10, null, 30)));
    }
    return obj("reason", "drill_unavailable", "drill_id", null, "retest_exercise_id", exercise, "blocks", arr());
  }
  public Map<String, Object> compare(Map<String, Object> before, Map<String, Object> after, String fault) {
    int b = integer(before.get("rated_reps")), a = integer(after.get("rated_reps"));
    int bc = integer(map(before.get("faults")).getOrDefault(fault, 0)), ac = integer(map(after.get("faults")).getOrDefault(fault, 0));
    Double br = b == 0 ? null : (double) bc / b, ar = a == 0 ? null : (double) ac / a;
    boolean enough = b >= 3 && a >= 3;
    String verdict = !enough ? "not_enough_data" : br - ar >= 0.2 - 1e-9 ? "improved" : br - ar <= -0.2 + 1e-9 ? "worse" : "same";
    return obj("before_count", bc, "before_rated", b, "after_count", ac, "after_rated", a, "before_rate", br, "after_rate", ar, "verdict", verdict, "enough_data", enough);
  }
}
