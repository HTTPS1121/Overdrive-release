package com.overdrive.app.voice.genai;

/**
 * Invalidates queued and in-flight action executions once a voice turn is cancelled.
 *
 * <p>Actions run on a worker thread after the user confirmed them; if the session ends in the
 * meantime the POST must not go out, and a result that arrives late must not be relayed. A
 * ticket captures the generation at enqueue time; {@link #bump()} or {@link #close()} makes
 * every older ticket invalid. Pure Java so the seam is JVM-testable.
 */
final class ActionQueueGate {

    static final class Ticket {
        final int generation;

        private Ticket(int generation) {
            this.generation = generation;
        }
    }

    private int generation;
    private boolean closed;

    synchronized Ticket take() {
        return new Ticket(generation);
    }

    synchronized boolean valid(Ticket ticket) {
        return ticket != null && !closed && ticket.generation == generation;
    }

    /** Drops every ticket taken so far; the gate stays open for new ones. */
    synchronized void bump() {
        generation++;
    }

    /** Permanently invalidates every ticket, past and future. */
    synchronized void close() {
        closed = true;
    }

    synchronized boolean isClosed() {
        return closed;
    }
}
