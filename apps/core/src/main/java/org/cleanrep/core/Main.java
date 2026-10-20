package org.cleanrep.core;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.cleanrep.core.http.Base64Url;
import org.cleanrep.core.http.Listeners;
import org.cleanrep.core.http.Log;
import org.cleanrep.core.http.StaticSource;
import org.cleanrep.core.http.TlsIdentity;

/**
 * The JVM entry point for the server: the TV app's core and listeners, on a computer. It serves
 * `make browser-test` and `make server`, for a phone-app test without a TV. SIGTERM or Ctrl-C stops the listeners and closes
 * the store, through a shutdown hook.
 */
public final class Main {
  static final String USAGE = "usage: java -cp apps/core/build/classes org.cleanrep.core.Main [--host 127.0.0.1] [--port 8105]\n"
      + "  [--https-port 8443] [--origin http://127.0.0.1:8105] [--data var/home] [--static apps/web/dist] [--root .]\n"
      + "  [--allow-replay] [--tls-dir var/home-tls] [--max-seconds N]";
  private static final Set<String> VALUED = new HashSet<>(Arrays.asList(
      "--host", "--port", "--https-port", "--origin", "--data", "--static", "--root", "--tls-dir", "--max-seconds"));

  private Main() {}

  /** Command-line settings; see {@link #USAGE}. */
  static final class Options {
    String host = "127.0.0.1";
    int port = 8105;
    /** -1: no HTTPS listener. */
    int httpsPort = -1;
    /** Null: `http://127.0.0.1:<the HTTP port>`. */
    String origin;
    File data = new File("var/home");
    File staticDir = new File("apps/web/dist");
    File root = new File(".");
    boolean allowReplay;
    File tlsDir = new File("var/home-tls");
    /** 0: no limit. */
    int maxSeconds;

    /** Throws IllegalArgumentException, with the reason, for an unknown flag, a missing value or a bad number. */
    static Options parse(String[] args) {
      Options o = new Options();
      for (int i = 0; i < args.length; i++) {
        String flag = args[i];
        if (flag.equals("--allow-replay")) {
          o.allowReplay = true;
          continue;
        }
        if (!VALUED.contains(flag)) throw new IllegalArgumentException("unknown flag " + flag);
        if (i + 1 >= args.length) throw new IllegalArgumentException(flag + " needs a value");
        String value = args[++i];
        switch (flag) {
          case "--host": o.host = value; break;
          case "--port": o.port = number(flag, value, 0, 65535); break;
          case "--https-port": o.httpsPort = number(flag, value, 0, 65535); break;
          case "--origin":
            if (!value.matches("https?://[A-Za-z0-9.:\\[\\]-]+")) throw new IllegalArgumentException("--origin must be an origin, like http://127.0.0.1:8105");
            o.origin = value;
            break;
          case "--data": o.data = new File(value); break;
          case "--static": o.staticDir = new File(value); break;
          case "--root": o.root = new File(value); break;
          case "--tls-dir": o.tlsDir = new File(value); break;
          default: o.maxSeconds = number(flag, value, 1, 86400); break;
        }
      }
      return o;
    }

    private static int number(String flag, String value, int min, int max) {
      int n = value.matches("[0-9]{1,6}") ? Integer.parseInt(value) : -1;
      if (n < min || n > max) throw new IllegalArgumentException(flag + " must be a number from " + min + " to " + max);
      return n;
    }
  }

  /** A running server: the core and its listeners. Closing stops the listeners, then closes the store. */
  static final class Server implements Closeable {
    final CleanRepApi api;
    final Listeners listeners;
    final CoreConfig config;
    /** The address the listeners are bound to, as given. */
    final String host;
    /** Null without HTTPS. */
    final TlsIdentity tls;
    /** What a phone dials: the bound address, or the LAN address when bound to all interfaces; null if neither. */
    final String address;
    private final AtomicBoolean closed = new AtomicBoolean();

    Server(CleanRepApi api, Listeners listeners, CoreConfig config, String host, TlsIdentity tls, String address) {
      this.api = api;
      this.listeners = listeners;
      this.config = config;
      this.host = host;
      this.tls = tls;
      this.address = address;
    }

