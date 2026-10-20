package org.cleanrep.core.http;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Read-only files for the 8105 listener, by relative name (`index.html`, `assets/index-x.js`): the web build's
 * folder on the JVM ({@link Directory}), the APK's assets on the TV. Names arrive already checked by the router.
 */
public interface StaticSource {
  /** The file's size in bytes, or -1 if there is no such file. */
  long length(String name) throws IOException;

  InputStream open(String name) throws IOException;

  /** Files under one folder; anything that resolves outside it (a symbolic link, say) counts as missing. */
  final class Directory implements StaticSource {
    private final File root;
    private final String rootPath;

    public Directory(File root) throws IOException {
      this.root = root.getCanonicalFile();
      this.rootPath = this.root.getPath() + File.separator;
    }

    private File resolve(String name) throws IOException {
      // The router checks names already; refusing dot segments here too keeps this class safe on its own.
      for (String segment : name.split("/", -1)) {
        if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.indexOf('\\') >= 0) return null;
      }
      File file = new File(root, name).getCanonicalFile();
      return file.getPath().startsWith(rootPath) && file.isFile() ? file : null;
    }

    @Override
    public long length(String name) throws IOException {
      File file = resolve(name);
      return file == null ? -1 : file.length();
    }

    @Override
    public InputStream open(String name) throws IOException {
      File file = resolve(name);
      if (file == null) throw new java.io.FileNotFoundException(name);
      return new FileInputStream(file);
    }
  }
}
