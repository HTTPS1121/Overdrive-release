package com.overdrive.app.safekeep;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.safekeep.dest.Destination;
import com.overdrive.app.safekeep.dest.DestinationFactory;
import com.overdrive.app.safekeep.dest.UploadJob;
import com.overdrive.app.safekeep.net.HttpFactory;
import com.overdrive.app.safekeep.util.NetRoute;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** The running SafeKeep instance. Created when the master toggle turns ON, discarded when OFF. */
final class Engine {

    private static final DaemonLogger logger = DaemonLogger.getInstance("SafeKeep");
    static final List<String> ALL_LANES = Collections.unmodifiableList(Arrays.asList(
            "evidence", "archive", "trips", "tripExports", "settings"));
    private static final long TICK_MS = 30_000;
    private static final long NET_WATCH_MS = 5_000;
    private static final long RECONCILE_DELAY_MS = 2 * 60_000L;
    private static final long RECONCILE_EVERY_MS = 6 * 3600_000L;
    private static final long JANITOR_EVERY_MS = 24 * 3600_000L;

    final Retention retention;
    final Ledger ledger = new Ledger();
    final UploadSignals signals = new UploadSignals();
    final Governor governor = new Governor();
    final Notifier notifier = new Notifier(this);
    final Enqueuer enqueuer = new Enqueuer(this);
    final Maintenance maintenance = new Maintenance(this);
    final LinkManager links = new LinkManager(this);

    volatile SafeKeepConfig cfg;
    volatile boolean stopping;
    volatile long pausedUntil;
    volatile String reason;
    volatile long lastOkAt;
    volatile String lastError;

    ScheduledThreadPoolExecutor ctl;
    /** Short network chores: link polls, notices, webhooks, session cleanup. */
    ScheduledThreadPoolExecutor aux;
    /** Remote retention and quota relief (can take minutes). */
    ScheduledThreadPoolExecutor maint;
    /** Work a UI request waits on (test, sign-in, restore, share), never queued behind chores. */
    ThreadPoolExecutor req;
    ThreadPoolExecutor xfer;

    final ConcurrentHashMap<String, Destination> dests = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Long, Transfer> running = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Ledger.DestState> destStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, long[]> lanProbe = new ConcurrentHashMap<>();
    private final AtomicBoolean planQueued = new AtomicBoolean();
    private ScheduledFuture<?> tick;
    private ScheduledFuture<?> netWatch;
    private volatile NetRoute.Snapshot watchedRoute;
    private int idlePlans;

    Engine(Retention retention) {
        this.retention = retention;
    }

    // ── lifecycle ──────────────────────────────────────────────────────