    String readyLine() {
      StringBuilder line = new StringBuilder("CleanRep core: serving http://").append(host).append(':').append(listeners.httpPort());
      line.append(" (origin ").append(config.origin).append(", data ").append(config.dataDir.getPath());
      line.append(config.allowReplay ? ", replay on)" : ")");
      if (tls != null) {
        line.append("; https on port ").append(listeners.httpsPort()).append(", fingerprint ").append(tls.fingerprint())
            .append(", phone link ").append(Listeners.pairLinkTemplate(address, listeners.httpsPort(), tls.fingerprint()));
      }
      return line.toString();
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) return;
      listeners.close();
      api.close();
    }
  }

  /** Binds the ports, opens the store (recovering pending work), then serves. */
  static Server start(Options o, Log log) throws IOException, GeneralSecurityException {
    InetAddress host = InetAddress.getByName(o.host);
    TlsIdentity tls = null;
    if (o.httpsPort >= 0) {
      tls = TlsIdentity.loadOrCreate(new File(o.tlsDir, "tv.p12"), password(new File(o.tlsDir, "tv.password")),
          new SecureRandom(), System.currentTimeMillis(), log);
    }
    Listeners listeners = Listeners.bind(host, o.port, tls, o.httpsPort);
    try {
      String address = host.isAnyLocalAddress() ? Listeners.lanAddress() : host.isLoopbackAddress() ? null : host.getHostAddress();
      CoreConfig.Builder config = CoreConfig.builder(o.data, new RepoResources(o.root))
          .origin(o.origin != null ? o.origin : "http://127.0.0.1:" + listeners.httpPort())
          .allowReplay(o.allowReplay);
      if (tls != null) {
        config.extraOrigins(Listeners.phoneOrigins(address, listeners.httpsPort()));
        config.phoneUrl(Listeners.phoneUrl(address, listeners.httpPort()));
      }
      CoreConfig built = config.build();
      CleanRepApi api = new CleanRepApi(built);
      try {
        api.recover();
        listeners.start(api, new StaticSource.Directory(o.staticDir), log);
      } catch (RuntimeException | IOException e) {
        api.close();
        throw e;
      }
      return new Server(api, listeners, built, o.host, tls, address);
    } catch (RuntimeException | IOException e) {
      listeners.close();
      throw e;
    }
  }

  /** The PKCS#12 password, kept beside it in an owner-only file and made on first use. The TV keeps its own under an Android Keystore key. */
  static char[] password(File file) throws IOException {
    if (file.isFile()) {
      String text = new String(readAll(file), StandardCharsets.US_ASCII).trim();
      if (!text.isEmpty()) return text.toCharArray();
    }
    byte[] random = new byte[24];
    new SecureRandom().nextBytes(random);
    String text = Base64Url.encode(random);
    File dir = file.getAbsoluteFile().getParentFile();
    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
    File temp = File.createTempFile(file.getName(), ".tmp", dir);
    try {
      TlsIdentity.restrict(temp);
      try (FileOutputStream out = new FileOutputStream(temp)) {
        out.write(text.getBytes(StandardCharsets.US_ASCII));
        out.getFD().sync();
      }
      if (!temp.renameTo(file)) throw new IOException("cannot write " + file);
    } finally {
      if (temp.exists() && !temp.delete()) temp.deleteOnExit();
    }
    return text.toCharArray();
  }

  private static byte[] readAll(File file) throws IOException {
    try (InputStream in = new FileInputStream(file)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[256];
      int n;
      while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      return out.toByteArray();
    }
  }

  public static void main(String[] args) throws InterruptedException {
    final Options options;
    try {
      options = Options.parse(args);
    } catch (IllegalArgumentException e) {
      System.err.println("CleanRep core: " + e.getMessage());
      System.err.println(USAGE);
      System.exit(2);
      return;
    }
    final Server server;
    try {
      server = start(options, Log.STDERR);
    } catch (IOException | GeneralSecurityException | RuntimeException e) {
      System.err.println("CleanRep core: cannot start: " + e.getMessage());
      System.exit(1);
      return;
    }
    Runtime.getRuntime().addShutdownHook(new Thread(server::close, "cleanrep-shutdown"));
    if (!new File(options.staticDir, "index.html").isFile()) {
      System.err.println("CleanRep core: " + options.staticDir + "/index.html is missing (make build); pages will be 404");
    }
    System.out.println(server.readyLine());
    System.out.flush();
    if (options.maxSeconds > 0) {
      Thread limit = new Thread(() -> {
        try {
          Thread.sleep(options.maxSeconds * 1000L);
        } catch (InterruptedException e) {
          return;
        }
        System.err.println("CleanRep core: stopping after --max-seconds " + options.maxSeconds);
        System.exit(0);
      }, "cleanrep-max-seconds");
      limit.setDaemon(true);
      limit.start();
    }
    new CountDownLatch(1).await(); // the listeners' threads are daemons; the shutdown hook ends the process
  }
}
