package org.cleanrep.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import static org.cleanrep.core.Json.*;

/** Fixture-mode sessions.py port (RoomQuest MIT). All aggregate operations share this monitor.
 * Planning computes outside the monitor, then publishes under the same revision-checked transaction.
 */
final class Sessions implements AutoCloseable {
  final JsonStore store;
  private final CoreConfig config;
  private final Domain domain;
  private final PhaseMachine machine;
  private final Wording wording;
  private final CoachNotices coach;
  private final Map<String, Frame> mirrors = new LinkedHashMap<>();
  private final Map<String, List<Object>> mirrorRates = new LinkedHashMap<>();
  private final Set<String> tasks = new HashSet<>();
  private final ThreadPoolExecutor workers;
  private boolean closed;
  private double nextPrune;
  private static final class Frame {
    final byte[] bytes; final double at;
    Frame(byte[] bytes, double at) { this.bytes = bytes.clone(); this.at = at; }
  }
  Sessions(CoreConfig config, Domain domain, Schemas schemas) {
    this.config = config; this.domain = domain; machine = new PhaseMachine(domain);
    wording = new Wording(domain.catalog, schemas); coach = new CoachNotices(config.clock);
    store = new JsonStore(config.dataDir);
    workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(32), r -> {
      Thread t = new Thread(r, "cleanrep-plan"); t.setDaemon(true); return t;
    }, new ThreadPoolExecutor.CallerRunsPolicy());
  }
  private double now() { return config.clock.getAsDouble(); }
  static Map<String, Object> envelope(Object version, Object data) { return obj("status", "ok", "version", version, "data", data == null ? obj() : data, "error", null); }
  static Map<String, Object> envelope() { return envelope(null, null); }
  private void bump(Map<String, Object> s, String key) { s.put(key, ((Number) s.get(key)).longValue() + 1); }
  synchronized void sweep() {
    double time = now();
    mirrors.values().removeIf(f -> time - f.at > 2);
    Iterator<List<Object>> windows = mirrorRates.values().iterator();
    while (windows.hasNext()) { List<Object> times = windows.next(); times.removeIf(t -> num(t) <= time - 1); if (times.isEmpty()) windows.remove(); }
    coach.sweep();
    if (time >= nextPrune) { store.pruneAuxiliary(time); nextPrune = time + 60; }
  }
  private void rate(String identity, String action, int limit, int seconds) {
    double time = now(); String key = "rate:" + Secrets.hash(action + ":" + identity);
    Map<String, Object> old = store.get(key), value = old == null ? obj("revision", 0, "times", arr(), "expires_at", time + seconds) : copy(old);
    List<Object> times = list(value.get("times")); times.removeIf(t -> num(t) <= time - seconds);
    if (times.size() >= limit) throw new DomainError("rate_limited", 429);
    times.add(time); bump(value, "revision");
    // Sliding expiry only affects garbage collection, not the window's admission rule.
    value.put("expires_at", time + seconds);
    if (old == null) store.create(key, value); else store.compareAndSwap(key, ((Number) old.get("revision")).longValue(), value);
  }
  synchronized Map<String, Object> bootstrap(String peer) {
    if (!arr("127.0.0.1", "::1").contains(peer)) rate(peer, "bootstrap", 12, 3600);
    String token = Secrets.token(config.random); double expires = now() + config.sessionSeconds;
    store.create("tv:" + Secrets.hash(token), obj("revision", 0, "expires_at", expires, "session_id", null));
    return envelope(null, obj("tv_token", token, "expires_at", expires));
  }
  synchronized Map<String, Object> create(String token) {
    String key = "tv:" + Secrets.hash(token); Map<String, Object> boot = store.get(key);
    if (boot == null || num(boot.get("expires_at")) <= now()) throw new DomainError("unauthorized", 401);
    if (boot.get("session_id") != null) throw new DomainError("session_exists", 409);
    String sid = Secrets.id(config.random, "session_"); Map<String, Object> state = machine.initial(sid);
    state.putAll(obj("revision", 0, "version", 0, "expires_at", boot.get("expires_at"), "tv_hash", Secrets.hash(token), "phone_hash", null,
        "pairing_code", String.format(java.util.Locale.ROOT, "%06d", config.random.nextInt(1000000)), "pairing_expires_at", now() + config.pairingSeconds,
        "pairing_requests", obj(), "jobs", obj(), "pending_job", null, "phone_last_seen", null, "camera_check", null,
        "camera_check_at", null, "traces", arr(), "planner_runs", 0, "explanation_runs", 0));
    Map<String, Object> reserved = copy(boot); bump(reserved, "revision"); reserved.put("session_id", sid);
    try { store.compareAndSwap(key, ((Number) boot.get("revision")).longValue(), reserved); store.create(sid, state); }
    catch (StoreConflict e) { throw new DomainError("session_exists", 409); }
    return envelope(0, view(state, "tv"));
  }
  synchronized Map<String, Object> load(String sid) {
    Map<String, Object> state = store.get(sid);
    if (state == null) throw new DomainError("not_found", 404);
    if (num(state.get("expires_at")) <= now()) { purge(state); throw new DomainError("session_expired", 410); }
    return state;
  }
  private String role(Map<String, Object> s, String token) {
    String hash = Secrets.hash(token);
    if (Secrets.equal(str(s, "tv_hash"), hash)) return "tv";
    if (Secrets.equal(str(s, "phone_hash"), hash)) return "phone";
    throw new DomainError("unauthorized", 401);
  }
  private Map<String, Object> auth(String sid, String token, String required) {
    Map<String, Object> s = load(sid); String role = role(s, token);
    if (required != null && !required.equals(role)) throw new DomainError("forbidden", 403);
    return s;
  }
  private Map<String, Object> view(Map<String, Object> state, String role) {
    Map<String, Object> active = state.get("active_set") == null ? null : map(state.get("active_set")), publicActive = null;
    if (active != null) {
      publicActive = copy(active); publicActive.remove("reps"); publicActive.remove("hold");
      List<Object> reps = list(active.get("reps"));
      publicActive.putAll(obj("reps_counted", reps.size(), "rated", !reps.isEmpty() && yes(map(reps.get(reps.size() - 1)).get("rated")), "hold", copy(active.get("hold"))));
    }
    Map<String, Object> out = obj("session_id", state.get("session_id"), "phase", state.get("phase"), "paused", state.get("paused"), "sensor_mode", state.get("sensor_mode"), "active_set", publicActive);
    if (role.equals("phone")) { out.put("catalog", domain.catalog.publicView(active == null ? null : str(active, "exercise_id"))); return out; }
    for (String k : new String[]{"profile", "plan", "summary", "explanation", "recap", "traces", "block_index", "set_index", "completed_sets"}) out.put(k, copy(state.get(k)));
    Object check = state.get("camera_check"); Map<String, Object> publicCheck = null;
    if (check != null && "READY".equals(state.get("phase"))) {
      publicCheck = obj(); for (String k : new String[]{"full_body", "side_view", "light"}) publicCheck.put(k, map(check).get(k));
    }
    List<Object> requests = arr();
    for (Map.Entry<String, Object> e : map(state.get("pairing_requests")).entrySet()) {
      Map<String, Object> r = map(e.getValue());
      if (!yes(r.get("used")) && num(r.get("expires_at")) > now()) requests.add(obj("request_id", e.getKey(), "verification_code", r.get("verification_code"), "approved", r.get("approved")));
    }
    out.putAll(obj("camera_check", publicCheck, "readiness_restricted", map(state.get("profile")).get("readiness_restricted"),
        "pairing_code", now() < num(state.get("pairing_expires_at")) ? state.get("pairing_code") : "", "phone_url", config.phoneUrl,
        "pairing_requests", requests, "phone_connected", state.get("phone_last_seen") != null && now() - num(state.get("phone_last_seen")) < 20,
        "phone_sensing", state.get("camera_check_at") != null && now() - num(state.get("camera_check_at")) < 4,
        "coach_notice", null, "mirror_live", mirrorFrame(state) != null, "last_rep", null, "rep_marks", active == null ? null : arr()));
    if (active != null) {
      List<Object> reps = list(active.get("reps"));
      for (Object raw : reps) {
        Map<String, Object> rep = storedRep(raw);
        list(out.get("rep_marks")).add(rep == null ? "unrated" : domain.repMark(rep));
      }
      if (!reps.isEmpty()) {
        Map<String, Object> last = storedRep(reps.get(reps.size() - 1));
        if (last != null) out.put("last_rep", obj("index", last.get("rep_index"), "rated", last.get("rated"), "faults", domain.classify(last).get(0)));
      }
    }
    return out;
  }
  private Map<String, Object> storedRep(Object raw) {
    if (!(raw instanceof Map)) return null;
    try {
      Map<String, Object> rep = copy(map(raw)), e = domain.catalog.exercise(String.valueOf(rep.get("exercise_id"))), metrics = map(rep.get("metrics"));
      if (!new HashSet<>(list(e.get("metrics"))).equals(metrics.keySet())) return null;
      for (String k : metrics.keySet()) {
        Object rawValue = metrics.get(k); double value = rawValue instanceof Boolean ? (yes(rawValue) ? 1 : 0) : Double.parseDouble(String.valueOf(rawValue));
        Map<String, Object> limits = map(domain.catalog.metrics.get(k));
        if (!(num(limits.get("minimum")) <= value && value <= num(limits.get("maximum")))) return null;
        metrics.put(k, value);
      }
      rep.put("rep_index", (int) Double.parseDouble(String.valueOf(rep.get("rep_index"))));
      Object rated = rep.get("rated"); if (rated == null) return null;
      rep.put("rated", rated instanceof Boolean ? rated : rated instanceof Number ? num(rated) != 0 : !rated.toString().isEmpty());
      return rep;
    } catch (IllegalArgumentException | NullPointerException e) { return null; }
  }
  synchronized Map<String, Object> get(String sid, String token) {
    Map<String, Object> state = auth(sid, token, null); String role = role(state, token);
    if ("IN_SET".equals(state.get("phase")) && "paired".equals(state.get("sensor_mode")) && !yes(state.get("paused"))) {
      Map<String, Object> active = map(state.get("active_set"));
      double idle = "hold".equals(active.get("kind")) ? num(active.get("target")) + 15 : 20;
      if (now() - num(state.getOrDefault("last_rep_at", now())) >= idle) {
        Map<String, Object> updated = machine.apply(state, "sets/end", obj("set_id", active.get("set_id"))); bump(updated, "version");
        save(state, updated, null, null); mirrors.remove(sid); state = updated;
      }
    }
    if (role.equals("tv") && "SET_SUMMARY".equals(state.get("phase")) && state.get("summary") != null && state.get("explanation") == null) {
      Map<String, Object> updated = copy(state); updated.put("explanation", fixtureWording(map(state.get("summary"))));
      save(state, updated, null, null); state = updated;
    }
    Map<String, Object> view = view(state, role);
    if (role.equals("tv") && "IN_SET".equals(state.get("phase")) && !yes(state.get("paused")) && state.get("active_set") != null)
      view.put("coach_notice", coach.peek(sid, str(map(state.get("active_set")), "set_id")));
    return envelope(state.get("version"), view);
  }
  synchronized void save(Map<String, Object> previous, Map<String, Object> state, String receiptKey, Map<String, Object> receipt) {
    state.put("revision", ((Number) previous.get("revision")).longValue() + 1);
    if (canonical(state).getBytes(StandardCharsets.UTF_8).length > 24 * 1024) throw new DomainError("session_size_limit", 413);
    String sid = str(state, "session_id"); long revision = ((Number) previous.get("revision")).longValue();
    try {
      if (receiptKey == null) store.compareAndSwap(sid, revision, state);
      else store.commit(sid, revision, state, receiptKey, receipt);
    } catch (StoreConflict e) { throw new DomainError("stale_version", 409, previous.get("version")); }
    Object active = state.get("active_set"), old = previous.get("active_set");
    if (!"IN_SET".equals(state.get("phase")) || yes(state.get("paused")) || active == null || old == null
        || !map(active).get("set_id").equals(map(old).get("set_id")) || list(map(active).get("reps")).size() > list(map(old).get("reps")).size()) coach.clear(sid);
  }
  synchronized Map<String, Object> pair(String code, String peer) {
    rate(peer, "pair", 20, 60); Map<String, Object> found = null;
    for (String key : store.keys("session_")) {
      Map<String, Object> s = store.get(key);
      if (code.equals(s.get("pairing_code")) && num(s.get("expires_at")) > now() && num(s.get("pairing_expires_at")) > now()) { found = s; break; }
    }
    if (found == null) throw new DomainError("pairing_expired", 410);
    if (map(found.get("pairing_requests")).size() >= 8) throw new DomainError("pairing_limit", 429);
    String rid = Secrets.id(config.random, "pair_"), token = Secrets.token(config.random);
    String verification = String.format(java.util.Locale.ROOT, "%04d", config.random.nextInt(10000));
    Map<String, Object> updated = copy(found);
    map(updated.get("pairing_requests")).put(rid, obj("claim_hash", Secrets.hash(token), "verification_code", verification, "expires_at", found.get("pairing_expires_at"), "approved", false, "used", false));
    bump(updated, "version"); save(found, updated, null, null);
    return envelope(updated.get("version"), obj("request_id", rid, "claim_token", token, "verification_code", verification));
  }
  synchronized Map<String, Object> claim(String rid, String token, String peer) {
    rate(peer, "claim", 40, 60);
    for (String key : store.keys("session_")) {
      Map<String, Object> state = store.get(key); if (num(state.get("expires_at")) <= now()) continue;
      Object raw = map(state.get("pairing_requests")).get(rid); if (raw == null) continue;
      Map<String, Object> request = map(raw); if (!Secrets.equal(str(request, "claim_hash"), Secrets.hash(token))) continue;
      if (yes(request.get("used"))) throw new DomainError("claim_used", 409);
      if (num(request.get("expires_at")) <= now()) throw new DomainError("pairing_expired", 410);
      if (!yes(request.get("approved"))) throw new DomainError("not_approved", 409);
      String phone = Secrets.token(config.random); Map<String, Object> updated = copy(state);
      map(map(updated.get("pairing_requests")).get(rid)).put("used", true);
      updated.putAll(obj("phone_hash", Secrets.hash(phone), "phone_last_seen", now())); save(state, updated, null, null);
      return envelope(state.get("version"), obj("phone_token", phone, "session_id", state.get("session_id")));
    }
    throw new DomainError("pairing_expired", 401);
  }
  private String fingerprint(String action, Map<String, Object> payload) { return Secrets.hash(canonical(obj("action", action, "payload", payload))); }
  private String receiptKey(String sid, String token, Map<String, Object> p) { return "receipt:" + sid + ":" + Secrets.hash(token) + ":" + p.get("idempotency_key"); }
  synchronized Map<String, Object> mutate(String sid, String token, String action, Map<String, Object> payload) {
    Map<String, Object> state = auth(sid, token, "tv");
    String key = receiptKey(sid, token, payload), fingerprint = fingerprint(action, payload);
    Map<String, Object> receipt = store.get(key);
    if (receipt != null) {
      if (!fingerprint.equals(receipt.get("fingerprint"))) throw new DomainError("idempotency_mismatch", 409, state.get("version"));
      Map<String, Object> response = map(receipt.get("response"));
      if (action.equals("plan") || action.equals("adapt")) schedule(sid, str(map(response.get("data")), "job_id"));
      return response;
    }
    if (!same(state.get("version"), payload.get("expected_version"))) throw new DomainError("stale_version", 409, state.get("version"));
    Map<String, Object> updated = copy(state), p = copy(payload), data = null;
    if (action.equals("plan") || action.equals("adapt")) {
      try { updated = machine.apply(updated, action, p); }
      catch (PhaseMachine.PhaseError e) { throw new DomainError(e.getMessage(), 409); }
      String jid = Secrets.id(config.random, "job_"); Map<String, Object> jobs = map(updated.get("jobs"));
      while (jobs.size() > 7) jobs.remove(jobs.keySet().iterator().next());
      jobs.put(jid, obj("state", "pending", "source", null)); updated.put("pending_job", jid); data = obj("job_id", jid);
    } else if (action.equals("explain")) {
      Map<String, Object> summary = null;
      for (Object value : list(state.get("completed_sets"))) if (map(value).get("set_id").equals(p.get("set_id"))) { summary = map(value); break; }
      if (summary == null) throw new DomainError("set_not_found", 404);
      data = fixtureWording(summary); updated.put("explanation", data);
    } else if (action.equals("pair/approve")) {
      Object raw = map(updated.get("pairing_requests")).get(p.get("request_id"));
      if (raw == null || num(map(raw).get("expires_at")) <= now() || yes(map(raw).get("approved"))) throw new DomainError("pairing_expired");
      map(raw).put("approved", true);
      // A second phone approved after the first has left the lobby: a conflict, like every other phase error.
      try { updated = machine.apply(updated, "pair", p); }
      catch (PhaseMachine.PhaseError e) { throw new DomainError(e.getMessage(), 409, state.get("version")); }
      updated.put("pairing_code", "");
    } else {
      if (action.equals("sets/start")) { p.put("set_id", Secrets.id(config.random, "set_")); updated.put("last_rep_at", now()); }
      try { updated = machine.apply(updated, action, p); }
      catch (PhaseMachine.PhaseError e) { throw new DomainError(e.getMessage(), 409, state.get("version")); }
      if (action.equals("remote-only")) updated.putAll(obj("phone_hash", null, "pairing_requests", obj()));
      if (action.equals("resume")) updated.put("last_rep_at", now());
    }
    bump(updated, "version");
    if (yes(updated.get("paused")) || !"paired".equals(updated.get("sensor_mode")) || !arr("READY", "DEMO", "IN_SET").contains(updated.get("phase"))) mirrors.remove(sid);
    Map<String, Object> response = envelope(updated.get("version"), data == null ? view(updated, "tv") : data);
    receipt = obj("revision", 0, "expires_at", state.get("expires_at"), "fingerprint", fingerprint, "response", response);
    save(state, updated, key, receipt);
    if (action.equals("plan") || action.equals("adapt")) schedule(sid, str(data, "job_id"));
    return response;
  }
  private Map<String, Object> fixtureWording(Map<String, Object> summary) {
    Map<String, Object> scope = copy(summary); Object offer = scope.get("drill_offer"); scope.put("drill_id", offer == null ? null : map(offer).get("drill_id"));
    Map<String, Object> result = wording.fallback(scope); result.put("source", "fixture"); return result;
  }
  synchronized Map<String, Object> sensor(String sid, String token, String action, Map<String, Object> payload) {
    Map<String, Object> state = auth(sid, token, "phone");
    if (action.equals("coach")) {
      Object active = state.get("active_set");
      if (!"IN_SET".equals(state.get("phase")) || yes(state.get("paused")) || active == null || !map(active).get("set_id").equals(payload.get("set_id"))) throw new DomainError("set_not_active", 409);
      if (!coach.add(sid, payload, num(state.get("expires_at")))) throw new DomainError("rate_limited", 429);
      return envelope(state.get("version"), obj());
    }
    Map<String, Object> updated = copy(state); updated.put("phone_last_seen", now());
    if (action.equals("heartbeat") || action.equals("camera-check")) {
      if (action.equals("camera-check")) {
        rate(sid, "camera-check", 1, 1); updated.put("camera_check", copy(payload)); updated.put("camera_check_at", now());
      }
      save(state, updated, null, null); return envelope(state.get("version"), obj());
    }
    boolean rep = action.equals("reps");
    String natural = payload.get("set_id") + ":" + (rep ? payload.get("rep_index") : "hold");
    String key = "receipt:" + sid + ":sensor:" + natural, fingerprint = Secrets.hash(canonical(payload));
    Map<String, Object> old = store.get(key);
    if (old != null) {
      if (!fingerprint.equals(old.get("fingerprint"))) throw new DomainError("idempotency_mismatch", 409);
      return map(old.get("response"));
    }
    Object raw = updated.get("active_set");
    if (!"IN_SET".equals(updated.get("phase")) || yes(updated.get("paused")) || raw == null
        || !map(raw).get("set_id").equals(payload.get("set_id")) || !map(raw).get("exercise_id").equals(payload.get("exercise_id"))) throw new DomainError("set_not_active", 409);
    Map<String, Object> active = map(raw);
    if (rep) {
      if (!"reps".equals(active.get("kind"))) throw new DomainError("invalid_metric", 422);
      rate(sid, "reps", 4, 1); List<Object> reps = list(active.get("reps"));
      if (reps.size() >= 40) throw new DomainError("rep_limit", 429);
      Map<String, Object> event = copy(payload);
      if (num(event.get("confidence")) < num(domain.catalog.checks.get("rating_confidence")) || !yes(domain.catalog.exercise(str(event, "exercise_id")).get("rated"))) event.put("rated", false);
      reps.add(event);
    } else {
      if (!"hold".equals(active.get("kind"))) throw new DomainError("invalid_metric", 422);
      active.put("hold", copy(payload));
    }
    updated.put("last_rep_at", now()); int count = list(active.get("reps")).size();
    boolean reached = "reps".equals(active.get("kind")) ? count >= num(active.get("target")) : active.get("hold") != null;
    if (reached) {
      updated = machine.apply(updated, "sets/end", obj("set_id", active.get("set_id"))); bump(updated, "version"); mirrors.remove(sid);
    }
    Map<String, Object> result = envelope(updated.get("version"), obj("accepted", true, "reps_counted", count));
    save(state, updated, key, obj("revision", 0, "expires_at", state.get("expires_at"), "fingerprint", fingerprint, "response", result));
    return result;
  }
  synchronized Map<String, Object> putMirror(String sid, String token, byte[] frame) {
    Map<String, Object> state = auth(sid, token, "phone");
    if (yes(state.get("paused"))) { mirrors.remove(sid); throw new DomainError("paused", 409, state.get("version")); }
    if (!"paired".equals(state.get("sensor_mode")) || !arr("READY", "DEMO", "IN_SET").contains(state.get("phase"))) {
      mirrors.remove(sid); throw new DomainError("phase_invalid", 409, state.get("version"));
    }
    double time = now(); List<Object> times = mirrorRates.get(sid);
    if (times == null) { times = arr(); mirrorRates.put(sid, times); }
    times.removeIf(t -> num(t) <= time - 1);
    if (times.size() >= 12) throw new DomainError("rate_limited", 429, state.get("version"));
    times.add(time); mirrors.put(sid, new Frame(frame, time));
    return envelope(state.get("version"), obj("accepted", true));
  }
  synchronized byte[] getMirror(String sid, String token) {
    byte[] frame = mirrorFrame(auth(sid, token, "tv")); return frame == null ? null : frame.clone();
  }
  private byte[] mirrorFrame(Map<String, Object> state) {
    String sid = str(state, "session_id"); Frame entry = mirrors.get(sid); if (entry == null) return null;
    if (now() - entry.at > 2 || yes(state.get("paused")) || !"paired".equals(state.get("sensor_mode"))
        || state.get("phone_hash") == null || !arr("READY", "DEMO", "IN_SET").contains(state.get("phase"))) {
      mirrors.remove(sid); return null;
    }
    return entry.bytes;
  }
  private void schedule(String sid, String jid) {
    if (closed || !tasks.add(sid + jid)) return;
    workers.execute(() -> {
      try { runJob(sid, jid, false); }
      catch (RuntimeException e) { System.err.println("CleanRep fixture job failed; pending state retained for recovery."); }
      finally { synchronized (Sessions.this) { tasks.remove(sid + jid); } }
    });
  }
  private void runJob(String sid, String jid, boolean recovery) {
    Map<String, Object> snapshot;
    synchronized (this) {
      if (closed) return;
      snapshot = store.get(sid);
      if (snapshot == null || !jid.equals(snapshot.get("pending_job")) || num(snapshot.get("expires_at")) <= now()) return;
    }
    List<Object> candidate = domain.fallback(map(snapshot.get("profile")), machine.completedBlocks(snapshot));
    String source = recovery ? "fallback" : "fixture", reason = recovery ? "restart_recovery" : null;
    synchronized (this) {
      if (closed) return;
      Map<String, Object> current = store.get(sid);
      if (current == null || !jid.equals(current.get("pending_job"))) return;
      Map<String, Object> updated = machine.publish(current, candidate, source, reason);
      updated.put("pending_job", null); map(updated.get("jobs")).put(jid, obj("state", "done", "source", source, "reason", reason));
      bump(updated, "version"); save(current, updated, null, null);
    }
  }
  synchronized Map<String, Object> job(String sid, String token, String jid) {
    Map<String, Object> state = auth(sid, token, "tv"); Object job = map(state.get("jobs")).get(jid);
    if (job == null) throw new DomainError("not_found", 404);
    return envelope(state.get("version"), job);
  }
  synchronized Map<String, Object> delete(String sid, String token, Map<String, Object> payload) {
    Map<String, Object> state = auth(sid, token, "tv");
    if (!same(state.get("version"), payload.get("expected_version"))) throw new DomainError("stale_version", 409, state.get("version"));
    purge(state); return envelope(((Number) state.get("version")).longValue() + 1, obj("deleted", true));
  }
  private void purge(Map<String, Object> state) {
    String sid = str(state, "session_id"); mirrors.remove(sid); mirrorRates.remove(sid); coach.forget(sid);
    store.deleteSession(sid, ((Number) state.get("revision")).longValue(), "tv:" + state.get("tv_hash"));
  }
  synchronized void recover() {
    for (String key : store.keys("session_")) {
      Map<String, Object> state = store.get(key);
      if (state == null) continue;
      if (num(state.get("expires_at")) <= now()) purge(state);
      else if (state.get("pending_job") != null) runJob(key, str(state, "pending_job"), true);
    }
  }
  @Override public void close() {
    synchronized (this) { if (closed) return; closed = true; }
    workers.shutdownNow();
    try { workers.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    synchronized (this) { tasks.clear(); mirrors.clear(); mirrorRates.clear(); coach.close(); store.close(); }
  }
}
