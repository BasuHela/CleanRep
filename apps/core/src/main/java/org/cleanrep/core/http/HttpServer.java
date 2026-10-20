package org.cleanrep.core.http;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSocket;
import org.cleanrep.core.ApiRequest;

/**
 * A small HTTP/1.1 server for one listener, with no dependencies, sized for a TV that also runs a WebView:
 * - one accept thread, a few workers (one connection each), a short queue, then 503 and close;
 * - deadlines on the TLS handshake and headers, the body, idle keep-alive and every write, enforced by one reaper
 *   thread that closes the plain socket (a blocked read or write can't outwait it);
 * - Content-Length bodies only, capped at {@link ApiRequest#MAX_BODY_BYTES}; a 16 KB, 100-line header block;
 * - keep-alive, closed early when every worker is busy so idle connections don't starve new ones.
 * With a {@link TlsIdentity} it speaks HTTPS only.
 */
public final class HttpServer implements Closeable {
  /** Answers one request. Throwing is a bug: the server logs it and answers 500. */
  public interface Handler {
    HttpResponse handle(HttpRequest request);
  }

  /** Limits and timeouts; the defaults suit the TV. */
  public static final class Options {
    public int workers = 6;
    /** Connections that may wait for a worker; beyond this, 503. */
    public int queue = 16;
    /** Open connections per client address; loopback (the TV itself) is exempt unless {@link #limitLoopback}. */
    public int perPeer = 12;
    public boolean limitLoopback = false;
    /** From accepting the connection (or a request's first byte) to the end of its headers, TLS handshake included. */
    public int headerTimeoutMillis = 10_000;
    public int bodyTimeoutMillis = 20_000;
    public int idleTimeoutMillis = 5_000;
    /** Without progress; a file also gets a total budget of this plus 20 ms per KB. */
    public int writeTimeoutMillis = 20_000;
    public int maxHeaderBytes = 16 * 1024;
    public int maxHeaders = 100;
    public int maxRequestLine = 8 * 1024;
  }

  private static final Set<String> SINGLE = new HashSet<>(Arrays.asList(
      "content-length", "content-type", "authorization", "origin", "host", "transfer-encoding"));
  private static final Set<String> RESERVED = new HashSet<>(Arrays.asList(
      "content-length", "content-type", "connection", "transfer-encoding", "keep-alive", "upgrade"));

  private final String name;
  private final ServerSocket server;
  private final TlsIdentity tls;
  private final Handler handler;
  private final Options options;
  private final Log log;
  private final ThreadPoolExecutor workers;
  private final Set<Connection> open = Collections.newSetFromMap(new ConcurrentHashMap<Connection, Boolean>());
  private final Map<InetAddress, Integer> peers = new HashMap<>();
  private final Thread acceptor;
  private final Thread reaper;
  private final AtomicBoolean started = new AtomicBoolean();
  private volatile boolean closed;

  /** `server` is bound already; `tls` null for plain HTTP. Call {@link #start()}. */
  public HttpServer(String name, ServerSocket server, TlsIdentity tls, Handler handler, Options options, Log log) {
    this.name = name;
    this.server = server;
    this.tls = tls;
    this.handler = handler;
    this.options = options;
    this.log = log;
    final AtomicInteger count = new AtomicInteger();
    BlockingQueue<Runnable> queue = options.queue == 0 ? new SynchronousQueue<Runnable>() : new ArrayBlockingQueue<Runnable>(options.queue);
    workers = new ThreadPoolExecutor(options.workers, options.workers, 30, TimeUnit.SECONDS, queue, r -> {
      Thread t = new Thread(r, "cleanrep-" + name + "-" + count.incrementAndGet());
      t.setDaemon(true);
      return t;
    });
    workers.allowCoreThreadTimeOut(true);
    acceptor = new Thread(this::acceptLoop, "cleanrep-" + name + "-accept");
    acceptor.setDaemon(true);
    reaper = new Thread(this::reapLoop, "cleanrep-" + name + "-deadlines");
    reaper.setDaemon(true);
  }

  public void start() {
    if (started.compareAndSet(false, true)) {
      reaper.start();
      acceptor.start();
    }
  }

  public int port() {
    return server.getLocalPort();
  }

