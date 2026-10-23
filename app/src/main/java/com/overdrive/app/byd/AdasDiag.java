package com.overdrive.app.byd;

import com.overdrive.app.logging.DaemonLogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A short field trail for the radar blind-spot warnings, whose live register is still unconfirmed:
 * ADAS callbacks, warning-register reads and the widget zones lighting, changes only, rate-limited
 * and capped per daemon run. Read-only: it never touches the poll's counter baselines.
 */
public final class AdasDiag {
    // the collector's tag, so these lines land wherever its lines are captured
    private static final DaemonLogger logger = DaemonLogger.getInstance("BydDataCollector");

    private static final int MAX_LINES = 1000;
    /** A register logs at most once per KEY_GAP_MS; after KEY_MAX lines, once per SLOW_GAP_MS. */
    private static final long KEY_GAP_MS = 1000L;
    private static final int KEY_MAX = 24;
    private static final long SLOW_GAP_MS = 30_000L;
    private static final long ZONE_GAP_MS = 10_000L;
    // the sweep reads a slice of registers per tick, bounded in count and time so the HAL stays free
    private static final long TICK_MS = 500L;
    private static final int TICK_READS = 12;
    private static final long TICK_BUDGET_MS = 40L;
    private static final int MAX_SWEEP_IDS = 96;
    /** Driving time the sweep covers per daemon run. */
    private static final long SWEEP_BUDGET_MS = 20 * 60_000L;

    private static final int[] ALERT_IDS = {
        BydFeatureIds.ADAS_FL_BLIND_SPOT_ALARM, BydFeatureIds.ADAS_FR_BLIND_SPOT_ALARM,
        BydFeatureIds.ADAS_LCA_WARNING_LEFT, BydFeatureIds.ADAS_LCA_WARNING_RIGHT,
        BydFeatureIds.ADAS_RCTA_WARNING_LEFT, BydFeatureIds.ADAS_RCTA_WARNING_RIGHT,
        BydFeatureIds.ADAS_DOW_WARN_LEFT, BydFeatureIds.ADAS_DOW_WARN_RIGHT,
        BydFeatureIds.ADAS_SLW_FUNC_SWITCH_STATE,
    };
    private static final String[] ALERT_NAMES = {
        "FL_BLIND_SPOT_ALARM", "FR_BLIND_SPOT_ALARM", "LCA_WARNING_LEFT", "LCA_WARNING_RIGHT",
        "RCTA_WARNING_LEFT", "RCTA_WARNING_RIGHT", "DOW_WARN_LEFT", "DOW_WARN_RIGHT", "SLW_FUNC_SWITCH_STATE",
    };
    // platform Adas fields whose names could carry a blind-spot, cross-traffic or door-open warning
    private static final String[] NAME_HINTS = { "BLIND", "BSD", "BSIS", "LCA", "RCTA", "RTCA", "DOW", "RADAR", "WARN", "ALARM" };

    /** One register's or one event id's history; guarded by the class lock. */
    private static final class Track {
        final int id;
        final boolean event;
        long logged;    // the value last logged
        long seen;      // the value last seen
        int changes;    // changes seen since the last line
        long at;        // when the last line went out
        int lines;

        Track(int id, boolean event) { this.id = id; this.event = event; }
    }

    private static final AtomicInteger lines = new AtomicInteger();
    private static final Map<Integer, Track> reads = new HashMap<>();
    private static final Map<Integer, Track> events = new HashMap<>();
    private static final Map<Integer, String> names = new ConcurrentHashMap<>();
    private static final Map<String, String> states = new ConcurrentHashMap<>();
    private static final long[] zoneAt = new long[3];
    private static final AtomicBoolean started = new AtomicBoolean();
    private static ScheduledExecutorService ticker;
    // ticker thread only
    private static long sweptMs;
    private static boolean sweepDone;
    private static int[] sweepIds;
    private static int sweepNext;
    private static boolean baselined;
    private static final StringBuilder baseline = new StringBuilder();
    private static int baselineCount;

    static {
        for (int i = 0; i < ALERT_IDS.length; i++) names.put(ALERT_IDS[i], ALERT_NAMES[i]);
    }

    private AdasDiag() {}

    private static boolean quiet() { return lines.get() >= MAX_LINES; }

    // one line, counted against the cap; the last one says the trail stops there
    private static void emit(String line) {
        int n = lines.incrementAndGet();
        if (n > MAX_LINES) return;
        logger.info(n == MAX_LINES ? line + " — line cap reached, quiet until the daemon restarts" : line);
    }

    private static boolean sentinel(int raw) { return raw < 0 || raw == 65535 || raw == 65534; }

    private static String name(int id) {
        String n = names.get(id);
        return (n != null ? n : "id") + String.format(" (0x%08X)", id);
    }

