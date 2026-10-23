package com.overdrive.app.server;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/** Confirms vehicle power from two live, matching reads rather than command acceptance. */
final class VehiclePowerConfirmation {
    private static final long POLL_MS = 250L;
    interface Sleeper { void sleep(long millis) throws InterruptedException; }

    static final class Result {
        final boolean confirmed;
        final int readback;
        Result(boolean confirmed, int readback) {
            this.confirmed = confirmed;
            this.readback = readback;
        }
    }

    private VehiclePowerConfirmation() {}

    static Result await(boolean on, long timeoutMs, IntSupplier read,
            BooleanSupplier current, LongSupplier clock, Sleeper sleeper) throws InterruptedException {
        long started = clock.getAsLong();
        int matches = 0, last = -1;
        while (timeoutMs > 0 && current.getAsBoolean()) {
            long before = clock.getAsLong();
            if (before < started || before - started >= timeoutMs) break;
            try { last = read.getAsInt(); }
            catch (RuntimeException unavailable) { last = -1; }
            long after = clock.getAsLong();
            if (after < before || after - started >= timeoutMs || !current.getAsBoolean()) break;
            boolean target = on ? last >= 2 && last <= 3 : last == 0;
            matches = target ? matches + 1 : 0;
            if (matches >= 2) return new Result(true, last);
            sleeper.sleep(Math.min(POLL_MS, timeoutMs - (after - started)));
        }
        return new Result(false, last);
    }
}
