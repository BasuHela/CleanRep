package org.cleanrep.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** One `/v1/*` request as the HTTP layer hands it over. Header names are lower case. */
public final class ApiRequest {
  /** The HTTP layer refuses a larger body with 413 before the API sees it; the API applies the route limits. */
  public static final int MAX_BODY_BYTES = 160 * 1024;

  public final String method;
  /** The decoded path, for example `/v1/sessions/s_1/reps`; never the query. */
  public final String path;
  /** The raw query string without `?`, or "" when there is none. */
  public final String query;
  public final Map<String, String> headers;
  /** The whole request body, at most {@link #MAX_BODY_BYTES}; empty for none. */
  public final byte[] body;
  /** The client's IP address as text, for per-address rate limits. */
  public final String peer;

  public ApiRequest(String method, String path, String query, Map<String, String> headers, byte[] body, String peer) {
    if (body.length > MAX_BODY_BYTES) throw new IllegalArgumentException("body over " + MAX_BODY_BYTES + " bytes");
    Map<String, String> lower = new HashMap<>();
    for (Map.Entry<String, String> h : headers.entrySet()) lower.put(h.getKey().toLowerCase(Locale.ROOT), h.getValue());
    this.method = method;
    this.path = path;
    this.query = query == null ? "" : query;
    this.headers = Collections.unmodifiableMap(lower);
    this.body = body;
    this.peer = peer;
  }

  /** The header's value, or null; `name` in any case. */
  public String header(String name) {
    return headers.get(name.toLowerCase(Locale.ROOT));
  }
}
