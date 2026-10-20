package org.cleanrep.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.cleanrep.core.Json.*;

/** Fixture templates and grounding checks from explain_rules.py; ReplayLab validate-or-template (MIT). */
public final class Wording {
  private final Catalog catalog;
  private final Schemas schemas;
  private static final String NUMBER = "(?<![\\w.])(-?\\d+(?:\\.\\d+)?)";
  private static final String[][] FAULTS = {
      {"shallow_depth", "were above about-parallel depth", "shallow|depth|parallel"},
      {"forward_lean", "leaned the trunk far forward", "lean|leaned|leaning|forward"},
      {"rushed_descent", "went down quickly", "rushed|quick|quickly|fast|descent"},
      {"partial_range", "had a partial range at the elbows", "partial|range|elbow|elbows"},
      {"hip_sag", "let the hips sag", "sag|sagged|sagging"},
      {"hip_pike", "piked the hips up", "pike|piked|piking"},
      {"incomplete_extension", "did not fully extend the hips", "extend|extended|extension"},
      {"no_pause", "had little or no pause at the top", "pause|paused|pausing"}
  };
  public Wording(Catalog catalog, Schemas schemas) { this.catalog = catalog; this.schemas = schemas; }
  static String fold(String s) {
    return s.toLowerCase(Locale.ROOT).replace("ß", "ss").replace("ſ", "s").replace("ﬀ", "ff")
        .replace("ﬁ", "fi").replace("ﬂ", "fl").replace("ﬃ", "ffi").replace("ﬄ", "ffl").replace("ﬅ", "st").replace("ﬆ", "st");
  }
  /** Python's re and pydantic treat \d, \w, \s and \b as Unicode. The JVM needs UNICODE_CHARACTER_CLASS for that;
   * Android's ICU-based Pattern is Unicode-aware already and refuses the flag ("Unsupported flags"), so this
   * falls back to a plain compile there. A syntax error fails both ways. */
  static Pattern regex(String regex) {
    try { return Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS); }
    catch (IllegalArgumentException e) { return Pattern.compile(regex); }
  }
  private static Matcher matches(String regex, String text) { return regex(regex).matcher(text); }
  private static void fail(String code) { throw new IllegalArgumentException(code); }
  public Map<String, Object> validate(Map<String, Object> raw, Map<String, Object> scope) {
    Map<String, Object> out = schemas.validate("Explanation", raw), facts = obj(), citedByName = obj();
    for (Object f : list(scope.get("facts"))) facts.put(str(map(f), "id"), f);
    List<Object> cites = list(out.get("cited_fact_ids"));
    Set<Double> values = new HashSet<>();
    for (Object id : cites) {
      if (!facts.containsKey(id)) fail("uncited_fact");
      Map<String, Object> fact = map(facts.get(id));
      citedByName.put(str(fact, "name"), fact.get("value"));
      for (int p = 0; p < 3; p++) values.add(Domain.round(num(fact.get("value")), p));
    }
    String text = fold(str(out, "text"));
    for (String word : new String[]{"injury", "posture correction", "diagnosis", "safe", "perfect form", "cure", "medical"})
      if (matches("\\b" + Pattern.quote(word) + "\\b", text).find()) fail("banned_word");
    if (matches("\\b(you|your|must|should|ignore|instructions|system|prompt|treatment|pain|diagnose)\\b", text).find()) fail("unsupported_instruction");
    Matcher numbers = matches("(?<![\\w])-?\\d+(?:\\.\\d+)?", text);
    while (numbers.find()) if (!values.contains(Double.valueOf(numbers.group()))) fail("invented_number");
    checkPairs(text, citedByName);
    String verdict = scope.get("comparison") == null ? null : str(map(scope.get("comparison")), "verdict");
    for (String[] words : new String[][]{{"improved", "improved|improvement|better"}, {"worse", "worse"}, {"same", "no change"}})
      if (!words[0].equals(verdict) && matches("\\b(?:" + words[1] + ")\\b", text).find()) fail("wrong_verdict");
    List<String[]> labels = new ArrayList<>();
    for (String id : catalog.exercises.keySet()) {
      labels.add(new String[]{fold(str(catalog.exercise(id), "name")), id});
      labels.add(new String[]{id.replace('_', ' '), id});
    }
    Collections.sort(labels, (a, b) -> Integer.compare(b[0].length(), a[0].length()));
    String remaining = text;
    for (String[] label : labels) if (remaining.contains(label[0])) {
      if (!label[1].equals(scope.get("exercise_id")) && !label[1].equals(scope.get("drill_id"))) fail("wrong_exercise");
      remaining = remaining.replace(label[0], "");
    }
    Matcher ids = matches("\\bF\\d+\\b", str(out, "text"));
    while (ids.find()) if (!cites.contains(ids.group())) fail("uncited_fact");
    return out;
  }
  private void checkPairs(String text, Map<String, Object> facts) {
    String[][] singles = {{" reps counted", "reps_counted"}, {" rated\\b", "rated_reps"}, {" seconds held", "hold_seconds"}};
    for (String[] single : singles) {
      Matcher m = matches(NUMBER + single[0], text);
      while (m.find()) if (!equalFact(m.group(1), facts, arr(single[1]))) fail("mismatched_number");
    }
    Matcher held = matches(NUMBER + " of " + NUMBER + " seconds", text);
    while (held.find()) if (!equalFact(held.group(1), facts, arr("aligned_seconds")) || !equalFact(held.group(2), facts, arr("hold_seconds"))) fail("mismatched_number");
    Matcher reps = matches(NUMBER + " of " + NUMBER + " (?:rated )?reps\\b([^.;]*)", text);
    while (reps.find()) {
      List<Object> named = arr();
      for (String[] f : FAULTS) if (matches("\\b(?:" + f[2] + ")\\b", reps.group(3)).find()) named.add(f[0]);
      if (named.isEmpty()) { for (String[] f : FAULTS) named.add(f[0]); named.add("rated_reps"); }
      if (!equalFact(reps.group(1), facts, named) || !equalFact(reps.group(2), facts, arr("rated_reps", "reps_counted"))) fail("mismatched_number");
    }
  }
  private boolean equalFact(String n, Map<String, Object> facts, List<Object> names) {
    for (Object name : names) if (facts.containsKey(name) && Double.parseDouble(n) == num(facts.get(name))) return true;
    return false;
  }
  private String value(Map<String, Object> fact) { return String.valueOf(fact.get("value")); }
  public Map<String, Object> fallback(Map<String, Object> scope) {
    Map<String, Object> facts = obj();
    for (Object f : list(scope.get("facts"))) facts.put(str(map(f), "name"), f);
    Map<String, Object> counted = map(facts.get("reps_counted")), rated = map(facts.get("rated_reps"));
    String text = value(counted) + " reps counted; " + value(rated) + " rated (estimated).";
    List<Object> citations = arr(counted.get("id"), rated.get("id"));
    if (facts.containsKey("hold_seconds")) {
      Map<String, Object> held = map(facts.get("hold_seconds")), aligned = map(facts.get("aligned_seconds"));
      if (scope.get("hold") != null && yes(map(scope.get("hold")).get("rated"))) {
        text = value(aligned) + " of " + value(held) + " seconds aligned (estimated)."; citations = arr(aligned.get("id"), held.get("id"));
      } else { text = value(held) + " seconds held; alignment not rated (unclear view)."; citations = arr(held.get("id")); }
    } else if (num(rated.get("value")) < 3) text += " Not enough clear reps to rate.";
    else for (String[] f : FAULTS) if (f[0].equals(scope.get("dominant_fault")) && facts.containsKey(f[0])) {
      Map<String, Object> fault = map(facts.get(f[0]));
      text = value(fault) + " of " + value(rated) + " rated reps " + f[1] + " (estimated).";
      citations = arr(fault.get("id"), rated.get("id")); break;
    }
    if (scope.get("drill_id") != null) text += " Next: " + catalog.exercise(str(scope, "drill_id")).get("name") + ".";
    return validate(obj("text", text, "cited_fact_ids", citations), scope);
  }
}
