package com.overdrive.app.surveillance;

import java.util.function.LongSupplier;

/**
 * Monotonic admission limiter, with a per-encoder default for viewer-driven IDRs.
 *
 * <p>The stream encoder is shared by every live viewer, so one viewer's
 * "give me a keyframe" (a /ws attach, the camera's client rising edge, a
 * congestion skip, a re-attach after an encoder restart) forces an IDR on
 * everyone. Several viewers reconnecting at once would otherwise turn the
 * stream into a run of back-to-back IDRs at several times the P-frame
 * bitrate. The first request in a window passes immediately; later requests
 * inside {@link #MIN_INTERVAL_MS} are coalesced into the IDR that is already
 * on its way.
 *
 * <p>Monotonic clock only (never wall time, which jumps on NTP/GPS fixes).
 * Thread-safe: requests arrive from HTTP worker threads and the GL thread.
 * Custom clock/interval instances also gate motion frames independently of IDRs.
 */
final class SyncFrameRateLimiter {

    /** Minimum spacing between forced IDRs on one encoder. */
    static final long MIN_INTERVAL_MS = 1_000L;

    private final LongSupplier monotonicMs;
    private final long minIntervalMs;
    private long lastGrantedMs = Long.MIN_VALUE;

    /** Production clock: {@link System#nanoTime()} scaled to milliseconds. */
    SyncFrameRateLimiter() {
        this(() -> System.nanoTime() / 1_000_000L, MIN_INTERVAL_MS);
    }

    SyncFrameRateLimiter(LongSupplier monotonicMs, long minIntervalMs) {
        this.monotonicMs = monotonicMs;
        this.minIntervalMs = minIntervalMs;
    }

    /**
     * @return true if the caller should forward the request to the encoder
     *         now; false if an IDR was already requested within the window
     *         and this request is coalesced into it.
     */
    synchronized boolean tryAcquire() {
        long now = monotonicMs.getAsLong();
        if (lastGrantedMs != Long.MIN_VALUE && now - lastGrantedMs < minIntervalMs) {
            return false;
        }
        lastGrantedMs = now;
        return true;
    }
}
