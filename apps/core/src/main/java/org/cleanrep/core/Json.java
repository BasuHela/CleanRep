package org.cleanrep.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** JSON values are maps, lists, strings, numbers, booleans and null. No reflection or dependencies. */
public final class Json {
  private Json() {}

  public static Object parse(byte[] bytes) {
    String encoding = "UTF-8";
    int offset = 0;
    if (bytes.length >= 4 && bytes[0] == 0 && bytes[1] == 0) {
      encoding = "UTF-32BE";
      if (bytes[2] == (byte) 0xfe && bytes[3] == (byte) 0xff) offset = 4;
    } else if (bytes.length >= 4 && bytes[2] == 0 && bytes[3] == 0) {
      encoding = "UTF-32LE";
      if (bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) offset = 4;
    } else if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) {
      encoding = "UTF-16BE"; offset = 2;
    } else if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) {
      encoding = "UTF-16LE"; offset = 2;
    } else if (bytes.length >= 2 && bytes[0] == 0) encoding = "UTF-16BE";
    else if (bytes.length >= 2 && bytes[1] == 0) encoding = "UTF-16LE";
    else if (bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf) offset = 3;
    return parse(decode(bytes, offset, encoding));
  }
  private static String decode(byte[] bytes, int at, String encoding) {
    StringBuilder out = new StringBuilder();
    boolean little = encoding.endsWith("LE");
    if (encoding.startsWith("UTF-16")) {
      if ((bytes.length - at) % 2 != 0) throw new IllegalArgumentException("invalid JSON encoding");
      while (at < bytes.length) {
        int a = bytes[at++] & 255, b = bytes[at++] & 255;
        out.append((char) (little ? a | (b << 8) : (a << 8) | b));
      }
    } else if (encoding.startsWith("UTF-32")) {
      if ((bytes.length - at) % 4 != 0) throw new IllegalArgumentException("invalid JSON encoding");
      while (at < bytes.length) {
        long cp = 0;
        for (int i = 0; i < 4; i++) cp |= (long) (bytes[at++] & 255) << (8 * (little ? i : 3 - i));
        if (cp > 0x10ffff) throw new IllegalArgumentException("invalid JSON encoding");
        out.appendCodePoint((int) cp);
      }
    } else {
      while (at < bytes.length) {
        int lead = bytes[at++] & 255, cp, remaining, minimum;
        if (lead < 128) { out.append((char) lead); continue; }
        if (lead >= 0xc2 && lead <= 0xdf) { cp = lead & 31; remaining = 1; minimum = 0x80; }
        else if (lead >= 0xe0 && lead <= 0xef) { cp = lead & 15; remaining = 2; minimum = 0x800; }
        else if (lead >= 0xf0 && lead <= 0xf4) { cp = lead & 7; remaining = 3; minimum = 0x10000; }
        else throw new IllegalArgumentException("invalid JSON encoding");
        if (at + remaining > bytes.length) throw new IllegalArgumentException("invalid JSON encoding");
        while (remaining-- > 0) {
          int next = bytes[at++] & 255;
          if ((next & 0xc0) != 0x80) throw new IllegalArgumentException("invalid JSON encoding");
          cp = (cp << 6) | (next & 63);
        }
        if (cp < minimum || cp > 0x10ffff) throw new IllegalArgumentException("invalid JSON encoding");
        // Python json.loads(bytes) uses surrogatepass, so a lone surrogate is preserved.
        out.appendCodePoint(cp);
      }
    }
    return out.toString();
  }
  static String strip(String s, boolean numeric) {
    int start = 0, end = s.length();
    while (start < end && whitespace(s.charAt(start), numeric)) start++;
    while (end > start && whitespace(s.charAt(end - 1), numeric)) end--;
    return s.substring(start, end);
  }
  private static boolean whitespace(char c, boolean numeric) {
    return c >= 9 && c <= 13 || c == 32 || c == 133 || c == 160 || c == 5760
        || c >= 8192 && c <= 8202 || c == 8232 || c == 8233 || c == 8239 || c == 8287 || c == 12288
        || !numeric && c >= 28 && c <= 31;
  }

  public static Object parse(String text) {
    Reader r = new Reader(text);
    Object value = r.value(0);
    r.space();
    if (r.at != text.length()) throw r.invalid();
    return value;
  }

  public static String stringify(Object value) { return write(value, false); }
  /** Python json.dumps(sort_keys=True, separators=(",", ":"), ensure_ascii=True). */
  public static String canonical(Object value) { return write(value, true); }
  private static String write(Object value, boolean sorted) {
    StringBuilder out = new StringBuilder();
    append(out, value, sorted);
    return out.toString();
  }
  private static void append(StringBuilder out, Object value, boolean sorted) {
    if (value == null) out.append("null");
    else if (value instanceof String) quote(out, (String) value);
    else if (value instanceof Boolean) out.append(value);
    else if (value instanceof Number) {
      if (value instanceof Double || value instanceof Float) {
        double d = ((Number) value).doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("nonfinite JSON number");
        String s = Double.toString(d);
        // Python uses decimal notation in [1e-4, 1e16), scientific notation outside it.
        if (d != 0 && (Math.abs(d) < 0.0001 || Math.abs(d) >= 1e16)) {
          java.math.BigDecimal dec = java.math.BigDecimal.valueOf(d).stripTrailingZeros();
          String digits = dec.unscaledValue().abs().toString();
          int exp = digits.length() - 1 - dec.scale();
          s = (d < 0 ? "-" : "") + digits.charAt(0) + (digits.length() > 1 ? "." + digits.substring(1) : "")
              + "e" + (exp < 0 ? "-" : "+") + (Math.abs(exp) < 10 ? "0" : "") + Math.abs(exp);
        } else if (s.indexOf('E') >= 0) {
          s = java.math.BigDecimal.valueOf(d).toPlainString();
          if (s.indexOf('.') < 0) s += ".0";
        }
        out.append(s);
      } else out.append(value);
    } else if (value instanceof Map) {
      out.append('{'); boolean first = true;
      Map<String, Object> entries = map(value);
      if (sorted) entries = new TreeMap<>(entries);
      for (Map.Entry<String, Object> e : entries.entrySet()) {
        if (!first) out.append(','); first = false;
        quote(out, e.getKey()); out.append(':'); append(out, e.getValue(), sorted);
      }
      out.append('}');
    } else if (value instanceof List) {
      out.append('['); boolean first = true;
      for (Object item : list(value)) {
        if (!first) out.append(','); first = false; append(out, item, sorted);
      }
      out.append(']');
    } else throw new IllegalArgumentException("not JSON: " + value.getClass());
  }
  private static void quote(StringBuilder out, String s) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"': out.append("\\\""); break;
        case '\\': out.append("\\\\"); break;
        case '\b': out.append("\\b"); break;
        case '\f': out.append("\\f"); break;
        case '\n': out.append("\\n"); break;
        case '\r': out.append("\\r"); break;
        case '\t': out.append("\\t"); break;
        default:
          if (c < 32 || c > 126) {
            String hex = Integer.toHexString(c);
            out.append("\\u"); for (int j = hex.length(); j < 4; j++) out.append('0'); out.append(hex);
          } else out.append(c);
      }
    }
    out.append('"');
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> map(Object value) {
    if (!(value instanceof Map)) throw new IllegalArgumentException("object required");
    return (Map<String, Object>) value;
  }
  @SuppressWarnings("unchecked")
  public static List<Object> list(Object value) {
    if (!(value instanceof List)) throw new IllegalArgumentException("array required");
    return (List<Object>) value;
  }
  public static Map<String, Object> obj(Object... pairs) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
    return result;
  }
  public static List<Object> arr(Object... values) { return new ArrayList<>(Arrays.asList(values)); }
  @SuppressWarnings("unchecked")
  public static <T> T copy(T value) {
    if (value instanceof Map) {
      Map<String, Object> result = obj();
      for (Map.Entry<String, Object> e : map(value).entrySet()) result.put(e.getKey(), copy(e.getValue()));
      return (T) result;
    }
    if (value instanceof List) {
      List<Object> result = arr(); for (Object item : list(value)) result.add(copy(item)); return (T) result;
    }
    return value;
  }
  public static String str(Map<String, Object> m, String key) { return (String) m.get(key); }
  public static double num(Object value) { return ((Number) value).doubleValue(); }
  public static int integer(Object value) { return ((Number) value).intValue(); }
  public static boolean yes(Object value) { return Boolean.TRUE.equals(value); }
  public static boolean same(Object a, Object b) {
    if (a instanceof Number && b instanceof Number) return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString())) == 0;
    if (a instanceof Map && b instanceof Map) {
      Map<String, Object> x = map(a), y = map(b);
      if (!x.keySet().equals(y.keySet())) return false;
      for (String k : x.keySet()) if (!same(x.get(k), y.get(k))) return false;
      return true;
    }
    if (a instanceof List && b instanceof List) {
      List<Object> x = list(a), y = list(b);
      if (x.size() != y.size()) return false;
      for (int i = 0; i < x.size(); i++) if (!same(x.get(i), y.get(i))) return false;
      return true;
    }
    return java.util.Objects.equals(a, b);
  }

  private static final class Reader {
    final String text; int at;
    Reader(String text) { this.text = text; }
    IllegalArgumentException invalid() { return new IllegalArgumentException("invalid JSON at " + at); }
    void space() { while (at < text.length() && " \r\n\t".indexOf(text.charAt(at)) >= 0) at++; }
    boolean take(char c) { if (at < text.length() && text.charAt(at) == c) { at++; return true; } return false; }
    void expect(char c) { if (!take(c)) throw invalid(); }
    Object value(int depth) {
      if (depth > 900) throw invalid();
      space(); if (at == text.length()) throw invalid();
      char c = text.charAt(at);
      if (c == '"') return string();
      if (take('{')) {
        Map<String, Object> result = obj(); space();
        if (take('}')) return result;
        do { space(); String k = string(); space(); expect(':'); result.put(k, value(depth + 1)); space(); }
        while (take(','));
        expect('}'); return result;
      }
      if (take('[')) {
        List<Object> result = arr(); space();
        if (take(']')) return result;
        do { result.add(value(depth + 1)); space(); } while (take(','));
        expect(']'); return result;
      }
      String[] literals = {"true", "false", "null", "NaN", "Infinity", "-Infinity"};
      Object[] values = {true, false, null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
      for (int i = 0; i < literals.length; i++) if (text.startsWith(literals[i], at)) { at += literals[i].length(); return values[i]; }
      int start = at; take('-');
      if (!take('0')) { if (!digit()) throw invalid(); while (digit()) at++; }
      boolean decimal = false;
      if (take('.')) { decimal = true; if (!digit()) throw invalid(); while (digit()) at++; }
      if (take('e') || take('E')) { decimal = true; if (!take('+')) take('-'); if (!digit()) throw invalid(); while (digit()) at++; }
      String n = text.substring(start, at);
      try {
        if (decimal) return Double.valueOf(n);
        if (n.length() - (n.startsWith("-") ? 1 : 0) > 4300) throw invalid();
        try { return Long.valueOf(n); } catch (NumberFormatException e) { return new BigInteger(n); }
      } catch (NumberFormatException e) { throw invalid(); }
    }
    boolean digit() { return at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9'; }
    String string() {
      expect('"'); StringBuilder out = new StringBuilder();
      while (at < text.length()) {
        char c = text.charAt(at++);
        if (c == '"') return out.toString();
        if (c < 32) throw invalid();
        if (c == '\\') {
          if (at == text.length()) throw invalid();
          c = text.charAt(at++);
          switch (c) {
            case '"': case '\\': case '/': out.append(c); break;
            case 'b': out.append('\b'); break;
            case 'f': out.append('\f'); break;
            case 'n': out.append('\n'); break;
            case 'r': out.append('\r'); break;
            case 't': out.append('\t'); break;
            case 'u':
              if (at + 4 > text.length()) throw invalid();
              int code = 0;
              for (int i = 0; i < 4; i++) {
                char h = text.charAt(at++);
                int d = h >= '0' && h <= '9' ? h - '0' : h >= 'a' && h <= 'f' ? h - 'a' + 10 : h >= 'A' && h <= 'F' ? h - 'A' + 10 : -1;
                if (d < 0) throw invalid(); code = code * 16 + d;
              }
              out.append((char) code); break;
            default: throw invalid();
          }
        } else out.append(c);
      }
      throw invalid();
    }
  }
}
