package org.cleanrep.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;
import org.cleanrep.core.http.Listeners;
import org.cleanrep.core.http.Log;
import org.cleanrep.core.testing.Check;
import org.cleanrep.phone.PinnedTls;

/** C-MAIN-1..3: the JVM entry point's flags, the real core behind real listeners, restart and the HTTPS listener. */
public class MainTest {
  final List<String> log = Collections.synchronizedList(new ArrayList<String>());
  final Log sink = (message, error) -> log.add(message);

  static File scratch(String name) {
    File dir = new File(System.getProperty("cleanrep.root"), "temp/core-tests/" + name + "-" + UUID.randomUUID().toString().substring(0, 8));
    if (!dir.mkdirs()) throw new IllegalStateException("cannot create " + dir);
    return dir;
  }

  /** A server on 127.0.0.1 and free ports, with the repo's catalog and contracts and a one-file web build. */
  static Main.Options options(File dir, boolean https) throws IOException {
    File web = new File(dir, "web");
    if (!web.isDirectory() && !web.mkdirs()) throw new IOException("cannot create " + web);
    try (OutputStream out = new FileOutputStream(new File(web, "index.html"))) {
      out.write("<!doctype html><title>CleanRep</title>".getBytes(StandardCharsets.UTF_8));
    }
    List<String> args = new ArrayList<>(Arrays.asList(
        "--port", "0", "--data", new File(dir, "data").getPath(), "--static", web.getPath(),
        "--root", System.getProperty("cleanrep.root"), "--tls-dir", new File(dir, "tls").getPath()));
    if (https) args.addAll(Arrays.asList("--https-port", "0"));
    return Main.Options.parse(args.toArray(new String[0]));
  }

  static final class Reply {
    int status;
    String type;
    String body;

    Map<String, Object> json() {
      return Json.map(Json.parse(body));
    }
  }

