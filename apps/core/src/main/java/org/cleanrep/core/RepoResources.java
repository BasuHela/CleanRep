package org.cleanrep.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/** {@link Resources} from a CleanRep checkout, for the JVM (tests, `Main`, `make browser-test`). */
public final class RepoResources implements Resources {
  private final File root;

  public RepoResources(File root) {
    this.root = root;
  }

  /** The checkout named by the `cleanrep.root` system property, which `scripts/core.sh` sets. */
  public static RepoResources fromSystemProperty() {
    String root = System.getProperty("cleanrep.root");
    if (root == null || root.isEmpty()) throw new IllegalStateException("cleanrep.root is not set");
    return new RepoResources(new File(root));
  }

  @Override
  public byte[] read(String name) throws IOException {
    if (!name.matches("[A-Za-z0-9_./-]+") || name.contains("..") || name.startsWith("/")) {
      throw new FileNotFoundException(name);
    }
    String path;
    if (name.startsWith("domain/") || name.startsWith("contracts/")) path = "packages/" + name;
    else if (name.startsWith("fixtures/landmarks/")) path = name;
    else throw new FileNotFoundException(name);
    try (InputStream in = new FileInputStream(new File(root, path))) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      return out.toByteArray();
    }
  }
}
