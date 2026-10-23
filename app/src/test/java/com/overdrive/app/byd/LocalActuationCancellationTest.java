package com.overdrive.app.byd;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class LocalActuationCancellationTest {
    @Test public void legacyCallerReceivesOnlyItsFinalReplyAndTypedRequestsNeverRegister() {
        assertFalse(LocalActuationCancellation.registrationAllowed(true, false));
        assertFalse(LocalActuationCancellation.registrationAllowed(false, false));
        assertFalse(LocalActuationCancellation.registrationAllowed(false, true));
        assertTrue(LocalActuationCancellation.registrationAllowed(true, true));
    }

    @Test public void cancellationBeforeRemoteRegistrationRevokesTheLateWorker() {
        LocalActuationCancellation caller = new LocalActuationCancellation();
        LocalActuationCancellation worker = new LocalActuationCancellation();
        caller.cancel();
        caller.onRemoteCancellation(worker::cancel);
        assertTrue(caller.isCancelled());
        assertTrue(worker.isCancelled());
    }

    @Test public void registrationBeforeCancellationRevokesExactlyOnce() {
        LocalActuationCancellation caller = new LocalActuationCancellation();
        AtomicInteger calls = new AtomicInteger();
        caller.onRemoteCancellation(calls::incrementAndGet);
        assertEquals(0, calls.get());
        caller.cancel();
        caller.cancel();
        assertEquals(1, calls.get());
        assertTrue(caller.isCancelled());
    }

    @Test public void lateCancellationCannotRevokeANewerRequest() {
        LocalActuationCancellation oldCaller = new LocalActuationCancellation();
        LocalActuationCancellation oldWorker = new LocalActuationCancellation();
        LocalActuationCancellation newWorker = new LocalActuationCancellation();
        oldCaller.onRemoteCancellation(oldWorker::cancel);
        oldCaller.cancel();
        assertTrue(oldWorker.isCancelled());
        assertFalse(newWorker.isCancelled());
    }

    @Test public void duplicateOwnerRegistrationCannotLeaveAnEarlierWorkerAlive() {
        LocalActuationCancellation caller = new LocalActuationCancellation();
        LocalActuationCancellation first = new LocalActuationCancellation();
        LocalActuationCancellation second = new LocalActuationCancellation();
        caller.onRemoteCancellation(first::cancel);
        caller.onRemoteCancellation(second::cancel);
        caller.cancel();
        assertTrue(first.isCancelled());
        assertTrue(second.isCancelled());
        LocalActuationCancellation late = new LocalActuationCancellation();
        caller.onRemoteCancellation(late::cancel);
        assertTrue(late.isCancelled());
    }

    @Test public void unavailableRemoteProcessDoesNotUndoCancellation() {
        LocalActuationCancellation caller = new LocalActuationCancellation();
        caller.onRemoteCancellation(() -> { throw new IllegalStateException("process gone"); });
        caller.cancel();
        assertTrue(caller.isCancelled());
    }

    @Test public void racingRegistrationAndCancellationCannotLoseRevocation() throws Exception {
        for (int i = 0; i < 50; i++) {
            LocalActuationCancellation caller = new LocalActuationCancellation();
            AtomicInteger calls = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            FutureTask<Void> register = new FutureTask<>(() -> {
                start.await();
                caller.onRemoteCancellation(calls::incrementAndGet);
                return null;
            });
            FutureTask<Void> cancel = new FutureTask<>(() -> {
                start.await();
                caller.cancel();
                return null;
            });
            Thread a = new Thread(register, "cancel-registration");
            Thread b = new Thread(cancel, "cancel-request");
            a.setDaemon(true);
            b.setDaemon(true);
            a.start();
            b.start();
            start.countDown();
            register.get(2, TimeUnit.SECONDS);
            cancel.get(2, TimeUnit.SECONDS);
            assertEquals(1, calls.get());
            assertTrue(caller.isCancelled());
        }
    }
}
