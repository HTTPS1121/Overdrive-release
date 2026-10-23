package com.overdrive.app.byd;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Stage 0 app-side OTA lock probe: tokens, throttle and the collector wiring contract. */
public class BydDataCollectorDiLink5OtaLockTest {

    /** Fake OTA device with the getter but none of the readiness fields. */
    public static final class LockedDevice {
        public int getLFDoorLockState() { return 2; }
    }

    public static final class ThrowingDevice {
        public int getLFDoorLockState() { throw new IllegalStateException("not bound"); }
    }

    public static final class NoGetterDevice {
        public int getBatteryVoltage() { return 12; }
    }

    public static final class WrongShapeDevice {
        public long getLFDoorLockState() { return 2L; }
    }

    /** Mirrors BYDAutoOtaDevice.mManagerImpl -> OtaManagerImpl.{mForTest,mSdkManager}. */
    public static final class ShapedDevice {
        private final Manager mManagerImpl = new Manager();
        public int getLFDoorLockState() { return -1; }

        public static final class Manager {
            private int mForTest = 0;
            private Sdk mSdkManager = new Sdk();
        }

        public static final class Sdk {
            private Object mService = null;
            public boolean ready() { return mService != null; }
        }
    }

    private static final DiLink5OtaLockProbe.TypedTokens TYPED =
            new DiLink5OtaLockProbe.TypedTokens(0, 0, "driver-door lock readback unavailable");

    @Test
    public void getterValueIsReportedAndMissingReadinessFieldsAreClassified() {
        DiLink5OtaLockProbe.Snapshot s = DiLink5OtaLockProbe.probe(new LockedDevice());
        assertEquals("2", s.ota);
        assertEquals("unknown(NoSuchFieldException)", s.otaReady);
        assertEquals("?", s.otaForTest);
        assertEquals("?", s.otaService);
        assertTrue(s.callMs >= 0L);
        String line = DiLink5OtaLockProbe.formatLine(TYPED, s, false, 12_345L);
        assertTrue(line, line.startsWith("DI5 lock rails: typedRaw=0 typedState=0"
                + " typedReason=driver-door lock readback unavailable ota=2"
                + " otaReady=unknown(NoSuchFieldException) otaForTest=? otaService=? otaCallMs="));
        assertTrue(line, line.endsWith(" appAcc=false atElapsedMs=12345"));
    }

    @Test
    public void getterExceptionBecomesExcToken() {
        DiLink5OtaLockProbe.Snapshot s = DiLink5OtaLockProbe.probe(new ThrowingDevice());
        assertEquals("exc:IllegalStateException", s.ota);
    }

    @Test
    public void missingGetterAndWrongShapeAreClassified() {
        assertEquals("null(method-missing)",
                DiLink5OtaLockProbe.probe(new NoGetterDevice()).ota);
        assertEquals("null(method-shape)",
                DiLink5OtaLockProbe.probe(new WrongShapeDevice()).ota);
        // The resolution cache is per class: switching classes re-resolves.
        assertEquals("2", DiLink5OtaLockProbe.probe(new LockedDevice()).ota);
        assertEquals("null(method-missing)",
                DiLink5OtaLockProbe.probe(new NoGetterDevice()).ota);
    }

    @Test
    public void readinessFieldsAreReadThroughTheSdkLayout() {
        DiLink5OtaLockProbe.Snapshot s = DiLink5OtaLockProbe.probe(new ShapedDevice());
        assertEquals("-1", s.ota);
        assertEquals("false", s.otaReady);
        assertEquals("0", s.otaForTest);
        assertEquals("null", s.otaService);
    }

    @Test
    public void nullDeviceLineCarriesTheDeviceToken() {
        String line = DiLink5OtaLockProbe.formatLine(
                TYPED, DiLink5OtaLockProbe.nullDevice(), true, 7L);
        assertTrue(line, line.contains(
                " ota=null(device) otaReady=n/a otaForTest=? otaService=? otaCallMs=0 appAcc=true"
                        + " atElapsedMs=7"));
        assertEquals(DiLink5OtaLockProbe.nullDevice().ota,
                DiLink5OtaLockProbe.probe(null).ota);
    }

