package org.cleanrep.core.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import org.cleanrep.core.Api;
import org.cleanrep.core.ApiRequest;
import org.cleanrep.core.ApiResponse;

/** An {@link Api} that records each request and answers with a configurable function (by default a small 200). */
final class FakeApi implements Api {
  final List<ApiRequest> requests = Collections.synchronizedList(new ArrayList<ApiRequest>());
  volatile Function<ApiRequest, ApiResponse> answer =
      r -> ApiResponse.json(200, "{\"status\":\"ok\",\"version\":null,\"data\":{\"path\":\"" + r.path + "\"},\"error\":null}");

  @Override
  public ApiResponse handle(ApiRequest request) {
    requests.add(request);
    return answer.apply(request);
  }

  ApiRequest last() {
    return requests.get(requests.size() - 1);
  }
}
