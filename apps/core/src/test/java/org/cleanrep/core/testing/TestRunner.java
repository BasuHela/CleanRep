package org.cleanrep.core.testing;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A dependency-free test runner (no JUnit download). It finds every class named `*Test` under the compiled
 * test folder and runs each public no-argument method whose name starts with `test`, in name order, on a
 * fresh instance (or statically). Put the spec ID in the method name: `test_C_DOM_1_catalog_loads`.
 * Usage: TestRunner <test-classes-dir> [filter]; the filter is a substring of `Class.method`.
 */
public final class TestRunner {
  private TestRunner() {}

  public static void main(String[] args) throws Exception {
    File dir = new File(args[0]);
    String filter = args.length > 1 ? args[1] : "";
    List<String> classes = new ArrayList<>();
    collect(dir, "", classes);
    Collections.sort(classes);
    int run = 0, failed = 0;
    for (String name : classes) {
      Class<?> type = Class.forName(name);
      List<Method> tests = new ArrayList<>();
      for (Method m : type.getMethods()) {
        if (m.getName().startsWith("test") && m.getParameterTypes().length == 0
            && m.getDeclaringClass() == type) tests.add(m);
      }
      Collections.sort(tests, (a, b) -> a.getName().compareTo(b.getName()));
      for (Method m : tests) {
        String label = type.getSimpleName() + "." + m.getName();
        if (!label.contains(filter)) continue;
        run++;
        try {
          Object target = Modifier.isStatic(m.getModifiers()) ? null : type.getDeclaredConstructor().newInstance();
          m.invoke(target);
          System.out.println("PASS " + label);
        } catch (InvocationTargetException e) {
          failed++;
          System.out.println("FAIL " + label);
          e.getCause().printStackTrace(System.out);
        }
      }
    }
    if (run == 0) {
      System.out.println("NO TESTS RUN" + (filter.isEmpty() ? "" : " (filter: " + filter + ")"));
      System.exit(1);
    }
    if (failed > 0) {
      System.out.println("FAILED " + failed + " of " + run + " tests");
      System.exit(1);
    }
    System.out.println("ALL PASSED (" + run + " tests)");
  }

  private static void collect(File dir, String pkg, List<String> out) {
    File[] files = dir.listFiles();
    if (files == null) return;
    for (File f : files) {
      if (f.isDirectory()) collect(f, pkg + f.getName() + ".", out);
      else if (f.getName().endsWith("Test.class") && !f.getName().contains("$")) {
        out.add(pkg + f.getName().substring(0, f.getName().length() - ".class".length()));
      }
    }
  }
}
