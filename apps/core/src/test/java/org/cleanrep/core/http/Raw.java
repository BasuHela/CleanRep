package org.cleanrep.core.http;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A raw HTTP/1.1 test client on 127.0.0.1, so tests control every byte and see exactly what the server writes. */
final class Raw implements Closeable {
  final Socket socket;
  final InputStream in;
  final OutputStream out;

  Raw(int port) throws IOException {
    this(new Socket(InetAddress.getByName("127.0.0.1"), port));
  }

  Raw(Socket socket) throws IOException {
    this.socket = socket;
    socket.setSoTimeout(5000);
    in = socket.getInputStream();
    out = socket.getOutputStream();
  }

  Raw send(String text) throws IOException {
    return send(text.getBytes(StandardCharsets.ISO_8859_1));
  }

  Raw send(byte[] bytes) throws IOException {
    out.write(bytes);
    out.flush();
    return this;
  }

  /** One request and its reply, on this connection. */
  Reply exchange(String request) throws IOException {
    send(request);
    return read(request.startsWith("HEAD "));
  }

  Reply read() throws IOException {
    return read(false);
  }

  /** Reads one response; skips a 100 Continue first and records it. */
  Reply read(boolean head) throws IOException {
    Reply reply = new Reply();
    while (true) {
      String status = line();
      if (status == null) throw new IOException("connection closed before a response");
      reply.statusLine = status;
      reply.status = Integer.parseInt(status.split(" ")[1]);
      reply.headers.clear();
      reply.headerLines.clear();
      for (String h = line(); h != null && !h.isEmpty(); h = line()) {
        reply.headerLines.add(h);
        int colon = h.indexOf(':');
        reply.headers.put(h.substring(0, colon).toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
      }
      if (reply.status == 100) {
        reply.continued = true;
        continue;
      }
      break;
    }
    String length = reply.headers.get("content-length");
    int n = head || length == null ? 0 : Integer.parseInt(length);
    byte[] body = new byte[n];
    int got = 0;
    while (got < n) {
      int r = in.read(body, got, n - got);
      if (r < 0) throw new IOException("body cut short at " + got + " of " + n);
      got += r;
    }
    reply.body = body;
    return reply;
  }

  private String line() throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    int c;
    while ((c = in.read()) != -1) {
      if (c == '\n') {
        byte[] bytes = b.toByteArray();
        int len = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
        return new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
      }
      b.write(c);
    }
    return b.size() == 0 ? null : new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
  }

  /** True if the server closed the connection (EOF or reset) within `millis`, with no further bytes. */
  boolean closedWithin(int millis) throws IOException {
    socket.setSoTimeout(millis);
    try {
      return in.read() == -1;
    } catch (SocketTimeoutException e) {
      return false;
    } catch (IOException e) {
      return true; // reset
    } finally {
      socket.setSoTimeout(5000);
    }
  }

  /** The bytes read before the server closed (EOF or reset) within `millis`, or null if it stayed open or sent more than `max`. */
  byte[] drainUntilClosed(int millis, int max) throws IOException {
    long end = System.nanoTime() + millis * 1_000_000L;
    ByteArrayOutputStream got = new ByteArrayOutputStream();
    try {
      while (got.size() <= max) {
        long left = (end - System.nanoTime()) / 1_000_000L;
        if (left <= 0) return null;
        socket.setSoTimeout((int) left);
        int c = in.read();
        if (c == -1) return got.toByteArray();
        got.write(c);
      }
      return null;
    } catch (SocketTimeoutException e) {
      return null;
    } catch (IOException e) {
      return got.toByteArray(); // reset
    } finally {
      if (!socket.isClosed()) socket.setSoTimeout(5000);
    }
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }

  static final class Reply {
    String statusLine;
    int status;
    boolean continued;
    final Map<String, String> headers = new LinkedHashMap<>();
    final List<String> headerLines = new ArrayList<>();
    byte[] body;

    String text() {
      return new String(body, StandardCharsets.UTF_8);
    }

    String header(String name) {
      return headers.get(name.toLowerCase(Locale.ROOT));
    }
  }
}
