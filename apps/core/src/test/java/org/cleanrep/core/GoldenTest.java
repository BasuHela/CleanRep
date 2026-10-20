package org.cleanrep.core;

import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.cleanrep.core.Json.*;
import static org.cleanrep.core.testing.Check.*;

/** Each HTTP response is compared structurally, without dropping fields or rounding numbers. */
public final class GoldenTest {
  public void test_C_GLD_1_policy_reference() throws Exception { replay("policy"); }
  public void test_C_GLD_2_remote_reference() throws Exception { replay("remote"); }
  public void test_C_GLD_3_paired_drill_retest_reference() throws Exception { replay("paired"); }
  public void test_C_GLD_4_hold_reference() throws Exception { replay("hold"); }
  public void test_C_GLD_5_live_channels_reference() throws Exception { replay("live"); }
  public void test_C_GLD_6_rejections_reference() throws Exception { replay("rejections"); }
  public void test_C_GLD_7_rates_reference() throws Exception { replay("rates"); }
  public void test_C_GLD_8_approval_error_reference() throws Exception { replay("approval_error"); }
  private void replay(String name) throws Exception {
    File file = new File(System.getProperty("cleanrep.root"), "apps/core/src/test/resources/golden/" + name + ".json");
    Map<String, Object> golden = map(Json.parse(Files.readAllBytes(file.toPath())));
    Map<String, String> bindings = new LinkedHashMap<>();
    try (Harness h = new Harness()) {
      int index = 0;
      for (Object step : list(golden.get("steps"))) {
        Map<String, Object> s = map(step), req = map(substitute(map(s.get("request")), bindings));
        h.clock.now = num(s.get("at"));
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map(req.get("headers")).entrySet()) headers.put(e.getKey(), (String) e.getValue());
        byte[] body = req.containsKey("json") ? stringify(req.get("json")).getBytes(StandardCharsets.UTF_8) : hex((String) req.getOrDefault("hex", ""));
        ApiRequest request = new ApiRequest(str(req, "method"), str(req, "path"), str(req, "query"), headers, body, str(req, "peer"));
        ApiResponse response = h.api.handle(request);
        if (yes(req.get("wait_job"))) {
          for (int poll = 0; poll < 200 && !"done".equals(map(map(Json.parse(response.body)).get("data")).get("state")); poll++) {
            Thread.sleep(5); response = h.api.handle(request);
          }
        }
        Map<String, Object> actual = obj("status", response.status, "content_type", response.contentType);
        if (response.body.length != 0 && "application/json".equals(response.contentType)) actual.put("json", Json.parse(response.body));
        else actual.put("hex", Secrets.hex(response.body));
        Map<String, Object> expected = copy(map(s.get("response")));
        // This is an intentional backend identity difference, not a behavioral normalization.
        if (request.path.equals("/v1/healthz") && response.status == 200) {
          equal("json", map(map(actual.get("json")).get("data")).get("store"));
          map(map(expected.get("json")).get("data")).put("store", "json");
        }
        bind(expected, actual, bindings);
        Object expanded = substitute(expected, bindings);
        if (!same(expanded, actual)) throw new AssertionError(name + " step " + index + " " + request.method + " " + request.path + "\n" + difference(expanded, actual, "$"));
        index++;
      }
    }
  }
  private void bind(Object expected, Object actual, Map<String, String> bindings) {
    if (expected instanceof String && actual instanceof String && ((String) expected).matches("\\$\\{[A-Za-z_]+_[0-9]+}")) {
      String placeholder = (String) expected;
      if (!bindings.containsKey(placeholder)) {
        isTrue(!bindings.containsValue(actual), "random values must not alias: " + placeholder);
        bindings.put(placeholder, (String) actual);
      }
    } else if (expected instanceof Map && actual instanceof Map) {
      for (String k : map(expected).keySet()) bind(map(expected).get(k), map(actual).get(k), bindings);
    } else if (expected instanceof List && actual instanceof List) {
      for (int i = 0; i < Math.min(list(expected).size(), list(actual).size()); i++) bind(list(expected).get(i), list(actual).get(i), bindings);
    }
  }
  private Object substitute(Object value, Map<String, String> bindings) {
    if (value instanceof Map) {
      Map<String, Object> result = obj(); for (String k : map(value).keySet()) result.put(k, substitute(map(value).get(k), bindings)); return result;
    }
    if (value instanceof List) { List<Object> out = arr(); for (Object item : list(value)) out.add(substitute(item, bindings)); return out; }
    if (value instanceof String) { String text = (String) value; for (Map.Entry<String, String> b : bindings.entrySet()) text = text.replace(b.getKey(), b.getValue()); return text; }
    return value;
  }
  private String difference(Object expected, Object actual, String path) {
    if (expected instanceof Map && actual instanceof Map && map(expected).keySet().equals(map(actual).keySet())) {
      for (String k : map(expected).keySet()) if (!same(map(expected).get(k), map(actual).get(k))) return difference(map(expected).get(k), map(actual).get(k), path + "." + k);
    }
    if (expected instanceof List && actual instanceof List && list(expected).size() == list(actual).size()) {
      for (int i = 0; i < list(expected).size(); i++) if (!same(list(expected).get(i), list(actual).get(i))) return difference(list(expected).get(i), list(actual).get(i), path + "[" + i + "]");
    }
    return path + " expected " + stringify(expected) + " but was " + stringify(actual);
  }
  private byte[] hex(String text) {
    byte[] out = new byte[text.length() / 2];
    for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
    return out;
  }
}
