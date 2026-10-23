package com.overdrive.app.launcher;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded in-memory diagnostics ring for DiLink 5 ADB recovery.
 *
 * <p>Holds the most recent {@link #CAPACITY} events (stage, result, gate
 * verdict, timestamps) so the escalator can dump a short tail at bounded
 * moments without any per-poll logging. Pure in-process state, synchronized for
 * cross-thread recording and reading.
 */
public final class DiLink5AdbRecoveryDiagnostics {

    /** Most recent events retained. */
    public static final int CAPACITY = 64;

    /** Each text field is truncated to this many characters. */
    public static final int MAX_FIELD_CHARS = 96;

    private final ArrayDeque<Event> events = new ArrayDeque<>();

    /** One recorded diagnostics event. */
    public static final class Event {
        public final long elapsedMs;
        public final long wallMs;
        public final String stage;
        public final String result;
        public final String gateVerdict;

        Event(long elapsedMs, long wallMs,
                String stage, String result, String gateVerdict) {
            this.elapsedMs = elapsedMs;
            this.wallMs = wallMs;
            this.stage = stage;
            this.result = result;
            this.gateVerdict = gateVerdict;
        }

        /** Single-line, stable rendering for logs and snapshots. */
        public String format() {
            return elapsedMs + " wall=" + wallMs + " " + stage
                    + " -> " + result + " [" + gateVerdict + "]";
        }
    }

    /** Record an event, truncating text fields and dropping the oldest beyond capacity. */
    public synchronized void record(
            long elapsedMs, long wallMs,
            String stage, String result, String gateVerdict) {
        events.addLast(new Event(
                elapsedMs, wallMs,
                truncate(stage), truncate(result), truncate(gateVerdict)));
        while (events.size() > CAPACITY) {
            events.removeFirst();
        }
    }

    /** All retained events, oldest first. */
    public synchronized List<Event> snapshot() {
        return new ArrayList<>(events);
    }

    /** The most recent {@code n} events, oldest first. */
    public synchronized List<Event> tail(int n) {
        if (n <= 0) {
            return new ArrayList<>();
        }
        int size = events.size();
        int skip = size - n;
        if (skip <= 0) {
            return new ArrayList<>(events);
        }
        List<Event> result = new ArrayList<>(n);
        int index = 0;
        for (Event event : events) {
            if (index++ >= skip) {
                result.add(event);
            }
        }
        return result;
    }

    /** Number of events currently retained. */
    public synchronized int size() {
        return events.size();
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= MAX_FIELD_CHARS) {
            return value;
        }
        return value.substring(0, MAX_FIELD_CHARS);
    }
}
