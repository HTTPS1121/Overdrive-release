package com.overdrive.app.surveillance;

/**
 * Fan-out of {@link HardwareEventRecorderGpu.StreamCallback#onStreamRetired()}.
 *
 * <p>When the stream encoder is torn down (quality change from another
 * viewer, idle shutdown, pipeline stop) its callbacks are cleared. Before
 * this hook existed a viewer whose callback had just been cleared had no way
 * to know: its send loop sat on an empty queue, pinged, and only gave up at
 * the 60 s idle exit while the user saw a frozen "Live" picture. The
 * retirement notice lets the viewer re-attach to the replacement encoder on
 * the same socket.
 *
 * <p>Kept out of {@code HardwareEventRecorderGpu} so the contract (every
 * registered callback is told exactly once, one misbehaving callback cannot
 * starve the others, and the notice is delivered BEFORE the set is cleared
 * by the caller) is unit-testable without MediaCodec.
 *
 * <p>Runs under {@code streamCallbackLock} inside
 * {@code disableStreamingLocked}, so implementations must not block; the
 * /ws implementation only {@code offer()}s a sentinel into its own queue.
 */
final class StreamCallbackRetirement {

    private StreamCallbackRetirement() {}

    /**
     * Notify every callback; a throwing callback is isolated so the rest are
     * still told.
     *
     * @return number of callbacks notified (including those that threw)
     */
    static int notifyRetired(Iterable<? extends HardwareEventRecorderGpu.StreamCallback> callbacks) {
        int notified = 0;
        for (HardwareEventRecorderGpu.StreamCallback callback : callbacks) {
            if (callback == null) continue;
            notified++;
            try {
                callback.onStreamRetired();
            } catch (Throwable ignored) {
                // One viewer's failure must not hide the notice from others.
            }
        }
        return notified;
    }
}
