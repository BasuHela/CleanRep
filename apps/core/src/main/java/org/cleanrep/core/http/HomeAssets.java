package org.cleanrep.core.http;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import org.cleanrep.core.Resources;

/**
 * Where the home-mode TV build keeps its extra assets, next to the web build in the APK: the catalog, the
 * contract schemas and the phone APK, all under {@link #PREFIX}. The router never serves that prefix as a static
 * file (only the phone APK, through its own route), and the TV's WebView refuses it too.
 */
public final class HomeAssets {
  public static final String PREFIX = "cleanrep-home/";
  public static final String PHONE_APK = PREFIX + "cleanrep-phone.apk";

  private HomeAssets() {}

  /** Opens an asset by its path in the APK, as Android's AssetManager.open does. */
  public interface Opener {
    InputStream open(String assetPath) throws IOException;
  }

  /** The asset path for a {@link Resources} name, with RepoResources' name rules; throws for any other name. */
  public static String assetName(String name) throws FileNotFoundException {
    if (!name.matches("[A-Za-z0-9_./-]+") || name.contains("..") || name.startsWith("/")) throw new FileNotFoundException(name);
    if (name.startsWith("domain/") || name.startsWith("contracts/") || name.startsWith("fixtures/landmarks/")) return PREFIX + name;
    throw new FileNotFoundException(name);
  }

  /** The core's {@link Resources}, read from the APK's assets. */
  public static Resources resources(final Opener assets) {
    return name -> {
      try (InputStream in = assets.open(assetName(name))) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toByteArray();
      }
    };
  }
}
