package org.cleanrep.core.http;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a handler answers: inline bytes, or a file streamed from a {@link StaticSource}. The server adds
 * Content-Length, Connection and the common headers of `services/api/app.py`'s middleware ({@link #commonHeaders}).
 */
public final class HttpResponse {
  static final String CSP = "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; "
      + "img-src 'self' data: blob:; media-src 'self' blob:; worker-src 'self' blob:; connect-src 'self'; "
      + "frame-ancestors 'none'; base-uri 'self'; object-src 'none'";

  public final int status;
  /** Null for no body (204). */
  public final String contentType;
  /** Extra headers; the common ones override any with the same name. */
  public final Map<String, String> headers;
  /** The inline body, or null for a file. */
  public final byte[] body;
  /** For a file: where it comes from, its name and size. */
  public final StaticSource source;
  public final String file;
  public final long length;

  private HttpResponse(int status, String contentType, Map<String, String> headers, byte[] body, StaticSource source, String file,
      long length) {
    this.status = status;
    this.contentType = contentType;
    this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    this.body = body;
    this.source = source;
    this.file = file;
    this.length = length;
  }

  public static HttpResponse bytes(int status, String contentType, byte[] body, Map<String, String> headers) {
    return new HttpResponse(status, contentType, headers, body, null, null, body.length);
  }

  public static HttpResponse json(int status, String json) {
    return bytes(status, "application/json", json.getBytes(StandardCharsets.UTF_8), Collections.<String, String>emptyMap());
  }

  public static HttpResponse file(String contentType, StaticSource source, String name, long length, Map<String, String> headers) {
    return new HttpResponse(200, contentType, headers, null, source, name, length);
  }

  /**
   * The API's error envelope, byte for byte as `app.py`'s `reject()` writes it (Starlette's compact JSON in dict
   * order): `{"status":"rejected","version":null,"data":{},"error":{"code":"https_required","message":"https required"}}`.
   * Codes are lower-case words joined by `_`, so they need no JSON escaping.
   */
  public static HttpResponse envelope(int status, String code) {
    String outcome = status == 409 ? "conflict" : status >= 500 ? "unavailable" : "rejected";
    return json(status, "{\"status\":\"" + outcome + "\",\"version\":null,\"data\":{},\"error\":{\"code\":\"" + code
        + "\",\"message\":\"" + code.replace('_', ' ') + "\"}}");
  }

  /** Starlette's error body outside the API, as `{"detail":"Not Found"}`. */
  public static HttpResponse detail(int status) {
    return json(status, "{\"detail\":\"" + reason(status) + "\"}");
  }

  /** An error the HTTP layer makes itself: the envelope on API paths, Starlette's detail elsewhere. */
  static HttpResponse error(int status, String code, boolean api) {
    return api ? envelope(status, code) : detail(status);
  }

  /** The headers `app.py`'s middleware puts on every response, by request path. No HSTS: the TV's own origin is plain HTTP. */
  static Map<String, String> commonHeaders(String path) {
    Map<String, String> h = new LinkedHashMap<>();
    h.put("Cache-Control", path.startsWith("/v1/") ? "no-store" : "no-cache");
    h.put("X-Content-Type-Options", "nosniff");
    h.put("Referrer-Policy", "no-referrer");
    h.put("Permissions-Policy",
        path.equals("/phone") ? "camera=(self), microphone=(), geolocation=()" : "camera=(), microphone=(), geolocation=()");
    h.put("Content-Security-Policy", CSP);
    return h;
  }

  static String reason(int status) {
    switch (status) {
      case 100: return "Continue";
      case 200: return "OK";
      case 201: return "Created";
      case 202: return "Accepted";
      case 204: return "No Content";
      case 304: return "Not Modified";
      case 400: return "Bad Request";
      case 401: return "Unauthorized";
      case 403: return "Forbidden";
      case 404: return "Not Found";
      case 405: return "Method Not Allowed";
      case 409: return "Conflict";
      case 410: return "Gone";
      case 411: return "Length Required";
      case 413: return "Payload Too Large";
      case 414: return "URI Too Long";
      case 415: return "Unsupported Media Type";
      case 417: return "Expectation Failed";
      case 422: return "Unprocessable Entity";
      case 429: return "Too Many Requests";
      case 431: return "Request Header Fields Too Large";
      case 500: return "Internal Server Error";
      case 501: return "Not Implemented";
      case 502: return "Bad Gateway";
      case 503: return "Service Unavailable";
      case 505: return "HTTP Version Not Supported";
      default: return status < 300 ? "OK" : status < 400 ? "Redirect" : status < 500 ? "Client Error" : "Server Error";
    }
  }
}
