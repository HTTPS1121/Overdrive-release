package com.overdrive.app.voice;

import java.util.concurrent.locks.LockSupport;

/** Single-producer single-consumer ring of 16 kHz samples; the producer overwrites when full. */
final class PcmRing {

    private final short[] data;
    private final int capacity;
    private volatile long written;
    private long read;
    private volatile Thread consumer;
    private long dropped;

    PcmRing(int capacitySamples) {
        capacity = capacitySamples;
        data = new short[capacitySamples];
    }

    void bindConsumer(Thread thread) {
        consumer = thread;
    }

    /** Producer side (capture thread). */
    void write(short[] src, int off, int len) {
        long w = written;
        for (int i = 0; i < len; i++) {
            data[(int) ((w + i) % capacity)] = src[off + i];
        }
        written = w + len;
        Thread c = consumer;
        if (c != null) LockSupport.unpark(c);
    }

    /** Consumer side: copies up to {@code max} unread samples; skips ahead if the producer lapped us. */
    int read(short[] dst, int off, int max) {
        long w = written;
        if (w - read > capacity) {
            dropped += (w - read) - capacity;
            read = w - capacity;
        }
        int n = (int) Math.min(max, w - read);
        for (int i = 0; i < n; i++) {
            dst[off + i] = data[(int) ((read + i) % capacity)];
        }
        read += n;
        return n;
    }

    /** Unread samples the consumer will get from read(): capped like read() caps a lap. */
    int available() {
        return (int) Math.min(capacity, written - read);
    }

    /** Consumer side: drops everything unread (used after half-duplex mute). */
    void skipToLatest() {
        read = written;
    }

    void awaitData(long maxWaitNanos) {
        if (written == read) LockSupport.parkNanos(this, maxWaitNanos);
    }

    long droppedSamples() {
        return dropped;
    }
}