    @Test
    public void probeThrottleIsFiveSecondsOrTypedLineChange() {
        assertTrue(DiLink5OtaLockProbe.Throttle.shouldProbe(10_000L, 5_000L, false));
        assertFalse(DiLink5OtaLockProbe.Throttle.shouldProbe(9_999L, 5_000L, false));
        assertTrue(DiLink5OtaLockProbe.Throttle.shouldProbe(5_001L, 5_000L, true));
        assertEquals(5_000L, DiLink5OtaLockProbe.PROBE_INTERVAL_MS);
    }

    @Test
    public void logThrottleIsTokenChangeOrThirtySeconds() {
        String a = DiLink5OtaLockProbe.formatLine(
                TYPED, new DiLink5OtaLockProbe.Snapshot("2", "true", "0", "bound", 3L), true, 100L);
        String aLater = DiLink5OtaLockProbe.formatLine(
                TYPED, new DiLink5OtaLockProbe.Snapshot("2", "true", "0", "bound", 9L), true, 900L);
        String otaChanged = DiLink5OtaLockProbe.formatLine(
                TYPED, new DiLink5OtaLockProbe.Snapshot("-1", "true", "0", "bound", 3L), true, 100L);
        String readyChanged = DiLink5OtaLockProbe.formatLine(
                TYPED, new DiLink5OtaLockProbe.Snapshot("2", "false", "0", "bound", 3L), true, 100L);
        String typedChanged = DiLink5OtaLockProbe.formatLine(
                new DiLink5OtaLockProbe.TypedTokens(1, 2, "driver-door readback"),
                new DiLink5OtaLockProbe.Snapshot("2", "true", "0", "bound", 3L), true, 100L);
        assertTrue(DiLink5OtaLockProbe.Throttle.shouldLog(a, null, 1_000L, 0L));
        // Only callMs/atElapsedMs/appAcc differ: throttled until 30 s.
        assertFalse(DiLink5OtaLockProbe.Throttle.shouldLog(aLater, a, 30_999L, 1_000L));
        assertTrue(DiLink5OtaLockProbe.Throttle.shouldLog(aLater, a, 31_000L, 1_000L));
        assertTrue(DiLink5OtaLockProbe.Throttle.shouldLog(otaChanged, a, 1_001L, 1_000L));
        assertTrue(DiLink5OtaLockProbe.Throttle.shouldLog(readyChanged, a, 1_001L, 1_000L));
        assertTrue(DiLink5OtaLockProbe.Throttle.shouldLog(typedChanged, a, 1_001L, 1_000L));
        assertEquals(30_000L, DiLink5OtaLockProbe.LOG_INTERVAL_MS);
    }

    @Test
    public void collectorWiresTheProbeAfterTheTypedThrottleOffTheFastPollThread()
            throws Exception {
        String collector = read(
                "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        int readStart = collector.indexOf(
                "    private DiLink5DoorLockControl.ReadResult readDiLink5DriverLock() {");
        assertTrue(readStart >= 0);
        int typedLog = collector.indexOf("logger.info(\"DI5 driver lock: \" + diagnostic", readStart);
        int probeCall = collector.indexOf(
                "probeDiLink5OtaLock(result, typedLineJustLogged);", readStart);
        int ret = collector.indexOf("        return result;\n    }", readStart);
        assertTrue(typedLog > readStart && probeCall > typedLog && ret > probeCall);

        int probeStart = collector.indexOf("    private void probeDiLink5OtaLock(");
        int probeEnd = collector.indexOf("    private void stopDiLink5OtaLockProbe() {", probeStart);
        assertTrue(probeStart > 0 && probeEnd > probeStart);
        String probe = collector.substring(probeStart, probeEnd);
        assertTrue(probe.contains("if (!isDiLink5ProducerActive() || !initialized) return;"));
        assertTrue(probe.contains("DiLink5OtaLockProbe.Throttle.shouldProbe("));
        assertTrue(probe.contains("DiLink5OtaLockProbe.nullDevice()"));
        assertTrue(probe.contains("diLink5OtaProbeInFlight.compareAndSet(false, true)"));
        assertTrue(probe.contains("\"DI5 lock rails: ota probe still in flight after \""));
        assertTrue(probe.contains("new Thread(r, \"DI5OtaLockProbe\")"));
        assertTrue(probe.contains(".execute(() -> {"));
        assertTrue(probe.contains("DiLink5OtaLockProbe.probe(device)"));
        assertTrue(probe.contains("lastDiLink5LockDiag = line;"));
        // Fire-and-forget only: no Future is created, so nothing can block on .get().
        assertFalse(probe.contains("Future"));
        assertFalse(probe.contains("submit("));
        assertFalse(probe.contains("invokeAll("));
        assertFalse(probe.contains("registerListener"));
        assertFalse(probe.contains("getInstance"));
        assertFalse(probe.contains("synchronized"));
        assertTrue(collector.contains("        stopDiLink5CarSvcChargingPoll();\n"
                + "        stopDiLink5OtaLockProbe();\n"));

        String probeClass = read(
                "app/src/main/java/com/overdrive/app/byd/DiLink5OtaLockProbe.java");
        assertFalse(probeClass.contains("registerListener"));
        assertFalse(probeClass.contains("getInstance"));
        assertFalse(probeClass.contains("setLFDoorLock"));
    }

