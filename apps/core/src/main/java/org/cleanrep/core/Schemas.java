package org.cleanrep.core;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import static org.cleanrep.core.Json.*;

/** The generated contracts are authoritative. Pydantic's non-strict bool/float coercions are explicit. */
public final class Schemas {
  private final Map<String, Map<String, Object>> schemas = new LinkedHashMap<>();
  private final Map<String, Pattern> patterns = new java.util.HashMap<>();
  public Schemas(Resources resources) throws IOException {
    for (String name : new String[]{"Empty", "Mutation", "Profile", "Setup", "Readiness", "PairRequest", "PairClaim", "PairApprove", "Block", "PlanCandidate", "RepEvent", "HoldEvent", "CameraCheck", "CoachEvent", "CoachNotice", "StartSet", "SelfReport", "EndSet", "Explain", "Drill", "Adapt", "Explanation", "Envelope"})
      schemas.put(name, map(Json.parse(resources.read("contracts/" + name + ".json"))));
  }
  public Map<String, Object> validate(String name, Object value) {
    Map<String, Object> root = schemas.get(name);
    if (root == null) throw new IllegalArgumentException("unknown schema");
    return map(check(root, value, root));
  }
  private IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_request"); }
  /** pydantic's Rust regex anchors `$` at the very end (no trailing newline), as `\z` does. Compiled once per pattern. */
  private Pattern pattern(String source) {
    synchronized (patterns) {
      Pattern p = patterns.get(source);
      if (p == null) { p = Wording.regex(source.replace("$", "\\z")); patterns.put(source, p); }
      return p;
    }
  }
  private Object check(Map<String, Object> s, Object v, Map<String, Object> root) {
    if (s.containsKey("$ref")) {
      String ref = str(s, "$ref");
      if (!ref.startsWith("#/$defs/")) throw invalid();
      return check(map(map(root.get("$defs")).get(ref.substring(8))), v, root);
    }
    if (s.containsKey("anyOf")) {
      for (Object branch : list(s.get("anyOf"))) {
        try { return check(map(branch), v, root); } catch (IllegalArgumentException ignored) { /* next union arm */ }
      }
      throw invalid();
    }
    if (s.containsKey("enum")) {
      for (Object allowed : list(s.get("enum"))) {
        if (same(allowed, v)) return copy(allowed);
      }
      throw invalid();
    }
    String type = str(s, "type");
    if (type == null) return copy(v);
    switch (type) {
      case "null": if (v != null) throw invalid(); return null;
      case "object": {
        Map<String, Object> input = map(v), out = obj();
        Map<String, Object> props = s.containsKey("properties") ? map(s.get("properties")) : obj();
        List<Object> required = s.containsKey("required") ? list(s.get("required")) : arr();
        for (Map.Entry<String, Object> e : props.entrySet()) {
          Map<String, Object> p = map(e.getValue());
          if (input.containsKey(e.getKey())) out.put(e.getKey(), check(p, input.get(e.getKey()), root));
          else if (required.contains(e.getKey())) throw invalid();
          else if (p.containsKey("default")) out.put(e.getKey(), copy(p.get("default")));
          // default_factory values are omitted by pydantic's JSON schema.
          else if ("array".equals(p.get("type"))) out.put(e.getKey(), arr());
          else if ("object".equals(p.get("type"))) out.put(e.getKey(), obj());
        }
        Object additional = s.get("additionalProperties");
        for (Map.Entry<String, Object> e : input.entrySet()) if (!props.containsKey(e.getKey())) {
          if (Boolean.FALSE.equals(additional)) throw invalid();
          out.put(e.getKey(), additional instanceof Map ? check(map(additional), e.getValue(), root) : copy(e.getValue()));
        }
        return out;
      }
      case "array": {
        List<Object> input = list(v), out = arr(); bounds(s, input.size(), "minItems", "maxItems");
        List<Object> prefix = s.containsKey("prefixItems") ? list(s.get("prefixItems")) : arr();
        for (int i = 0; i < input.size(); i++) {
          Object child = i < prefix.size() ? prefix.get(i) : s.get("items");
          out.add(child instanceof Map ? check(map(child), input.get(i), root) : copy(input.get(i)));
        }
        return out;
      }
      case "string": {
        if (!(v instanceof String)) throw invalid();
        String text = (String) v;
        bounds(s, text.codePointCount(0, text.length()), "minLength", "maxLength");
        if (s.containsKey("pattern") && !pattern(str(s, "pattern")).matcher(text).find()) throw invalid();
        return text;
      }
      case "boolean": {
        if (v instanceof Boolean) return v;
        if (v instanceof Number) { if (num(v) == 0) return false; if (num(v) == 1) return true; }
        if (v instanceof String) {
          String text = ((String) v).toLowerCase(Locale.ROOT);
          if (arr("0", "off", "f", "false", "n", "no").contains(text)) return false;
          if (arr("1", "on", "t", "true", "y", "yes").contains(text)) return true;
        }
        throw invalid();
      }
      case "integer":
        // Envelope.version is the one non-strict integer; JSON Schema does not encode strict=True.
        if ("Envelope".equals(root.get("title"))) {
          if (v instanceof Boolean) v = yes(v) ? 1 : 0;
          if (v instanceof Double || v instanceof Float) {
            try { v = new java.math.BigDecimal(num(v)).toBigIntegerExact(); }
            catch (ArithmeticException | NumberFormatException e) { throw invalid(); }
          } else if (v instanceof String) {
            String text = Json.strip((String) v, true).replaceAll("(?<=[0-9])_(?=[0-9])", "");
            if (!text.matches("[+-]?[0-9]+(?:\\.0+)?")) throw invalid();
            try { v = new java.math.BigDecimal(text).toBigIntegerExact(); }
            catch (ArithmeticException | NumberFormatException e) { throw invalid(); }
          }
        }
        if (!(v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long || v instanceof java.math.BigInteger)) throw invalid();
        numericBounds(s, num(v)); return v;
      case "number": {
        double n;
        if (v instanceof Number) n = num(v);
        else if (v instanceof Boolean) n = yes(v) ? 1 : 0;
        else if (v instanceof String) {
          String text = Json.strip((String) v, true).replaceAll("(?<=[0-9])_(?=[0-9])", "");
          if (!text.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) throw invalid();
          try { n = Double.parseDouble(text); } catch (NumberFormatException e) { throw invalid(); }
        } else throw invalid();
        if (Double.isNaN(n) || Double.isInfinite(n)) throw invalid();
        numericBounds(s, n); return n;
      }
      default: throw new IllegalArgumentException("unsupported schema type " + type);
    }
  }
  private void bounds(Map<String, Object> s, double n, String min, String max) {
    if (s.containsKey(min) && n < num(s.get(min)) || s.containsKey(max) && n > num(s.get(max))) throw invalid();
  }
  private void numericBounds(Map<String, Object> s, double n) {
    bounds(s, n, "minimum", "maximum");
    if (s.containsKey("exclusiveMinimum") && n <= num(s.get("exclusiveMinimum"))
        || s.containsKey("exclusiveMaximum") && n >= num(s.get("exclusiveMaximum"))) throw invalid();
  }
}
