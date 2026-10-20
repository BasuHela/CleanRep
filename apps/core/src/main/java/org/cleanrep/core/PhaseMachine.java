package org.cleanrep.core;

import java.util.List;
import java.util.Map;
import static org.cleanrep.core.Json.*;

/** Pure session aggregate, ported from packages/domain/session.py (MIT). */
public final class PhaseMachine {
  private final Domain domain;
  public PhaseMachine(Domain domain) { this.domain = domain; }
  public static final class PhaseError extends RuntimeException {
    private static final long serialVersionUID = 1L;
    PhaseError(String message) { super(message); }
  }
  public Map<String, Object> initial(String id) {
    return obj("session_id", id, "phase", "LOBBY", "paused", false, "sensor_mode", "remote_only", "profile", domain.profile(),
        "plan", null, "active_set", null, "completed_sets", arr(), "summary", null, "explanation", null, "recap", null,
        "block_index", 0, "set_index", 0, "retest", null);
  }
  private void require(Map<String, Object> s, String... phases) {
    if (!java.util.Arrays.asList(phases).contains(s.get("phase"))) throw new PhaseError("phase_invalid");
  }
  List<Object> blocks(Map<String, Object> s) { return list(map(s.get("plan")).get("blocks")); }
  Map<String, Object> block(Map<String, Object> s) { return map(blocks(s).get(integer(s.get("block_index")))); }
  private List<Object> models(List<Object> blocks) {
    List<Object> models = copy(blocks); for (Object b : models) map(b).remove("block_id"); return models;
  }
  public List<Object> completedBlocks(Map<String, Object> s) {
    return s.get("plan") == null ? arr() : models(blocks(s).subList(0, integer(s.get("block_index"))));
  }
  public Map<String, Object> publish(Map<String, Object> state, List<Object> candidate, String source, String reason) {
    require(state, "PLANNING", "ADAPTING");
    Map<String, Object> s = copy(state); boolean adapting = "ADAPTING".equals(s.get("phase"));
    List<Object> done = adapting ? completedBlocks(s) : arr();
    if (!domain.validate(candidate, map(s.get("profile")), done).isEmpty()) throw new IllegalArgumentException("invalid plan");
    List<Object> blocks = copy(candidate);
    for (int i = 0; i < blocks.size(); i++) map(blocks.get(i)).put("block_id", i < done.size() ? map(blocks(s).get(i)).get("block_id") : "b_" + i);
    s.putAll(obj("plan", obj("blocks", blocks, "source", source, "reason", reason, "seconds", domain.seconds(candidate)),
        "phase", adapting ? "DEMO" : "READY", "active_set", null, "paused", false));
    if (adapting && blocks.size() <= done.size()) s.putAll(obj("phase", "RECAP", "recap", recap(s)));
    return s;
  }
  public Map<String, Object> drillPlan(Map<String, Object> s, Map<String, Object> offer) {
    int index = integer(s.get("block_index")) + 1, suffix = list(s.get("completed_sets")).size();
    List<Object> frozen = copy(blocks(s).subList(0, index));
    map(frozen.get(frozen.size() - 1)).put("sets", integer(s.get("set_index")) + 1);
    List<Object> inserted = copy(list(offer.get("blocks")));
    for (int i = 0; i < inserted.size(); i++) map(inserted.get(i)).put("block_id", "d_" + suffix + "_" + i);
    frozen.addAll(inserted);
    Map<String, Object> profile = map(s.get("profile")); List<Object> fixed = models(frozen);
    List<Object> remaining = copy(blocks(s).subList(index, blocks(s).size()));
    for (int keep = remaining.size(); keep >= 0; keep--) {
      List<Object> proposed = copy(frozen); proposed.addAll(remaining.subList(0, keep));
      if (domain.validate(models(proposed), profile, fixed).isEmpty())
        return obj("blocks", proposed, "source", map(s.get("plan")).get("source"), "reason", "targeted_drill", "seconds", domain.seconds(proposed));
    }
    List<Object> candidate;
    try { candidate = domain.fallback(profile, fixed); } catch (IllegalArgumentException e) { return null; }
    List<Object> result = copy(frozen);
    for (int i = frozen.size(); i < candidate.size(); i++) {
      Map<String, Object> b = copy(map(candidate.get(i))); b.put("block_id", "after_" + suffix + "_" + (i - frozen.size())); result.add(b);
    }
    return obj("blocks", result, "source", "fixture".equals(map(s.get("plan")).get("source")) ? "fixture" : "fallback", "reason", "targeted_drill", "seconds", domain.seconds(candidate));
  }
  private void advanceBlock(Map<String, Object> s) {
    int set = integer(s.get("set_index")) + 1, index = integer(s.get("block_index"));
    if (set >= integer(block(s).get("sets"))) { set = 0; index++; }
    s.put("set_index", set); s.put("block_index", index);
    if (index >= blocks(s).size()) s.putAll(obj("phase", "RECAP", "recap", recap(s)));
    else s.put("phase", "DEMO");
  }
  public Map<String, Object> apply(Map<String, Object> state, String action, Map<String, Object> p) {
    Map<String, Object> s = copy(state), profile = map(s.get("profile"));
    if (action.equals("pause") || action.equals("resume")) {
      if (arr("LOBBY", "ENDED").contains(s.get("phase"))) throw new PhaseError("phase_invalid");
      s.put("paused", action.equals("pause")); return s;
    }
    if (action.equals("remote-only")) {
      s.put("sensor_mode", "remote_only"); if ("LOBBY".equals(s.get("phase"))) s.put("phase", "READINESS"); return s;
    }
    if (yes(s.get("paused")) && !arr("adapt", "delete").contains(action)) throw new PhaseError("paused");
    switch (action) {
      case "pair": require(s, "LOBBY"); s.putAll(obj("sensor_mode", "paired", "phase", "READINESS")); break;
      case "readiness":
        require(s, "READINESS"); profile.put("readiness_restricted", list(p.get("answers")).contains(true)); s.put("phase", "SETUP"); break;
      case "setup":
        require(s, "SETUP", "READY");
        for (String k : new String[]{"equipment", "quiet", "no_floor", "length_min", "go_easy"}) profile.put(k, copy(p.get(k)));
        if (yes(profile.get("readiness_restricted"))) profile.put("length_min", Math.min(15, integer(profile.get("length_min"))));
        s.put("phase", "SETUP"); break;
      case "plan": require(s, "SETUP", "READY"); s.put("phase", "PLANNING"); break;
      case "advance":
        require(s, "READY", "DEMO", "SET_SUMMARY", "DRILL_OFFER", "RECAP");
        if ("READY".equals(s.get("phase"))) s.put("phase", "DEMO");
        else if ("RECAP".equals(s.get("phase"))) s.put("phase", "ENDED");
        else {
          if ("DEMO".equals(s.get("phase"))) s.put("set_index", integer(block(s).get("sets")) - 1);
          advanceBlock(s);
        }
        break;
      case "sets/start": {
        require(s, "DEMO"); if (list(s.get("completed_sets")).size() >= 60) throw new PhaseError("set_quota");
        Map<String, Object> b = block(s);
        if (!p.get("block_id").equals(b.get("block_id"))) throw new PhaseError("block_invalid");
        boolean hold = b.get("hold_s") != null && num(b.get("hold_s")) != 0;
        s.putAll(obj("phase", "IN_SET", "summary", null, "explanation", null, "active_set",
            obj("set_id", p.get("set_id"), "exercise_id", b.get("exercise_id"), "target", hold ? b.get("hold_s") : b.get("reps"), "kind", hold ? "hold" : "reps", "reps", arr(), "hold", null)));
        break;
      }
      case "sets/end": endSet(s, p); break;
      case "drill": {
        require(s, "SET_SUMMARY", "DRILL_OFFER");
        Map<String, Object> summary = map(s.get("summary")); Object offer = summary.get("drill_offer");
        if ("accept".equals(p.get("decision")) && offer != null && map(offer).get("drill_id") != null) {
          Map<String, Object> revised = drillPlan(s, map(offer)); if (revised == null) throw new PhaseError("drill_unavailable");
          s.put("plan", revised);
          s.put("retest", obj("block_id", map(blocks(s).get(integer(s.get("block_index")) + 2)).get("block_id"), "before", copy(summary), "fault", summary.get("dominant_fault")));
        }
        advanceBlock(s); break;
      }
      case "adapt":
        require(s, "DEMO", "IN_SET", "SET_SUMMARY", "DRILL_OFFER");
        for (String k : new String[]{"quiet", "no_floor"}) if (p.get(k) != null) profile.put(k, p.get(k));
        if (p.get("discomfort") != null && !list(profile.get("go_easy")).contains(p.get("discomfort"))) list(profile.get("go_easy")).add(p.get("discomfort"));
        s.putAll(obj("phase", "ADAPTING", "active_set", null, "paused", false, "retest", null)); break;
      case "delete": s.put("phase", "ENDED"); break;
      default: throw new PhaseError("unknown_action");
    }
    return s;
  }
  private void endSet(Map<String, Object> s, Map<String, Object> p) {
    require(s, "IN_SET"); Map<String, Object> active = map(s.get("active_set"));
    if (!active.get("set_id").equals(p.get("set_id"))) throw new PhaseError("set_not_active");
    Object report = p.get("self_report");
    if ("remote_only".equals(s.get("sensor_mode")) && report == null) throw new PhaseError("self_report_required");
    if ("paired".equals(s.get("sensor_mode")) && report != null) throw new PhaseError("self_report_remote_only");
    List<Object> reps = list(active.get("reps"));
    for (Object rep : reps) domain.validateRep(map(rep));
    Map<String, Object> summary = domain.summarize(reps, str(active, "exercise_id"), str(active, "set_id"), report == null ? null : integer(map(report).get("reps")));
    if (report != null) {
      List<Object> facts = arr(); Domain.fact(facts, "reps_counted", map(report).get("reps")); Domain.fact(facts, "rated_reps", 0);
      summary.putAll(obj("rated_reps", 0, "faults", obj(), "borderline", obj(), "dominant_fault", null, "effort", map(report).get("effort"), "facts", facts));
    }
    double duration = 0;
    for (Object rep : reps) duration += num(map(rep).get("duration_s"));
    if (active.get("hold") != null) {
      Map<String, Object> hold = map(active.get("hold")); summary.put("hold", hold);
      Domain.fact(list(summary.get("facts")), "hold_seconds", (int) (num(hold.get("total_s")) + 0.5));
      Domain.fact(list(summary.get("facts")), "aligned_seconds", (int) (num(hold.get("aligned_s")) + 0.5));
      duration = num(hold.get("total_s"));
    }
    if (report != null) duration = num(map(report).get("reps")) * num(domain.catalog.exercise(str(active, "exercise_id")).get("rep_seconds"));
    summary.put("duration_s", duration);
    Map<String, Object> offer = domain.select(str(summary, "dominant_fault"), str(active, "exercise_id"), map(s.get("profile")));
    summary.put("drill_offer", offer);
    Object retest = s.get("retest");
    if (retest != null && block(s).get("block_id").equals(map(retest).get("block_id"))) {
      summary.put("comparison", domain.compare(map(map(retest).get("before")), summary, str(map(retest), "fault")));
      summary.put("drill_offer", null); s.put("retest", null);
    }
    s.putAll(obj("phase", "SET_SUMMARY", "summary", summary, "active_set", null));
    List<Object> completed = list(s.get("completed_sets")); completed.add(copy(summary));
    if (offer != null && offer.get("drill_id") != null && drillPlan(s, offer) == null) {
      summary.put("drill_offer", obj("drill_id", null, "reason", "time_budget", "blocks", arr())); completed.set(completed.size() - 1, copy(summary));
    }
    if (report != null && !"ok".equals(map(report).get("effort"))) {
      List<Object> proposed = copy(blocks(s));
      for (int i = integer(s.get("block_index")) + 1; i < proposed.size(); i++) {
        Map<String, Object> b = map(proposed.get(i));
        if (b.get("reps") != null) b.put("reps", Math.max(5, Math.min(15, integer(b.get("reps")) + ("easy".equals(map(report).get("effort")) ? 2 : -2))));
      }
      if (domain.validate(models(proposed), map(s.get("profile")), completedBlocks(s)).isEmpty())
        map(s.get("plan")).putAll(obj("blocks", proposed, "seconds", domain.seconds(proposed), "reason", "self_report_difficulty"));
    }
  }
  public Map<String, Object> recap(Map<String, Object> s) {
    List<Object> summaries = list(s.get("completed_sets")), comparisons = arr(); int count = 0, rated = 0; double duration = 0;
    for (Object v : summaries) {
      Map<String, Object> summary = map(v); count += integer(summary.get("reps_counted")); rated += integer(summary.get("rated_reps"));
      duration += num(summary.getOrDefault("duration_s", 0)); if (summary.get("comparison") != null) comparisons.add(summary.get("comparison"));
    }
    return obj("sets_done", summaries.size(), "reps_counted", count, "rated_reps", rated, "comparisons", comparisons, "estimated_movement_min", Domain.round(duration / 60, 1), "next_time", nextTime(summaries));
  }
  private String name(Map<String, Object> summary) { return str(domain.catalog.exercise(str(summary, "exercise_id")), "name"); }
  private String nextTime(List<Object> summaries) {
    String view = "Next time, keep the whole body in the phone's view so";
    for (int i = summaries.size() - 1; i > 0; i--) {
      Map<String, Object> s = map(summaries.get(i));
      if (s.get("comparison") != null) {
        String drill = name(map(summaries.get(i - 1))), retest = name(s), verdict = str(map(s.get("comparison")), "verdict");
        if (verdict.equals("improved")) return "Next time, start with " + drill + " before " + retest + " again: the re-test improved.";
        if (verdict.equals("not_enough_data")) return view + " the re-test can be rated.";
        return "Next time, practise " + drill + " before " + retest + ", then compare again.";
      }
    }
    int best = 0;
    for (int i = summaries.size() - 1; i >= 0; i--) {
      Map<String, Object> s = map(summaries.get(i)); Object offer = s.get("drill_offer");
      if (s.get("dominant_fault") != null && offer != null && map(offer).get("drill_id") != null)
        return "Next time, try " + domain.catalog.exercise(str(map(offer), "drill_id")).get("name") + " before " + name(s) + ".";
      best = Math.max(best, integer(s.get("rated_reps")));
    }
    if (summaries.isEmpty()) return "Next time, try the same movement and compare clear reps.";
    if (best >= 3) return "Next time, repeat the same plan and compare clear reps.";
    if (best == 0) return "Next time, pair the phone camera so reps can be rated.";
    return view + " more reps can be rated.";
  }
}