    // ---- Stage 1, item 1.5b: the OTA rail reader (two-direction debounce, inactive unless enabled)

    private static final int INVALID = BydDataCollector.DOOR_STATE_INVALID;
    private static final int UNLOCK = BydDataCollector.DOOR_STATE_UNLOCK;
    private static final int LOCK = BydDataCollector.DOOR_STATE_LOCK;

    /** Feeds {@code raws} at 2 s cadence from t=1000 and returns the published states in order. */
    private static int[] publish(BydDataCollector.DiLink5OtaLockDebounce debounce, int... raws) {
        int[] states = new int[raws.length];
        for (int i = 0; i < raws.length; i++) {
            BydDataCollector.DiLink5OtaLockDebounce.Published p =
                    debounce.next(raws[i], 1_000L + 2_000L * i);
            assertEquals(1_000L + 2_000L * i, p.readAt); // always THIS read's time
            states[i] = p.state;
        }
        return states;
    }

    @Test
    public void otaRawMappingIsIdentityIntoTheAppDomain() {
        assertEquals(UNLOCK, BydDataCollector.DiLink5OtaLockDebounce.mapOtaRaw(1));
        assertEquals(LOCK, BydDataCollector.DiLink5OtaLockDebounce.mapOtaRaw(2));
        assertEquals(INVALID, BydDataCollector.DiLink5OtaLockDebounce.mapOtaRaw(0));
        assertEquals(INVALID, BydDataCollector.DiLink5OtaLockDebounce.mapOtaRaw(-1));
        assertEquals(INVALID, BydDataCollector.DiLink5OtaLockDebounce.mapOtaRaw(3));
        assertEquals(INVALID, BydDataCollector.DiLink5OtaLockDebounce.mapOtaRaw(Integer.MIN_VALUE));
        assertEquals(1, BydDataCollector.DiLink5OtaLockDebounce.OTA_LF_UNLOCKED);
        assertEquals(2, BydDataCollector.DiLink5OtaLockDebounce.OTA_LF_LOCKED);
    }

    @Test
    public void singleReadIsNeverPublishedAsAState() {
        assertArrayEquals(new int[] {INVALID},
                publish(new BydDataCollector.DiLink5OtaLockDebounce(), 2));
        assertArrayEquals(new int[] {INVALID},
                publish(new BydDataCollector.DiLink5OtaLockDebounce(), 1));
    }

    @Test
    public void twoConsecutiveIdenticalReadsPublishTheStateAtTheSecondReadsTime() {
        BydDataCollector.DiLink5OtaLockDebounce debounce = new BydDataCollector.DiLink5OtaLockDebounce();
        assertArrayEquals(new int[] {INVALID, LOCK}, publish(debounce, 2, 2));
        // The streak keeps publishing while the value holds.
        BydDataCollector.DiLink5OtaLockDebounce.Published third = debounce.next(2, 9_000L);
        assertEquals(LOCK, third.state);
        assertEquals(9_000L, third.readAt);
    }

    @Test
    public void aChangeIsInvalidForOneReadThenPublishesTheNewState() {
        assertArrayEquals(new int[] {INVALID, LOCK, INVALID},
                publish(new BydDataCollector.DiLink5OtaLockDebounce(), 2, 2, 1));
        assertArrayEquals(new int[] {INVALID, LOCK, INVALID, UNLOCK},
                publish(new BydDataCollector.DiLink5OtaLockDebounce(), 2, 2, 1, 1));
    }

