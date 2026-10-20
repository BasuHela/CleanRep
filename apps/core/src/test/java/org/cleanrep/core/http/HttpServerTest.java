package org.cleanrep.core.http;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.cleanrep.core.ApiRequest;
import org.cleanrep.core.ApiResponse;
import org.cleanrep.core.testing.Check;

/** C-HTTP-1..19: parsing, limits, connections and responses of the HTTP/1.1 server, over real loopback sockets. */
public class HttpServerTest {
  static final String CSP = "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; "
      + "img-src 'self' data: blob:; media-src 'self' blob:; worker-src 'self' blob:; connect-src 'self'; "
      + "frame-ancestors 'none'; base-uri 'self'; object-src 'none'";

  final FakeApi api = new FakeApi();
  final List<String> log = Collections.synchronizedList(new ArrayList<String>());

  static HttpServer.Options fast() {
    HttpServer.Options o = new HttpServer.Options();
    o.headerTimeoutMillis = 600;
    o.bodyTimeoutMillis = 600;
    o.idleTimeoutMillis = 600;
    o.writeTimeoutMillis = 800;
    return o;
  }

  /** The 8105 listener's stack (Router.local) on 127.0.0.1 and an ephemeral port. */
  HttpServer start(HttpServer.Options options, StaticSource files) throws IOException {
    ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
    HttpServer server = new HttpServer("test", socket, null, Router.local(api, files, Temp.log(log)), options, Temp.log(log));
    server.start();
    return server;
  }

  HttpServer start() throws IOException {
    return start(fast(), webFiles());
  }

  static StaticSource webFiles() throws IOException {
    File dir = Temp.dir("web");
    write(new File(dir, "index.html"), "<!doctype html><title>CleanRep</title>");
    write(new File(dir, "assets/app.js"), "console.log(1)");
    return new StaticSource.Directory(dir);
  }

  static void write(File file, String text) throws IOException {
    file.getParentFile().mkdirs();
    try (FileOutputStream out = new FileOutputStream(file)) {
      out.write(text.getBytes(StandardCharsets.UTF_8));
    }
  }

