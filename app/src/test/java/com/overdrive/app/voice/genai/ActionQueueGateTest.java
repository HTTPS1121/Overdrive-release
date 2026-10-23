package com.overdrive.app.voice.genai;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ActionQueueGateTest {

    @Test
    public void ticketIsValidUntilClosed() {
        ActionQueueGate gate = new ActionQueueGate();
        ActionQueueGate.Ticket ticket = gate.take();
        assertTrue(gate.valid(ticket));
        assertFalse(gate.isClosed());
        gate.close();
        assertTrue(gate.isClosed());
        assertFalse(gate.valid(ticket));
        // Closed is permanent: tickets taken afterwards are dead on arrival.
        assertFalse(gate.valid(gate.take()));
    }

    @Test
    public void bumpInvalidatesOlderTicketsOnly() {
        ActionQueueGate gate = new ActionQueueGate();
        ActionQueueGate.Ticket old = gate.take();
        gate.bump();
        ActionQueueGate.Ticket fresh = gate.take();
        assertFalse(gate.valid(old));
        assertTrue(gate.valid(fresh));
        gate.bump();
        assertFalse(gate.valid(fresh));
    }

    @Test
    public void nullTicketIsInvalid() {
        assertFalse(new ActionQueueGate().valid(null));
    }
}
