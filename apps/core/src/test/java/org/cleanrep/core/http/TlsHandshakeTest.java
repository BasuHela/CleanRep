package org.cleanrep.core.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import org.cleanrep.core.testing.Check;
import org.cleanrep.phone.PhonePolicy;
import org.cleanrep.phone.PinnedTls;

/** C-TLS-11..15: real TLS handshakes on 127.0.0.1 between the 8443 listener and the phone app's own PinnedTls. */
public class TlsHandshakeTest {
  static final TlsIdentity TV = identity();
  static final TlsIdentity OTHER = identity();

  final FakeApi api = new FakeApi();
  final List<String> log = Collections.synchronizedList(new ArrayList<String>());

  static TlsIdentity identity() {
    try {
      return TlsIdentity.generate(new SecureRandom(), System.currentTimeMillis());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  HttpServer start(HttpServer.Options options) throws IOException {
    ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
    HttpServer server = new HttpServer("https", socket, TV, Router.secure(api, Temp.log(log)), options, Temp.log(log));
    server.start();
    return server;
  }

  /** What the phone app does: HttpsURLConnection with PinnedTls's socket factory and host check. */
  static HttpURLConnection phone(int port, String fingerprint, String path) throws IOException {
    PinnedTls tls = new PinnedTls("127.0.0.1", fingerprint);
    PhonePolicy.ServerConfig config = PhonePolicy.ServerConfig.validate("127.0.0.1", port, fingerprint);
    HttpsURLConnection conn = (HttpsURLConnection) new URL(config.origin() + path).openConnection();
    conn.setSSLSocketFactory(tls.socketFactory());
    conn.setHostnameVerifier(tls.hostnameVerifier());
    conn.setConnectTimeout(3000);
    conn.setReadTimeout(5000);
    conn.setRequestProperty("Origin", config.origin());
    conn.setRequestProperty("Content-Type", "application/json");
    return conn;
  }

  static String read(HttpURLConnection conn) throws IOException {
    InputStream in = conn.getResponseCode() < 400 ? conn.getInputStream() : conn.getErrorStream();
    byte[] buffer = new byte[4096];
    StringBuilder out = new StringBuilder();
    int n;
    while ((n = in.read(buffer)) != -1) out.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
    return out.toString();
  }

  public void test_C_TLS_11_the_phone_pin_gets_an_answer_from_the_api() throws Exception {
    try (HttpServer server = start(HttpServerTest.fast())) {
      HttpURLConnection conn = phone(server.port(), TV.fingerprint(), "/v1/healthz");
      Check.equal(200, conn.getResponseCode());
      Check.isTrue(read(conn).contains("/v1/healthz"), "the fake API answered");
      Check.equal("127.0.0.1", api.last().peer);
      // The JDK's HttpURLConnection drops Origin (a restricted header; Android's does not), so check it raw.
      PhonePolicy.ServerConfig config = PhonePolicy.ServerConfig.validate("127.0.0.1", server.port(), TV.fingerprint());
      PinnedTls tls = new PinnedTls("127.0.0.1", TV.fingerprint());
      try (Raw c = new Raw(tls.socketFactory().createSocket("127.0.0.1", server.port()))) {
        Check.equal(200, c.exchange("GET /v1/healthz HTTP/1.1\r\nOrigin: " + config.origin() + "\r\n\r\n").status);
      }
      Check.equal("https://127.0.0.1:" + server.port(), api.last().headers.get("origin"));
      HttpURLConnection post = phone(server.port(), TV.fingerprint(), "/v1/pair/request");
      post.setRequestMethod("POST");
      post.setDoOutput(true);
      post.getOutputStream().write("{\"pairing_code\":\"123456\"}".getBytes(StandardCharsets.UTF_8));
      Check.equal(200, post.getResponseCode());
      read(post);
      Check.equal("{\"pairing_code\":\"123456\"}", new String(api.last().body, StandardCharsets.UTF_8));
      // Only the API on this port.
      HttpURLConnection page = phone(server.port(), TV.fingerprint(), "/phone");
      Check.equal(404, page.getResponseCode());
    }
  }

  public void test_C_TLS_12_a_pin_for_another_certificate_fails_the_handshake() throws Exception {
    try (HttpServer server = start(HttpServerTest.fast())) {
      HttpURLConnection wrong = phone(server.port(), OTHER.fingerprint(), "/v1/healthz");
      IOException e = Check.raises(IOException.class, wrong::getResponseCode);
      Check.isTrue(e instanceof javax.net.ssl.SSLHandshakeException, "handshake failure, got " + e);
      Check.isTrue(PinnedTls.isPinMismatch(e), "the phone reports a changed key");
      Check.equal(0, api.requests.size());
      HttpURLConnection right = phone(server.port(), TV.fingerprint(), "/v1/healthz");
      Check.equal(200, right.getResponseCode());
    }
  }

  public void test_C_TLS_13_only_tls_1_2_or_later_with_ecdhe_ecdsa_aes_gcm_is_negotiated() throws Exception {
    try (HttpServer server = start(HttpServerTest.fast())) {
      PinnedTls tls = new PinnedTls("127.0.0.1", TV.fingerprint());
      try (SSLSocket s = (SSLSocket) tls.socketFactory().createSocket("127.0.0.1", server.port())) {
        s.startHandshake();
        String protocol = s.getSession().getProtocol();
        Check.isTrue(protocol.equals("TLSv1.3") || protocol.equals("TLSv1.2"), "negotiated " + protocol);
      }
      try (SSLSocket s = (SSLSocket) tls.socketFactory().createSocket("127.0.0.1", server.port())) {
        s.setEnabledProtocols(new String[] {"TLSv1.2"});
        s.startHandshake();
        Check.equal("TLSv1.2", s.getSession().getProtocol());
        String suite = s.getSession().getCipherSuite();
        Check.isTrue(suite.startsWith("TLS_ECDHE_ECDSA_WITH_AES_") && suite.contains("_GCM_"), "suite " + suite);
      }
      // A client that offers only suites outside our list gets no handshake.
      try (SSLSocket s = (SSLSocket) tls.socketFactory().createSocket("127.0.0.1", server.port())) {
        s.setEnabledProtocols(new String[] {"TLSv1.2"});
        s.setEnabledCipherSuites(new String[] {"TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256"});
        Check.raises(IOException.class, s::startHandshake);
      }
      // A client that allows only TLS 1.1 gets no handshake (the JDK may refuse on its own side too).
      try (SSLSocket s = (SSLSocket) tls.socketFactory().createSocket("127.0.0.1", server.port())) {
        try {
          s.setEnabledProtocols(new String[] {"TLSv1.1"});
        } catch (IllegalArgumentException unsupported) {
          // not even supported here
        }
        Check.raises(IOException.class, s::startHandshake);
      }
      // What the listener enables on each socket.
      try (ServerSocket raw = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
           Socket client = new Socket(InetAddress.getByName("127.0.0.1"), raw.getLocalPort());
           Socket accepted = raw.accept()) {
        SSLSocket wrapped = TV.wrap(accepted);
        Check.isTrue(!wrapped.getUseClientMode(), "server mode");
        Check.isTrue(!wrapped.getNeedClientAuth() && !wrapped.getWantClientAuth(), "no client certificates");
        for (String p : wrapped.getEnabledProtocols()) Check.isTrue(p.equals("TLSv1.3") || p.equals("TLSv1.2"), "protocol " + p);
        for (String c : wrapped.getEnabledCipherSuites()) {
          Check.isTrue(Arrays.asList("TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
              "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384").contains(c), "suite " + c);
        }
        Check.isTrue(client.isConnected(), "the plain client was only a peer for the wrap");
      }
    }
  }

  public void test_C_TLS_14_hostile_tls_clients_are_dropped_and_the_listener_keeps_serving() throws Exception {
    try (HttpServer server = start(HttpServerTest.fast())) {
      // Connects and never starts a handshake.
      try (Raw silent = new Raw(server.port())) {
        long t0 = System.nanoTime();
        Check.isTrue(silent.closedWithin(2500), "dropped at the header deadline");
        Check.isTrue((System.nanoTime() - t0) / 1e6 < 2000, "quickly");
      }
      // Plain HTTP to the TLS port: at most a TLS alert record, then the close; never an HTTP answer.
      try (Raw plain = new Raw(server.port())) {
        plain.send("GET /v1/healthz HTTP/1.1\r\n\r\n");
        byte[] got = plain.drainUntilClosed(2500, 64);
        Check.isTrue(got != null, "plain HTTP refused");
        Check.isTrue(got.length == 0 || got[0] == 0x15, "only an alert record before the close");
      }
      // The pinned phone is still served.
      HttpURLConnection right = phone(server.port(), TV.fingerprint(), "/v1/healthz");
      Check.equal(200, right.getResponseCode());
      Check.equal(1, api.requests.size());
    }
  }

  public void test_C_TLS_15_two_requests_share_one_tls_connection() throws Exception {
    try (HttpServer server = start(HttpServerTest.fast())) {
      PinnedTls tls = new PinnedTls("127.0.0.1", TV.fingerprint());
      try (SSLSocket s = (SSLSocket) tls.socketFactory().createSocket("127.0.0.1", server.port()); Raw c = new Raw(s)) {
        Check.equal(200, c.exchange("GET /v1/one HTTP/1.1\r\nHost: tv\r\n\r\n").status);
        Check.equal(200, c.exchange("GET /v1/two HTTP/1.1\r\nHost: tv\r\n\r\n").status);
        Check.equal(404, c.exchange("GET /tv HTTP/1.1\r\nHost: tv\r\n\r\n").status);
      }
      Check.equal(2, api.requests.size());
      SSLContext.getDefault(); // the default context is untouched by the listener
    }
  }
}
