package org.cleanrep.core;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;
import static org.cleanrep.core.Json.*;

/** Latest notice plus bounded one-second window; no store dependency, as coach.py. */
final class CoachNotices {
  private final DoubleSupplier clock;
  private final Map<String, Pending> pending = new LinkedHashMap<>();
  private static final class Pending {
    double expiry, until;
    long seq;
    String set;
    List<Object> times = arr();
    Map<String, Object> notice;
  }
  CoachNotices(DoubleSupplier clock) { this.clock = clock; }
  synchronized void sweep() {
    double now = clock.getAsDouble();
    Iterator<Pending> it = pending.values().iterator();
    while (it.hasNext()) {
      Pending p = it.next();
      if (p.expiry <= now) { it.remove(); continue; }
      if (p.until <= now) p.notice = null;
      p.times.removeIf(t -> num(t) <= now - 1);
    }
  }
  synchronized boolean add(String sid, Map<String, Object> event, double expiry) {
    sweep(); double now = clock.getAsDouble();
    Pending p = pending.get(sid);
    if (p == null) { p = new Pending(); p.expiry = expiry; pending.put(sid, p); }
    if (p.times.size() >= 4) return false;
    p.times.add(now); p.seq++; p.set = str(event, "set_id"); p.until = now + 4;
    p.notice = obj("kind", event.get("kind"), "seq", p.seq); return true;
  }
  synchronized Map<String, Object> peek(String sid, String set) {
    sweep(); Pending p = pending.get(sid);
    return p != null && set.equals(p.set) ? copy(p.notice) : null;
  }
  synchronized void clear(String sid) { Pending p = pending.get(sid); if (p != null) p.notice = null; }
  synchronized void forget(String sid) { pending.remove(sid); }
  synchronized void close() { pending.clear(); }
}
