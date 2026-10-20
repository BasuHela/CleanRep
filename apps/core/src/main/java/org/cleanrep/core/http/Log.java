package org.cleanrep.core.http;

/** Where the HTTP layer reports problems: standard error on the JVM, logcat (tag CleanRep) on the TV. */
public interface Log {
  /** One line; `error` is null or the cause, whose stack trace the sink may print. */
  void line(String message, Throwable error);

  Log STDERR = (message, error) -> {
    synchronized (System.err) {
      System.err.println(message);
      if (error != null) error.printStackTrace(System.err);
    }
  };
}
