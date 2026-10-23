package com.overdrive.app.safekeep.util;

import java.util.function.BooleanSupplier;

/** Byte-rate token bucket shared by upload streams; a rate of 0 means unlimited. */
public final class TokenBucket {

    private double bytesPerSec;
    private double tokens;
    private long lastNs = System.nanoTime();

    public TokenBucket(double bytesPerSec) {
        this.bytesPerSec = Math.max(0, bytesPerSec);
        this.tokens = burst();
    }

    public synchronized void setRate(double newBytesPerSec) {
        refill();
        bytesPerSec = Math.max(0, newBytesPerSec);
        tokens = Math.min(tokens, burst());
    }

    public synchronized double getRate() {
        return bytesPerSec;
    }

    /** Blocks until {@code n} bytes may pass. Returns early once {@code cancelled} reports true. */
    public void acquire(int n, BooleanSupplier cancelled) throws InterruptedException {
        while (true) {
            long waitMs;
            synchronized (this) {
                if (bytesPerSec <= 0) return;
                refill();
                if (tokens >= n) {
                    tokens -= n;
                    return;
                }
                waitMs = (long) Math.ceil((n - tokens) * 1000.0 / bytesPerSec);
            }
            if (cancelled != null && cancelled.getAsBoolean()) return;
            Thread.sleep(Math.max(5L, Math.min(waitMs, 250L)));
        }
    }

    private double burst() {
        return Math.max(128 * 1024, bytesPerSec * 0.25);
    }

    private void refill() {
        long now = System.nanoTime();
        if (bytesPerSec > 0) {
            tokens = Math.min(burst(), tokens + (now - lastNs) / 1e9 * bytesPerSec);
        }
        lastNs = now;
    }
}
