package org.cleanrep.core.http;

import java.net.InetAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One parsed request, as the server hands it to a {@link HttpServer.Handler}. */
public final class HttpRequest {
  public final String method;
  /** Percent-decoded as UTF-8, always starting with `/`, never the query. */
  public final String path;
  /** The raw query without `?`, or "". */
  public final String query;
  /** Lower-case names. */
  public final Map<String, String> headers;
  public final byte[] body;
  public final InetAddress peer;

  public HttpRequest(String method, String path, String query, Map<String, String> headers, byte[] body, InetAddress peer) {
    this.method = method;
    this.path = path;
    this.query = query;
    this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    this.body = body;
    this.peer = peer;
  }
}
