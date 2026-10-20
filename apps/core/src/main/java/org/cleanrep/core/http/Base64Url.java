package org.cleanrep.core.http;

/** Base64url without padding (RFC 4648 §5). The JDK encoder reached Android only in API 26, after the TV's minSdk 25. */
public final class Base64Url {
  private static final char[] ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

  private Base64Url() {}

  public static String encode(byte[] data) {
    StringBuilder out = new StringBuilder((data.length * 4 + 2) / 3);
    int i = 0;
    for (; i + 2 < data.length; i += 3) {
      int n = (data[i] & 0xff) << 16 | (data[i + 1] & 0xff) << 8 | (data[i + 2] & 0xff);
      out.append(ALPHABET[n >>> 18]).append(ALPHABET[n >>> 12 & 63]).append(ALPHABET[n >>> 6 & 63]).append(ALPHABET[n & 63]);
    }
    int rest = data.length - i;
    if (rest == 1) {
      int n = (data[i] & 0xff) << 16;
      out.append(ALPHABET[n >>> 18]).append(ALPHABET[n >>> 12 & 63]);
    } else if (rest == 2) {
      int n = (data[i] & 0xff) << 16 | (data[i + 1] & 0xff) << 8;
      out.append(ALPHABET[n >>> 18]).append(ALPHABET[n >>> 12 & 63]).append(ALPHABET[n >>> 6 & 63]);
    }
    return out.toString();
  }
}