  static Reply call(HttpURLConnection conn, String method, String token, String body) throws IOException {
    conn.setRequestMethod(method);
    conn.setConnectTimeout(3000);
    conn.setReadTimeout(5000);
    if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token);
    if (body != null) {
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setDoOutput(true);
      conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }
    Reply r = new Reply();
    r.status = conn.getResponseCode();
    r.type = conn.getContentType();
    InputStream in = r.status < 400 ? conn.getInputStream() : conn.getErrorStream();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    if (in != null) {
      try (InputStream stream = in) {
        byte[] buffer = new byte[4096];
        int n;
        while ((n = stream.read(buffer)) != -1) bytes.write(buffer, 0, n);
      }
    }
    r.body = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    return r;
  }

  static Reply http(int port, String method, String path, String token, String body) throws IOException {
    return call((HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection(), method, token, body);
  }

  public void test_C_MAIN_1_flags_defaults_values_and_refusals() {
    Main.Options d = Main.Options.parse(new String[0]);
    Check.equal("127.0.0.1", d.host);
    Check.equal(8105, d.port);
    Check.equal(-1, d.httpsPort);
    Check.equal(null, d.origin);
    Check.equal(new File("var/home"), d.data);
    Check.equal(new File("apps/web/dist"), d.staticDir);
    Check.isTrue(!d.allowReplay, "replay off by default");
    Check.equal(0, d.maxSeconds);
    Main.Options o = Main.Options.parse(new String[] {
      "--host", "0.0.0.0", "--port", "8125", "--https-port", "8443", "--origin", "http://127.0.0.1:8125",
      "--data", "var/x", "--static", "dist", "--root", "/r", "--allow-replay", "--tls-dir", "t", "--max-seconds", "300",
    });
    Check.equal("0.0.0.0", o.host);
    Check.equal(8125, o.port);
    Check.equal(8443, o.httpsPort);
    Check.equal("http://127.0.0.1:8125", o.origin);
    Check.equal(new File("var/x"), o.data);
    Check.equal(new File("dist"), o.staticDir);
    Check.equal(new File("/r"), o.root);
    Check.isTrue(o.allowReplay, "--allow-replay");
    Check.equal(new File("t"), o.tlsDir);
    Check.equal(300, o.maxSeconds);
    String[][] bad = {
      {"--bogus"}, {"--port"}, {"--port", "65536"}, {"--port", "-1"}, {"--port", "8x"}, {"--https-port", ""},
      {"--max-seconds", "0"}, {"--origin", "http://a/b"}, {"--origin", "ftp://a"}, {"--origin", "http://u@a"},
      {"--allow-replay", "--data"},
    };
    for (String[] args : bad) {
      Check.raises(IllegalArgumentException.class, () -> Main.Options.parse(args));
    }
    Check.isTrue(Main.USAGE.contains("--https-port") && Main.USAGE.contains("--max-seconds"), "usage names the flags");
  }

  public void test_C_MAIN_2_the_real_core_serves_pages_and_api_and_keeps_sessions_across_a_restart() throws Exception {
    File dir = scratch("main2");
    try {
      Main.Options o = options(dir, false);
      String token;
      String session;
      try (Main.Server server = Main.start(o, sink)) {
        int port = server.listeners.httpPort();
        Check.equal("http://127.0.0.1:" + port, server.config.origin);
        Check.isTrue(server.readyLine().startsWith("CleanRep core: serving http://127.0.0.1:" + port), server.readyLine());
        Reply health = http(port, "GET", "/v1/healthz", null, null);
        Check.equal(200, health.status);
        Check.equal("json", Json.str(Json.map(health.json().get("data")), "store"));
        Reply page = http(port, "GET", "/phone", null, null);
        Check.equal(200, page.status);
        Check.equal("<!doctype html><title>CleanRep</title>", page.body);
        Check.equal(404, http(port, "GET", "/cleanrep-phone.apk", null, null).status);
        token = Json.str(Json.map(http(port, "POST", "/v1/bootstrap", null, "{}").json().get("data")), "tv_token");
        Reply created = http(port, "POST", "/v1/sessions", token, "{}");
        Check.equal(200, created.status);
        Map<String, Object> data = Json.map(created.json().get("data"));
        session = Json.str(data, "session_id");
        Check.equal("LOBBY", Json.str(data, "phase"));
        Check.equal(null, data.get("phone_url")); // HTTP only, on loopback: the TV derives it
        // The same data folder can't get a second owner, and the failed start leaves no port bound.
        Check.raises(IllegalStateException.class, () -> Main.start(options(dir, false), sink));
        Check.equal(200, http(port, "GET", "/v1/healthz", null, null).status);
      }
      try (Main.Server again = Main.start(o, sink)) {
        Reply state = http(again.listeners.httpPort(), "GET", "/v1/sessions/" + session, token, null);
        Check.equal(200, state.status);
        Check.equal(session, Json.str(Json.map(state.json().get("data")), "session_id"));
        Check.equal(0, Json.integer(state.json().get("version")));
      }
    } finally {
      TestFiles.remove(dir);
    }
  }

  public void test_C_MAIN_3_https_listener_pins_a_stable_identity_and_names_the_phone_origins() throws Exception {
    File dir = scratch("main3");
    try {
      Main.Options o = options(dir, true);
      String fingerprint;
      try (Main.Server server = Main.start(o, sink)) {
        int https = server.listeners.httpsPort();
        Check.isTrue(https > 0, "HTTPS bound");
        fingerprint = server.tls.fingerprint();
        Check.isTrue(server.readyLine().contains("fingerprint " + fingerprint), server.readyLine());
        Check.isTrue(server.readyLine().contains("cleanrep://pair?host=<ip>&port=" + https + "&code=<code>&fp=" + fingerprint),
            server.readyLine());
        Check.isTrue(server.config.origins().contains("https://127.0.0.1:" + https), "127.0.0.1 origin");
        Check.isTrue(server.config.origins().contains("https://localhost:" + https), "localhost origin");
        Check.equal(null, server.config.phoneUrl); // bound to loopback: no phone can dial it
        PinnedTls pin = new PinnedTls("127.0.0.1", fingerprint);
        HttpsURLConnection conn = (HttpsURLConnection) new URL("https://127.0.0.1:" + https + "/v1/healthz").openConnection();
        conn.setSSLSocketFactory(pin.socketFactory());
        conn.setHostnameVerifier(pin.hostnameVerifier());
        Check.equal(200, call(conn, "GET", null, null).status);
        // 8105's rules on the plain port: loopback may use the API there.
        Check.equal(200, http(server.listeners.httpPort(), "GET", "/v1/healthz", null, null).status);
      }
      File password = new File(dir, "tls/tv.password");
      Check.isTrue(password.isFile() && new File(dir, "tls/tv.p12").isFile(), "identity and password kept");
      try (Main.Server again = Main.start(o, sink)) {
        Check.equal(fingerprint, again.tls.fingerprint());
      }
      Check.equal(0, log.size());
      // The pure helpers the TV app shares.
      Check.equal(Arrays.asList("https://192.168.1.20:8443", "https://localhost:8443", "https://127.0.0.1:8443"),
          new ArrayList<>(Listeners.phoneOrigins("192.168.1.20", 8443)));
      Check.equal(Arrays.asList("https://localhost:8443", "https://127.0.0.1:8443"), new ArrayList<>(Listeners.phoneOrigins(null, 8443)));
      Check.equal("http://192.168.1.20:8105/phone", Listeners.phoneUrl("192.168.1.20", 8105));
      Check.equal(null, Listeners.phoneUrl(null, 8105));
      // The TV only advertises an address the phone app accepts: the same rule as PhonePolicy, compiled in from apps/phone.
      for (String host : Arrays.asList("10.217.25.162", "10.0.0.0", "172.16.0.1", "172.31.255.255", "192.168.1.20",
          "172.15.0.1", "172.32.0.1", "192.169.1.1", "127.0.0.1", "8.8.8.8", "010.1.1.1", "10.1.1", "10.1.1.1.1",
          "10.256.1.1", "10.1.1.1 ", "localhost", "", "1e1.1.1.1", "١٠.1.1.1")) {
        Check.isTrue(org.cleanrep.phone.PhonePolicy.isPrivateNonLoopbackHost(host) == Listeners.isPrivateIPv4(host), "same rule for " + host);
      }
      Check.isTrue(Listeners.isPrivateIPv4("10.217.25.162") && !Listeners.isPrivateIPv4(null), "private and null");
      // A busy port fails before the store is opened, so nothing is left locked.
      try (ServerSocket busy = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))) {
        Main.Options clash = options(dir, false);
        clash.port = busy.getLocalPort();
        Check.raises(IOException.class, () -> Main.start(clash, sink));
      }
      Main.start(options(dir, false), sink).close();
    } finally {
      TestFiles.remove(dir);
    }
  }
}