  static String rep(String s, int n) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < n; i++) b.append(s);
    return b.toString();
  }

  static String envelope(String code) {
    return "{\"status\":\"rejected\",\"version\":null,\"data\":{},\"error\":{\"code\":\"" + code + "\",\"message\":\""
        + code.replace('_', ' ') + "\"}}";
  }

  public void test_C_HTTP_1_parses_the_request_into_an_api_request() throws Exception {
    try (HttpServer server = start(); Raw c = new Raw(server.port())) {
      Raw.Reply r = c.exchange("POST /v1/sessions/s_1/reps?a=1&b=%41 HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\n"
          + "X-Mixed-Case: Value  \r\nAuthorization: Bearer t0k\r\nContent-Length: 11\r\n\r\n{\"rep\": 12}");
      Check.equal(200, r.status);
      Check.equal("HTTP/1.1 200 OK", r.statusLine);
      Check.equal("application/json", r.header("content-type"));
      Check.equal(String.valueOf(r.body.length), r.header("content-length"));
      ApiRequest a = api.last();
      Check.equal("POST", a.method);
      Check.equal("/v1/sessions/s_1/reps", a.path);
      Check.equal("a=1&b=%41", a.query);
      Check.equal("Value", a.headers.get("x-mixed-case"));
      Check.equal("Bearer t0k", a.headers.get("authorization"));
      Check.equal("application/json", a.headers.get("content-type"));
      Check.isTrue(!a.headers.containsKey("Content-Type"), "names are lower case");
      Check.equal("{\"rep\": 12}", new String(a.body, StandardCharsets.UTF_8));
      Check.equal("127.0.0.1", a.peer);
      c.exchange("GET /v1/healthz HTTP/1.1\r\nHost: x\r\n\r\n");
      Check.equal("", api.last().query);
      Check.equal(0, api.last().body.length);
    }
  }

  public void test_C_HTTP_2_keeps_the_connection_alive_until_asked_to_close() throws Exception {
    try (HttpServer server = start(); Raw c = new Raw(server.port())) {
      Raw.Reply first = c.exchange("GET /v1/one HTTP/1.1\r\nHost: x\r\n\r\n");
      Raw.Reply second = c.exchange("GET /v1/two HTTP/1.1\r\nHost: x\r\n\r\n");
      Check.equal(200, first.status);
      Check.equal(200, second.status);
      Check.equal(null, second.header("connection"));
      Check.equal(2, api.requests.size());
      // Pipelined: both requests in one write.
      c.send("GET /v1/three HTTP/1.1\r\nHost: x\r\n\r\nGET /v1/four HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
      Check.isTrue(c.read().text().contains("/v1/three"), "third");
      Raw.Reply last = c.read();
      Check.isTrue(last.text().contains("/v1/four"), "fourth");
      Check.equal("close", last.header("connection"));
      Check.isTrue(c.closedWithin(1000), "closed after Connection: close");
    }
    try (HttpServer server = start(); Raw c = new Raw(server.port())) {
      Raw.Reply r = c.exchange("GET /v1/old HTTP/1.0\r\n\r\n");
      Check.equal("close", r.header("connection"));
      Check.isTrue(c.closedWithin(1000), "HTTP/1.0 closes by default");
    }
    try (HttpServer server = start(); Raw c = new Raw(server.port())) {
      Raw.Reply r = c.exchange("GET /v1/old HTTP/1.0\r\nConnection: keep-alive\r\n\r\n");
      Check.equal("keep-alive", r.header("connection"));
      Check.equal(200, c.exchange("GET /v1/again HTTP/1.0\r\nConnection: keep-alive\r\n\r\n").status);
    }
  }

  public void test_C_HTTP_3_slow_or_idle_clients_are_dropped_and_others_still_served() throws Exception {
    try (HttpServer server = start()) {
      // Slowloris: a header block that never ends.
      try (Raw slow = new Raw(server.port())) {
        long t0 = System.nanoTime();
        slow.send("GET /v1/x HTTP/1.1\r\n");
        for (int i = 0; i < 20 && !slow.socket.isClosed(); i++) {
          Thread.sleep(100);
          try {
            slow.send("X-Drip: " + i + "\r\n");
          } catch (IOException e) {
            break;
          }
        }
        Check.isTrue(slow.closedWithin(2000), "slow headers dropped");
        Check.isTrue((System.nanoTime() - t0) / 1e6 < 2000, "within the header deadline, not per-read timeouts");
      }
      // A body that stops short.
      try (Raw body = new Raw(server.port())) {
        body.send("POST /v1/x HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: 10\r\n\r\n{\"a\"");
        Check.isTrue(body.closedWithin(2000), "short body dropped");
      }
      // A connection that never sends anything.
      try (Raw silent = new Raw(server.port())) {
        Check.isTrue(silent.closedWithin(2000), "silent connection dropped");
      }
      // Idle keep-alive after a response.
      try (Raw idle = new Raw(server.port())) {
        Check.equal(200, idle.exchange("GET /v1/x HTTP/1.1\r\n\r\n").status);
        long t0 = System.nanoTime();
        Check.isTrue(idle.closedWithin(2000), "idle connection dropped");
        Check.isTrue((System.nanoTime() - t0) / 1e6 >= 400, "but not before the idle timeout");
      }
      Check.equal(1, api.requests.size());
      try (Raw ok = new Raw(server.port())) {
        Check.equal(200, ok.exchange("GET /v1/still HTTP/1.1\r\n\r\n").status);
      }
    }
  }

  public void test_C_HTTP_4_transfer_encoding_is_refused_with_411() throws Exception {
    try (HttpServer server = start()) {
      try (Raw c = new Raw(server.port())) {
        Raw.Reply r = c.exchange("POST /v1/sessions HTTP/1.1\r\nTransfer-Encoding: chunked\r\nContent-Type: application/json\r\n\r\n2\r\n{}\r\n0\r\n\r\n");
        Check.equal(411, r.status);
        Check.equal(envelope("length_required"), r.text());
        Check.equal("close", r.header("connection"));
        Check.isTrue(c.closedWithin(1000), "closed");
      }
      try (Raw c = new Raw(server.port())) {
        Raw.Reply r = c.exchange("POST /v1/sessions HTTP/1.1\r\nTransfer-Encoding: identity\r\nContent-Length: 2\r\n\r\n{}");
        Check.equal(411, r.status);
      }
      try (Raw c = new Raw(server.port())) {
        Check.equal(411, c.exchange("GET /tv HTTP/1.1\r\nTransfer-Encoding: gzip\r\n\r\n").status);
      }
      Check.equal(0, api.requests.size());
    }
  }

  public void test_C_HTTP_5_a_body_over_the_cap_gets_413_without_being_read() throws Exception {
    try (HttpServer server = start(); Raw c = new Raw(server.port())) {
      long t0 = System.nanoTime();
      Raw.Reply r = c.exchange("POST /v1/sessions/s/mirror HTTP/1.1\r\nContent-Type: image/jpeg\r\nContent-Length: "
          + (ApiRequest.MAX_BODY_BYTES + 1) + "\r\n\r\n");
      Check.equal(413, r.status);
      Check.equal(envelope("payload_too_large"), r.text());
      Check.isTrue((System.nanoTime() - t0) / 1e6 < 500, "answered before any body arrived");
      Check.equal(0, api.requests.size());
    }
    try (HttpServer server = start(); Raw c = new Raw(server.port())) {
      byte[] body = new byte[ApiRequest.MAX_BODY_BYTES];
      body[0] = (byte) 0xff;
      c.send("POST /v1/sessions/s/mirror HTTP/1.1\r\nContent-Type: image/jpeg\r\nContent-Length: " + body.length + "\r\n\r\n");
      c.send(body);
      Check.equal(200, c.read().status);
      Check.equal(body.length, api.last().body.length);
    }
  }

  public void test_C_HTTP_6_oversized_header_blocks_get_431_and_long_targets_414() throws Exception {
    try (HttpServer server = start()) {
      StringBuilder big = new StringBuilder("GET /v1/x HTTP/1.1\r\n");
      while (big.length() < 17 * 1024) big.append("X-Pad: ").append(rep("a", 200)).append("\r\n");
      try (Raw c = new Raw(server.port())) {
        c.send(big.toString());
        Raw.Reply r = c.read();
        Check.equal(431, r.status);
        Check.equal(envelope("headers_too_large"), r.text());
      }
      StringBuilder many = new StringBuilder("GET /v1/x HTTP/1.1\r\n");
      for (int i = 0; i < 101; i++) many.append("X-H").append(i).append(": 1\r\n");
      try (Raw c = new Raw(server.port())) {
        Check.equal(431, c.exchange(many.append("\r\n").toString()).status);
      }
      StringBuilder hundred = new StringBuilder("GET /v1/x HTTP/1.1\r\n");
      for (int i = 0; i < 100; i++) hundred.append("X-H").append(i).append(": 1\r\n");
      try (Raw c = new Raw(server.port())) {
        Check.equal(200, c.exchange(hundred.append("\r\n").toString()).status);
      }
      try (Raw c = new Raw(server.port())) {
        c.send("GET /v1/" + rep("a", 9000) + " HTTP/1.1\r\n\r\n");
        Check.equal(414, c.read().status);
      }
      // An endless line without a newline is cut off at the limit, not buffered without end.
      try (Raw c = new Raw(server.port())) {
        try {
          c.send("GET /" + rep("a", 64 * 1024));
        } catch (IOException ignored) {
          // the server may close while we write
        }
        Check.equal(414, c.read().status);
      }
      Check.equal(1, api.requests.size());
    }
  }

  public void test_C_HTTP_7_malformed_requests_get_400() throws Exception {
    String[] bad = {
      "POST /v1/x HTTP/1.1\r\nContent-Length: abc\r\n\r\n",
      "POST /v1/x HTTP/1.1\r\nContent-Length: -1\r\n\r\n",
      "POST /v1/x HTTP/1.1\r\nContent-Length: 1,1\r\n\r\n",
      "POST /v1/x HTTP/1.1\r\nContent-Length: +2\r\n\r\n",
      "POST /v1/x HTTP/1.1\r\nContent-Length: 99999999999999999999\r\n\r\n",
      "POST /v1/x HTTP/1.1\r\nContent-Length: 2\r\nContent-Length: 3\r\n\r\n{}",
      "GET /v1/x HTTP/1.1\r\nAuthorization: Bearer a\r\nAuthorization: Bearer b\r\n\r\n",
      "GET /v1/a%zz HTTP/1.1\r\n\r\n",
      "GET /v1/a%4 HTTP/1.1\r\n\r\n",
      "GET /v1/a%00b HTTP/1.1\r\n\r\n",
      "GET /v1/a%C3 HTTP/1.1\r\n\r\n",
      "GET /v1/a%C3%28 HTTP/1.1\r\n\r\n",
      "GET /v1/a%ED%A0%80 HTTP/1.1\r\n\r\n",
      "GET http://example.com/v1/x HTTP/1.1\r\n\r\n",
      "GET * HTTP/1.1\r\n\r\n",
      "GET v1/x HTTP/1.1\r\n\r\n",
      "GET /v1/x#frag HTTP/1.1\r\n\r\n",
      "GET /v1/x  HTTP/1.1\r\n\r\n",
      "GET /v1/x\r\n\r\n",
      "get /v1/x HTTP/1.1\r\n\r\n",
      "GET /v1/x HTTP/1.1\r\nNoColon\r\n\r\n",
      "GET /v1/x HTTP/1.1\r\nBad Name: 1\r\n\r\n",
      "GET /v1/x HTTP/1.1\r\nHost : x\r\n\r\n",
      "GET /v1/x HTTP/1.1\r\nX-A: 1\r\n folded\r\n\r\n",
      "GET /v1/x HTTP/1.1\r\nX-A: a\u0001b\r\n\r\n",
      "GET /v1/x HTTP/1.1\r\nX-A: a\rb\r\n\r\n",
      "\u0016\u0003\u0001\u0002\u0000\u0001\u0000\u0001\r\n\r\n",
    };
    try (HttpServer server = start()) {
      for (String request : bad) {
        try (Raw c = new Raw(server.port())) {
          Raw.Reply r = c.exchange(request);
          Check.isTrue(r.status == 400, "400 for " + request.replace("\r\n", "|") + " but got " + r.status);
          Check.isTrue(c.closedWithin(1000), "closed after " + request);
        }
      }
      try (Raw c = new Raw(server.port())) {
        Raw.Reply r = c.exchange("POST /v1/x HTTP/1.1\r\nContent-Length: abc\r\n\r\n");
        Check.equal(envelope("invalid_length"), r.text());
      }
      try (Raw c = new Raw(server.port())) {
        Check.equal(envelope("invalid_request"), c.exchange("GET /v1/a%zz HTTP/1.1\r\n\r\n").text());
      }
      try (Raw c = new Raw(server.port())) {
        Check.equal(505, c.exchange("GET /v1/x HTTP/2.0\r\n\r\n").status);
      }
      Check.equal(0, api.requests.size());
    }
  }

  public void test_C_HTTP_8_the_path_is_percent_decoded_as_utf8_and_the_query_kept_raw() throws Exception {
    try (HttpServer server = start(); Raw c = new Raw(server.port())) {
      c.exchange("GET /v1/caf%C3%A9/a%20b%2Fc+d%7e?q=a%2Fb+c&x HTTP/1.1\r\n\r\n");
      Check.equal("/v1/café/a b/c+d~", api.last().path);
      Check.equal("q=a%2Fb+c&x", api.last().query);
      c.exchange("GET /v1/x? HTTP/1.1\r\n\r\n");
      Check.equal("/v1/x", api.last().path);
      Check.equal("", api.last().query);
      // A decoded prefix is still the API, so the loopback rule sees what the router routes.
      c.exchange("GET /%761/healthz HTTP/1.1\r\n\r\n");
      Check.equal("/v1/healthz", api.last().path);
    }
  }

  public void test_C_HTTP_9_api_and_static_responses_carry_the_common_headers() throws Exception {
    api.answer = r -> {
      Map<String, String> extra = new java.util.LinkedHashMap<>();
      extra.put("Cache-Control", "public, max-age=60");
      extra.put("Retry-After", "2");
      return new ApiResponse(429, "application/json", "{}".getBytes(StandardCharsets.UTF_8), extra);
    };
    try (HttpServer server = start(); Raw c = new Raw(server.port())) {
      Raw.Reply r = c.exchange("GET /v1/sessions/s HTTP/1.1\r\n\r\n");
      Check.equal(429, r.status);
      Check.equal("HTTP/1.1 429 Too Many Requests", r.statusLine);
      Check.equal("2", r.header("retry-after"));
      Check.equal("no-store", r.header("cache-control"));
      Check.equal("nosniff", r.header("x-content-type-options"));
      Check.equal("no-referrer", r.header("referrer-policy"));
      Check.equal("camera=(), microphone=(), geolocation=()", r.header("permissions-policy"));
      Check.equal(CSP, r.header("content-security-policy"));
      Check.equal(null, r.header("strict-transport-security"));
      long cacheControls = r.headerLines.stream().filter(h -> h.toLowerCase().startsWith("cache-control:")).count();
      Check.equal(1L, cacheControls);
      Raw.Reply s = c.exchange("GET /assets/app.js HTTP/1.1\r\n\r\n");
      Check.equal(200, s.status);
      Check.equal("no-cache", s.header("cache-control"));
      Check.equal("nosniff", s.header("x-content-type-options"));
      Check.equal("no-referrer", s.header("referrer-policy"));
      Check.equal("camera=(), microphone=(), geolocation=()", s.header("permissions-policy"));
      Check.equal(CSP, s.header("content-security-policy"));
      Raw.Reply e = c.exchange("GET /missing.js HTTP/1.1\r\n\r\n");
      Check.equal(404, e.status);
      Check.equal(CSP, e.header("content-security-policy"));
      Check.equal("no-cache", e.header("cache-control"));
    }
    try (HttpServer server = start(); Raw c = new Raw(server.port())) {
      Raw.Reply e = c.exchange("POST /v1/x HTTP/1.1\r\nContent-Length: x\r\n\r\n");
      Check.equal("no-store", e.header("cache-control"));
      Check.equal(CSP, e.header("content-security-policy"));
      Check.equal("application/json", e.header("content-type"));
    }
  }

  public void test_C_HTTP_10_permissions_policy_allows_the_camera_on_phone_only() throws Exception {
    try (HttpServer server = start(); Raw c = new Raw(server.port())) {
      Check.equal("camera=(self), microphone=(), geolocation=()",
          c.exchange("GET /phone HTTP/1.1\r\n\r\n").header("permissions-policy"));
      for (String path : new String[] {"/", "/tv", "/phone/", "/phone?x=1", "/assets/app.js", "/v1/phone"}) {
        String policy = c.exchange("GET " + path + " HTTP/1.1\r\n\r\n").header("permissions-policy");
        boolean phone = path.equals("/phone?x=1");
        Check.equal(phone ? "camera=(self), microphone=(), geolocation=()" : "camera=(), microphone=(), geolocation=()", policy);
      }
    }
  }

  public void test_C_HTTP_11_an_exception_in_handle_becomes_a_500_envelope_and_serving_goes_on() throws Exception {
    api.answer = r -> {
      if (r.path.endsWith("/boom")) throw new IllegalStateException("bug in the core");
      if (r.path.endsWith("/deep")) throw new StackOverflowError();
      return ApiResponse.json(200, "{}");
    };
    try (HttpServer server = start(); Raw c = new Raw(server.port())) {
      Raw.Reply r = c.exchange("GET /v1/boom HTTP/1.1\r\n\r\n");
      Check.equal(500, r.status);
      Check.equal("{\"status\":\"unavailable\",\"version\":null,\"data\":{},\"error\":{\"code\":\"internal_error\",\"message\":\"internal error\"}}",
          r.text());
      Check.equal("no-store", r.header("cache-control"));
      Check.equal(200, c.exchange("GET /v1/fine HTTP/1.1\r\n\r\n").status);
      Check.equal(500, c.exchange("GET /v1/deep HTTP/1.1\r\n\r\n").status);
      Check.equal(500, c.exchange("GET /v1/boom HTTP/1.1\r\n\r\n").status);
      Check.equal(200, c.exchange("GET /v1/fine HTTP/1.1\r\n\r\n").status);
      Check.equal(3, log.size());
      Check.isTrue(log.get(0).contains("IllegalStateException") && log.get(0).contains("/v1/boom"), log.get(0));
    }
  }

  public void test_C_HTTP_12_a_full_pool_answers_503_and_closes() throws Exception {
    HttpServer.Options o = fast();
    o.workers = 1;
    o.queue = 0;
    o.headerTimeoutMillis = 3000;
    try (HttpServer server = start(o, webFiles()); Raw holder = new Raw(server.port())) {
      holder.send("GET /v1/x HTTP/1.1\r\n"); // occupies the only worker
      Thread.sleep(200);
      try (Raw refused = new Raw(server.port())) {
        Raw.Reply r = refused.read();
        Check.equal(503, r.status);
        Check.equal("{\"status\":\"unavailable\",\"version\":null,\"data\":{},\"error\":{\"code\":\"server_busy\",\"message\":\"server busy\"}}",
            r.text());
        Check.equal("close", r.header("connection"));
        Check.isTrue(refused.closedWithin(1000), "closed");
      }
      holder.send("\r\n");
      Raw.Reply r = holder.read();
      Check.equal(200, r.status);
      Check.equal("close", r.header("connection")); // nobody waits, but the pool is all taken by this one
    }
  }

  public void test_C_HTTP_13_head_sends_headers_without_a_body() throws Exception {
    try (HttpServer server = start(); Raw c = new Raw(server.port())) {
      Raw.Reply h = c.exchange("HEAD /assets/app.js HTTP/1.1\r\n\r\n");
      Check.equal(200, h.status);
      Check.equal("14", h.header("content-length"));
      Check.equal(0, h.body.length);
      // The next response starts right away, so no body bytes were sent.
      Raw.Reply g = c.exchange("GET /assets/app.js HTTP/1.1\r\n\r\n");
      Check.equal("console.log(1)", g.text());
      Check.equal(405, c.exchange("POST /assets/app.js HTTP/1.1\r\nContent-Length: 0\r\n\r\n").status);
    }
  }

  public void test_C_HTTP_14_expect_100_continue_gets_an_interim_response() throws Exception {
    try (HttpServer server = start(); Raw c = new Raw(server.port())) {
      c.send("POST /v1/x HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: 2\r\nExpect: 100-continue\r\n\r\n");
      c.socket.setSoTimeout(1000);
      byte[] interim = new byte[25];
      int got = 0;
      while (got < interim.length) got += c.in.read(interim, got, interim.length - got);
      Check.equal("HTTP/1.1 100 Continue\r\n\r\n", new String(interim, StandardCharsets.ISO_8859_1));
      c.send("{}");
      Check.equal(200, c.read().status);
      Check.equal("{}", new String(api.last().body, StandardCharsets.UTF_8));
    }
    try (HttpServer server = start(); Raw c = new Raw(server.port())) {
      Check.equal(417, c.exchange("POST /v1/x HTTP/1.1\r\nContent-Length: 2\r\nExpect: something\r\n\r\n").status);
    }
  }

  public void test_C_HTTP_15_unsafe_response_headers_from_the_api_become_a_500() throws Exception {
    api.answer = r -> {
      Map<String, String> extra = new java.util.LinkedHashMap<>();
      extra.put("X-Evil", "a\r\nSet-Cookie: x=1");
      return new ApiResponse(200, "application/json", new byte[0], extra);
    };
    try (HttpServer server = start(); Raw c = new Raw(server.port())) {
      Raw.Reply r = c.exchange("GET /v1/x HTTP/1.1\r\n\r\n");
      Check.equal(500, r.status);
      Check.equal(null, r.header("set-cookie"));
    }
    api.answer = r -> new ApiResponse(204, null, new byte[0], Collections.<String, String>emptyMap());
    try (HttpServer server = start(); Raw c = new Raw(server.port())) {
      Raw.Reply r = c.exchange("GET /v1/x HTTP/1.1\r\n\r\n");
      Check.equal(204, r.status);
      Check.equal(null, r.header("content-type"));
      Check.equal(null, r.header("content-length")); // RFC 9110 §8.6: never on a 204
      Check.equal(200, c.exchange("GET /tv HTTP/1.1\r\n\r\n").status);
    }
  }

  public void test_C_HTTP_16_a_client_that_never_reads_is_cut_off_and_frees_its_worker() throws Exception {
    // A 256 MB virtual file: more than any socket buffer can hold.
    final long size = 256L << 20;
    StaticSource huge = new StaticSource() {
      @Override public long length(String name) {
        return name.equals("index.html") ? size : -1;
      }

      @Override public InputStream open(String name) {
        return new InputStream() {
          long left = size;

          @Override public int read() {
            return left-- > 0 ? 0 : -1;
          }

          @Override public int read(byte[] b, int off, int len) {
            if (left <= 0) return -1;
            int n = (int) Math.min(len, left);
            java.util.Arrays.fill(b, off, off + n, (byte) 'x');
            left -= n;
            return n;
          }
        };
      }
    };
    HttpServer.Options o = fast();
    o.workers = 1;
    o.queue = 4;
    try (HttpServer server = start(o, huge)) {
      Socket stuck = new Socket();
      stuck.setReceiveBufferSize(4096);
      stuck.connect(new java.net.InetSocketAddress("127.0.0.1", server.port()));
      try (Raw c = new Raw(stuck)) {
        c.send("GET /tv HTTP/1.1\r\n\r\n");
        Thread.sleep(300); // never read
        long t0 = System.nanoTime();
        try (Raw next = new Raw(server.port())) {
          next.socket.setSoTimeout(4000);
          Check.equal(200, next.exchange("GET /v1/x HTTP/1.1\r\n\r\n").status);
        }
        Check.isTrue((System.nanoTime() - t0) / 1e6 < 3000, "the stuck writer was cut off by the write deadline");
      }
    }
  }

  public void test_C_HTTP_17_close_stops_listening_and_ends_open_connections() throws Exception {
    HttpServer server = start();
    int port = server.port();
    try (Raw c = new Raw(port)) {
      Check.equal(200, c.exchange("GET /v1/x HTTP/1.1\r\n\r\n").status);
      server.close();
      Check.isTrue(c.closedWithin(2000), "open keep-alive connection ended");
    }
    Check.raises(IOException.class, () -> new Socket(InetAddress.getByName("127.0.0.1"), port).close());
    server.close(); // twice is fine
  }

  public void test_C_HTTP_18_too_many_connections_from_one_peer_are_refused() throws Exception {
    HttpServer.Options o = fast();
    o.perPeer = 2;
    o.limitLoopback = true; // the real servers exempt the TV itself (loopback); this test has only loopback peers
    o.headerTimeoutMillis = 3000;
    try (HttpServer server = start(o, webFiles()); Raw b = new Raw(server.port())) {
      Raw a = new Raw(server.port());
      Thread.sleep(100);
      try (Raw third = new Raw(server.port())) {
        Check.isTrue(third.closedWithin(1000), "third connection from the same peer closed");
      }
      Check.equal(200, a.exchange("GET /v1/x HTTP/1.1\r\n\r\n").status);
      Check.isTrue(!b.socket.isClosed(), "the second connection still holds its slot");
      a.close(); // frees one slot
      Thread.sleep(200);
      try (Raw again = new Raw(server.port())) {
        Check.equal(200, again.exchange("GET /v1/x HTTP/1.1\r\n\r\n").status);
      }
    }
  }

  public void test_C_HTTP_19_lf_only_line_endings_are_accepted() throws Exception {
    try (HttpServer server = start(); Raw c = new Raw(server.port())) {
      Check.equal(200, c.exchange("GET /v1/x HTTP/1.1\nHost: x\n\n").status);
      Check.equal(200, c.exchange("POST /v1/y HTTP/1.1\nContent-Length: 2\n\n{}").status);
      Check.equal("{}", new String(api.last().body, StandardCharsets.UTF_8));
    }
  }
}
