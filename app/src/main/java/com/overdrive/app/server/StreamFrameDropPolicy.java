package com.overdrive.app.server;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Keyframe-aware congestion policy for the single-port {@code /ws} H.264
 * sender in {@link HttpServer}.
 *
 * <p>The encoder drain thread offers every packet; one sender thread per
 * viewer drains the queue and writes to the socket. When the viewer's link
 * cannot keep up (tunnel, cellular) the queue fills. The previous policy
 * dropped whichever frame arrived while the queue was full, IDRs included,
 * so every drop broke the reference chain and the viewer saw smeared
 * green/grey blocks until the next keyframe; hardware WebCodecs decoders on
 * phones raise a fatal error on the missing reference instead.
 *
 * <p>This policy never emits a reference-broken P-frame:
 * <ul>
 *   <li>When the queue is full, or the oldest queued frame is older than
 *       {@link #MAX_QUEUE_AGE_MS} (the sender is stuck in a blocking write),
 *       the whole backlog is discarded and the policy enters <em>skipping</em>.
 *       The caller should ask the encoder for a sync frame (rate-limited).</li>
 *   <li>While skipping, every non-key frame is discarded.</li>
 *   <li>The next IDR resumes delivery. If an SPS/PPS config was discarded,
 *       or could not be queued when it arrived, it is re-sent immediately
 *       before that IDR, so the config is never lost silently.</li>
 * </ul>
 * The wire format is untouched: each queued {@link Frame} is one bare
 * Annex-B WebSocket message exactly as before.
 *
 * <p>Pure state machine over an {@link ArrayBlockingQueue}: no sockets, no
 * threads, injectable monotonic clock, so it is unit-testable in isolation.
 * All {@code offer*} methods are non-blocking; the encoder drain thread is
 * never stalled by a slow viewer.
 */
final class StreamFrameDropPolicy {

    /** Same depth as the previous {@code ArrayBlockingQueue<byte[]>(60)}. */
    static final int DEFAULT_CAPACITY = 60;

    /** Oldest queued frame older than this means the sender is stuck. */
    static final long MAX_QUEUE_AGE_MS = 1_000L;

    /** Minimum spacing of the daemon-log drop summary. */
    static final long SUMMARY_INTERVAL_MS = 10_000L;

    /** One queued WebSocket message. */
    static final class Frame {
        /** Bare Annex-B payload; {@code null} only for {@link #RETIRED}. */
        final byte[] bytes;
        /** {@code MediaCodec.BUFFER_FLAG_KEY_FRAME} was set on the packet. */
        final boolean isKey;
        /** SPS+PPS codec config (from {@code onSpsPps}), not a picture. */
        final boolean isConfig;
        /** Encoder presentation timestamp (µs); 0 for config. */
        final long ptsUs;
        /** Monotonic ms at enqueue; drives the age cap. */
        final long enqueuedAtMs;

        /**
         * Sentinel: the encoder feeding this queue was retired
         * ({@code StreamCallback.onStreamRetired}). The sender must re-attach
         * to the replacement encoder instead of sending anything.
         */
        static final Frame RETIRED = new Frame(null, false, false, 0L, 0L);

        Frame(byte[] bytes, boolean isKey, boolean isConfig, long ptsUs, long enqueuedAtMs) {
            this.bytes = bytes;
            this.isKey = isKey;
            this.isConfig = isConfig;
            this.ptsUs = ptsUs;
            this.enqueuedAtMs = enqueuedAtMs;
        }

        boolean isRetired() {
            return this == RETIRED;
        }
    }

    private final ArrayBlockingQueue<Frame> queue;
    private final LongSupplier monotonicMs;
    private final long maxQueueAgeMs;

    // State below is guarded by `this`. Offers come from the encoder drain
    // thread, the retirement sentinel from the thread disabling streaming,
    // reset/summary from the sender thread.
    private boolean skipping;
    private boolean syncFrameWanted;
    private byte[] latestConfig;
    private boolean configDirty;

    // Counters for the rate-limited daemon-log summary.
    private long droppedFrames;
    private long gopSkips;
    private long fullTriggers;
    private long staleTriggers;
    private long configDeferrals;
    private long lastSummaryMs = Long.MIN_VALUE;
    private long summarizedDropped;
    private long summarizedSkips;
    private long summarizedDeferrals;

    /** Production configuration: 60 frames, 1 s age cap, System.nanoTime clock. */
    StreamFrameDropPolicy() {
        this(DEFAULT_CAPACITY, MAX_QUEUE_AGE_MS, () -> System.nanoTime() / 1_000_000L);
    }

    StreamFrameDropPolicy(int capacity, long maxQueueAgeMs, LongSupplier monotonicMs) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.maxQueueAgeMs = maxQueueAgeMs;
        this.monotonicMs = monotonicMs;
    }

    // ---------------------------------------------------------------- producers

    /**
     * Queue an SPS+PPS config message. Never dropped silently: if the queue is
     * full the config is remembered and re-sent right before the IDR that
     * resumes delivery.
     */
    synchronized void offerConfig(byte[] spsPps) {
        if (spsPps == null) return;
        long now = monotonicMs.getAsLong();
        latestConfig = spsPps;
        if (queue.offer(new Frame(spsPps, false, true, 0L, now))) {
            configDirty = false;
            return;
        }
        configDirty = true;
        configDeferrals++;
    }

    /**
     * Cheap pre-check for the drain thread so it can avoid copying a frame
     * that is about to be discarded anyway.
     *
     * @return true if a non-key frame is being discarded while waiting for an
     *         IDR (the drop is counted)
     */
    synchronized boolean discardsWhileSkipping(boolean isKey) {
        if (skipping && !isKey) {
            droppedFrames++;
            return true;
        }
        return false;
    }

    /**
     * Queue one encoded picture.
     *
     * @return true if the frame was queued; false if it was dropped (check
     *         {@link #consumeSyncFrameRequest()} to learn whether a keyframe
     *         should be requested from the encoder)
     */
    synchronized boolean offerVideo(byte[] bytes, boolean isKey, long ptsUs) {
        long now = monotonicMs.getAsLong();
        if (skipping && !isKey) {
            droppedFrames++;
            return false;
        }
        boolean needsConfig = isKey && configDirty && latestConfig != null;
        int slotsNeeded = needsConfig ? 2 : 1;
        if (!skipping) {
            boolean full = queue.remainingCapacity() < slotsNeeded;
            if (full || oldestOlderThan(now)) {
                enterSkip(full);
                needsConfig = isKey && configDirty && latestConfig != null;
            }
        }
        if (skipping) {
            if (!isKey) {
                droppedFrames++;
                return false;
            }
            skipping = false;          // this IDR restarts the reference chain
            syncFrameWanted = false;   // no need to ask for one we already have
        }
        if (needsConfig) {
            // Room is guaranteed: the queue was just cleared or had >= 2 slots.
            queue.offer(new Frame(latestConfig, false, true, 0L, now));
            configDirty = false;
        }
        if (queue.offer(new Frame(bytes, isKey, false, ptsUs, now))) {
            return true;
        }
        // Unreachable after the capacity check above; keep the invariant that a
        // dropped IDR always leaves us waiting for the next one.
        droppedFrames++;
        skipping = true;
        syncFrameWanted = true;
        return false;
    }

    /**
     * Deliver the retirement notice ({@link Frame#RETIRED}). Non-blocking and
     * never lost: if the queue is full, the backlog (all from the encoder
     * being retired) is discarded in favour of the notice.
     */
    synchronized void offerRetired() {
        if (queue.offer(Frame.RETIRED)) return;
        int discarded = 0;
        for (Frame f : queue) {
            if (!f.isConfig && !f.isRetired()) discarded++;
        }
        queue.clear();
        droppedFrames += discarded;
        queue.offer(Frame.RETIRED);
    }

    /**
     * @return true exactly once per skip entry: the caller should request a
     *         sync frame so the skip ends at the next possible IDR
     */
    synchronized boolean consumeSyncFrameRequest() {
        boolean wanted = syncFrameWanted;
        syncFrameWanted = false;
        return wanted;
    }

    // ----------------------------------------------------------------- consumer

    /** Sender-side poll; mirrors {@link ArrayBlockingQueue#poll(long, TimeUnit)}. */
    Frame poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    /**
     * Forget everything about the retired encoder before re-attaching to its
     * replacement: queued leftovers, skip state and the old SPS/PPS (the new
     * encoder sends its own on attach).
     */
    synchronized void resetForReattach() {
        queue.clear();
        skipping = false;
        syncFrameWanted = false;
        latestConfig = null;
        configDirty = false;
    }

    /**
     * Rate-limited daemon-log line, or {@code null} when nothing new happened
     * or the last summary was less than {@link #SUMMARY_INTERVAL_MS} ago. The
     * first skip is reported immediately.
     */
    synchronized String drainSummary() {
        if (gopSkips == summarizedSkips
                && droppedFrames == summarizedDropped
                && configDeferrals == summarizedDeferrals) {
            return null;
        }
        long now = monotonicMs.getAsLong();
        if (lastSummaryMs != Long.MIN_VALUE && now - lastSummaryMs < SUMMARY_INTERVAL_MS) {
            return null;
        }
        lastSummaryMs = now;
        summarizedSkips = gopSkips;
        summarizedDropped = droppedFrames;
        summarizedDeferrals = configDeferrals;
        return "WS: dropped " + gopSkips + " GOPs (" + droppedFrames
                + " frames; queue full x" + fullTriggers
                + ", stale >" + maxQueueAgeMs + "ms x" + staleTriggers
                + ", SPS/PPS deferred x" + configDeferrals + ")";
    }

    // --------------------------------------------------------------- inspection

    synchronized boolean isSkipping() {
        return skipping;
    }

    int size() {
        return queue.size();
    }

    synchronized long droppedFrames() {
        return droppedFrames;
    }

    synchronized long gopSkips() {
        return gopSkips;
    }

    synchronized long configDeferrals() {
        return configDeferrals;
    }

    // ----------------------------------------------------------------- internals

    private boolean oldestOlderThan(long now) {
        Frame oldest = queue.peek();
        return oldest != null && !oldest.isRetired() && now - oldest.enqueuedAtMs > maxQueueAgeMs;
    }

    /**
     * Discard the backlog and wait for the next IDR. A queued retirement
     * sentinel survives (the viewer must still learn about it) and a queued
     * config is marked for re-send so it is not lost.
     */
    private void enterSkip(boolean becauseFull) {
        int discardedVideo = 0;
        boolean hadRetired = false;
        boolean hadConfig = false;
        for (Frame f : queue) {
            if (f.isRetired()) hadRetired = true;
            else if (f.isConfig) hadConfig = true;
            else discardedVideo++;
        }
        queue.clear();
        if (hadRetired) queue.offer(Frame.RETIRED);
        if (hadConfig && latestConfig != null) configDirty = true;
        droppedFrames += discardedVideo;
        gopSkips++;
        if (becauseFull) fullTriggers++;
        else staleTriggers++;
        skipping = true;
        syncFrameWanted = true;
    }
}
