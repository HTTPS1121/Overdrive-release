package com.overdrive.app.safekeep.dest;

/** Typed failure from a destination; the core decides retry/backoff from {@link #kind}. */
public final class DestError extends Exception {

    public enum Kind {
        /** Governor pause, pre-emption or cancel — not a failure. */
        ABORTED,
        AUTH_EXPIRED,
        QUOTA_FULL,
        RATE_LIMITED,
        NETWORK,
        /** Remote resumable session is gone; restart the object from byte 0. */
        SESSION_GONE,
        CLOCK_SKEW,
        CONFLICT,
        LOCAL_IO,
        PERMANENT
    }

    public final Kind kind;
    public final int httpCode;
    public final long retryAfterMs;

    public DestError(Kind kind, String message) {
        this(kind, message, 0, 0);
    }

    public DestError(Kind kind, String message, int httpCode, long retryAfterMs) {
        super(message);
        this.kind = kind;
        this.httpCode = httpCode;
        this.retryAfterMs = retryAfterMs;
    }

    public static DestError network(Throwable t) {
        return new DestError(Kind.NETWORK, t == null ? "network error" : String.valueOf(t.getMessage()));
    }

    public static DestError aborted() {
        return new DestError(Kind.ABORTED, "paused");
    }
}