    // a read packs both overloads' answers; the second is only tried when the first is a sentinel
    private static long pack(int single, int array) { return ((long) single << 32) | (array & 0xFFFFFFFFL); }

    private static String text(Track t, long v) {
        if (t.event) return "ADAS event " + name(t.id) + " = " + (int) v;
        int single = (int) (v >> 32);
        return "ADAS read " + name(t.id) + ": single=" + single + (sentinel(single) ? " array=" + (int) v : "");
    }

    // the line [v] makes for [t] now, or null; a change the rate limit holds back stays pending
    private static String note(Track t, long v, long now) {
        if (v != t.seen) { t.seen = v; t.changes++; }
        if (t.changes == 0 || now - t.at < (t.lines < KEY_MAX ? KEY_GAP_MS : SLOW_GAP_MS)) return null;
        String suffix = v == t.logged ? " (back after " + t.changes + " short changes)"
                : t.changes > 1 ? " (" + t.changes + " changes since the last line)" : "";
        if (++t.lines == KEY_MAX) suffix += " — changes often, from now on at most every " + SLOW_GAP_MS / 1000 + " s";
        t.logged = v; t.changes = 0; t.at = now;
        return text(t, v) + suffix;
    }

    private static synchronized String observe(Map<Integer, Track> map, int id, boolean event, long v, boolean silent) {
        long now = android.os.SystemClock.elapsedRealtime();
        Track t = map.get(id);
        if (t != null) return note(t, v, now);
        t = new Track(id, event);
        t.logged = v; t.seen = v; t.at = now;
        map.put(id, t);
        if (silent) return null;
        t.lines = 1;
        return text(t, v) + " (first)";
    }

    // changes the rate limit held back, once they are due, so a last change is never lost
    private static void flushPending() {
        List<String> out = new ArrayList<>(0);
        synchronized (AdasDiag.class) {
            long now = android.os.SystemClock.elapsedRealtime();
            for (Track t : reads.values()) if (t.changes > 0) { String l = note(t, t.seen, now); if (l != null) out.add(l); }
            for (Track t : events.values()) if (t.changes > 0) { String l = note(t, t.seen, now); if (l != null) out.add(l); }
        }
        for (String l : out) emit(l);
    }

    /** A warning register's raw reading: [single] from get(int,int), and [array] from get(int[],Class) when that was tried. */
    public static void read(int id, int single, int array, boolean arrayTried) {
        if (quiet()) return;
        // the callers are the poll and the ADAS callback: nothing here may throw into them
        try {
            String line = observe(reads, id, false, pack(single, arrayTried ? array : 0), false);
            if (line != null) emit(line);
        } catch (Throwable ignored) { }
    }

    /** Every ADAS listener callback: the first per id and each change of value; any other method once by name. */
    public static void event(String method, Object[] args) {
        if (quiet()) return;
        try {
            if (!"onDataEventChanged".equals(method) || args == null || args.length < 2 || !(args[0] instanceof Number)) {
                if (states.putIfAbsent("m:" + method, "") == null) emit("ADAS callback " + method + " args=" + describe(args));
                return;
            }
            int id = ((Number) args[0]).intValue();
            String line = observe(events, id, true, BydDeviceHelper.getIntValue(args[1]), false);
            if (line != null) emit(line);
        } catch (Throwable ignored) { }
    }

