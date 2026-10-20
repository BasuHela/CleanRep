package org.cleanrep.core;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import static org.cleanrep.core.Json.*;
import static org.cleanrep.core.testing.Check.*;

public final class StoreTest {
  public void test_C_STO_1_cas_durable_defensive_copy() throws Exception {
    File dir = Files.createTempDirectory("cleanrep-store-").toFile();
    try (JsonStore store = new JsonStore(dir)) {
      Map<String, Object> v = obj("revision", 0, "text", "é");
      store.create("session_a", v); v.put("text", "changed");
      equal("é", store.get("session_a").get("text"));
      raises(StoreConflict.class, () -> store.create("session_a", v));
      raises(StoreConflict.class, () -> store.compareAndSwap("session_a", 1, v));
      store.compareAndSwap("session_a", 0, obj("revision", 1, "text", "saved"));
    }
    try (JsonStore store = new JsonStore(dir)) {
      equal("saved", store.get("session_a").get("text"));
      raises(StoreConflict.class, () -> store.delete("session_a", 0));
      store.delete("session_a", 1); equal(null, store.get("session_a"));
    }
    TestFiles.remove(dir);
  }
  public void test_C_STO_2_receipt_commit_rollback_and_delete() throws Exception {
    File dir = Files.createTempDirectory("cleanrep-store-").toFile();
    try (JsonStore store = new JsonStore(dir)) {
      store.create("session_a", obj("revision", 0));
      store.create("tv:x", obj("revision", 0));
      store.create("receipt:session_a:key", obj("revision", 0));
      raises(StoreConflict.class, () -> store.commit("session_a", 0, obj("revision", 1), "receipt:session_a:key", obj("revision", 0)));
      equal(0, integer(store.get("session_a").get("revision")));
      store.commit("session_a", 0, obj("revision", 1), "receipt:session_a:two", obj("revision", 0));
      raises(StoreConflict.class, () -> store.deleteSession("session_a", 0, "tv:x"));
      equal(2, store.keys("receipt:").size());
      store.deleteSession("session_a", 1, "tv:x"); equal(0, store.keys("").size());
    }
    TestFiles.remove(dir);
  }
  public void test_C_STO_3_concurrent_cas_one_winner_and_exclusive_owner() throws Exception {
    File dir = Files.createTempDirectory("cleanrep-store-").toFile();
    try (JsonStore store = new JsonStore(dir)) {
      raises(IllegalStateException.class, () -> new JsonStore(dir));
      store.create("session_a", obj("revision", 0));
      CountDownLatch start = new CountDownLatch(1); AtomicInteger wins = new AtomicInteger();
      Thread[] workers = new Thread[8];
      for (int i = 0; i < workers.length; i++) {
        workers[i] = new Thread(() -> {
          try { start.await(); store.compareAndSwap("session_a", 0, obj("revision", 1)); wins.incrementAndGet(); }
          catch (StoreConflict expected) { /* one winner */ }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }); workers[i].start();
      }
      start.countDown(); for (Thread t : workers) t.join(); equal(1, wins.get());
    }
    TestFiles.remove(dir);
  }
}
