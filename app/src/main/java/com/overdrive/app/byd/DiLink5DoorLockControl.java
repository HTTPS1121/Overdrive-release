package com.overdrive.app.byd;

import android.os.IInterface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

/**
 * DiLink 5's typed cabin door-lock rail. Its state domain is LOCK=1, UNLOCK=2;
 * the OTA driver's-door readback used elsewhere has the opposite domain.
 *
 * <p>The SDK can return zero without writing while its adapter is disconnected.
 * A live adapter and a matching driver-door readback therefore determine success.
 */
final class DiLink5DoorLockControl {
    private static final long VERIFY_TIMEOUT_MS = 2_000L;
    private static final long VERIFY_INTERVAL_MS = 100L;
    static final int LOCK = 1;
    static final int UNLOCK = 2;

    interface NativePort {
        boolean ready() throws Exception;
        int readDriverLock() throws Exception;
        int writeAllDoors(int state) throws Exception;
        /** Post-write confirmation budget; the typed cabin rail keeps its 2 000 ms. */
        default long confirmationTimeoutMs() { return VERIFY_TIMEOUT_MS; }
    }

    interface Clock {
        long elapsedMs();
        void sleep(long millis) throws InterruptedException;
    }

    static final class Result {
        /**
         * CONFIRMED: this process read the target back on its own rail. WRITTEN_UNCONFIRMED: the
         * write was accepted (or nothing needed writing) and confirmation is delegated to the daemon
         * rail. FAILED: nothing may be reported as done.
         */
        enum Outcome { CONFIRMED, WRITTEN_UNCONFIRMED, FAILED }

        final boolean confirmed;
        final Outcome outcome;
        final String reason;

        private Result(boolean confirmed, String reason) {
            this(confirmed ? Outcome.CONFIRMED : Outcome.FAILED, reason);
        }

        private Result(Outcome outcome, String reason) {
            this.outcome = outcome;
            this.confirmed = outcome == Outcome.CONFIRMED;
            this.reason = reason;
        }
    }

    /** One actual cabin read, expressed in the app's LOCK=2 / UNLOCK=1 domain. */
    static final class ReadResult {
        final int nativeState;
        final int state;
        final long observedAtElapsedMs;
        final String reason;

        private ReadResult(int nativeState, int state, long observedAtElapsedMs, String reason) {
            this.nativeState = nativeState;
            this.state = state;
            this.observedAtElapsedMs = observedAtElapsedMs;
            this.reason = reason;
        }
    }

    private DiLink5DoorLockControl() {}

    static ReadResult readDriverLock(Object device, BooleanSupplier sourceCurrent) {
        return readDriverLock(device, sourceCurrent, systemClock());
    }

    static ReadResult readDriverLock(Object device, BooleanSupplier sourceCurrent, Clock clock) {
        try {
            if (!current(sourceCurrent)) return unknownRead(clock, "source expired or mode changed");
            return readDriverLock(new ReflectivePort(device), sourceCurrent, clock);
        } catch (Exception unavailable) {
            return unknownRead(clock, "typed door-lock rail unavailable ("
                    + unavailable.getClass().getSimpleName() + ")");
        }
    }

    static ReadResult readDriverLock(NativePort port, BooleanSupplier sourceCurrent, Clock clock) {
        try {
            if (!current(sourceCurrent)) return unknownRead(clock, "source expired or mode changed");
            if (!port.ready()) return unknownRead(clock, "cabin adapter unavailable");
            if (!current(sourceCurrent)) return unknownRead(clock, "source changed before read");
            int raw = port.readDriverLock();
            // Preserve the getter's own completion time, even if a later readiness call is slow.
            long readAt = clock.elapsedMs();
            if (!port.ready()) return unknownRead(clock, "cabin adapter lost during read");
            if (!current(sourceCurrent)) return unknownRead(clock, "source changed during read");
            int state = normalizedState(raw);
            return new ReadResult(raw, state, readAt, state == 0
                    ? "driver-door lock readback unavailable" : "driver-door readback");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return unknownRead(clock, "door-lock read interrupted");
        } catch (Exception failure) {
            return unknownRead(clock, "typed door-lock read failed ("
                    + failure.getClass().getSimpleName() + ")");
        }
    }