  /** Stops accepting, lets requests in progress finish for up to two seconds, then closes every connection. */
  @Override
  public void close() {
    if (closed) return;
    closed = true;
    quietly(server);
    workers.shutdown();
    for (Connection c : open) if (!c.busy) c.abort();
    try {
      workers.awaitTermination(2, TimeUnit.SECONDS);
      for (Connection c : open) c.abort();
      workers.shutdownNow();
      acceptor.join(1000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    reaper.interrupt();
  }

  private void acceptLoop() {
    while (!closed) {
      Socket socket;
      try {
        socket = server.accept();
      } catch (IOException e) {
        if (closed) return;
        log.line("CleanRep: " + name + " listener: accept failed: " + e, null);
        try {
          Thread.sleep(100); // for example out of file descriptors: don't spin
        } catch (InterruptedException stop) {
          return;
        }
        continue;
      }
      InetAddress peer = socket.getInetAddress();
      boolean limited = options.limitLoopback || !peer.isLoopbackAddress();
      if (limited && !claim(peer)) {
        quietly(socket);
        continue;
      }
      Connection c = new Connection(socket, limited);
      open.add(c);
      try {
        workers.execute(c);
      } catch (RejectedExecutionException e) {
        c.refuse();
      }
    }
  }

  private boolean claim(InetAddress peer) {
    synchronized (peers) {
      Integer n = peers.get(peer);
      if (n != null && n >= options.perPeer) return false;
      peers.put(peer, n == null ? 1 : n + 1);
      return true;
    }
  }

  private void release(InetAddress peer) {
    synchronized (peers) {
      Integer n = peers.get(peer);
      if (n == null || n <= 1) peers.remove(peer);
      else peers.put(peer, n - 1);
    }
  }

  private void reapLoop() {
    int shortest = Math.min(Math.min(options.headerTimeoutMillis, options.bodyTimeoutMillis),
        Math.min(options.idleTimeoutMillis, options.writeTimeoutMillis));
    long tick = Math.max(10, Math.min(250, shortest / 5));
    while (!closed) {
      try {
        Thread.sleep(tick);
      } catch (InterruptedException e) {
        return;
      }
      long now = System.nanoTime();
      for (Connection c : open) {
        long d = c.deadline;
        if (d != 0 && now - d > 0) c.abort();
      }
    }
  }

  /** Too busy to hold an idle connection: someone waits for a worker, or none is spare. */
  private boolean pressed() {
    return !workers.getQueue().isEmpty() || workers.getActiveCount() >= options.workers;
  }

  /** A request the server refuses itself, with the response to send before closing. */
  private static final class Refusal extends Exception {
    private static final long serialVersionUID = 1L;
    final int status;
    final String code;
    final boolean api;

    Refusal(int status, String code, boolean api) {
      super(code, null, false, false);
      this.status = status;
      this.code = code;
      this.api = api;
    }
  }

  private final class Connection implements Runnable {
    private final Socket plain;
    private final InetAddress peer;
    private final boolean limited;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final byte[] buffer = new byte[options.maxHeaderBytes];
    private int pos;
    private int lim;
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    volatile long deadline;
    volatile boolean busy;

    Connection(Socket plain, boolean limited) {
      this.plain = plain;
      this.peer = plain.getInetAddress();
      this.limited = limited;
      deadline(options.headerTimeoutMillis);
    }

    void deadline(long millis) {
      long d = System.nanoTime() + millis * 1_000_000L;
      deadline = d == 0 ? 1 : d;
    }

    void abort() {
      quietly(plain);
    }

    /** The pool is full: a short 503 on plain HTTP (TLS would need a handshake first), then close. */
    void refuse() {
      try {
        if (tls == null) {
          OutputStream o = plain.getOutputStream();
          writeRefusal(o, HttpResponse.envelope(503, "server_busy"), "");
          InputStream i = plain.getInputStream();
          int ready = i.available();
          if (ready > 0) i.skip(ready); // unread input would turn our close into a reset
          plain.shutdownOutput();
        }
      } catch (IOException ignored) {
        // the client is gone
      } finally {
        finish();
      }
    }

    @Override
    public void run() {
      try {
        if (closed) return;
        serve();
      } catch (IOException e) {
        // the client went away, a deadline passed, or the handshake failed: nothing to report
      } catch (RuntimeException | Error e) {
        log.line("CleanRep: " + name + " connection from " + peer.getHostAddress() + " failed: " + e, e);
      } finally {
        finish();
      }
    }

    private void serve() throws IOException {
      socket = plain;
      if (tls != null) {
        SSLSocket ssl = tls.wrap(plain);
        ssl.startHandshake();
        socket = ssl;
      }
      in = socket.getInputStream();
      out = new BufferedOutputStream(socket.getOutputStream(), 16 * 1024);
      boolean first = true;
      while (!closed) {
        if (!first) deadline(options.idleTimeoutMillis);
        if (!awaitRequest()) return;
        if (!first) deadline(options.headerTimeoutMillis);
        first = false;
        try {
          if (!exchange()) return;
        } catch (Refusal r) {
          busy = true;
          deadline(options.writeTimeoutMillis);
          // The path is unknown or unparsed here; "/v1/" gives an API refusal the API's headers (no-store).
          writeRefusal(out, HttpResponse.error(r.status, r.code, r.api), r.api ? "/v1/" : "");
          linger();
          return;
        } finally {
          busy = false;
        }
      }
    }

    /** Waits for the next request's first byte, skipping blank lines; false at a clean end of stream. */
    private boolean awaitRequest() throws IOException {
      while (true) {
        while (pos < lim && (buffer[pos] == '\r' || buffer[pos] == '\n')) pos++;
        if (pos < lim) return true;
        pos = lim = 0;
        int n = in.read(buffer, 0, buffer.length);
        if (n < 0) return false;
        lim = n;
      }
    }

    /** Reads, handles and answers one request; false when the connection should close. */
    private boolean exchange() throws IOException, Refusal {
      int end = readHead();
      String head = new String(buffer, pos, end - pos, StandardCharsets.ISO_8859_1);
      pos = end;
      String[] lines = head.split("\n", -1);
      for (int i = 0; i < lines.length; i++) {
        String line = lines[i];
        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
        lines[i] = line;
      }
      String requestLine = lines[0];
      boolean api = requestLine.matches("[A-Z]+ /v1/.*");
      if (requestLine.length() > options.maxRequestLine) throw new Refusal(414, "uri_too_long", api);
      String[] parts = requestLine.split(" ", -1);
      if (parts.length != 3 || !parts[0].matches("[A-Z]{1,20}") || requestLine.indexOf('\r') >= 0) {
        throw new Refusal(400, "invalid_request", api);
      }
      String method = parts[0];
      String target = parts[1];
      String version = parts[2];
      if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) {
        throw new Refusal(version.matches("HTTP/[0-9]\\.[0-9]") ? 505 : 400, "invalid_request", api);
      }
      int question = target.indexOf('?');
      String rawPath = question < 0 ? target : target.substring(0, question);
      String query = question < 0 ? "" : target.substring(question + 1);
      String path = decodePath(target, rawPath, api);
      api = path.startsWith("/v1/");

      Map<String, String> headers = new LinkedHashMap<>();
      int count = 0;
      for (int i = 1; i < lines.length; i++) {
        String line = lines[i];
        if (line.isEmpty()) continue; // the blank line that ends the block
        if (++count > options.maxHeaders) throw new Refusal(431, "headers_too_large", api);
        int colon = line.indexOf(':');
        if (colon <= 0 || !isToken(line, 0, colon)) throw new Refusal(400, "invalid_request", api);
        String value = line.substring(colon + 1).trim();
        for (int k = 0; k < value.length(); k++) {
          char c = value.charAt(k);
          if ((c < 0x20 && c != '\t') || c == 0x7f) throw new Refusal(400, "invalid_request", api);
        }
        String key = line.substring(0, colon).toLowerCase(Locale.ROOT);
        String before = headers.get(key);
        if (before == null) headers.put(key, value);
        else if (!SINGLE.contains(key)) headers.put(key, before + ", " + value);
        else if (!key.equals("content-length") || !before.equals(value)) throw new Refusal(400, "invalid_request", api);
      }

      if (headers.containsKey("transfer-encoding")) throw new Refusal(411, "length_required", api);
      int length = 0;
      String declared = headers.get("content-length");
      if (declared != null) {
        if (!declared.matches("[0-9]{1,10}")) throw new Refusal(400, "invalid_length", api);
        long n = Long.parseLong(declared);
        if (n > ApiRequest.MAX_BODY_BYTES) throw new Refusal(413, "payload_too_large", api);
        length = (int) n;
      }
      boolean http10 = version.equals("HTTP/1.0");
      String expect = headers.get("expect");
      if (expect != null) {
        if (http10 || !expect.equalsIgnoreCase("100-continue")) throw new Refusal(417, "expectation_failed", api);
        if (length > 0 && pos == lim) {
          out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
          out.flush();
        }
      }
      deadline(options.bodyTimeoutMillis);
      byte[] body = readBody(length);

      String connection = headers.containsKey("connection") ? headers.get("connection").toLowerCase(Locale.ROOT) : "";
      boolean keepAlive = http10 ? tokens(connection).contains("keep-alive") : !tokens(connection).contains("close");

      busy = true;
      HttpRequest request = new HttpRequest(method, path, query, headers, body, peer);
      HttpResponse response;
      try {
        response = handler.handle(request);
        if (response == null) throw new IllegalStateException("no response");
      } catch (RuntimeException | Error e) {
        log.line("CleanRep: " + name + " handler failed on " + method + " " + path + ": " + e, e);
        response = HttpResponse.error(500, "internal_error", api);
      }
      boolean close = !keepAlive || closed || pressed();
      boolean headOnly = method.equals("HEAD");
      byte[] bytes;
      try {
        bytes = head(response, path, close, http10 && !close);
      } catch (IllegalArgumentException e) {
        log.line("CleanRep: " + name + " refused an unsafe response header on " + path + ": " + e.getMessage(), null);
        response = HttpResponse.error(500, "internal_error", api);
        bytes = head(response, path, close, http10 && !close);
      }
      deadline(options.writeTimeoutMillis);
      out.write(bytes);
      if (!headOnly && hasBody(response.status)) writeBody(response);
      out.flush();
      return !close;
    }

    /** Fills the buffer until it holds a whole header block; returns the index just past its blank line. */
    private int readHead() throws IOException, Refusal {
      int scanned = pos;
      while (true) {
        for (int i = Math.max(scanned, pos); i < lim; i++) {
          if (buffer[i] != '\n') continue;
          if (i + 1 < lim && buffer[i + 1] == '\n') return i + 2;
          if (i + 2 < lim && buffer[i + 1] == '\r' && buffer[i + 2] == '\n') return i + 3;
        }
        boolean apiLine = startsWithApi();
        if (indexOf((byte) '\n') < 0 && lim - pos > options.maxRequestLine) throw new Refusal(414, "uri_too_long", apiLine);
        if (lim - pos >= options.maxHeaderBytes) throw new Refusal(431, "headers_too_large", apiLine);
        scanned = Math.max(pos, lim - 2);
        if (lim == buffer.length) {
          System.arraycopy(buffer, pos, buffer, 0, lim - pos);
          scanned -= pos;
          lim -= pos;
          pos = 0;
        }
        int n = in.read(buffer, lim, buffer.length - lim);
        if (n < 0) throw new EOFException("headers cut short");
        lim += n;
      }
    }

    private int indexOf(byte b) {
      for (int i = pos; i < lim; i++) if (buffer[i] == b) return i;
      return -1;
    }

    private boolean startsWithApi() {
      int space = indexOf((byte) ' ');
      if (space < 0 || lim - space < 5) return false;
      return buffer[space + 1] == '/' && buffer[space + 2] == 'v' && buffer[space + 3] == '1' && buffer[space + 4] == '/';
    }

    private byte[] readBody(int length) throws IOException {
      byte[] body = new byte[length];
      int got = Math.min(length, lim - pos);
      System.arraycopy(buffer, pos, body, 0, got);
      pos += got;
      while (got < length) {
        int n = in.read(body, got, length - got);
        if (n < 0) throw new EOFException("body cut short");
        got += n;
      }
      return body;
    }

    private void writeBody(HttpResponse response) throws IOException {
      if (response.body != null) {
        out.write(response.body);
        return;
      }
      long total = System.nanoTime() + (options.writeTimeoutMillis + response.length / 1024 * 20) * 1_000_000L;
      byte[] chunk = new byte[16 * 1024];
      long left = response.length;
      try (InputStream file = response.source.open(response.file)) {
        while (left > 0) {
          int n = file.read(chunk, 0, (int) Math.min(chunk.length, left));
          if (n < 0) throw new EOFException(response.file + " is shorter than its length");
          out.write(chunk, 0, n);
          left -= n;
          deadline(options.writeTimeoutMillis);
          if (deadline - total > 0) deadline = total;
        }
      }
    }

    /** After a refusal on plain HTTP: send FIN, then read and drop what the client still sends, briefly, so our close isn't a reset. */
    private void linger() {
      if (tls != null) return;
      try {
        plain.shutdownOutput();
        deadline(1000);
        byte[] sink = new byte[8192];
        long drained = 0;
        int n;
        while (drained < (1 << 20) && (n = plain.getInputStream().read(sink)) >= 0) drained += n;
      } catch (IOException ignored) {
        // closed by the deadline or the client
      }
    }

    void finish() {
      if (!finished.compareAndSet(false, true)) return;
      try {
        if (socket != null && socket != plain) {
          deadline(1000); // an SSLSocket's close writes close_notify, which a client that never reads could block
          quietly(socket);
        }
      } finally {
        quietly(plain);
        open.remove(this);
        if (limited) release(peer);
      }
    }
  }

  /** A response the server makes itself (always inline bytes), then Connection: close. */
  private static void writeRefusal(OutputStream o, HttpResponse r, String path) throws IOException {
    o.write(head(r, path, true, false));
    o.write(r.body);
    o.flush();
  }

  /** The status line and headers. Throws IllegalArgumentException for a header that could split the response. */
  private static byte[] head(HttpResponse r, String path, boolean close, boolean keepAlive10) {
    int status = r.status >= 200 && r.status <= 599 ? r.status : 500;
    StringBuilder h = new StringBuilder(512);
    h.append("HTTP/1.1 ").append(status).append(' ').append(HttpResponse.reason(status)).append("\r\n");
    if (r.contentType != null) h.append("Content-Type: ").append(safe(r.contentType)).append("\r\n");
    if (hasBody(status)) h.append("Content-Length: ").append(r.length).append("\r\n");
    Map<String, String> common = HttpResponse.commonHeaders(path);
    Set<String> commonNames = new HashSet<>();
    for (String name : common.keySet()) commonNames.add(name.toLowerCase(Locale.ROOT));
    for (Map.Entry<String, String> e : r.headers.entrySet()) {
      String name = e.getKey();
      if (!isToken(name, 0, name.length())) throw new IllegalArgumentException("header name " + name);
      String lower = name.toLowerCase(Locale.ROOT);
      if (RESERVED.contains(lower) || commonNames.contains(lower)) continue;
      h.append(name).append(": ").append(safe(e.getValue())).append("\r\n");
    }
    for (Map.Entry<String, String> e : common.entrySet()) h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
    if (close) h.append("Connection: close\r\n");
    else if (keepAlive10) h.append("Connection: keep-alive\r\n");
    h.append("\r\n");
    return h.toString().getBytes(StandardCharsets.ISO_8859_1);
  }

  private static boolean hasBody(int status) {
    return status != 204 && status != 304;
  }

  private static String safe(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if ((c < 0x20 && c != '\t') || c == 0x7f || c > 0xff) throw new IllegalArgumentException("header value with a control character");
    }
    return value;
  }

