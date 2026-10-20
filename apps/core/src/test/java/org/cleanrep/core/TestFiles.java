package org.cleanrep.core;

import java.io.File;
import java.io.IOException;

final class TestFiles {
  private TestFiles() {}
  static void remove(File f) throws IOException {
    File[] children = f.listFiles();
    if (children != null) for (File child : children) remove(child);
    if (f.exists() && !f.delete()) throw new IOException("cannot delete " + f);
  }
}
