package com.overdrive.app.byd;

import static org.junit.Assert.*;

import com.overdrive.app.byd.routing.VehicleCommandRouter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Method;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;

public class LocalActuationAttemptTest {
    @After public void clearInterrupt() { Thread.interrupted(); }

    @Test public void ordinaryHalRefusalLeavesCloudEligible() {
        LocalActuationAttempt attempt = new LocalActuationAttempt();
        assertNull(attempt.cloudAbort());
    }

    @Test public void cancelledLocalAttemptCannotEscapeOnADifferentCloudThread() throws Exception {
        LocalActuationAttempt attempt = new LocalActuationAttempt();
        LocalActuationAbort stopped = new LocalActuationAbort(
                LocalActuationAbort.Kind.CANCELLED, "cancelled local frame");
        attempt.abort(stopped);
        FutureTask<LocalActuationAbort> cloud = new FutureTask<>(attempt::cloudAbort);
        Thread worker = new Thread(cloud, "cloud-admission-test");
        worker.setDaemon(true);
        worker.start();
        assertSame(stopped, cloud.get(2, TimeUnit.SECONDS));
    }

    @Test public void expiredAndBlockedLocalAttemptsAreAlsoTerminal() {
        for (LocalActuationAbort.Kind kind : new LocalActuationAbort.Kind[] {
                LocalActuationAbort.Kind.REQUEST_EXPIRED,
                LocalActuationAbort.Kind.BLOCKED_DRIVING}) {
            LocalActuationAttempt attempt = new LocalActuationAttempt();
            attempt.abort(new LocalActuationAbort(kind, "stopped"));
            assertEquals(kind, attempt.cloudAbort().kind);
        }
    }

    @Test public void firstTerminalRefusalCannotBeReplacedByAnEligibleLaterFailure() {
        LocalActuationAttempt attempt = new LocalActuationAttempt();
        LocalActuationAbort expired = new LocalActuationAbort(
                LocalActuationAbort.Kind.REQUEST_EXPIRED, "expired");
        attempt.abort(expired);
        attempt.abort(new LocalActuationAbort(LocalActuationAbort.Kind.CANCELLED, "cancelled"));
        assertSame(expired, attempt.cloudAbort());
    }

    @Test public void aNewRequestCanReuseTheCommandValueWithoutOldCancellation() {
        LocalActuationAttempt previous = new LocalActuationAttempt();
        previous.abort(new LocalActuationAbort(LocalActuationAbort.Kind.CANCELLED, "old request"));
        LocalActuationAttempt next = new LocalActuationAttempt();
        assertNotNull(previous.cloudAbort());
        assertNull(next.cloudAbort());
    }

    @Test public void actualCommandRetainsRefusalUntilANewRoutingAttempt() throws Exception {
        VehicleCommandRouter.LockCommand command = new VehicleCommandRouter.LockCommand();
        LocalActuationAttempt first = attempt(command);
        first.abort(new LocalActuationAbort(LocalActuationAbort.Kind.CANCELLED, "old attempt"));
        assertSame(first, attempt(command));
        assertEquals(LocalActuationAbort.Kind.CANCELLED, attempt(command).cloudAbort().kind);
        Method begin = VehicleCommandRouter.VehicleCommand.class.getDeclaredMethod("beginLocalAttempt");
        begin.setAccessible(true);
        begin.invoke(command);
        assertNotSame(first, attempt(command));
        assertNull(attempt(command).cloudAbort());
        assertNotNull(first.cloudAbort()); // Captured older workers retain their own cancellation.
    }

    @Test public void actualCommandNeverSerializesAPreviousAttemptRefusal() throws Exception {
        VehicleCommandRouter.LockCommand command = new VehicleCommandRouter.LockCommand();
        attempt(command).abort(new LocalActuationAbort(
                LocalActuationAbort.Kind.REQUEST_EXPIRED, "old process attempt"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(command); }
        Object copy;
        try (ObjectInputStream in = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            copy = in.readObject();
        }
        assertNull(attempt((VehicleCommandRouter.VehicleCommand) copy).cloudAbort());
        assertNotNull(attempt(command).cloudAbort());
    }

    private static LocalActuationAttempt attempt(VehicleCommandRouter.VehicleCommand command)
            throws Exception {
        Method getter = VehicleCommandRouter.VehicleCommand.class.getDeclaredMethod("localAttempt");
        getter.setAccessible(true);
        return (LocalActuationAttempt) getter.invoke(command);
    }

    @Test public void cloudAdmissionHonorsPreexistingCallerInterruptWithoutClearingIt() {
        LocalActuationAttempt attempt = new LocalActuationAttempt();
        Thread.currentThread().interrupt();
        assertEquals(LocalActuationAbort.Kind.CANCELLED, attempt.cloudAbort().kind);
        assertTrue(Thread.currentThread().isInterrupted());
        Thread.interrupted();
        assertEquals(LocalActuationAbort.Kind.CANCELLED, attempt.cloudAbort().kind);
    }

    @Test public void terminalKindAndDetailSurviveTheBridgeRepresentation() {
        for (LocalActuationAbort.Kind kind : LocalActuationAbort.Kind.values()) {
            LocalActuationAbort reply = LocalActuationAbort.fromWire(kind.name(), "local detail");
            assertEquals(kind, reply.kind);
            assertEquals("local detail", reply.getMessage());
        }
        assertNull(LocalActuationAbort.fromWire(null, "ordinary refusal"));
        assertEquals(LocalActuationAbort.Kind.REQUEST_EXPIRED,
                LocalActuationAbort.fromWire("invalid", "malformed").kind);
    }
}
