package org.cleanrep.core.http;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.cleanrep.core.Api;
import org.cleanrep.core.ApiRequest;
import org.cleanrep.core.ApiResponse;

/**
 * What each home-mode listener serves (ARCHITECTURE.md):
 * - 8105, plain HTTP ({@link #local}): the web build and the phone APK to anyone; `/v1/*` to loopback peers only
 *   (the TV's own UI), and 403 `https_required` to any other peer before the API sees the request;
 * - 8443, HTTPS ({@link #secure}): `/v1/*` to anyone, where pairing and tokens protect it; nothing else.
 */
public final class Router implements HttpServer.Handler {
  static final String APK_TYPE = "application/vnd.android.package-archive";
  private static final String NAME_SEGMENT = "[A-Za-z0-9_@+~=,-][A-Za-z0-9._@+~=,-]*";

  private final Api api;
  private final StaticSource files;
  private final boolean secure;
  private final Log log;
  private final AtomicInteger failures = new AtomicInteger();

  private Router(Api api, StaticSource files, boolean secure, Log log) {
    this.api = api;
    this.files = files;
    this.secure = secure;
    this.log = log;
  }

  public static Router local(Api api, StaticSource files, Log log) {
    return new Router(api, files, false, log);
  }

  public static Router secure(Api api, Log log) {
    return new Router(api, null, true, log);
  }

  /** The 8105 rule: only the TV itself may call the API over plain HTTP. */
  static boolean isLocal(InetAddress peer) {
    return peer.isLoopbackAddress();
  }

  @Override
  public HttpResponse handle(HttpRequest request) {
    if (request.path.startsWith("/v1/")) {
      if (!secure && !isLocal(request.peer)) return HttpResponse.envelope(403, "https_required");
      return api(request);
    }
    if (secure) return HttpResponse.detail(404);
    if (!request.method.equals("GET") && !request.method.equals("HEAD")) {
      HttpResponse r = HttpResponse.detail(405);
      return HttpResponse.bytes(405, r.contentType, r.body, Collections.singletonMap("Allow", "GET, HEAD"));
    }
    String path = request.path;
    if (path.equals("/cleanrep-phone.apk")) {
      return file(HomeAssets.PHONE_APK, APK_TYPE, Collections.singletonMap("Content-Disposition", "attachment; filename=\"cleanrep-phone.apk\""));
    }
    String name = path.equals("/") || path.equals("/tv") || path.equals("/phone") ? "index.html" : staticName(path);
    if (name == null) return HttpResponse.detail(404);
    return file(name, mimeType(name), Collections.<String, String>emptyMap());
  }

  private HttpResponse api(HttpRequest request) {
    try {
      ApiResponse r = api.handle(new ApiRequest(request.method, request.path, request.query, request.headers, request.body,
          request.peer.getHostAddress()));
      if (r == null) throw new IllegalStateException("the API returned no response");
      Map<String, String> headers = new LinkedHashMap<>(r.headers);
      return HttpResponse.bytes(r.status, r.contentType, r.body == null ? new byte[0] : r.body, headers);
    } catch (Throwable e) {
      // The first failure gets a stack trace; later ones one line each, so a broken route can't flood the log.
      int n = failures.incrementAndGet();
      log.line("CleanRep: " + request.method + " " + request.path + " failed (" + n + " so far): " + e, n == 1 ? e : null);
      return HttpResponse.envelope(500, "internal_error");
    }
  }

  private HttpResponse file(String name, String type, Map<String, String> headers) {
    long length;
    try {
      length = files.length(name);
    } catch (IOException | RuntimeException e) {
      length = -1;
    }
    return length < 0 ? HttpResponse.detail(404) : HttpResponse.file(type, files, name, length, headers);
  }

  /** The file name for a decoded path, or null: no empty, dot, hidden or odd segments, and nothing under the home assets. */
  static String staticName(String path) {
    if (!path.startsWith("/") || path.length() == 1) return null;
    String name = path.substring(1);
    for (String segment : name.split("/", -1)) {
      if (!segment.matches(NAME_SEGMENT)) return null;
    }
    return name.startsWith(HomeAssets.PREFIX) || name.equals(HomeAssets.PREFIX.substring(0, HomeAssets.PREFIX.length() - 1)) ? null : name;
  }

  /** MainActivity's types, plus the other kinds the web build has; text types say UTF-8. */
  static String mimeType(String name) {
    String n = name.toLowerCase(java.util.Locale.ROOT);
    if (n.endsWith(".html")) return "text/html; charset=utf-8";
    if (n.endsWith(".js") || n.endsWith(".mjs")) return "text/javascript; charset=utf-8";
    if (n.endsWith(".css")) return "text/css; charset=utf-8";
    if (n.endsWith(".txt") || n.endsWith(".md")) return "text/plain; charset=utf-8";
    if (n.endsWith(".json")) return "application/json";
    if (n.endsWith(".mp4")) return "video/mp4";
    if (n.endsWith(".png")) return "image/png";
    if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
    if (n.endsWith(".svg")) return "image/svg+xml";
    if (n.endsWith(".wasm")) return "application/wasm";
    if (n.endsWith(".woff2")) return "font/woff2";
    return "application/octet-stream";
  }
}
