package com.overdrive.app.byd;

/** Terminal admission belongs to one routing attempt, not the lifetime of a command value. */
public final class LocalActuationAttempt {
    private volatile LocalActuationAbort stopped;

    public synchronized void abort(LocalActuationAbort reason) {
        if (stopped == null) stopped = reason;
    }

    public LocalActuationAbort cloudAbort() {
        if (stopped == null && Thread.currentThread().isInterrupted()) {
            abort(new LocalActuationAbort(
                    LocalActuationAbort.Kind.CANCELLED, "vehicle command cancelled"));
        }
        return stopped;
    }
}
