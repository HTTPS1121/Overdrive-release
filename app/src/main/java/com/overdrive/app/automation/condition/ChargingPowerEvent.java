package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;

/**
 * Publishes {@link BydEvent#CHARGING_POWER} from the resolved charging state.
 *
 * <p>The resolved figure is too expensive to recompute on every snapshot rebuild, so it is
 * owned by this poll instead. Gated only on whether a rule references the key — never on ACC,
 * because charging happens with the car off and nothing else publishes this key.
 */
public final class ChargingPowerEvent {
    private static final long POLL_MS = 10_000L;
    private static final ConditionalPoller poller = new ConditionalPoller(
            "charging power",
            POLL_MS,
            () -> Automations.isEventReferenced(BydEvent.CHARGING_POWER),
            BydEvent::pollChargingPower);

    private ChargingPowerEvent() {}

    public static void refresh() {
        poller.refresh();
    }
}
