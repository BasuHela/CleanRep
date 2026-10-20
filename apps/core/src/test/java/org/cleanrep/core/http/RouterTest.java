package org.cleanrep.core.http;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cleanrep.core.Resources;
import org.cleanrep.core.testing.Check;

/** C-HTTP-20..28: which listener serves what, the loopback rule, static files, the phone APK and the TV's assets. */
public class RouterTest {
  static final String HTTPS_REQUIRED =
      "{\"status\":\"rejected\",\"version\":null,\"data\":{},\"error\":{\"code\":\"https_required\",\"message\":\"https required\"}}";

  final FakeApi api = new FakeApi();
  final List<String> log = Collections.synchronizedList(new ArrayList<String>());

  static HttpRequest get(String path, String peer) throws IOException {
    return request("GET", path, peer);
  }

  static HttpRequest request(String method, String path, String peer) throws IOException {
    return new HttpRequest(method, path, "", new HashMap<String, String>(), new byte[0], InetAddress.getByName(peer));
  }

  static String text(HttpResponse r) {
    return new String(r.body, StandardCharsets.UTF_8);
  }

  public void test_C_HTTP_20_the_loopback_rule_is_a_decision_on_the_peer_address() throws Exception {
    for (String local : new String[] {"127.0.0.1", "127.8.9.10", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1"}) {
      Check.isTrue(Router.isLocal(InetAddress.getByName(local)), local + " is loopback");
    }
    for (String remote : new String[] {"192.168.1.20", "10.0.2.2", "172.16.0.1", "0.0.0.0", "::", "fe80::1", "::ffff:192.168.1.2",
        "8.8.8.8", "100.64.0.1", "169.254.1.1"}) {
      Check.isTrue(!Router.isLocal(InetAddress.getByName(remote)), remote + " is not loopback");
    }
  }

  public void test_C_HTTP_21_port_8105_sends_v1_from_loopback_to_the_api_and_refuses_others() throws Exception {
    Router local = Router.local(api, new StaticSource.Directory(Temp.dir("r21")), Temp.log(log));
    HttpResponse ok = local.handle(get("/v1/healthz", "127.0.0.1"));
    Check.equal(200, ok.status);
    Check.equal(1, api.requests.size());
    Check.equal("127.0.0.1", api.last().peer);
    Check.equal(200, local.handle(get("/v1/healthz", "::1")).status);
    Check.equal("0:0:0:0:0:0:0:1", api.last().peer);
    for (String peer : new String[] {"192.168.1.20", "10.0.2.2", "fe80::1"}) {
      for (String method : new String[] {"GET", "POST", "DELETE", "HEAD"}) {
        HttpResponse r = local.handle(request(method, "/v1/sessions/s_1/reps", peer));
        Check.equal(403, r.status);
        Check.equal("application/json", r.contentType);
        Check.equal(HTTPS_REQUIRED, text(r));
      }
    }
    Check.equal(2, api.requests.size());
  }

  public void test_C_HTTP_22_port_8443_serves_only_the_api_for_every_peer() throws Exception {
    Router secure = Router.secure(api, Temp.log(log));
    Check.equal(200, secure.handle(get("/v1/healthz", "192.168.1.20")).status);
    Check.equal("192.168.1.20", api.last().peer);
    Check.equal(200, secure.handle(get("/v1/healthz", "127.0.0.1")).status);
    for (String path : new String[] {"/", "/phone", "/tv", "/index.html", "/assets/app.js", "/cleanrep-phone.apk", "/v1", "/v2/x"}) {
      HttpResponse r = secure.handle(get(path, "192.168.1.20"));
      Check.isTrue(r.status == 404, path + " gave " + r.status);
      Check.equal("{\"detail\":\"Not Found\"}", text(r));
    }
    Check.equal(2, api.requests.size());
  }

  public void test_C_HTTP_23_index_mime_types_and_refused_names() throws Exception {
    File dir = Temp.dir("r23");
    String[][] files = {
      {"index.html", "text/html; charset=utf-8"}, {"assets/a.js", "text/javascript; charset=utf-8"},
      {"vision/v.mjs", "text/javascript; charset=utf-8"}, {"assets/a.css", "text/css; charset=utf-8"},
      {"demo/v.mp4", "video/mp4"}, {"p.png", "image/png"}, {"j.jpg", "image/jpeg"}, {"i.svg", "image/svg+xml"},
      {"w/x.wasm", "application/wasm"}, {"f.woff2", "font/woff2"}, {"d.json", "application/json"},
      {"models/pose.task", "application/octet-stream"}, {"LICENSE.txt", "text/plain; charset=utf-8"},
      {"other.bin", "application/octet-stream"},
    };
    for (String[] f : files) HttpServerTest.write(new File(dir, f[0]), "x-" + f[0]);
    HttpServerTest.write(new File(dir, ".env"), "SECRET=1");
    HttpServerTest.write(new File(dir, "assets/.hidden.js"), "x");
    HttpServerTest.write(new File(dir, "cleanrep-home/domain/catalog.json"), "{}");
    HttpServerTest.write(new File(dir.getParentFile(), dir.getName() + "-outside.txt"), "outside");
    Router local = Router.local(api, new StaticSource.Directory(dir), Temp.log(log));
    for (String index : new String[] {"/", "/phone", "/tv", "/index.html"}) {
      HttpResponse r = local.handle(get(index, "192.168.1.20"));
      Check.equal(200, r.status);
      Check.equal("index.html", r.file);
      Check.equal("text/html; charset=utf-8", r.contentType);
    }
    for (String[] f : files) {
      HttpResponse r = local.handle(get("/" + f[0], "192.168.1.20"));
      Check.isTrue(r.status == 200, f[0] + " gave " + r.status);
      Check.equal(f[1], r.contentType);
      Check.equal((long) ("x-" + f[0]).length(), r.length);
    }
    Check.equal(200, local.handle(request("HEAD", "/assets/a.js", "192.168.1.20")).status);
    String[] refused = {
      "/.env", "/assets/.hidden.js", "/assets/../index.html", "/..", "/../" + dir.getName() + "-outside.txt",
      "/assets/./a.js", "//index.html", "/assets//a.js", "/assets/", "/assets", "/missing.js", "/a\\b.js",
      "/cleanrep-home/domain/catalog.json", "/cleanrep-home/cleanrep-phone.apk", "/phone/", "/tv/x", "/index.html/",
    };
    for (String path : refused) {
      HttpResponse r = local.handle(get(path, "192.168.1.20"));
      Check.isTrue(r.status == 404, path + " gave " + r.status);
      Check.equal("{\"detail\":\"Not Found\"}", text(r));
    }
    HttpResponse post = local.handle(request("POST", "/tv", "127.0.0.1"));
    Check.equal(405, post.status);
    Check.equal("GET, HEAD", post.headers.get("Allow"));
    Check.equal(0, api.requests.size());
  }

  public void test_C_HTTP_24_the_phone_apk_downloads_when_the_source_has_it() throws Exception {
    File dir = Temp.dir("r24");
    Router without = Router.local(api, new StaticSource.Directory(dir), Temp.log(log));
    Check.equal(404, without.handle(get("/cleanrep-phone.apk", "192.168.1.20")).status);
    HttpServerTest.write(new File(dir, HomeAssets.PHONE_APK), "PK-apk-bytes");
    Router with = Router.local(api, new StaticSource.Directory(dir), Temp.log(log));
    HttpResponse r = with.handle(get("/cleanrep-phone.apk", "192.168.1.20"));
    Check.equal(200, r.status);
    Check.equal("application/vnd.android.package-archive", r.contentType);
    Check.equal("attachment; filename=\"cleanrep-phone.apk\"", r.headers.get("Content-Disposition"));
    Check.equal(HomeAssets.PHONE_APK, r.file);
    Check.equal(12L, r.length);
  }

  public void test_C_HTTP_25_static_files_and_the_apk_over_a_real_socket() throws Exception {
    File dir = Temp.dir("r25");
    HttpServerTest.write(new File(dir, "index.html"), "<!doctype html>");
    HttpServerTest.write(new File(dir, HomeAssets.PHONE_APK), "PK-apk-bytes");
    ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
    HttpServer.Options o = HttpServerTest.fast();
    try (HttpServer server = new HttpServer("t", socket, null, Router.local(api, new StaticSource.Directory(dir), Temp.log(log)), o, Temp.log(log));
         Raw c = new Raw(startAndPort(server))) {
      Raw.Reply index = c.exchange("GET /phone HTTP/1.1\r\n\r\n");
      Check.equal("<!doctype html>", index.text());
      Check.equal("text/html; charset=utf-8", index.header("content-type"));
      Raw.Reply apk = c.exchange("GET /cleanrep-phone.apk HTTP/1.1\r\n\r\n");
      Check.equal("PK-apk-bytes", apk.text());
      Check.equal("application/vnd.android.package-archive", apk.header("content-type"));
      Check.equal("attachment; filename=\"cleanrep-phone.apk\"", apk.header("content-disposition"));
      Check.equal(404, c.exchange("GET /%2e%2e/" + dir.getName() + "/index.html HTTP/1.1\r\n\r\n").status);
      Check.equal(404, c.exchange("GET /assets/%2E%2E/index.html HTTP/1.1\r\n\r\n").status);
      Check.equal(404, c.exchange("GET /cleanrep-home%2Fcleanrep-phone.apk HTTP/1.1\r\n\r\n").status);
    }
  }

  static int startAndPort(HttpServer server) {
    server.start();
    return server.port();
  }

  public void test_C_HTTP_26_the_tv_asset_resources_apply_the_repo_name_rules() throws Exception {
    final Map<String, byte[]> assets = new HashMap<>();
    assets.put("cleanrep-home/domain/catalog.json", "{\"exercises\":[]}".getBytes(StandardCharsets.UTF_8));
    assets.put("cleanrep-home/contracts/RepEvent.json", "{}".getBytes(StandardCharsets.UTF_8));
    Resources resources = HomeAssets.resources(name -> {
      byte[] b = assets.get(name);
      if (b == null) throw new FileNotFoundException(name);
      return new ByteArrayInputStream(b);
    });
    Check.equal("{\"exercises\":[]}", new String(resources.read("domain/catalog.json"), StandardCharsets.UTF_8));
    Check.equal("{}", new String(resources.read("contracts/RepEvent.json"), StandardCharsets.UTF_8));
    for (String name : new String[] {"../AGENTS.md", "domain/../../AGENTS.md", "/etc/passwd", "services/api/app.py",
        "domain/x y.json", "contracts/Missing.json", "fixtures/landmarks/squat_good_10.json", "cleanrep-phone.apk", ""}) {
      Check.raises(FileNotFoundException.class, () -> resources.read(name));
    }
    Check.equal("cleanrep-home/contracts/RepEvent.json", HomeAssets.assetName("contracts/RepEvent.json"));
    Check.equal("cleanrep-home/fixtures/landmarks/a.json", HomeAssets.assetName("fixtures/landmarks/a.json"));
  }

  public void test_C_HTTP_27_static_source_reads_stay_inside_the_folder() throws Exception {
    File dir = Temp.dir("r27");
    HttpServerTest.write(new File(dir, "a/b.txt"), "hello");
    StaticSource.Directory files = new StaticSource.Directory(dir);
    Check.equal(5L, files.length("a/b.txt"));
    try (InputStream in = files.open("a/b.txt")) {
      Check.equal('h', (char) in.read());
    }
    Check.equal(-1L, files.length("a"));
    Check.equal(-1L, files.length("missing"));
    Check.equal(-1L, files.length("../" + dir.getName() + "/a/b.txt"));
    File outside = new File(dir.getParentFile(), dir.getName() + "-secret.txt");
    HttpServerTest.write(outside, "secret");
    try {
      java.nio.file.Files.createSymbolicLink(new File(dir, "link.txt").toPath(), outside.toPath());
      Check.equal(-1L, files.length("link.txt"));
    } catch (UnsupportedOperationException | IOException e) {
      // no symlinks on this file system
    }
  }

  public void test_C_HTTP_28_api_responses_keep_status_type_body_and_extra_headers() throws Exception {
    api.answer = r -> {
      Map<String, String> extra = new HashMap<>();
      extra.put("Retry-After", "3");
      return new org.cleanrep.core.ApiResponse(409, "application/json", "{\"x\":1}".getBytes(StandardCharsets.UTF_8), extra);
    };
    Router local = Router.local(api, new StaticSource.Directory(Temp.dir("r28")), Temp.log(log));
    HttpResponse r = local.handle(request("DELETE", "/v1/sessions/s", "127.0.0.1"));
    Check.equal(409, r.status);
    Check.equal("application/json", r.contentType);
    Check.equal("{\"x\":1}", text(r));
    Check.equal("3", r.headers.get("Retry-After"));
    api.answer = req -> null;
    Check.equal(500, local.handle(get("/v1/x", "127.0.0.1")).status);
  }
}
