package org.cleanrep.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.cleanrep.core.Json.*;

/** SQLiteStore's conditional transactions, in one atomic JSON snapshot. Adapted from RoomQuest (MIT).
 * A separate lock file permits exactly one owner, including across processes. As the only owner, the store keeps
 * the snapshot in memory: reads cost a copy of one record instead of parsing the whole file (every TV poll and
 * camera frame reads), and a write replaces the in-memory snapshot only once its file is safely renamed.
 */
public final class JsonStore implements AutoCloseable {
  private final File file;
  private final File temporary;
  private final RandomAccessFile lockFile;
  private final FileLock lock;
  private Map<String, Object> data;
  private boolean closed;

  public JsonStore(File directory) {
    try {
      if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("cannot create data directory");
      file = new File(directory, "sessions.json");
      temporary = new File(directory, "sessions.json.tmp");
      lockFile = new RandomAccessFile(new File(directory, "sessions.lock"), "rw");
      FileLock acquired;
      try { acquired = lockFile.getChannel().tryLock(); }
      catch (OverlappingFileLockException e) { acquired = null; }
      if (acquired == null) { lockFile.close(); throw new IllegalStateException("session store is already open"); }
      lock = acquired;
      try {
        // A temp file is an uncommitted transaction, even if its contents look complete.
        if (temporary.exists() && !temporary.delete()) throw new IOException("cannot remove abandoned transaction");
        if (!file.exists()) write(obj());
        else data = readFile();
      } catch (RuntimeException | IOException e) { lock.release(); lockFile.close(); throw e; }
    } catch (IOException e) { throw new IllegalStateException("cannot open session store", e); }
  }

  public synchronized Map<String, Object> get(String key) {
    Object value = read().get(key);
    return value == null ? null : copy(map(value));
  }
  public synchronized List<String> keys(String prefix) {
    List<String> result = new ArrayList<>();
    for (String key : read().keySet()) if (key.startsWith(prefix)) result.add(key);
    java.util.Collections.sort(result); // SQLite primary-key scans use key order.
    return result;
  }
  public synchronized void create(String key, Map<String, Object> value) {
    Map<String, Object> all = read();
    if (all.containsKey(key)) throw new StoreConflict();
    all.put(key, copy(value)); write(all);
  }
  public synchronized void compareAndSwap(String key, long revision, Map<String, Object> value) {
    Map<String, Object> all = read(); require(all, key, revision); all.put(key, copy(value)); write(all);
  }
  public synchronized void delete(String key, long revision) {
    Map<String, Object> all = read(); require(all, key, revision); all.remove(key); write(all);
  }
  public synchronized void commit(String key, long revision, Map<String, Object> value, String receiptKey, Map<String, Object> receipt) {
    Map<String, Object> all = read(); require(all, key, revision);
    if (all.containsKey(receiptKey)) throw new StoreConflict();
    all.put(key, copy(value)); all.put(receiptKey, copy(receipt)); write(all);
  }
  public synchronized void deleteSession(String key, long revision, String tokenKey) {
    Map<String, Object> all = read(); require(all, key, revision); all.remove(key);
    for (String candidate : new ArrayList<>(all.keySet())) {
      if (candidate.startsWith("receipt:" + key + ":") || candidate.equals(tokenKey) || candidate.equals("explanation:" + key)) all.remove(candidate);
    }
    write(all);
  }
  /** Removes only expired auxiliary records. Session expiry remains observable as 410 until load/recover. */
  synchronized void pruneAuxiliary(double now) {
    Map<String, Object> all = read(); boolean changed = false;
    for (String key : new ArrayList<>(all.keySet())) {
      Map<String, Object> v = map(all.get(key));
      if ((key.startsWith("rate:") || key.startsWith("tv:") && v.get("session_id") == null)
          && v.get("expires_at") != null && num(v.get("expires_at")) <= now) {
        all.remove(key); changed = true;
      }
    }
    if (changed) write(all);
  }
  private void require(Map<String, Object> all, String key, long revision) {
    Object old = all.get(key);
    if (old == null || ((Number) map(old).get("revision")).longValue() != revision) throw new StoreConflict();
  }
  /** A shallow copy of the snapshot to change; records themselves are replaced, never edited in place. */
  private Map<String, Object> read() {
    if (closed) throw new IllegalStateException("store closed");
    return new LinkedHashMap<>(data);
  }
  private Map<String, Object> readFile() {
    try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192]; int n;
      while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
      return map(Json.parse(bytes.toByteArray()));
    } catch (IOException e) { throw new IllegalStateException("cannot read session store", e); }
  }
  private void write(Map<String, Object> all) {
    if (closed) throw new IllegalStateException("store closed");
    byte[] bytes = Json.stringify(all).getBytes(StandardCharsets.UTF_8);
    try {
      try (FileOutputStream out = new FileOutputStream(temporary)) {
        out.write(bytes); out.flush(); out.getFD().sync();
      }
      // Android/Linux rename on one filesystem atomically replaces the old snapshot. Never delete it first.
      if (!temporary.renameTo(file)) throw new IOException("atomic store rename failed");
    } catch (IOException e) { throw new IllegalStateException("cannot write session store", e); }
    data = all;
  }
  @Override public synchronized void close() {
    if (closed) return;
    closed = true;
    try { lock.release(); lockFile.close(); }
    catch (IOException e) { throw new IllegalStateException("cannot close session store", e); }
  }
}
