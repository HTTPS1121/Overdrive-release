package com.overdrive.app.byd;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Stage 0 diagnostic probe of the DiLink 5 OTA driver-door lock getter
 * ({@code BYDAutoOtaDevice.getLFDoorLockState}), read-only and purely reflective.
 *
 * <p>Every method here is pure: nothing is logged, nothing is cached beyond the resolved
 * getter, and no failure escapes as an exception — each is folded into a classifiable token
 * ({@code ota=exc:<Cls>}, {@code otaReady=unknown(<Cls>)}, {@code ?}) so the emitted
 * {@code DI5 lock rails:} line can be read side by side with the typed-cabin diagnostic.
 * The caller (the collector) runs {@link #probe(Object)} only on its {@code DI5OtaLockProbe}
 * thread, never on the fast-poll thread. Nothing here registers a listener, writes a property
 * or re-acquires the SDK singleton.
 */
final class DiLink5OtaLockProbe {
    static final String LINE_PREFIX = "DI5 lock rails: ";
    static final String GETTER = "getLFDoorLockState";
    static final long PROBE_INTERVAL_MS = 5_000L;
    static final long LOG_INTERVAL_MS = 30_000L;

    private static volatile Class<?> resolvedType;
    private static volatile Method resolvedGetter;
    private static volatile String resolutionFailure;

    private DiLink5OtaLockProbe() {}

    /** One probe's tokens; strings are already in their logged form. */
    static final class Snapshot {
        final String ota;
        final String otaReady;
        final String otaForTest;
        final String otaService;
        final long callMs;

        Snapshot(String ota, String otaReady, String otaForTest, String otaService, long callMs) {
            this.ota = ota;
            this.otaReady = otaReady;
            this.otaForTest = otaForTest;
            this.otaService = otaService;
            this.callMs = callMs;
        }
    }

    /** The typed-cabin read of the same tick, reduced to the tokens the line prints. */
    static final class TypedTokens {
        final int raw;
        final int state;
        final String reason;

        TypedTokens(int raw, int state, String reason) {
            this.raw = raw;
            this.state = state;
            this.reason = reason;
        }
    }

    /** Tokens for an absent OTA device; the collector logs this synchronously (no executor). */
    static Snapshot nullDevice() {
        return new Snapshot("null(device)", "n/a", "?", "?", 0L);
    }

    /**
     * Resolve the getter once per device class, invoke it in its own try/catch, then read the
     * readiness fields by reflection. Never throws.
     */
    static Snapshot probe(Object otaDevice) {
        if (otaDevice == null) return nullDevice();
        String ota;
        long callMs = 0L;
        try {
            Method getter = resolveGetter(otaDevice.getClass());
            if (getter == null) {
                ota = resolutionFailure;
            } else {
                long startedNs = System.nanoTime();
                try {
                    Object value = getter.invoke(otaDevice);
                    callMs = (System.nanoTime() - startedNs) / 1_000_000L;
                    ota = value instanceof Number
                            ? Integer.toString(((Number) value).intValue())
                            : "null(value)";
                } catch (InvocationTargetException e) {
                    callMs = (System.nanoTime() - startedNs) / 1_000_000L;
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    ota = "exc:" + cause.getClass().getSimpleName();
                } catch (Throwable t) {
                    callMs = (System.nanoTime() - startedNs) / 1_000_000L;
                    ota = "exc:" + t.getClass().getSimpleName();
                }
            }
        } catch (Throwable t) {
            ota = "exc:" + t.getClass().getSimpleName();
        }
        String otaReady;
        String otaForTest = "?";
        String otaService = "?";
        try {
            Object manager = field(otaDevice.getClass(), "mManagerImpl").get(otaDevice);
            if (manager == null) {
                otaReady = "unknown(manager-null)";
            } else {
                try {
                    otaForTest = Integer.toString(
                            field(manager.getClass(), "mForTest").getInt(manager));
                } catch (Throwable ignored) {
                    otaForTest = "?";
                }
                Object sdk = field(manager.getClass(), "mSdkManager").get(manager);
                if (sdk == null) {
                    otaReady = "false";
                    otaService = "null";
                } else {
                    try {
                        Object ready = sdk.getClass().getMethod("ready").invoke(sdk);
                        otaReady = Boolean.TRUE.equals(ready) ? "true" : "false";
                    } catch (InvocationTargetException e) {
                        Throwable cause = e.getCause() == null ? e : e.getCause();
                        otaReady = "unknown(" + cause.getClass().getSimpleName() + ")";
                    } catch (Throwable t) {
                        otaReady = "unknown(" + t.getClass().getSimpleName() + ")";
                    }
                    try {
                        otaService = field(sdk.getClass(), "mService").get(sdk) == null
                                ? "null" : "bound";
                    } catch (Throwable ignored) {
                        otaService = "?";
                    }
                }
            }
        } catch (Throwable t) {
            otaReady = "unknown(" + t.getClass().getSimpleName() + ")";
        }
        return new Snapshot(ota, otaReady, otaForTest, otaService, callMs);
    }

    /**
     * {@code DI5 lock rails: typedRaw=<n> typedState=<s> typedReason=<r> ota=<…> otaReady=<…>
     * otaForTest=<…> otaService=<…> otaCallMs=<ms> appAcc=<bool> atElapsedMs=<n>}.
     */
    static String formatLine(TypedTokens typed, Snapshot s, boolean appAcc, long atElapsedMs) {
        return LINE_PREFIX
                + "typedRaw=" + typed.raw
                + " typedState=" + typed.state
                + " typedReason=" + typed.reason
                + " ota=" + s.ota
                + " otaReady=" + s.otaReady
                + " otaForTest=" + s.otaForTest
                + " otaService=" + s.otaService
                + " otaCallMs=" + s.callMs
                + " appAcc=" + appAcc
                + " atElapsedMs=" + atElapsedMs;
    }

    /** Pure throttle arithmetic shared by the collector wiring and its tests. */
    static final class Throttle {
        private Throttle() {}

        /** A probe is due when the typed line was just logged or 5 s passed since the last one. */
        static boolean shouldProbe(long now, long lastProbeAt, boolean typedLineJustLogged) {
            return typedLineJustLogged || now - lastProbeAt >= PROBE_INTERVAL_MS;
        }

        /** Log on any change of (typedRaw, ota, otaReady) or every 30 s. */
        static boolean shouldLog(String line, String lastLine, long now, long lastLogAt) {
            if (lastLine == null || !changeKey(line).equals(changeKey(lastLine))) return true;
            return now - lastLogAt >= LOG_INTERVAL_MS;
        }

        static String changeKey(String line) {
            return token(line, "typedRaw=") + "|" + token(line, " ota=") + "|"
                    + token(line, " otaReady=");
        }

        private static String token(String line, String key) {
            if (line == null) return "";
            int at = line.indexOf(key);
            if (at < 0) return "";
            int start = at + key.length();
            int end = line.indexOf(' ', start);
            return end < 0 ? line.substring(start) : line.substring(start, end);
        }
    }

    /**
     * The cached {@code int getLFDoorLockState()} of {@code type}, or null when the class lacks it
     * or it has another shape (the Stage 1 OTA rail reader shares this resolution; never throws).
     */
    static Method lockGetter(Class<?> type) {
        if (type == null) return null;
        try {
            return resolveGetter(type);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method resolveGetter(Class<?> type) {
        if (resolvedType == type) return resolvedGetter;
        Method getter = null;
        String failure = null;
        try {
            Method candidate = type.getMethod(GETTER);
            if (candidate.getReturnType() != int.class) {
                failure = "null(method-shape)";
            } else {
                getter = candidate;
            }
        } catch (NoSuchMethodException e) {
            failure = "null(method-missing)";
        } catch (Throwable t) {
            failure = "exc:" + t.getClass().getSimpleName();
        }
        resolvedGetter = getter;
        resolutionFailure = failure;
        resolvedType = type;
        return getter;
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field value = c.getDeclaredField(name);
                value.setAccessible(true);
                return value;
            } catch (NoSuchFieldException ignored) {
                // keep walking
            }
        }
        throw new NoSuchFieldException(name);
    }
}
