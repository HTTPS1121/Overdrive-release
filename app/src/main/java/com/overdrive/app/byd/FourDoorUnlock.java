package com.overdrive.app.byd;

import android.content.Context;
import android.os.SystemClock;

import com.overdrive.app.logging.DaemonLogger;

import java.util.function.BooleanSupplier;

/**
 * Backs up the legacy local unlock with the bodywork four-door write. Off with the whole local rail
 * ({@code vehicle.localRemoteKeyDisabled}) or alone ({@code vehicle.fourDoorUnlockDisabled}).
 */
public final class FourDoorUnlock {
    private static final DaemonLogger logger = DaemonLogger.getInstance("FourDoorUnlock");
    private static final FourDoorUnlockPolicy POLICY = new FourDoorUnlockPolicy();
    private static FourDoorUnlockWriter writer;
    private static Context writerContext;

    private FourDoorUnlock() {}

    /** {@code primary} is the regular local unlock, which registers {@code primaryIntents} door intents. */
    public static boolean unlock(BydDataCollector collector, boolean confirm, int primaryIntents,
                                 BooleanSupplier primary) {
        return POLICY.unlock(new Port(collector), confirm, primaryIntents, primary);
    }

    public static boolean isEnabled() {
        try {
            return !com.overdrive.app.config.UnifiedConfigManager.getVehicle()
                    .optBoolean("fourDoorUnlockDisabled", false);
        } catch (Throwable t) {
            return true;
        }
    }

    private static synchronized FourDoorUnlockWriter writer(Context context) {
        if (writer == null || writerContext != context) {
            writer = new FourDoorUnlockWriter(context);
            writerContext = context;
            logger.info("four-door: writer ready fid=" + BydFeatureIds.BODYWORK_FOUR_DOOR_SET);
        }
        return writer;
    }

    private static final class Port implements FourDoorUnlockPolicy.Port {
        private final BydDataCollector collector;

        Port(BydDataCollector collector) {
            this.collector = collector;
        }

        public boolean aborted() { return RemoteKeyControl.hasPendingAbort(); }

        public boolean enabled() { return RemoteKeyControl.isEnabled() && isEnabled(); }

        public boolean diLink5() {
            return com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected();
        }

        public String blockReason() {
            return com.overdrive.app.byd.routing.DrivingSafetyGuard.getActionBlockReason(
                    com.overdrive.app.byd.routing.DrivingSafetyGuard.GUARD_DOOR_LOCKS);
        }

        public boolean requestExpired() { return VehicleActuatorBridge.isRemoteKeyRequestExpired(); }

        public int readDoor() { return collector.readDoorLockState(); }

        public RemoteKeySequence.IntentWrite write(long expected) {
            Context context = collector.getRuntimeContext();
            if (context == null) throw new IllegalStateException("no context");
            FourDoorUnlockWriter target = writer(context);
            return RemoteKeyControl.writeAsDoorIntent(expected, target::unlock);
        }

        public long doorIntent() { return RemoteKeyControl.doorIntent(); }

        public long elapsedMs() { return SystemClock.elapsedRealtime(); }

        public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }

        public void log(String message) { logger.info(message); }

        public void warn(String message) { logger.warn(message); }
    }
}
