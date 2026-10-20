package org.cleanrep.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

final class Secrets {
  private static final char[] URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
  private static final char[] HEX = "0123456789abcdef".toCharArray();
  private Secrets() {}
  static String token(Random random) {
    byte[] bytes = new byte[32]; random.nextBytes(bytes);
    StringBuilder out = new StringBuilder(43); int bits = 0, buffer = 0;
    for (byte b : bytes) {
      buffer = (buffer << 8) | (b & 255); bits += 8;
      while (bits >= 6) { bits -= 6; out.append(URL[(buffer >>> bits) & 63]); }
    }
    if (bits > 0) out.append(URL[(buffer << (6 - bits)) & 63]);
    return out.toString();
  }
  static String id(Random random, String prefix) {
    byte[] bytes = new byte[16]; random.nextBytes(bytes);
    bytes[6] = (byte) ((bytes[6] & 15) | 64); bytes[8] = (byte) ((bytes[8] & 63) | 128);
    return prefix + hex(bytes);
  }
  static String hash(String value) {
    try { return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
  static String hex(byte[] bytes) {
    char[] out = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) { out[2 * i] = HEX[(bytes[i] & 255) >>> 4]; out[2 * i + 1] = HEX[bytes[i] & 15]; }
    return new String(out);
  }
  static boolean equal(String a, String b) {
    return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
  }
}
