package org.cleanrep.core.testing;

import java.util.Arrays;
import java.util.Objects;

/** Assertions for {@link TestRunner} tests. Each failure is an AssertionError with both values. */
public final class Check {
  private Check() {}

  public static void isTrue(boolean condition, String what) {
    if (!condition) throw new AssertionError(what);
  }

  public static void equal(Object expected, Object actual) {
    if (!Objects.equals(expected, actual)) throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
  }

  public static void equalBytes(byte[] expected, byte[] actual) {
    if (!Arrays.equals(expected, actual)) throw new AssertionError("byte arrays differ");
  }

  /** A block that should throw. */
  public interface Block {
    void run() throws Exception;
  }

  /** Runs the block and returns what it threw; fails if it threw nothing or something of another type. */
  public static <T extends Throwable> T raises(Class<T> type, Block block) {
    try {
      block.run();
    } catch (Throwable t) {
      if (type.isInstance(t)) return type.cast(t);
      throw new AssertionError("expected " + type.getSimpleName() + " but got " + t, t);
    }
    throw new AssertionError("expected " + type.getSimpleName() + " but nothing was thrown");
  }
}