    @Test
    public void aOneSampleBluffNeverLands() {
        // [2,2,1,2]: the bluff UNLOCK is INVALID, and the return to LOCK is a new one-read streak,
        // so it is INVALID too — the ledger never sees the bluff, in either direction.
        assertArrayEquals(new int[] {INVALID, LOCK, INVALID, INVALID},
                publish(new BydDataCollector.DiLink5OtaLockDebounce(), 2, 2, 1, 2));
        assertArrayEquals(new int[] {INVALID, UNLOCK, INVALID, INVALID, UNLOCK},
                publish(new BydDataCollector.DiLink5OtaLockDebounce(), 1, 1, 2, 1, 1));
    }

    @Test
    public void outOfDomainReadsResetTheStreak() {
        // -1 (service unbound), 0 (invalid), MIN_VALUE (exception / failed fence) all reset.
        assertArrayEquals(new int[] {INVALID, INVALID, INVALID, LOCK},
                publish(new BydDataCollector.DiLink5OtaLockDebounce(), 2, -1, 2, 2));
        assertArrayEquals(new int[] {INVALID, LOCK, INVALID, INVALID, LOCK},
                publish(new BydDataCollector.DiLink5OtaLockDebounce(), 2, 2, 0, 2, 2));
        assertArrayEquals(new int[] {INVALID, LOCK, INVALID, INVALID},
                publish(new BydDataCollector.DiLink5OtaLockDebounce(), 2, 2, Integer.MIN_VALUE, 1));
        BydDataCollector.DiLink5OtaLockDebounce debounce = new BydDataCollector.DiLink5OtaLockDebounce();
        publish(debounce, 2, 2);
        debounce.reset();
        assertEquals(INVALID, debounce.next(2, 50_000L).state);
        assertEquals(LOCK, debounce.next(2, 52_000L).state);
    }

    @Test
    public void otaReaderIsInactiveUnlessEnabled() {
        java.util.Set<DiLink5LockRail> typedOnly = java.util.EnumSet.of(DiLink5LockRail.TYPED_CABIN);
        java.util.Set<DiLink5LockRail> withOta =
                java.util.EnumSet.of(DiLink5LockRail.TYPED_CABIN, DiLink5LockRail.OTA);
        // Default setting: never submitted, even with everything else in place.
        assertFalse(BydDataCollector.shouldSubmitDiLink5OtaRead(typedOnly, true, true, true));
        assertFalse(BydDataCollector.shouldSubmitDiLink5OtaRead(null, true, true, true));
        assertFalse(BydDataCollector.shouldSubmitDiLink5OtaRead(
                java.util.EnumSet.of(DiLink5LockRail.TYPED_CABIN, DiLink5LockRail.CARSVC), true, true, true));
        // Enabled: needs an active, initialised producer and the OTA device from init().
        assertTrue(BydDataCollector.shouldSubmitDiLink5OtaRead(withOta, true, true, true));
        assertFalse(BydDataCollector.shouldSubmitDiLink5OtaRead(withOta, true, true, false));
        assertFalse(BydDataCollector.shouldSubmitDiLink5OtaRead(withOta, false, true, true));
        assertFalse(BydDataCollector.shouldSubmitDiLink5OtaRead(withOta, true, false, true));
        assertTrue(BydDataCollector.shouldSubmitDiLink5OtaRead(
                java.util.EnumSet.of(DiLink5LockRail.OTA), true, true, true));
    }

    @Test
    public void lockGetterIsSharedWithTheProbeAndNullWithoutTheRightShape() throws Exception {
        java.lang.reflect.Method getter = DiLink5OtaLockProbe.lockGetter(LockedDevice.class);
        assertTrue(getter != null);
        assertEquals(2, ((Number) getter.invoke(new LockedDevice())).intValue());
        assertEquals(null, DiLink5OtaLockProbe.lockGetter(NoGetterDevice.class));
        assertEquals(null, DiLink5OtaLockProbe.lockGetter(WrongShapeDevice.class));
        assertEquals(null, DiLink5OtaLockProbe.lockGetter(null));
        // Same cache as the probe: the probe still classifies after the reader resolved the class.
        assertEquals("2", DiLink5OtaLockProbe.probe(new LockedDevice()).ota);
        assertTrue(DiLink5OtaLockProbe.lockGetter(LockedDevice.class) != null);
    }

