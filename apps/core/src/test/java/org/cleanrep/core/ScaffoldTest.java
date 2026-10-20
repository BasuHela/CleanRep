package org.cleanrep.core;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.cleanrep.core.testing.Check;

/** Step 0: the fixed interfaces behave as documented. */
public class ScaffoldTest {
  private final RepoResources resources = RepoResources.fromSystemProperty();

  public void testRepoResourcesReadTheThreeKindsOfFile() throws Exception {
    Check.isTrue(new String(resources.read("domain/catalog.json"), StandardCharsets.UTF_8).contains("squat"), "catalog");
    Check.isTrue(resources.read("contracts/RepEvent.json").length > 0, "contract schema");
    Check.isTrue(resources.read("fixtures/landmarks/squat_good_10.json").length > 0, "replay fixture");
  }

  public void testRepoResourcesRefuseOtherNamesAndEscapes() {
    for (String name : new String[] {"../AGENTS.md", "domain/../../AGENTS.md", "/etc/passwd", "services/api/app.py", "domain/x y.json"}) {
      Check.raises(FileNotFoundException.class, () -> resources.read(name));
    }
  }

  public void testRequestHeadersAreLowerCaseAndTheBodyIsCapped() {
    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Type", "application/json");
    ApiRequest r = new ApiRequest("POST", "/v1/sessions", null, headers, new byte[0], "127.0.0.1");
    Check.equal("application/json", r.header("content-type"));
    Check.equal("application/json", r.header("CONTENT-TYPE"));
    Check.equal("", r.query);
    Check.raises(IllegalArgumentException.class,
        () -> new ApiRequest("POST", "/v1/x", "", headers, new byte[ApiRequest.MAX_BODY_BYTES + 1], "127.0.0.1"));
  }

  public void testConfigDefaultsMatchThePythonConfig() {
    CoreConfig c = CoreConfig.builder(new File("unused"), resources).build();
    Check.equal(7200, c.sessionSeconds);
    Check.equal(300, c.pairingSeconds);
    Check.isTrue(!c.allowReplay, "replay off by default");
    Check.isTrue(c.origins().contains("http://127.0.0.1:8105") && c.origins().contains("http://localhost:8105"), "loopback origins");
    CoreConfig tv = CoreConfig.builder(new File("unused"), resources)
        .extraOrigins(Collections.singleton("https://192.168.1.20:8443")).clock(() -> 1000.0).build();
    Check.isTrue(tv.origins().contains("https://192.168.1.20:8443"), "extra origin");
    Check.equal(1000.0, tv.clock.getAsDouble());
  }
}
