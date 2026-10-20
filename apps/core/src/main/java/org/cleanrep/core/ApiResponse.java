package org.cleanrep.core;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the API answers. The HTTP layer adds the headers every response gets (Cache-Control, nosniff,
 * Referrer-Policy, Permissions-Policy, CSP), as `app.py`'s middleware does; `headers` holds only extra ones.
 */
public final class ApiResponse {
  public final int status;
  /** For example `application/json`; null when there is no body (204). */
  public final String contentType;
  public final byte[] body;
  public final Map<String, String> headers;

  public ApiResponse(int status, String contentType, byte[] body, Map<String, String> headers) {
    this.status = status;
    this.contentType = contentType;
    this.body = body;
    this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
  }

  /** A JSON body, UTF-8, with no extra headers. */
  public static ApiResponse json(int status, String json) {
    return new ApiResponse(status, "application/json", json.getBytes(StandardCharsets.UTF_8), Collections.<String, String>emptyMap());
  }

  /** The body as UTF-8 text, for tests. */
  public String text() {
    return new String(body, StandardCharsets.UTF_8);
  }
}
