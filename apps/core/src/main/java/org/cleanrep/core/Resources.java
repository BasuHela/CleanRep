package org.cleanrep.core;

import java.io.IOException;

/**
 * Read-only files the API needs, by name:
 * - `domain/catalog.json` (the repo's `packages/domain/catalog.json`);
 * - `contracts/<Name>.json` (`packages/contracts/`, the JSON schemas pydantic generates);
 * - `fixtures/landmarks/<name>.json` (`fixtures/landmarks/`, the replay route).
 * On the JVM they come from the repo ({@link RepoResources}); on the TV, from the APK's assets.
 */
public interface Resources {
  /** The file's bytes; throws if it does not exist. */
  byte[] read(String name) throws IOException;
}
