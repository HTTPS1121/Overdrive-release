package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.camera.dilink5.DiLink5Platform;

/**
 * Publishes the central-lock state ({@link BydEvent#LOCK}) every second on non-DiLink 5 cars, so a
 * lock or unlock rule fires within seconds while parked rather than on the 90 s parked poll.
 *
 * <p>Not ACC-gated: locking and unlocking happen with the car off. DiLink 5 never runs this poll;
 * its app producer already reads the lock every second.
 */
public final class LockEvent {
    static final long POLL_MS = 1000L;
    private static final ConditionalPoller poller = new ConditionalPoller(
            "lock",
            POLL_MS,
            () -> !DiLink5Platform.isSelected()
                    && Automations.isEventReferenced(BydEvent.LOCK),
            () -> BydDataCollector.getInstance().pollLockStateNow());

    private LockEvent() {}

    public static void refresh() {
        poller.refresh();
    }
}
