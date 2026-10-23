package com.overdrive.app.surveillance;

import java.util.Objects;

/** Read-only cluster sampling. A stalled OEM getter cannot stall the overlay lifecycle. */
final class ClusterAdasPropertySampler {
    static final long POWER_INTERVAL_MS = 1_000L;
    static final long PLATFORM_INTERVAL_MS = 3_000L;
    private static final long POWER_MAX_AGE_MS = 3_000L;
    private static final long PLATFORM_MAX_AGE_MS = 7_000L;

    interface Backend {
        ClusterAdasLifecycle.Snapshot snapshot();
        boolean canRead();
        long nowMs();
        Integer readPower();
        Integer readPlatform();
        void log(String message);
    }

    private static final class Sample {
        final ClusterAdasLifecycle.Snapshot session;
        final int power, platform;
        final long powerAt, platformAt;

        Sample(ClusterAdasLifecycle.Snapshot session, int power, long powerAt,
                int platform, long platformAt) {
            this.session = session;
            this.power = power;
            this.powerAt = powerAt;
            this.platform = platform;
            this.platformAt = platformAt;
        }
    }

    private final Backend backend;
    private volatile Sample sample;
    private long platformDue;

    ClusterAdasPropertySampler(Backend backend) {
        this.backend = Objects.requireNonNull(backend);
    }

    /** Runs only on the property worker. Getter failures invalidate the current value. */
    void tick() {
        try {
            ClusterAdasLifecycle.Snapshot session = backend.snapshot();
            if (!session.active() || !backend.canRead()) {
                sample = null;
                platformDue = 0L;
                return;
            }
            Sample previous = sample;
            if (previous == null || !session.equals(previous.session)) {
                previous = null;
                platformDue = 0L;
            }
            int power = read(true);
            long powerAt = backend.nowMs();
            if (!current(session)) {
                sample = null;
                platformDue = 0L;
                return;
            }
            int platform = previous == null ? -1 : previous.platform;
            long platformAt = previous == null ? 0L : previous.platformAt;
            // Publish a confirmed power loss before the slower platform getter can block.
            sample = new Sample(session, power, powerAt, platform, platformAt);
            if (powerAt >= platformDue) {
                platform = read(false);
                platformAt = backend.nowMs();
                platformDue = platformAt + PLATFORM_INTERVAL_MS;
            }
            if (!current(session)) {
                sample = null;
                platformDue = 0L;
                return;
            }
            sample = new Sample(session, power, powerAt, platform, platformAt);
            if (previous == null || previous.power != power || previous.platform != platform) {
                backend.log("Optional cluster overlay: cluster properties power=" + power
                        + ", platform=" + platform + " (-1 means unavailable)");
            }
        } catch (Throwable unavailable) {
            sample = null;
            platformDue = 0L;
        }
    }

    /** Cached only: no reflection, native getters, waits or property-worker locks. */
    ClusterAdasLifecycle.ClusterState state(ClusterAdasLifecycle.Snapshot session, long nowMs) {
        Sample cached = sample;
        if (cached == null || !session.active() || !session.equals(cached.session)) {
            return ClusterAdasLifecycle.ClusterState.UNKNOWN;
        }
        int power = fresh(nowMs, cached.powerAt, POWER_MAX_AGE_MS) ? cached.power : -1;
        int platform = fresh(nowMs, cached.platformAt, PLATFORM_MAX_AGE_MS) ? cached.platform : -1;
        return new ClusterAdasLifecycle.ClusterState(power, platform);
    }

    private int read(boolean power) {
        try {
            Integer value = power ? backend.readPower() : backend.readPlatform();
            int maximum = power ? 3 : 255;
            return value != null && value >= 0 && value <= maximum ? value : -1;
        } catch (Throwable unavailable) {
            return -1;
        }
    }

    private boolean current(ClusterAdasLifecycle.Snapshot expected) {
        return backend.canRead() && expected.equals(backend.snapshot());
    }

    private static boolean fresh(long now, long sampledAt, long maximumAge) {
        return now >= sampledAt && now - sampledAt <= maximumAge;
    }
}