  private static boolean isToken(String s, int from, int to) {
    if (from >= to) return false;
    for (int i = from; i < to; i++) {
      char c = s.charAt(i);
      boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
      if (!ok) return false;
    }
    return true;
  }

  private static Set<String> tokens(String header) {
    Set<String> out = new HashSet<>();
    for (String t : header.split(",")) out.add(t.trim());
    return out;
  }

  /**
   * The request target's path, percent-decoded as UTF-8 like an ASGI server (so `%2F` becomes `/`), but strict:
   * a target that isn't origin-form, has a space, control, non-ASCII or `#`, a bad escape, invalid UTF-8 or a NUL is a 400.
   */
  static String decodePath(String target, String rawPath, boolean api) throws Refusal {
    if (!rawPath.startsWith("/")) throw new Refusal(400, "invalid_request", api);
    for (int i = 0; i < target.length(); i++) {
      char c = target.charAt(i);
      if (c <= 0x20 || c >= 0x7f || c == '#') throw new Refusal(400, "invalid_request", api);
    }
    if (rawPath.indexOf('%') < 0) return rawPath;
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(rawPath.length());
    for (int i = 0; i < rawPath.length(); i++) {
      char c = rawPath.charAt(i);
      if (c != '%') {
        bytes.write(c);
        continue;
      }
      int hi = i + 2 < rawPath.length() ? Character.digit(rawPath.charAt(i + 1), 16) : -1;
      int lo = hi < 0 ? -1 : Character.digit(rawPath.charAt(i + 2), 16);
      if (lo < 0) throw new Refusal(400, "invalid_request", api);
      bytes.write(hi << 4 | lo);
      i += 2;
    }
    String path;
    try {
      path = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
    } catch (CharacterCodingException e) {
      throw new Refusal(400, "invalid_request", api);
    }
    if (path.indexOf('\0') >= 0) throw new Refusal(400, "invalid_request", api);
    return path;
  }

  private static void quietly(Closeable c) {
    try {
      c.close();
    } catch (IOException ignored) {
      // closing anyway
    }
  }
}