    static int normalizedState(int nativeState) {
        return nativeState == LOCK ? 2 : nativeState == UNLOCK ? 1 : 0;
    }

    private static ReadResult unknownRead(Clock clock, String reason) {
        return new ReadResult(Integer.MIN_VALUE, 0, clock.elapsedMs(), reason);
    }

    private static Clock systemClock() {
        return new Clock() {
            public long elapsedMs() { return android.os.SystemClock.elapsedRealtime(); }
            public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
        };
    }

    static Result setLocked(Object device, boolean locked, BooleanSupplier requestCurrent) {
        return setLocked(device, locked, requestCurrent, () -> true);
    }

    static Result setLocked(Object device, boolean locked, BooleanSupplier requestCurrent,
                            BooleanSupplier beforeWrite) {
        try {
            return apply(new ReflectivePort(device), locked, requestCurrent, beforeWrite,
                    systemClock());
        } catch (Exception unavailable) {
            return failed("typed door-lock rail unavailable ("
                    + unavailable.getClass().getSimpleName() + ")");
        }
    }

    static Result apply(NativePort port, boolean locked,
                        BooleanSupplier requestCurrent, Clock clock) {
        return apply(port, locked, requestCurrent, () -> true, clock);
    }

    static Result apply(NativePort port, boolean locked,
                        BooleanSupplier requestCurrent, BooleanSupplier beforeWrite, Clock clock) {
        int target = locked ? LOCK : UNLOCK;
        try {
            if (!current(requestCurrent)) return failed("request expired or mode changed");
            if (!port.ready()) return failed("cabin adapter unavailable");
            int before = port.readDriverLock();
            if (before != LOCK && before != UNLOCK) {
                return failed("driver-door lock readback unavailable raw=" + before);
            }
            if (!current(requestCurrent) || !port.ready()) {
                return failed("request or cabin adapter changed before write");
            }
            // Readiness can involve Binder calls. Revalidate after they return, just before writing.
            if (!current(requestCurrent)) return failed("request expired before write");
            boolean alreadyAtTarget = before == target;
            if (!alreadyAtTarget) {
                if (beforeWrite == null || !beforeWrite.getAsBoolean()
                        || !current(requestCurrent)) {
                    return failed("proximity command revoked before write");
                }
                int rc = port.writeAllDoors(target);
                if (rc != 0) return failed("setAllDoorsLock refused rc=" + rc);
            }
            long startedAt = clock.elapsedMs();
            long timeoutMs = port.confirmationTimeoutMs();
            int consecutiveTargetReadbacks = 0;
            while (true) {
                if (!current(requestCurrent)) return failed("request expired during confirmation");
                if (!port.ready()) return failed("cabin adapter lost during confirmation");
                if (clock.elapsedMs() - startedAt >= timeoutMs) {
                    return failed("driver-door confirmation timed out");
                }
                consecutiveTargetReadbacks = port.readDriverLock() == target
                        ? consecutiveTargetReadbacks + 1 : 0;
                if (clock.elapsedMs() - startedAt >= timeoutMs) {
                    return failed("driver-door confirmation timed out");
                }
                if (consecutiveTargetReadbacks >= 2) {
                    if (!current(requestCurrent) || !port.ready()) {
                        return failed("request or cabin adapter changed during confirmation");
                    }
                    if (!current(requestCurrent)) {
                        return failed("request expired during confirmation");
                    }
                    return confirmed(alreadyAtTarget
                            ? "driver door already confirmed"
                            : "driver-door readback confirmed");
                }
                long remaining = timeoutMs - (clock.elapsedMs() - startedAt);
                if (remaining <= 0L) return failed("driver-door confirmation timed out");
                clock.sleep(Math.min(VERIFY_INTERVAL_MS, remaining));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed("door-lock request interrupted");
        } catch (Exception failure) {
            return failed("typed door-lock request failed ("
                    + failure.getClass().getSimpleName() + ")");
        }
    }

    private static boolean current(BooleanSupplier requestCurrent) {
        return !Thread.currentThread().isInterrupted()
                && requestCurrent != null && requestCurrent.getAsBoolean();
    }

    private static Result failed(String reason) { return new Result(false, reason); }
    private static Result confirmed(String reason) { return new Result(true, reason); }
    private static Result written(String reason) {
        return new Result(Result.Outcome.WRITTEN_UNCONFIRMED, reason);
    }

    // ---- 1.6a: actuator path selection by the daemon-selected gate rail ----

    /** Write path of a DI5 proximity command, selected from the gate rail carried in its scope. */
    enum Mode { TYPED, OTA, OTA_UNAVAILABLE, DELEGATED_CARSVC }

    /**
     * Pure: no rail or the typed rail keeps today's path (case A); {@code ota} needs the OTA device
     * and otherwise fails closed rather than silently taking another rail (case B); {@code carsvc}
     * delegates confirmation to the daemon (case C).
     */
    static Mode selectDiLink5LockMode(DiLink5LockRail gateRail, boolean otaDeviceAvailable) {
        if (gateRail == null) return Mode.TYPED;
        switch (gateRail) {
            case OTA: return otaDeviceAvailable ? Mode.OTA : Mode.OTA_UNAVAILABLE;
            case CARSVC: return Mode.DELEGATED_CARSVC;
            default: return Mode.TYPED;
        }
    }

    // ---- 1.6b: case (B) — typed write, OTA agreeing-pair readback ----

    /** The OTA driver's-door getter in its own domain: LF_DOOR_LOCK=2, LF_DOOR_UNLOCK=1, invalid=0. */
    interface OtaLfReader {
        boolean ready() throws Exception;
        int readLf() throws Exception;
    }

    /**
     * Case (B): the typed cabin rail writes, the OTA rail reads back. One {@link #readDriverLock()}
     * is two OTA reads {@value #OTA_AGREEMENT_SPACING_MS} ms apart (the injected clock sleeps,
     * NIT-4) that must agree and be in domain; the agreed value is inverted into the native cabin
     * domain, so {@link #apply} confirms only after two agreeing pairs spanning at least 600 ms over
     * four raw reads.
     *
     * <p>HAZARD, quoted from the recorded note on this getter (base {@code BydDataCollector.java:16860-16870},
     * WT {@code daemon/CameraDaemon.java:6746-6751}): "the OTA getLFDoorLockState rail emits
     * transient/bluff DOOR_STATE_UNLOCK reads on this hardware, so a single one must NOT fire an
     * 'unlocked' automation". A disagreeing pair therefore reads as 0 (unknown), never as a state.
     *
     * <p>Every {@code ready()} of the confirmation loop reaches the SDK's {@code OtaSdkManagerImpl.ready()},
     * which logs {@code Log.e("ready()")} ({@code OtaSdkManagerImpl.java:653}) — expected logcat noise.
     */
    static final class OtaReadPort implements NativePort {
        static final long OTA_AGREEMENT_SPACING_MS = 250L;
        static final long OTA_CONFIRMATION_TIMEOUT_MS = 4_000L;
        /** OTA domain ({@code BYDAutoOtaDevice.LF_DOOR_UNLOCK} / {@code LF_DOOR_LOCK}). */
        static final int OTA_LF_UNLOCK = 1;
        static final int OTA_LF_LOCK = 2;

        private final NativePort typed;
        private final OtaLfReader ota;
        private final Clock clock;

        OtaReadPort(NativePort typed, OtaLfReader ota, Clock clock) {
            this.typed = typed;
            this.ota = ota;
            this.clock = clock;
        }

        /** Both rails must be live: the typed one writes, the OTA one confirms. */
        @Override public boolean ready() throws Exception {
            return typed.ready() && ota.ready();
        }

        @Override public int readDriverLock() throws Exception {
            int first = ota.readLf();
            clock.sleep(OTA_AGREEMENT_SPACING_MS);
            int second = ota.readLf();
            return first == second ? nativeOfOta(first) : 0;
        }

        @Override public int writeAllDoors(int state) throws Exception {
            return typed.writeAllDoors(state);
        }

        @Override public long confirmationTimeoutMs() { return OTA_CONFIRMATION_TIMEOUT_MS; }

        /** OTA {@code 2} (LF_DOOR_LOCK) → native LOCK {@code 1}; OTA {@code 1} → native UNLOCK {@code 2}; else 0. */
        static int nativeOfOta(int otaValue) {
            return otaValue == OTA_LF_LOCK ? LOCK : otaValue == OTA_LF_UNLOCK ? UNLOCK : 0;
        }
    }

    /**
     * Resolve only the supplied OTA SDK layout (D4). The manager/SDK-manager field names below
     * the device class are INFERRED from the SDK's decompiled shape; any mismatch throws and
     * {@link #setLockedViaOta} fails closed as {@code typed door-lock rail unavailable (<Exc>)}.
     */
    static final class ReflectiveOtaLfReader implements OtaLfReader {
        static final String OTA_DEVICE_CLASS = "android.hardware.bydauto.ota.BYDAutoOtaDevice";

        private final Object device;
        private final Method getter;
        private final Object manager;
        private final Field sdkManager;
        private final Field forTest;
        private final Method ready;

        ReflectiveOtaLfReader(Object otaDevice) throws Exception {
            if (otaDevice == null) throw new IllegalArgumentException("ota device missing");
            Class<?> type = otaDevice.getClass();
            verifyOtaDeviceShape(type, OTA_DEVICE_CLASS);
            device = otaDevice;
            getter = type.getMethod("getLFDoorLockState");
            manager = declaredField(type, "mManagerImpl").get(otaDevice);
            if (manager == null) throw new IllegalStateException("ota manager missing");
            sdkManager = declaredField(manager.getClass(), "mSdkManager");
            forTest = declaredField(manager.getClass(), "mForTest");
            Object sdk = sdkManager.get(manager);
            if (sdk == null) throw new IllegalStateException("ota sdk manager missing");
            ready = sdk.getClass().getMethod("ready");
        }

        @Override public boolean ready() throws Exception {
            Object sdk = sdkManager.get(manager);
            return forTest.getInt(manager) == 0 && sdk != null
                    && Boolean.TRUE.equals(ready.invoke(sdk));
        }

        @Override public int readLf() throws Exception {
            return ((Integer) getter.invoke(device)).intValue();
        }

        private static Field declaredField(Class<?> type, String name) throws Exception {
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

    /**
     * D4 seam: the OTA device must be the named SDK class with the exact LF lock value domain
     * ({@code LF_DOOR_LOCK == 2}, {@code LF_DOOR_UNLOCK == 1}, {@code LF_DOOR_LOCK_INVALID == 0})
     * and an {@code int getLFDoorLockState()}. Any mismatch is an {@link IllegalArgumentException}.
     * Package-private so tests exercise it with their own classes (the stubs jar lacks the getter).
     */
    static void verifyOtaDeviceShape(Class<?> type, String expectedName) {
        if (type == null || expectedName == null || !type.getName().equals(expectedName)) {
            throw new IllegalArgumentException("unexpected ota device");
        }
        try {
            if (type.getField("LF_DOOR_LOCK").getInt(null) != OtaReadPort.OTA_LF_LOCK
                    || type.getField("LF_DOOR_UNLOCK").getInt(null) != OtaReadPort.OTA_LF_UNLOCK
                    || type.getField("LF_DOOR_LOCK_INVALID").getInt(null) != 0) {
                throw new IllegalArgumentException("unverified ota lock state domain");
            }
            if (type.getMethod("getLFDoorLockState").getReturnType() != int.class) {
                throw new IllegalArgumentException("unverified ota getter shape");
            }
        } catch (NoSuchFieldException | NoSuchMethodException | IllegalAccessException
                | NullPointerException missing) {
            throw new IllegalArgumentException("unverified ota device shape ("
                    + missing.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Case (B) entry for {@code setDiLink5AllDoorsLocked} when the gate rail is {@code ota}: typed
     * cabin write, OTA agreeing-pair readback, {@value OtaReadPort#OTA_CONFIRMATION_TIMEOUT_MS} ms
     * budget. A missing device or an unverified layout fails closed like {@link #setLocked}.
     */
    static Result setLockedViaOta(Object doorLockDevice, Object otaDevice, boolean locked,
                                  BooleanSupplier requestCurrent, BooleanSupplier beforeWrite) {
        if (otaDevice == null) return failed("typed door-lock rail unavailable (ota device missing)");
        try {
            return apply(new OtaReadPort(new ReflectivePort(doorLockDevice),
                            new ReflectiveOtaLfReader(otaDevice), systemClock()),
                    locked, requestCurrent, beforeWrite, systemClock());
        } catch (Exception unavailable) {
            return failed("typed door-lock rail unavailable ("
                    + unavailable.getClass().getSimpleName() + ")");
        }
    }

    // ---- 1.6c: case (C) — write authorised by the daemon's carsvc evidence, confirmation delegated ----

    /** Case (C): the gate evidence that authorises a write must be at most this old at write time. */
    static final long DILINK5_GATE_EVIDENCE_WRITE_MAX_AGE_MS = 6_000L;

    /** App LOCK {@code 2} → native LOCK {@code 1}; app UNLOCK {@code 1} → native UNLOCK {@code 2}; else 0. */
    static int nativeOf(int appState) {
        return appState == 2 ? LOCK : appState == 1 ? UNLOCK : 0;
    }

    /**
     * Case (C) entry for {@code setDiLink5AllDoorsLocked} when the gate rail is {@code carsvc};
     * the same layout check and fail-closed catch as {@link #setLocked}.
     */
    static Result applyWithDelegatedEvidence(Object doorLockDevice, boolean locked, int gateStateApp,
                                             long gateObservedElapsedMs, BooleanSupplier requestCurrent,
                                             BooleanSupplier beforeWrite) {
        try {
            return applyWithDelegatedEvidence(new ReflectivePort(doorLockDevice), locked, gateStateApp,
                    gateObservedElapsedMs, requestCurrent, beforeWrite, systemClock());
        } catch (Exception unavailable) {
            return failed("typed door-lock rail unavailable ("
                    + unavailable.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Case (C), design §4.5 steps 1–7. The typed rail is INVALID on this car, so the daemon's
     * {@code carsvc} evidence carried in the proximity scope ({@code gateStateApp} in the app
     * domain, read at {@code gateObservedElapsedMs}) authorises the write, and the daemon confirms
     * the result on its own rail. This never returns CONFIRMED: the app has no rail to confirm
     * with. A valid typed pre-read that agrees with the gate hands over to {@link #apply} (the
     * typed rail can confirm, so it does — one extra typed read is accepted); one that disagrees
     * fails closed.
     */
    static Result applyWithDelegatedEvidence(NativePort typed, boolean locked, int gateStateApp,
                                             long gateObservedElapsedMs, BooleanSupplier requestCurrent,
                                             BooleanSupplier beforeWrite, Clock clock) {
        int target = locked ? LOCK : UNLOCK;
        try {
            // (1) request and adapter
            if (!current(requestCurrent)) return failed("request expired or mode changed");
            if (!typed.ready()) return failed("cabin adapter unavailable");
            // (2) typed pre-read: valid → it must agree with the gate, then the typed path confirms
            int typedBefore = typed.readDriverLock();
            int gateNative = nativeOf(gateStateApp);
            if (typedBefore == LOCK || typedBefore == UNLOCK) {
                if (typedBefore != gateNative) {
                    return failed("rail disagreement typed=" + typedBefore + " gate=" + gateNative
                            + " rail=carsvc");
                }
                return apply(typed, locked, requestCurrent, beforeWrite, clock);
            }
            if (gateNative != LOCK && gateNative != UNLOCK) {
                return failed("gate evidence invalid state=" + gateStateApp);
            }
            // (3) the authorising evidence must still be fresh at write time
            long ageMs = clock.elapsedMs() - gateObservedElapsedMs;
            if (ageMs > DILINK5_GATE_EVIDENCE_WRITE_MAX_AGE_MS) {
                return failed("gate evidence too old for write ageMs=" + ageMs);
            }
            // (4) nothing to write; the daemon still confirms on its rail
            if (gateNative == target) {
                return written("nothing written; already at target per gate rail carsvc; confirmation delegated");
            }
            // (5) daemon re-validation and request currency immediately before the write
            if (beforeWrite == null || !beforeWrite.getAsBoolean() || !current(requestCurrent)) {
                return failed("proximity command revoked before write");
            }
            // (6) the write
            int rc = typed.writeAllDoors(target);
            if (rc != 0) return failed("setAllDoorsLock refused rc=" + rc);
            // (7) no confirmation readback here — never CONFIRMED from the app on this path
            return written("setAllDoorsLock accepted rc=0; confirmation delegated to daemon rail carsvc");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed("door-lock request interrupted");
        } catch (Exception failure) {
            return failed("typed door-lock request failed ("
                    + failure.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Resolve only the supplied DiLink 5 SDK layout. Unknown layouts fail closed;
     * no direct Binder transactions or generic property writes are substituted.
     */
    private static final class ReflectivePort implements NativePort {
        private final Object device;
        private final Object doorlockManager;
        private final Object tsManager;
        private final Method setter;
        private final Method reader;
        private final Method adapterConnected;
        private final Method carServiceConnected;
        private final Method cabinManager;
        private final Field forTest;

        ReflectivePort(Object device) throws Exception {
            if (device == null || !device.getClass().getName().equals(
                    "android.hardware.bydauto.doorlock.BYDAutoDoorLockDevice")) {
                throw new IllegalArgumentException("unexpected door-lock device");
            }
            this.device = device;
            Class<?> type = device.getClass();
            if (type.getField("DOOR_LOCK_STATE_LOCK").getInt(null) != LOCK
                    || type.getField("DOOR_LOCK_STATE_UNLOCK").getInt(null) != UNLOCK
                    || type.getField("DOOR_LOCK_COMMAND_SUCCESS").getInt(null) != 0
                    || type.getField("DOOR_LOCK_AREA_LEFT_FRONT").getInt(null) != 1) {
                throw new IllegalArgumentException("unverified door-lock state domain");
            }
            setter = type.getMethod("setAllDoorsLock", int.class);
            reader = type.getMethod("getDoorLockStatus", int.class);
            if (setter.getReturnType() != int.class || reader.getReturnType() != int.class) {
                throw new IllegalArgumentException("unverified door-lock method shape");
            }
            doorlockManager = field(type, "mDoorlockManagerImpl").get(device);
            if (doorlockManager == null || !doorlockManager.getClass().getName().equals(
                    "android.hardware.bydauto.doorlock.tsimpl.DoorlockManagerImpl")) {
                throw new IllegalArgumentException("unexpected door-lock manager");
            }
            forTest = field(doorlockManager.getClass(), "mForTest");
            tsManager = field(doorlockManager.getClass(), "mTsManagerImpl").get(doorlockManager);
            if (tsManager == null || !tsManager.getClass().getName().equals(
                    "android.hardware.bydauto.TsManagerImpl")) {
                throw new IllegalArgumentException("unexpected cabin manager");
            }
            adapterConnected = tsManager.getClass().getMethod("isConnected");
            carServiceConnected = tsManager.getClass().getMethod("isCarServiceConnect");
            cabinManager = tsManager.getClass().getMethod("getCarCabinAdapterManager");
        }

        @Override public boolean ready() throws Exception {
            // isConnectSuccess() is a latched SDK flag; it does not prove a current connection.
            if (forTest.getInt(doorlockManager) != 0
                    || !Boolean.TRUE.equals(adapterConnected.invoke(tsManager))
                    || !Boolean.TRUE.equals(carServiceConnected.invoke(tsManager))) {
                return false;
            }
            Object cabin = cabinManager.invoke(tsManager);
            if (cabin == null || !cabin.getClass().getName().equals(
                    "com.ts.lib.caradapter.cabin.CarCabinAdapterManager")) {
                return false;
            }
            Object service = field(cabin.getClass(), "mService").get(cabin);
            if (!(service instanceof IInterface)) return false;
            android.os.IBinder binder = ((IInterface) service).asBinder();
            return binder != null && binder.isBinderAlive();
        }

        @Override public int readDriverLock() throws Exception {
            return ((Integer) reader.invoke(device, 1)).intValue();
        }

        @Override public int writeAllDoors(int state) throws Exception {
            return ((Integer) setter.invoke(device, state)).intValue();
        }

        private static Field field(Class<?> type, String name) throws Exception {
            Field value = type.getDeclaredField(name);
            value.setAccessible(true);
            return value;
        }
    }
}