    @Test
    public void decodeCountsEveryDroppedEntry() throws Exception {
        org.json.JSONArray array = new org.json.JSONArray()
                .put(new org.json.JSONObject().put("rail", "ota").put("state", 2).put("raw", 2)
                        .put("observedElapsedMs", 1_000L))
                .put(new org.json.JSONObject().put("rail", "cloud").put("state", 2).put("raw", 2)
                        .put("observedElapsedMs", 1_000L))
                .put("not an object");
        int[] dropped = new int[1];
        java.util.List<BydDataCollector.DiLink5LockReading> readings =
                BydDataCollector.decodeDiLink5LockReadings(array, dropped);
        assertEquals(1, readings.size());
        assertEquals(DiLink5LockRail.OTA, readings.get(0).rail);
        assertEquals(2, dropped[0]);
        long before = BydDataCollector.diLink5DroppedLockReadingEntries();
        assertEquals(1, BydDataCollector.decodeDiLink5LockReadings(array).size());
        assertEquals(before + 2L, BydDataCollector.diLink5DroppedLockReadingEntries());
        assertEquals(4, BydDataCollector.DILINK5_LOCK_READINGS_MAX_ENTRIES);
    }

    @Test
    public void otaReaderIsSubmittedFromTheFastLoopEveryEighthTickAndFencedOffTheFastPollThread()
            throws Exception {
        String collector = read(
                "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        // NIT-3: the OTA read is enqueued next to the 4th-tick branch, every 8th tick (2 s).
        int fourth = collector.indexOf("if ((currentTick & 0x3L) == 0L) {");
        int eighth = collector.indexOf("if ((currentTick & 0x7L) == 0L) submitDiLink5OtaLockRead();");
        int ret = collector.indexOf("                    return;", eighth);
        assertTrue(fourth > 0 && eighth > fourth && ret > eighth);
        assertTrue(collector.substring(fourth, eighth).contains("collectLockState();"));
        assertEquals(1, count(collector, "submitDiLink5OtaLockRead();"));

        int submit = collector.indexOf("    private void submitDiLink5OtaLockRead() {");
        int reader = collector.indexOf("    private void readDiLink5OtaLock(Object device, String modeGeneration) {");
        int encode = collector.indexOf("    public static org.json.JSONArray encodeDiLink5LockReadings(");
        assertTrue(submit > 0 && reader > submit && encode > reader);
        String submitBody = collector.substring(submit, reader);
        assertTrue(submitBody.contains(
                "if (!shouldSubmitDiLink5OtaRead(diLink5ProducerEnabledRails, isDiLink5ProducerActive(),\n"
                        + "                    initialized, device != null)) {\n"
                        + "                return;"));
        assertTrue(submitBody.contains("if (!diLink5OtaProbeInFlight.compareAndSet(false, true)) return;"));
        assertTrue(submitBody.contains("diLink5OtaLockProbeExecutor().execute(() -> {"));
        assertTrue(submitBody.contains("readDiLink5OtaLock(device, modeGeneration);"));
        assertTrue(submitBody.contains("diLink5OtaProbeInFlight.set(false);"));
        // Enqueue only: no Binder call, no blocking, nothing can throw out of the fast loop.
        assertFalse(submitBody.contains("invoke("));
        assertFalse(submitBody.contains("Future"));
        assertFalse(submitBody.contains(".get("));
        assertFalse(submitBody.contains("synchronized"));
        assertTrue(submitBody.startsWith(
                "    private void submitDiLink5OtaLockRead() {\n        try {"));

        String readerBody = collector.substring(reader, encode);
        int fenceDecl = readerBody.indexOf("java.util.function.BooleanSupplier fence =");
        int getterResolve = readerBody.indexOf("DiLink5OtaLockProbe.lockGetter(device.getClass());");
        int fenceBefore = readerBody.indexOf("if (getter != null && fence.getAsBoolean()) {");
        int invoke = readerBody.indexOf("Object value = getter.invoke(device);");
        int readAt = readerBody.indexOf("long readAt = SystemClock.elapsedRealtime();");
        int fenceAfter = readerBody.indexOf("if (!fence.getAsBoolean()) raw = Integer.MIN_VALUE;");
        int debounce = readerBody.indexOf("diLink5OtaLockDebounce.next(raw, readAt);");
        int rawWrite = readerBody.indexOf("diLink5OtaLockRaw = raw;");
        int stateWrite = readerBody.indexOf("diLink5OtaLockState = published.state;");
        int timeWrite = readerBody.indexOf("diLink5OtaLockReadAtElapsedMs = published.readAt;");
        assertTrue(fenceDecl > 0 && getterResolve > fenceDecl && fenceBefore > getterResolve
                && invoke > fenceBefore && readAt > invoke && fenceAfter > readAt
                && debounce > fenceAfter && rawWrite > debounce && stateWrite > rawWrite
                && timeWrite > stateWrite);
        // Same fence shape as the typed reader: producer active, same device, same mode generation.
        assertTrue(readerBody.contains(
                "() -> isDiLink5ProducerActive() && otaDevice == device\n"
                        + "                        && com.overdrive.app.camera.dilink5.DiLink5Platform\n"
                        + "                                .matchesActiveModeGeneration(modeGeneration);"));
        assertEquals(2, count(readerBody, "fence.getAsBoolean()"));
        assertFalse(readerBody.contains("ready("));
        assertFalse(readerBody.contains("registerListener"));
        assertFalse(readerBody.contains("getInstance"));
        assertFalse(readerBody.contains("synchronized"));

        // The hazard note is quoted above the debounce, and the snapshot reads time before state.
        int debounceClass = collector.indexOf("    static final class DiLink5OtaLockDebounce {");
        assertTrue(debounceClass > 0);
        String aboveDebounce = collector.substring(Math.max(0, debounceClass - 1_500), debounceClass);
        assertTrue(aboveDebounce.contains("emits transient/bluff DOOR_STATE_UNLOCK reads"));
        int publish = collector.indexOf("    private void publishAutomationSnapshot(BydVehicleData published) {");
        String publishBody = collector.substring(publish, collector.indexOf("\n    }\n", publish));
        int otaTime = publishBody.indexOf("long otaReadAt = diLink5OtaLockReadAtElapsedMs;");
        int otaState = publishBody.indexOf("int otaState = diLink5OtaLockState;");
        int otaRaw = publishBody.indexOf("int otaRaw = diLink5OtaLockRaw;");
        int typedTime = publishBody.indexOf("long lockReadAt = diLink5DoorLockReadAtElapsedMs;");
        int typedState = publishBody.indexOf("int lockState = diLink5DoorLockState;");
        int typedRaw = publishBody.indexOf("int lockRaw = diLink5DoorLockRaw;");
        assertTrue(typedTime > 0 && typedState > typedTime && typedRaw > typedState
                && otaTime > typedRaw && otaState > otaTime && otaRaw > otaState);
        assertTrue(publishBody.contains(
                "diLink5ProducerEnabledRails.contains(DiLink5LockRail.OTA),"));
        // Typed raw is written in collectLockState before the state and the time.
        int collect = collector.indexOf("    private synchronized void collectLockState() {");
        String collectBody = collector.substring(collect, collector.indexOf("\n    }\n", collect));
        int typedRawWrite = collectBody.indexOf("diLink5DoorLockRaw = reading.nativeState;");
        int typedStateWrite = collectBody.indexOf("diLink5DoorLockState = state;");
        int typedTimeWrite = collectBody.indexOf("diLink5DoorLockReadAtElapsedMs = reading.observedAtElapsedMs;");
        assertTrue(typedRawWrite > 0 && typedStateWrite > typedRawWrite && typedTimeWrite > typedStateWrite);
        // stop() retires the OTA rail's state with the probe executor.
        int stopProbe = collector.indexOf("    private void stopDiLink5OtaLockProbe() {");
        String stopBody = collector.substring(stopProbe, collector.indexOf("\n    }\n", stopProbe));
        assertTrue(stopBody.contains("diLink5OtaLockDebounce.reset();"));
        assertTrue(stopBody.contains("diLink5OtaLockReadAtElapsedMs = 0L;"));
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private static String read(String relative) throws Exception {
        Path path = Paths.get(System.getProperty("user.dir"), relative);
        if (!Files.exists(path)) {
            path = Paths.get(System.getProperty("user.dir")).getParent()
                    .resolve(relative);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
