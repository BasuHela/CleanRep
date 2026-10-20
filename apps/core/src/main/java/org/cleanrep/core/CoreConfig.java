package org.cleanrep.core;

import java.io.File;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;
import java.util.function.DoubleSupplier;

/** Fixture-mode settings for the home-mode server; the Python equivalent is `services/api/config.py`. */
public final class CoreConfig {
  /** The origin the TV UI uses, like CLEANREP_ORIGIN. */
  public final String origin;
  /** More allowed browser origins, for example the TV's `https://<ip>:8443`. */
  public final Set<String> extraOrigins;
  /** What the TV pairing screen names as the phone address (`phone_url` in the view); null to let the TV derive it. */
  public final String phoneUrl;
  /** Where the session store keeps its files; created if missing. */
  public final File dataDir;
  /** Serve `/v1/replay/<fixture>` (CLEANREP_ALLOW_REPLAY=1); tests and demos only. */
  public final boolean allowReplay;
  public final Resources resources;
  /** Seconds since the epoch, as Python's `time.time()`. Tests inject a fake clock. */
  public final DoubleSupplier clock;
  /** Tokens, pairing codes and ids come from here: SecureRandom in use, a seeded Random in tests. */
  public final Random random;
  public final int sessionSeconds;
  public final int pairingSeconds;

  private CoreConfig(Builder b) {
    origin = b.origin;
    extraOrigins = Collections.unmodifiableSet(new LinkedHashSet<>(b.extraOrigins));
    phoneUrl = b.phoneUrl;
    dataDir = b.dataDir;
    allowReplay = b.allowReplay;
    resources = b.resources;
    clock = b.clock;
    random = b.random;
    sessionSeconds = b.sessionSeconds;
    pairingSeconds = b.pairingSeconds;
  }

  /** Allowed `Origin` header values, as `Config.origins` in Python. A request with no Origin header is allowed. */
  public Set<String> origins() {
    Set<String> all = new LinkedHashSet<>();
    all.add(origin);
    all.add("http://127.0.0.1:8105");
    all.add("http://localhost:8105");
    all.addAll(extraOrigins);
    return Collections.unmodifiableSet(all);
  }

  public static Builder builder(File dataDir, Resources resources) {
    return new Builder(dataDir, resources);
  }

  public static final class Builder {
    private String origin = "http://127.0.0.1:8105";
    private Set<String> extraOrigins = new LinkedHashSet<>();
    private String phoneUrl;
    private final File dataDir;
    private boolean allowReplay;
    private final Resources resources;
    private DoubleSupplier clock = () -> System.currentTimeMillis() / 1000.0;
    private Random random = new SecureRandom();
    private int sessionSeconds = 7200;
    private int pairingSeconds = 300;

    private Builder(File dataDir, Resources resources) {
      this.dataDir = dataDir;
      this.resources = resources;
    }

    public Builder origin(String value) { origin = value; return this; }
    public Builder extraOrigins(Set<String> value) { extraOrigins = new LinkedHashSet<>(value); return this; }
    public Builder phoneUrl(String value) { phoneUrl = value; return this; }
    public Builder allowReplay(boolean value) { allowReplay = value; return this; }
    public Builder clock(DoubleSupplier value) { clock = value; return this; }
    public Builder random(Random value) { random = value; return this; }
    public Builder sessionSeconds(int value) { sessionSeconds = value; return this; }
    public Builder pairingSeconds(int value) { pairingSeconds = value; return this; }

    public CoreConfig build() {
      return new CoreConfig(this);
    }
  }
}