    boolean start(SafeKeepConfig c) {
        ctl = executor("safekeep-ctl");
        aux = executor("safekeep-aux");
        maint = executor("safekeep-maint");
        req = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "safekeep-req");
            t.setDaemon(true);
            return t;
        });
        req.allowCoreThreadTimeOut(true);
        xfer = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(() -> {
                        try {
                            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                        } catch (Throwable ignored) {
                        }
                        r.run();
                    }, "safekeep-xfer");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                });
        xfer.allowCoreThreadTimeOut(true);
        try {
            Future<Boolean> opened = ctl.submit(() -> {
                if (!ledger.open()) return false;
                int reset = ledger.resetActive();
                if (reset > 0) logger.info("Resuming " + reset + " interrupted transfer(s)");
                restoreParked();
                rebuildPins();
                sweepStaging();
                return true;
            });
            if (!Boolean.TRUE.equals(opened.get(20, TimeUnit.SECONDS))) {
                shutdownExecutors();
                return false;
            }
        } catch (Exception e) {
            logger.error("SafeKeep start failed", e);
            shutdownExecutors();
            ledger.close();
            return false;
        }
        applyConfig(ensureIdentity(c));
        SafeKeepSignals.seed();
        ctl.schedule(maintenance::reconcile, RECONCILE_DELAY_MS, TimeUnit.MILLISECONDS);
        ctl.scheduleWithFixedDelay(maintenance::reconcile, RECONCILE_EVERY_MS, RECONCILE_EVERY_MS,
                TimeUnit.MILLISECONDS);
        maint.scheduleWithFixedDelay(maintenance::janitor, 10 * 60_000L, JANITOR_EVERY_MS, TimeUnit.MILLISECONDS);
        ctl.schedule(enqueuer::settingsSnapshot, 5 * 60_000L, TimeUnit.MILLISECONDS);
        wake();
        return true;
    }

    void stop() {
        stopping = true;
        for (Transfer t : running.values()) t.cancel();
        links.cancelAll();
        shutdownExecutors();
        ledger.close();
        dests.clear();
    }

    private void shutdownExecutors() {
        try {
            if (xfer != null) {
                xfer.shutdown();
                if (!xfer.awaitTermination(6, TimeUnit.SECONDS)) xfer.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (req != null) req.shutdownNow();
        for (ScheduledThreadPoolExecutor e : new ScheduledThreadPoolExecutor[]{ctl, aux, maint}) {
            if (e == null) continue;
            e.shutdownNow();
            try {
                e.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static ScheduledThreadPoolExecutor executor(String name) {
        ScheduledThreadPoolExecutor e = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
        e.setRemoveOnCancelPolicy(true);
        e.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return e;
    }

    /** Generates the stable anonymous vehicle id once (never the VIN). */
    private SafeKeepConfig ensureIdentity(SafeKeepConfig c) {
        if (!c.vehicleId.isEmpty()) return c;
        try {
            JSONObject s = new JSONObject(c.raw.toString());
            s.put("vehicleId", UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            com.overdrive.app.config.UnifiedConfigManager.updateSection(SafeKeepConfig.SECTION,
                    new JSONObject().put("vehicleId", s.getString("vehicleId")));
            return SafeKeepConfig.from(new JSONObject().put(SafeKeepConfig.SECTION, s));
        } catch (Exception e) {
            return c;
        }
    }

    void reloadConfigAsync() {
        ScheduledThreadPoolExecutor c = ctl;
        if (c == null || stopping) return;
        try {
            c.execute(() -> applyConfig(SafeKeepConfig.load()));
        } catch (Exception ignored) {
        }
    }

    /** Rebuilds destination drivers, re-reads limits, and drops pending rows for unrouted lanes. */
    synchronized void applyConfig(SafeKeepConfig c) {
        cfg = c;
        retention.configure(c);
        signals.setHomeSsids(c.limits.homeSsids);
        Set<String> live = new HashSet<>();
        for (SafeKeepConfig.Destination d : c.destinations) {
            if (!d.enabled) continue;
            live.add(d.id);
            Destination existing = dests.get(d.id);
            String sig = DestinationFactory.signature(d);
            if (existing == null || !sig.equals(driverSig.get(d.id))) {
                try {
                    Destination drv = DestinationFactory.create(d, ledger);
                    if (drv != null) {
                        dests.put(d.id, drv);
                        driverSig.put(d.id, sig);
                    }
                } catch (Exception e) {
                    logger.warn("Destination " + d.id + " unavailable: " + e.getMessage());
                }
            }
        }
        for (String id : new ArrayList<>(dests.keySet())) {
            if (!live.contains(id)) {
                dests.remove(id);
                driverSig.remove(id);
            }
        }
        if (ledger.isOpen()) {
            // Storage removed while SafeKeep was off: its queue and history go too (remote data stays).
            Set<String> known = new HashSet<>();
            for (SafeKeepConfig.Destination d : c.destinations) known.add(d.id);
            for (String id : ledger.destIds()) if (!known.contains(id)) ledger.forgetDestination(id);
            for (SafeKeepConfig.Destination d : c.destinations) {
                List<String> lanes = new ArrayList<>();
                for (SafeKeepConfig.Route r : c.routes) if (r.destIds.contains(d.id)) lanes.add(r.lane.id);
                if (!d.enabled) lanes.clear();
                for (Ledger.Item it : ledger.unroutedWithSession(d.id, lanes)) abandonSession(it);
                ledger.skipUnrouted(d.id, lanes);
            }
            rebuildPins();
            maintenance.resetAnchors(c);
        }
        wake();
    }

    private final ConcurrentHashMap<String, String> driverSig = new ConcurrentHashMap<>();

    void rebuildPins() {
        retention.replacePins(ledger.pendingWithLocalFiles());
    }

    private void sweepStaging() {
        Set<Long> live = new HashSet<>();
        for (Ledger.Item it : ledger.listQueue(null, null, 5000)) live.add(it.id);
        Staging.sweep(live);
    }

    // ── hooks (called from producer threads; hand off immediately) ─────────

    void onFileSaved(File f) {
        runCtl(() -> enqueuer.fileSaved(f));
    }

    void onEventSealed(File mp4) {
        runCtl(() -> enqueuer.eventSealed(mp4));
    }

    void onTripSaved(long id) {
        runCtl(() -> enqueuer.tripSaved(id));
    }

    void onSettingsChanged() {
        runCtl(enqueuer::settingsChanged);
    }

    void runCtl(Runnable r) {
        ScheduledThreadPoolExecutor c = ctl;
        if (c == null || stopping) return;
        try {
            c.execute(() -> {
                try {
                    r.run();
                } catch (Throwable t) {
                    logger.warn("SafeKeep task failed: " + t.getMessage());
                }
            });
        } catch (Exception ignored) {
        }
    }

    void runAux(Runnable r) {
        runOn(aux, r);
    }

    void runMaint(Runnable r) {
        runOn(maint, r);
    }

    private void runOn(ScheduledThreadPoolExecutor a, Runnable r) {
        if (a == null || stopping) return;
        try {
            a.execute(() -> {
                try {
                    r.run();
                } catch (Throwable t) {
                    logger.warn("SafeKeep aux task failed: " + t.getMessage());
                }
            });
        } catch (Exception ignored) {
        }
    }

    // ── planner ──────────────────────────────────────────────────────────

    void wake() {
        if (stopping || ctl == null) return;
        if (!planQueued.compareAndSet(false, true)) return;
        runCtl(() -> {
            planQueued.set(false);
            plan();
        });
    }

    private static final class Candidate {
        final Ledger.Item item;
        final SafeKeepConfig.Destination dest;
        final SafeKeepConfig.Route route;

        Candidate(Ledger.Item item, SafeKeepConfig.Destination dest, SafeKeepConfig.Route route) {
            this.item = item;
            this.dest = dest;
            this.route = route;
        }

        boolean beats(Candidate o) {
            if (o == null) return true;
            if (item.manual != o.item.manual) return item.manual;
            if (item.pclass != o.item.pclass) return item.pclass < o.item.pclass;
            return item.pscore > o.item.pscore;
        }
    }

    private void plan() {
        if (stopping || !ledger.isOpen()) return;
        SafeKeepConfig c = cfg;
        long now = System.currentTimeMillis();
        int pending = ledger.countWhere("state='" + Ledger.PENDING + "'");
        if (pending == 0 && running.isEmpty()) {
            reason = null;
            cancelTick();
            SafeKeepSignals.publishState("idle", 0);
            return;
        }
        UploadSignals.Snap s = signals.capture(2000);
        String why = null;
        if (pausedUntil > now) {
            why = "paused_by_user";
        } else {
            Policy.Verdict g = signals.guards(c, s);
            if (!g.ok) why = g.reason;
        }
        if (why == null) {
            int slots = governor.maxWorkers(c, s) - running.size();
            Candidate best = null;
            boolean searched = false;
            while (true) {
                String[] whyOut = new String[1];
                best = findBest(c, s, now, whyOut);
                searched = true;
                if (best == null) {
                    if (running.isEmpty()) why = whyOut[0];
                    break;
                }
                if (slots <= 0) break;
                dispatch(best);
                slots--;
                best = null;
            }
            if (searched && best != null && slots <= 0) maybePreempt(best);
        }
        reason = running.isEmpty() ? why : null;
        idlePlans = running.isEmpty() && pending > 0 ? Math.min(idlePlans + 1, 8) : 0;
        SafeKeepSignals.publishState(running.isEmpty() ? (why == null ? "idle" : "paused") : "uploading",
                pending + running.size());
        scheduleTick(pending > 0 || !running.isEmpty(), now);
        ensureNetWatch();
    }

    /**
     * The best row that would pass {@link #gateChunk} right now: same rules, caps and backpressure,
     * so nothing is dispatched only to stand down at its first chunk.
     */
    private Candidate findBest(SafeKeepConfig c, UploadSignals.Snap s, long now, String[] whyOut) {
        String tinyBp = governor.backpressure(c, s, 0);
        if (tinyBp != null) {
            whyOut[0] = tinyBp;
            return null;
        }
        String mediaBp = governor.backpressure(c, s, 1);
        boolean capHit = !s.unmetered() && mobileCapHit(c);
        if (capHit) notifier.mobileCap();
        Candidate best = null;
        for (SafeKeepConfig.Destination d : c.destinations) {
            if (!d.enabled || !dests.containsKey(d.id)) continue;
            String hold = destHold(destState(d.id), now);
            if (hold != null) {
                whyOut[0] = hold;
                continue;
            }
            boolean lan = lanReachable(d);
            // The first user-requested row that can really go (a Wi-Fi-only one must not block a
            // mobile-confirmed one behind it).
            for (Ledger.Item manual : ledger.eligible(d.id, ALL_LANES, mediaBp == null ? 2 : 0, now, true, 20)) {
                SafeKeepConfig.Route mr = c.route(SafeKeepConfig.Lane.parse(manual.lane), d.id);
                Policy.Verdict v = manualVerdict(manual, mr, c, s, lan);
                if (v.ok && capHit && !manual.meta().optBoolean("mobileOk", false)) v = Policy.no("mobile_cap");
                if (v.ok) {
                    Candidate cand = new Candidate(manual, d, mr);
                    if (cand.beats(best)) best = cand;
                    break;
                }
                whyOut[0] = v.reason;
            }
            if (capHit) {
                whyOut[0] = "mobile_cap";
                continue;
            }
            for (SafeKeepConfig.Route r : c.routes) {
                if (!r.destIds.contains(d.id)) continue;
                JSONObject eff = Policy.effective(r.rule);
                Policy.Verdict all = Policy.rule(eff, c, s, 2, lan, false, false);
                int maxClass;
                if (all.ok) {
                    maxClass = 2;
                } else if (Policy.rule(eff, c, s, 0, lan, false, false).ok) {
                    maxClass = 0;
                    whyOut[0] = all.reason;
                } else {
                    whyOut[0] = all.reason;
                    continue;
                }
                if (mediaBp != null && maxClass > 0) {
                    maxClass = 0;
                    whyOut[0] = mediaBp;
                }
                Ledger.Item it = ledger.nextEligible(d.id, Collections.singletonList(r.lane.id), maxClass, now, false);
                if (it == null) continue;
                Candidate cand = new Candidate(it, d, r);
                if (cand.beats(best)) best = cand;
            }
        }
        return best;
    }

    private void dispatch(Candidate cnd) {
        Destination drv = dests.get(cnd.dest.id);
        if (drv == null) return;
        ledger.markActive(cnd.item.id);
        cnd.item.state = Ledger.ACTIVE;
        Transfer t = new Transfer(this, cnd.item, drv, cnd.dest, cnd.route);
        running.put(cnd.item.id, t);
        accrueParked(signals.capture(5000), true);
        try {
            xfer.execute(t);
        } catch (Exception e) {
            running.remove(cnd.item.id);
            accrueParked(signals.capture(5000), !running.isEmpty());
            ledger.markRetry(cnd.item.id, null, "dispatch failed", System.currentTimeMillis() + 5000, false);
        }
    }

    /** A higher-class item waits with no free slot: the lowest-priority bulk transfer yields at its next chunk. */
    private void maybePreempt(Candidate waiting) {
        Transfer victim = null;
        for (Transfer t : running.values()) {
            if (t.item.pclass <= waiting.item.pclass || t.item.manual) continue;
            if (victim == null || t.item.pscore < victim.item.pscore) victim = t;
        }
        if (victim != null) victim.requestYield();
    }

    /** Best-effort cleanup of a dropped row's remote session (S3 multipart parts, Nextcloud chunk dirs). */
    void abandonSession(Ledger.Item it) {
        if (it == null || it.sessionJson == null || it.sessionJson.isEmpty()) return;
        final Destination drv = dests.get(it.destId);
        if (drv == null) return;
        runAux(() -> {
            try {
                UploadJob job = new UploadJob(it.id, it.remotePath, it.logicalKey, it.sizeBytes,
                        null, new JSONObject(it.sessionJson), offset -> {
                            throw new java.io.IOException("no source");
                        }, NOOP_HOOKS, null);
                drv.abandon(job);
                drv.release(job);
            } catch (Throwable ignored) {
            }
        });
    }

    private static final UploadJob.Hooks NOOP_HOOKS = new UploadJob.Hooks() {
        @Override public void persist(JSONObject state, long confirmed) {}
        @Override public void pace(int bytes) {}
        @Override public boolean cancelled() { return false; }
        @Override public void onCall(okhttp3.Call call) {}
        @Override public void onBytes(int n) {}
    };

    void onTransferDone(Transfer t) {
        running.remove(t.item.id);
        accrueParked(signals.capture(5000), !running.isEmpty());
        flushMobileBytes();
        wake();
    }

    /**
     * A user-requested row goes on Wi-Fi (or mobile data once confirmed), or whenever its own route
     * would take it right now: "Upload now" never makes a clip wait longer than it otherwise would.
     */
    static Policy.Verdict manualVerdict(Ledger.Item it, SafeKeepConfig.Route route, SafeKeepConfig c,
                                        UploadSignals.Snap s, boolean lan) {
        Policy.Verdict v = Policy.rule(null, c, s, it.pclass, lan, true, it.meta().optBoolean("mobileOk", false));
        if (v.ok || route == null) return v;
        Policy.Verdict viaRoute = Policy.rule(Policy.effective(route.rule), c, s, it.pclass, lan, false, false);
        return viaRoute.ok ? viaRoute : v;
    }

    /** Null when the destination may be tried now; quota_full retries itself once its backoff ends. */
    static String destHold(Ledger.DestState ds, long now) {
        if (ds.backoffUntil > now) return "ok".equals(ds.state) ? "backoff" : "dest_" + ds.state;
        if ("ok".equals(ds.state) || "quota_full".equals(ds.state)) return null;
        return "dest_" + ds.state;
    }

    private boolean mobileCapHit(SafeKeepConfig c) {
        return c.limits.mobileCapMb > 0 && mobileUsed() >= c.limits.mobileCapMb * 1024L * 1024L;
    }

    boolean mobileCapHitNow() {
        return mobileCapHit(cfg);
    }

    /** Per-chunk gate: null to proceed, else the reason the transfer must stand down. */
    String gateChunk(Transfer t) {
        if (stopping) return "stopping";
        long now = System.currentTimeMillis();
        SafeKeepConfig c = cfg;
        UploadSignals.Snap s = signals.capture(2000);
        accrueParked(s, true);
        if (t.isPauseRequested() || pausedUntil > now) return "paused_by_user";
        if (t.isYieldRequested()) return "yield";
        String hold = destHold(destState(t.dcfg.id), now);
        if (hold != null) return hold;
        Policy.Verdict g = signals.guards(c, s);
        if (!g.ok) return g.reason;
        JSONObject eff = Policy.effective(t.route == null ? null : t.route.rule);
        boolean mobileOk = t.item.manual && t.item.meta().optBoolean("mobileOk", false);
        Policy.Verdict r = t.item.manual ? manualVerdict(t.item, t.route, c, s, lanReachable(t.dcfg))
                : Policy.rule(eff, c, s, t.item.pclass, lanReachable(t.dcfg), false, false);
        if (!r.ok) return r.reason;
        String bp = governor.backpressure(c, s, t.item.pclass);
        if (bp != null) return bp;
        boolean metered = !s.unmetered();
        t.setMetered(metered);
        if (metered && !mobileOk && mobileCapHit(c)) {
            notifier.mobileCap();
            return "mobile_cap";
        }
        // Link-wide cap is shared by all transfers; a route's own cap binds only its transfer.
        governor.bucket().setRate(Policy.rateBytesPerSec(c, s, null));
        double own = Policy.rateBytesPerSec(c, s, eff);
        t.routeBucket().setRate(own);
        return null;
    }

    /** Monotonic time parked-upload time was last accounted; 0 while nothing uploads. */
    private long parkedMark;
    private long parkedSavedAt;

    /**
     * Wall time with at least one upload running while parked on battery, counted once however
     * many transfers overlap; called at dispatch, every gate and every transfer end.
     */
    synchronized void accrueParked(UploadSignals.Snap s, boolean stillRunning) {
        long el = android.os.SystemClock.elapsedRealtime();
        if (parkedMark > 0 && el > parkedMark && !s.accOn && !s.charging) {
            signals.addParkedUploadMs(el - parkedMark);
            if (el - parkedSavedAt > 60_000L) {
                parkedSavedAt = el;
                ledger.putKv("parked.upload", signals.parkedUploadMs() + "|" + System.currentTimeMillis());
            }
        }
        parkedMark = stillRunning ? el : 0;
    }

    /** A restart mid-park keeps the time already used (unless the car has been switched on since). */
    private void restoreParked() {
        String v = ledger.kv("parked.upload");
        if (v == null || v.indexOf('|') < 0) return;
        try {
            long ms = Long.parseLong(v.substring(0, v.indexOf('|')));
            long at = Long.parseLong(v.substring(v.indexOf('|') + 1));
            signals.restoreParkedUploadMs(ms, at);
        } catch (NumberFormatException ignored) {
        }
    }

    private final java.util.concurrent.atomic.AtomicLong mobilePending = new java.util.concurrent.atomic.AtomicLong();

    void addMobileBytes(int n) {
        if (mobilePending.addAndGet(n) >= 1024 * 1024) flushMobileBytes();
    }

    private void flushMobileBytes() {
        long n = mobilePending.getAndSet(0);
        if (n > 0 && ledger.isOpen()) {
            ledger.addBudget(Budgets.periodKey(cfg.limits.mobileResetDay, System.currentTimeMillis()), "mobile", n);
        }
    }

    long mobileUsed() {
        return ledger.budget(Budgets.periodKey(cfg.limits.mobileResetDay, System.currentTimeMillis()), "mobile")
                + mobilePending.get();
    }

    private final com.overdrive.app.safekeep.util.TokenBucket diskBucket =
            new com.overdrive.app.safekeep.util.TokenBucket(8 * 1024 * 1024);

    /** Card reads for uploads and re-hashes stay behind the recorder on the same card. */
    void diskPace(int n, java.util.function.BooleanSupplier cancelled) throws InterruptedException {
        UploadSignals.Snap s = signals.capture(5000);
        diskBucket.setRate(s.writing ? 2 * 1024 * 1024 : 8 * 1024 * 1024);
        diskBucket.acquire(n, () -> stopping || cancelled.getAsBoolean());
    }

    /** Post-upload bookkeeping: unpin, remember content hashes, notify, signal. */
    void onItemDone(Ledger.Item it, Destination.RemoteRef ref, String sha256, File file) {
        retention.unpinIfIdle(it.localPath, ledger);
        Naming.Kind kind = Naming.Kind.parse(it.kind);
        JSONObject meta = it.meta();
        try {
            if (kind == Naming.Kind.TRIP_ROW) {
                // The hash of the bytes actually sent (recorded at staging), not of the row as it is now.
                String h = meta.optString("rowHash", "");
                if (!h.isEmpty()) ledger.putKv("trip:" + it.destId + ":" + meta.optLong("startTime", 0), h);
            } else if (kind == Naming.Kind.SETTINGS) {
                String h = meta.optString("hash", "");
                if (!h.isEmpty()) ledger.putKv("settings:" + it.destId, h);
            }
            if (kind == Naming.Kind.TRIP_ROW || kind == Naming.Kind.TRIP_GPX || kind == Naming.Kind.SETTINGS) {
                Staging.discard(it);
            }
            boolean clip = kind == Naming.Kind.EVENT_CLIP || kind == Naming.Kind.PROXIMITY_CLIP
                    || kind == Naming.Kind.REPLAY_CLIP;
            if (clip && file != null) {
                SafeKeepSignals.publishClipSaved(file.getName());
                SafeKeepConfig.Destination d = cfg.destination(it.destId);
                String link = ref.webUrl;
                notifier.clipSaved(file.getName(), d == null ? it.destId : d.name, link);
                JSONObject data = new JSONObject().put("file", file.getName()).put("destination", it.destId)
                        .put("remotePath", it.remotePath).put("sha256", sha256 == null ? "" : sha256);
                if (link != null) data.put("link", link);
                notifier.webhook("evidence.saved", data);
            }
            SafeKeepSignals.clearError();
        } catch (Exception ignored) {
        }
        lastOkAt = System.currentTimeMillis();
    }

    private synchronized void scheduleTick(boolean needed, long now) {
        if (tick != null) {
            tick.cancel(false);
            tick = null;
        }
        if (!needed || stopping) return;
        // Nothing eligible for a while: back off to 2 min (arrival on home Wi-Fi is still caught).
        long delay = Math.min(120_000L, TICK_MS << Math.min(idlePlans, 2));
        long deferred = ledger.earliestDeferred(now);
        if (deferred > now) delay = Math.max(5_000, Math.min(delay, deferred - now + 250));
        if (pausedUntil > now && pausedUntil != Long.MAX_VALUE) delay = Math.min(delay, pausedUntil - now + 250);
        try {
            tick = ctl.schedule(this::wake, delay, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
    }

    private synchronized void cancelTick() {
        if (tick != null) {
            tick.cancel(false);
            tick = null;
        }
        if (netWatch != null) {
            netWatch.cancel(false);
            netWatch = null;
        }
    }

    /** While transfers run: a route change (e.g. Wi-Fi → cellular) cancels in-flight calls within 5 s. */
    private synchronized void ensureNetWatch() {
        if (running.isEmpty()) {
            if (netWatch != null) {
                netWatch.cancel(false);
                netWatch = null;
            }
            watchedRoute = null;
            return;
        }
        if (netWatch != null) return;
        watchedRoute = NetRoute.probe();
        netWatch = ctl.scheduleWithFixedDelay(() -> {
            if (running.isEmpty()) {
                ensureNetWatch();
                return;
            }
            NetRoute.Snapshot now = NetRoute.probe();
            NetRoute.Snapshot prev = watchedRoute;
            if (prev != null && !now.sameRoute(prev)) {
                logger.info("Network route changed (" + prev.iface + " → " + now.iface + "); re-planning uploads");
                for (Transfer t : running.values()) t.cancelForRouteChange();
            }
            watchedRoute = now;
        }, NET_WATCH_MS, NET_WATCH_MS, TimeUnit.MILLISECONDS);
    }

    // ── destination state ──────────────────────────────────────────────

    Ledger.DestState destState(String id) {
        Ledger.DestState d = destStates.get(id);
        if (d == null) {
            d = ledger.destState(id);
            destStates.put(id, d);
        }
        return d;
    }

    void setDestState(String id, String state, String error, long backoffUntil) {
        Ledger.DestState d = destState(id);
        boolean changed = !state.equals(d.state);
        d.state = state;
        d.lastError = error;
        d.backoffUntil = backoffUntil;
        ledger.saveDestState(id, d);
        if (changed && "needs_auth".equals(state)) notifier.needsAuth(id);
        if (changed && "ok".equals(state)) ledger.undeferDestination(id);
    }

    void noteDestOk(String id) {
        Ledger.DestState d = destState(id);
        long now = System.currentTimeMillis();
        boolean wasBad = !"ok".equals(d.state);
        if (wasBad || now - d.lastOkAt > 60_000) {
            d.state = "ok";
            d.lastOkAt = now;
            d.backoffUntil = 0;
            d.lastError = null;
            ledger.saveDestState(id, d);
        }
        lastOkAt = now;
    }

    void noteQuota(String id, Destination.Quota q) {
        if (q == null) return;
        Ledger.DestState d = destState(id);
        d.quotaUsed = q.used;
        d.quotaTotal = q.total;
        ledger.saveDestState(id, d);
    }

    /** LAN destinations: a cached 1 s TCP probe is the "home" signal (reachability over SSID). */
    boolean lanReachable(SafeKeepConfig.Destination d) {
        String hostPort = lanHostPort(d);
        if (hostPort == null) return false;
        long now = System.currentTimeMillis();
        long[] c = lanProbe.get(hostPort);
        if (c != null && now - c[1] < 60_000) return c[0] == 1;
        boolean ok = false;
        int colon = hostPort.lastIndexOf(':');
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(hostPort.substring(0, colon),
                    Integer.parseInt(hostPort.substring(colon + 1))), 1000);
            ok = true;
        } catch (Exception ignored) {
        }
        lanProbe.put(hostPort, new long[]{ok ? 1 : 0, now});
        return ok;
    }

    static String lanHostPort(SafeKeepConfig.Destination d) {
        try {
            String host;
            int port;
            if ("sftp".equals(d.type)) {
                host = d.params.optString("host", "");
                port = d.params.optInt("port", 22);
            } else {
                String url = d.params.optString("url", d.params.optString("endpoint", ""));
                if (url.isEmpty()) return null;
                URI u = new URI(url);
                host = u.getHost();
                port = u.getPort() > 0 ? u.getPort() : ("http".equalsIgnoreCase(u.getScheme()) ? 80 : 443);
            }
            if (host == null || host.isEmpty() || !HttpFactory.isLanHost(host)) return null;
            return host + ":" + port;
        } catch (Exception e) {
            return null;
        }
    }

    // ── user / automation commands ────────────────────────────────────────

    void command(String cmd, int minutes) {
        switch (cmd == null ? "" : cmd) {
            case "pause":
                pause(minutes);
                break;
            case "resume":
                resume();
                break;
            case "uploadLatestEvent":
                runCtl(enqueuer::uploadLatestEvent);
                break;
            case "syncNow":
            default:
                syncNow();
                break;
        }
    }

    void pause(int minutes) {
        pausedUntil = minutes <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() + minutes * 60_000L;
        for (Transfer t : running.values()) t.requestPause();
        wake();
    }

    void resume() {
        pausedUntil = 0;
        wake();
    }

    /** Clears backoffs so every eligible item is considered now. */
    void syncNow() {
        runCtl(() -> {
            for (SafeKeepConfig.Destination d : cfg.destinations) {
                Ledger.DestState ds = destState(d.id);
                if (ds.backoffUntil > 0) {
                    ds.backoffUntil = 0;
                    ledger.saveDestState(d.id, ds);
                }
                ledger.undeferDestination(d.id);
            }
            maintenance.reconcile();
            plan();
        });
    }

    // ── status ───────────────────────────────────────────────────────────

    JSONObject status() throws Exception {
        SafeKeepConfig c = cfg;
        JSONObject o = new JSONObject();
        o.put("enabled", true);
        long now = System.currentTimeMillis();
        boolean paused = pausedUntil > now;
        o.put("paused", paused);
        if (paused && pausedUntil != Long.MAX_VALUE) o.put("pausedUntil", pausedUntil);
        o.put("state", !running.isEmpty() ? "uploading" : (paused ? "paused" : (reason != null ? "waiting" : "idle")));
        if (reason != null) o.put("reason", reason);
        JSONArray cur = new JSONArray();
        for (Transfer t : running.values()) cur.put(t.progressJson());
        o.put("current", cur);
        JSONObject backlog = new JSONObject();
        Map<String, Map<String, long[]>> b = ledger.backlog();
        for (String lane : ALL_LANES) {
            Map<String, long[]> m = b.get(lane);
            long n = 0, bytes = 0, failed = 0;
            if (m != null) {
                for (Map.Entry<String, long[]> e : m.entrySet()) {
                    if (Ledger.FAILED.equals(e.getKey())) failed += e.getValue()[0];
                    else {
                        n += e.getValue()[0];
                        bytes += e.getValue()[1];
                    }
                }
            }
            backlog.put(lane, new JSONObject().put("count", n).put("bytes", bytes).put("failed", failed));
        }
        o.put("backlog", backlog);
        JSONArray ds = new JSONArray();
        for (SafeKeepConfig.Destination d : c.destinations) {
            Ledger.DestState st = destState(d.id);
            JSONObject j = new JSONObject().put("id", d.id).put("type", d.type).put("name", d.name)
                    .put("enabled", d.enabled).put("state", d.enabled ? st.state : "disabled")
                    .put("lastOkAt", st.lastOkAt).put("encrypted", d.encrypt);
            if (st.lastError != null) j.put("lastError", st.lastError);
            if (st.backoffUntil > now) j.put("backoffUntil", st.backoffUntil);
            if (st.quotaTotal > 0) j.put("quota", new JSONObject().put("used", st.quotaUsed).put("total", st.quotaTotal));
            ds.put(j);
        }
        o.put("destinations", ds);
        String period = Budgets.periodKey(c.limits.mobileResetDay, now);
        o.put("mobile", new JSONObject()
                .put("usedBytes", ledger.budget(period, "mobile"))
                .put("capMb", c.limits.mobileCapMb)
                .put("resetsOn", Budgets.nextReset(c.limits.mobileResetDay, now)));
        o.put("retention", new JSONObject()
                .put("pinned", retention.pinCount())
                .put("pinnedBytes", retention.pinnedBytes())
                .put("lockedBytes", retention.lockedBytes())
                .put("lockCapBytes", retention.lockCapBytes()));
        UploadSignals.Snap s = signals.capture(5000);
        JSONObject net = new JSONObject().put("transport", s.net == null ? "none"
                : s.net.transport.name().toLowerCase(java.util.Locale.ROOT))
                .put("metered", !s.unmetered()).put("phoneHotspot", s.phoneHotspot)
                .put("route", HttpFactory.lastRoute());
        if (s.ssid != null && !s.ssid.isEmpty()) net.put("ssid", Policy.normalizeSsid(s.ssid));
        o.put("network", net);
        o.put("lastOkAt", lastOkAt);
        if (lastError != null) o.put("lastError", lastError);
        o.put("onOnlyMode", s.onOnlyMode);
        return o;
    }

    Map<String, Object> clipStates(List<String> fileNames) {
        Map<String, Object> out = new HashMap<>();
        long now = System.currentTimeMillis();
        for (String name : fileNames) {
            if (name == null || name.isEmpty()) continue;
            List<Ledger.Item> rows = ledger.byLogicalKey(Naming.fileKey(name));
            JSONObject st = new JSONObject();
            try {
                String state = "none";
                double progress = 0;
                String why = null;
                String link = null;
                boolean anyActive = false, anyFailed = false, anyPending = false, anyDone = false, anyWaiting = false;
                Set<String> seen = new HashSet<>();
                for (Ledger.Item it : rows) {
                    if (!seen.add(it.destId)) continue;
                    switch (it.state) {
                        case Ledger.ACTIVE:
                            anyActive = true;
                            Transfer t = running.get(it.id);
                            long size = t != null ? t.length() : it.sizeBytes;
                            long sent = t != null ? t.confirmedBytes() : it.bytesConfirmed;
                            if (size > 0) progress = Math.max(progress, Math.min(1.0, sent / (double) size));
                            break;
                        case Ledger.FAILED:
                            anyFailed = true;
                            why = it.lastError;
                            break;
                        case Ledger.PENDING:
                            anyPending = true;
                            if (it.nextAttemptAt > now) {
                                anyWaiting = true;
                                if (it.errorClass != null) why = it.errorClass;
                            }
                            break;
                        case Ledger.DONE:
                        case Ledger.PRUNED:
                            anyDone = true;
                            if (link == null && it.remoteRef != null) link = webLink(it);
                            break;
                        default:
                            break;
                    }
                }
                if (anyActive) state = "uploading";
                else if (anyFailed) state = "failed";
                else if (anyPending) {
                    state = anyWaiting || reason != null ? "waiting" : "queued";
                    if (why == null) why = reason;
                } else if (anyDone) state = "saved";
                st.put("state", state);
                if (anyActive) st.put("progress", Math.round(progress * 100));
                if (why != null && !"saved".equals(state)) st.put("reason", why);
                if (link != null) st.put("link", link);
                st.put("locked", retention.isLockedName(name));
            } catch (Exception ignored) {
            }
            out.put(name, st);
        }
        return out;
    }

    /** Owner-only viewer link for providers that have one; null otherwise. */
    String webLink(Ledger.Item it) {
        SafeKeepConfig.Destination d = cfg.destination(it.destId);
        if (d == null || it.remoteRef == null) return null;
        JSONObject meta = it.meta();
        String url = meta.optString("webUrl", "");
        if (!url.isEmpty()) return url;
        if ("gdrive".equals(d.type)) return "https://drive.google.com/file/d/" + it.remoteRef + "/view";
        return null;
    }
}