    private static String describe(Object[] args) {
        if (args == null) return "none";
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < args.length && i < 4; i++) {
            if (i > 0) b.append(", ");
            Object a = args[i];
            b.append(a == null ? "null" : a instanceof Number ? a.toString() : a.getClass().getSimpleName() + "=" + BydDeviceHelper.getIntValue(a));
        }
        return b.append(']').toString();
    }

    /** A widget zone lighting from an alert, at most once per side per ZONE_GAP_MS. */
    public static void zone(boolean left, String via) { zone(left ? 0 : 1, via); }

    /** The BSD state's warning lighting the widget, side unknown. */
    public static void zoneAny(String via) { zone(2, via); }

    private static void zone(int i, String via) {
        if (quiet()) return;
        try {
            long now = android.os.SystemClock.elapsedRealtime();
            synchronized (zoneAt) {
                if (zoneAt[i] != 0 && now - zoneAt[i] < ZONE_GAP_MS) return;
                zoneAt[i] = now;
            }
            emit("Blind-spot zone " + (i == 0 ? "left" : i == 1 ? "right" : "(side unknown)") + " lit (" + via + ")");
        } catch (Throwable ignored) { }
    }

    /** A named state (the poll running, and why), logged when it changes. */
    public static void state(String key, String value) {
        if (quiet()) return;
        String prev = states.put("s:" + key, value);
        if (!value.equals(prev)) emit(key + ": " + value);
    }

    /**
     * Once the ADAS device is up: a tick every TICK_MS flushes held-back changes and, while driving
     * or reversing, sweeps a slice of the warning and candidate registers until the budget is spent.
     */
    static void start(BydDataCollector c) {
        if (!started.compareAndSet(false, true)) return;
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AdasDiag");
            t.setDaemon(true);
            return t;
        });
        ticker = ex;
        ex.scheduleWithFixedDelay(() -> {
            try { tick(c); } catch (Throwable t) { emit("ADAS sweep error: " + t); }
        }, 10_000L, TICK_MS, TimeUnit.MILLISECONDS);
    }

    private static void tick(BydDataCollector c) {
        if (quiet()) { ticker.shutdown(); return; }
        flushPending();
        if (sweepDone) return;
        if (sweptMs >= SWEEP_BUDGET_MS) {
            sweepDone = true;
            flushBaseline();
            emit("ADAS sweep finished after " + sweptMs / 60_000L + " min of driving");
            return;
        }
        if (!c.isAccOn()) return;
        BydVehicleData d = c.getData();
        boolean moving = d != null && ((Double.isFinite(d.speedKmh) && d.speedKmh >= 3.0)
                || d.gearMode == com.overdrive.app.monitor.GearMonitor.GEAR_R);
        if (!moving) return;
        Object device = c.adasDeviceIfAny();
        if (device == null) return;
        if (sweepIds == null) {
            sweepIds = sweepIds();
            emit("ADAS sweep started: " + sweepIds.length + " registers, " + TICK_READS + " every " + TICK_MS + " ms while driving, changes only");
        }
        if (sweepIds.length == 0) { sweepDone = true; return; }
        sweptMs += TICK_MS;
        long t0 = android.os.SystemClock.elapsedRealtime();
        for (int n = 0; n < TICK_READS && android.os.SystemClock.elapsedRealtime() - t0 < TICK_BUDGET_MS; n++) {
            int id = sweepIds[sweepNext];
            int single = BydDeviceHelper.callGetSingle(device, id);
            boolean tried = sentinel(single);
            int array = tried ? BydDeviceHelper.getIntValue(BydDeviceHelper.callGet(device, id, Integer.TYPE)) : 0;
            if (baselined) read(id, single, array, tried);
            else noteBaseline(id, single, array, tried);
            sweepNext = (sweepNext + 1) % sweepIds.length;
            if (sweepNext == 0 && !baselined) { baselined = true; flushBaseline(); }
        }
    }

    // the first pass is a baseline, a few lines of eight registers rather than one line each
    private static void noteBaseline(int id, int single, int array, boolean tried) {
        boolean known;
        synchronized (AdasDiag.class) { known = reads.containsKey(id); }
        // one the poll already logged keeps its own history
        if (known) { read(id, single, array, tried); return; }
        observe(reads, id, false, pack(single, tried ? array : 0), true);
        if (baseline.length() > 0) baseline.append(", ");
        String n = names.get(id);
        baseline.append(n != null ? n : String.format("0x%08X", id)).append('=').append(single);
        if (tried) baseline.append('/').append(array);
        if (++baselineCount % 8 == 0) flushBaseline();
    }

    private static void flushBaseline() {
        if (baseline.length() == 0) return;
        emit("ADAS baseline: " + baseline);
        baseline.setLength(0);
    }

    // the warning ids, the probe's candidates, then every platform Adas field named like a warning
    private static int[] sweepIds() {
        List<Integer> out = new ArrayList<>();
        for (int id : ALERT_IDS) if (BydFeatureIds.isResolved(id)) out.add(id);
        Class<?> adas = null;
        try {
            for (Class<?> in : Class.forName("android.hardware.bydauto.BYDAutoFeatureIds").getDeclaredClasses()) {
                if (in.getSimpleName().equals("Adas")) { adas = in; break; }
            }
        } catch (Throwable ignored) {}
        if (adas != null) {
            for (String n : AdasBlindSpotProbe.CANDIDATE_FIELDS) {
                try { add(out, n, adas.getField(n).getInt(null)); } catch (Throwable ignored) {}
            }
            for (java.lang.reflect.Field f : adas.getDeclaredFields()) {
                if (f.getType() != int.class || !java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                String n = f.getName();
                String u = n.toUpperCase(java.util.Locale.US);
                boolean hint = false;
                for (String h : NAME_HINTS) if (u.contains(h)) { hint = true; break; }
                if (!hint) continue;
                try { f.setAccessible(true); add(out, n, f.getInt(null)); } catch (Throwable ignored) {}
            }
        }
        int size = Math.min(out.size(), MAX_SWEEP_IDS);
        int[] a = new int[size];
        for (int i = 0; i < size; i++) a[i] = out.get(i);
        return a;
    }

    private static void add(List<Integer> out, String name, int id) {
        if (id == 0 || out.contains(id)) return;
        names.putIfAbsent(id, name);
        out.add(id);
    }
}
