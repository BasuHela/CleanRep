package org.cleanrep.core;

import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.util.Map;
import static org.cleanrep.core.testing.Check.*;

public final class JsonSchemaTest {
  public void test_C_JSON_1_roundtrip_unicode_numbers_escapes() {
    Object value = Json.parse("{\"s\":\"é😀\\n\\t\\u0000\\\"\\\\\",\"n\":[-0.0,1e30,1234567890123456789012345,true,null]}");
    equal(Json.stringify(value), Json.stringify(Json.parse(Json.stringify(value))));
    equal(new BigInteger("1234567890123456789012345"), Json.parse("1234567890123456789012345"));
    equal("{\"a\":1,\"z\":\"\\u00e9\\ud83d\\ude00\"}", Json.canonical(Json.obj("z", "é😀", "a", 1)));
    equal(2L, Json.map(Json.parse("{\"x\":1,\"x\":2}")).get("x"));
  }
  public void test_C_JSON_2_invalid_syntax_and_encoding() {
    for (String s : new String[]{"", "[1,]", "01", "1.", "1e", "{a:1}", "true false", "\"\n\"", "\"\\x\""})
      raises(IllegalArgumentException.class, () -> Json.parse(s));
    raises(IllegalArgumentException.class, () -> Json.parse(new byte[]{(byte) 0xc3, 0x28}));
    equal(Json.obj("ok", true), Json.parse("{\"ok\":true}".getBytes(StandardCharsets.UTF_16)));
    isTrue(Double.isNaN(((Number) Json.parse("NaN")).doubleValue()), "Python loads accepts NaN");
    raises(IllegalArgumentException.class, () -> Json.stringify(Double.NaN));
  }
  public void test_C_SCH_1_required_extra_strict_integer_nullable() throws Exception {
    Schemas schemas = new Schemas(RepoResources.fromSystemProperty());
    Map<String, Object> mutation = Json.obj("expected_version", 0, "idempotency_key", "abcdefgh");
    schemas.validate("Mutation", mutation);
    for (Object v : new Object[]{0.0, "0", true, -1}) {
      Map<String, Object> bad = Json.copy(mutation); bad.put("expected_version", v);
      raises(IllegalArgumentException.class, () -> schemas.validate("Mutation", bad));
    }
    raises(IllegalArgumentException.class, () -> schemas.validate("Empty", Json.obj("extra", 1)));
    raises(IllegalArgumentException.class, () -> schemas.validate("Mutation", Json.obj()));
    Map<String, Object> end = Json.copy(mutation); end.put("set_id", "s_1");
    isTrue(schemas.validate("EndSet", end).containsKey("self_report"), "nullable default included");
  }
  public void test_C_SCH_2_pydantic_coercion_bounds_and_defaults() throws Exception {
    Schemas schemas = new Schemas(RepoResources.fromSystemProperty());
    Map<String, Object> check = Json.obj("full_body", "yes", "side_view", 0, "light", "OFF", "fps", "30.5", "delegate", "GPU");
    Map<String, Object> v = schemas.validate("CameraCheck", check);
    equal(true, v.get("full_body")); equal(false, v.get("light")); equal(30.5, v.get("fps"));
    check.put("fps", "NaN"); raises(IllegalArgumentException.class, () -> schemas.validate("CameraCheck", check));
    equal(0, Json.list(schemas.validate("Profile", Json.obj()).get("equipment")).size());
    raises(IllegalArgumentException.class, () -> schemas.validate("Readiness", Json.obj("expected_version", 0, "idempotency_key", "abcdefgh", "answers", Json.arr(true, false))));
    equal("１２３４５６", schemas.validate("PairRequest", Json.obj("pairing_code", "１２３４５６")).get("pairing_code"));
  }
  public void test_C_SCH_3_pydantic_differential_corpus() throws Exception {
    Schemas schemas = new Schemas(RepoResources.fromSystemProperty());
    Domain domain = new Domain(new Catalog(RepoResources.fromSystemProperty()));
    java.io.File file = new java.io.File(System.getProperty("cleanrep.root"), "apps/core/src/test/resources/schema-cases.json");
    int index = 0;
    java.util.List<String> mismatches = new java.util.ArrayList<>();
    for (Object raw : Json.list(Json.parse(java.nio.file.Files.readAllBytes(file.toPath())))) {
      Map<String, Object> c = Json.map(raw); String schema = Json.str(c, "schema");
      Object actual = null; boolean valid = true;
      try {
        actual = schemas.validate(schema, c.get("input"));
        if (schema.equals("RepEvent")) domain.validateRep(Json.map(actual));
        if (schema.equals("HoldEvent")) domain.validateHold(Json.map(actual));
      } catch (IllegalArgumentException e) { valid = false; }
      if (valid != Json.yes(c.get("valid")) || valid && !Json.same(c.get("value"), actual)) {
        mismatches.add(index + " " + schema + " " + Json.stringify(c.get("input")) + " expected " + c.get("valid") + " " + Json.stringify(c.get("value")) + " got " + valid + " " + Json.stringify(actual));
      }
      index++;
    }
    if (!mismatches.isEmpty()) throw new AssertionError(mismatches.size() + " mismatches; " + mismatches.subList(0, Math.min(15, mismatches.size())));
  }

  public void test_C_JSON_3_python_surrogatepass_byte_decoding() {
    byte[][] inputs = {
        {(byte) 34, (byte) 0xed, (byte) 0xa0, (byte) 0x80, (byte) 34},
        {(byte) 0xfe, (byte) 0xff, 0, 34, (byte) 0xd8, 0, 0, 34},
        {0, 0, (byte) 0xfe, (byte) 0xff, 0, 0, 0, 34, 0, 0, (byte) 0xd8, 0, 0, 0, 0, 34}
    };
    for (byte[] bytes : inputs) equal(String.valueOf((char) 0xd800), Json.parse(bytes));
    for (byte[] bytes : new byte[][]{{34, (byte) 0xc0, (byte) 0xaf, 34}, {34, (byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80, 34}})
      raises(IllegalArgumentException.class, () -> Json.parse(bytes));
  }

}
