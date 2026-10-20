package org.cleanrep.core.http;

import java.io.File;
import java.util.List;
import java.util.UUID;

/** Test helpers: scratch folders under the repo's `temp/` (git-ignored) and a log that records lines. */
final class Temp {
  private Temp() {}

  static File dir(String name) {
    String root = System.getProperty("cleanrep.root");
    File base = root == null ? new File(System.getProperty("java.io.tmpdir")) : new File(root, "temp");
    File dir = new File(base, "core-tests/" + name + "-" + UUID.randomUUID().toString().substring(0, 8));
    if (!dir.mkdirs()) throw new IllegalStateException("cannot create " + dir);
    return dir;
  }

  /** A Log that keeps each line (with the error's class name appended, if any). */
  static Log log(List<String> lines) {
    return (message, error) -> {
      synchronized (lines) {
        lines.add(error == null ? message : message + " [" + error.getClass().getSimpleName() + "]");
      }
    };
  }
}
