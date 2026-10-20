package org.cleanrep.core.http;

import java.io.Closeable;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.cleanrep.core.Api;

/**
 * The server's two listeners around one {@link Api} (ARCHITECTURE.md), as both the TV app and `Main` run them:
 * plain HTTP for the web build, the phone APK and the TV's own loopback API calls, and, with a {@link TlsIdentity},
 * HTTPS for the phone. {@link #bind} first, so a busy port fails before the core opens its store and the bound
 * ports are known for its origins; then {@link #start}. The caller owns the Api and closes it after these.
 */
public final class Listeners implements Closeable {
  private final ServerSocket plain;
  private final ServerSocket secure;
  private final TlsIdentity tls;
  private HttpServer http;
  private HttpServer https;

  private Listeners(ServerSocket plain, ServerSocket secure, TlsIdentity tls) {
    this.plain = plain;
    this.secure = secure;
    this.tls = tls;
  }

  /** Binds the HTTP port and, with `tls`, the HTTPS port (port 0: any free one). Nothing is served yet. */
  public static Listeners bind(InetAddress host, int port, TlsIdentity tls, int httpsPort) throws IOException {
    ServerSocket plain = bind(host, port);
    ServerSocket secure = null;
    if (tls != null) {
      try {
        secure = bind(host, httpsPort);
      } catch (IOException e) {
        plain.close();
        throw e;
      }
    }
    return new Listeners(plain, secure, tls);
  }

  private static ServerSocket bind(InetAddress host, int port) throws IOException {
    ServerSocket socket = new ServerSocket();
    try {
      socket.setReuseAddress(true); // a quick restart must not wait out TIME_WAIT
      socket.bind(new InetSocketAddress(host, port), 50);
      return socket;
    } catch (IOException e) {
      socket.close();
      throw new IOException("cannot listen on " + host.getHostAddress() + ":" + port + ": " + e.getMessage(), e);
    }
  }

  /** Starts serving: 8105's rules on the HTTP port, 8443's on the HTTPS port. */
  public synchronized void start(Api api, StaticSource files, Log log) {
    if (http != null) throw new IllegalStateException("already started");
    http = new HttpServer("http", plain, null, Router.local(api, files, log), new HttpServer.Options(), log);
    if (secure != null) https = new HttpServer("https", secure, tls, Router.secure(api, log), new HttpServer.Options(), log);
    http.start();
    if (https != null) https.start();
  }

  public int httpPort() {
    return plain.getLocalPort();
  }

  /** The HTTPS port, or -1 without TLS. */
  public int httpsPort() {
    return secure == null ? -1 : secure.getLocalPort();
  }

  /** Stops both listeners (requests in progress get up to two seconds), or just releases the ports if never started. */
  @Override
  public synchronized void close() {
    if (https != null) https.close();
    if (http != null) http.close();
    quietly(secure);
    quietly(plain);
  }

  private static void quietly(ServerSocket socket) {
    if (socket == null) return;
    try {
      socket.close();
    } catch (IOException ignored) {
      // closing anyway
    }
  }

  /**
   * The `Origin` values the phone app sends (`PhonePolicy.ServerConfig.origin()`): the TV's network address, and
   * `localhost` and `127.0.0.1` for a phone reaching the TV through `adb reverse` and `adb forward`.
   */
  public static Set<String> phoneOrigins(String address, int httpsPort) {
    Set<String> origins = new LinkedHashSet<>();
    if (address != null) origins.add("https://" + address + ":" + httpsPort);
    origins.add("https://localhost:" + httpsPort);
    origins.add("https://127.0.0.1:" + httpsPort);
    return origins;
  }

  /** What the TV's pairing QR code opens in the phone's browser: the landing page over plain HTTP, or null to let the TV derive it. */
  public static String phoneUrl(String address, int httpPort) {
    return address == null ? null : "http://" + address + ":" + httpPort + "/phone";
  }

  /** The pairing link template a developer can turn into a QR code or an `adb shell am start` intent. */
  public static String pairLinkTemplate(String address, int httpsPort, String fingerprint) {
    return "cleanrep://pair?host=" + (address == null ? "<ip>" : address) + "&port=" + httpsPort + "&code=<code>&fp=" + fingerprint;
  }

  /**
   * A private IPv4 address of this device, the one a phone on the same network dials, or null. Wi-Fi first, then
   * Ethernet, then anything else that is up, skipping loopback, tunnels and container bridges.
   */
  public static String lanAddress() {
    Enumeration<NetworkInterface> all;
    try {
      all = NetworkInterface.getNetworkInterfaces();
    } catch (SocketException e) {
      return null;
    }
    if (all == null) return null;
    String best = null;
    int bestRank = Integer.MAX_VALUE;
    for (NetworkInterface nif : Collections.list(all)) {
      try {
        if (!nif.isUp() || nif.isLoopback() || nif.isVirtual() || nif.isPointToPoint()) continue;
      } catch (SocketException e) {
        continue;
      }
      int rank = rank(nif.getName());
      if (rank >= bestRank) continue;
      for (InetAddress address : Collections.list(nif.getInetAddresses())) {
        if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
          best = address.getHostAddress();
          bestRank = rank;
          break;
        }
      }
    }
    return best;
  }

  static int rank(String name) {
    if (name.startsWith("wl")) return 0; // wlan0 on Android, wlp2s0 on Linux
    if (name.startsWith("eth") || name.startsWith("en")) return 1;
    if (name.startsWith("docker") || name.startsWith("br-") || name.startsWith("veth") || name.startsWith("virbr")) return 3;
    return 2;
  }
}
