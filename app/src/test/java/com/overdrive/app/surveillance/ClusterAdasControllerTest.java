package com.overdrive.app.surveillance;

import static com.overdrive.app.surveillance.ClusterAdasLifecycle.Result.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ClusterAdasControllerTest {
    @Test public void nativeStatusAndVerdictAreBothRequiredOnDiLink5() {
        assertEquals(COMPLETED, classify(0, false, "Result: Parcel(00000000 00000001 '........')", true));
        assertEquals(REJECTED, classify(0, false, "Result: Parcel(00000000 ffffffff '........')", true));
        assertEquals(REJECTED, classify(0, false, "Result: Parcel(fffffffe 00000001 '........')", true));
        assertEquals(UNKNOWN, classify(0, false, "Result: Parcel(00000000 '....')", true));
        assertEquals(UNKNOWN, classify(0, false, "Result: Parcel()", true));
        assertEquals(COMPLETED, classify(0, false, "Result: Parcel(00000000 '....')", false));
    }

    @Test public void timeoutAndAmbiguousExitCannotBeAcknowledgedOrRetriedThroughAlias() {
        assertEquals(UNKNOWN, classify(0, true, "Result: Parcel(00000000 00000001)", true));
        assertEquals(UNKNOWN, classify(1, false, "Result: Parcel(00000000 00000001)", true));
        assertEquals(UNKNOWN, classify(1, false, "binder transaction failed", true));
        assertEquals(UNAVAILABLE, classify(1, false, "Service AutoContainer does not exist", true));
        assertEquals(UNAVAILABLE, ClusterAdasController.classifyContainer(
                -1, false, false, "not started", "AutoContainer", true));
    }

    @Test public void broadcastCompletionMeansTransportCompletionOnly() {
        assertEquals(COMPLETED, ClusterAdasController.classifyBroadcast(0, false, true,
                "Broadcasting: Intent { ... }\nBroadcast completed: result=0\n"));
        assertEquals(COMPLETED, ClusterAdasController.classifyBroadcast(0, false, true,
                "Broadcast completed: result=-1\n"));
        assertEquals(UNKNOWN, ClusterAdasController.classifyBroadcast(0, false, true,
                "Broadcasting: Intent { ... }\n"));
        assertEquals(UNKNOWN, ClusterAdasController.classifyBroadcast(0, true, true,
                "Broadcast completed: result=0"));
        assertEquals(REJECTED, ClusterAdasController.classifyBroadcast(1, false, true,
                "java.lang.SecurityException: Permission Denial"));
    }

    @Test public void capturedBackgroundDenialUsesOneTemporaryExemption() {
        ReceiverFake f = new ReceiverFake();
        assertEquals(COMPLETED, prepare(f));
        assertEquals(Arrays.asList("start", "temporary", "start"), f.calls);
        assertTrue(f.logs.get(0).contains("Error: app is in background uid null"));
        assertTrue(f.logs.get(0).contains("exit=255"));
    }

    @Test public void successfulStartDoesNotChangeAnyExemption() {
        ReceiverFake f = new ReceiverFake();
        f.first = f.success;
        assertEquals(COMPLETED, prepare(f));
        assertEquals(Arrays.asList("start"), f.calls);
    }

    @Test public void unrelatedStartFailuresDoNotRequestAnExemption() {
        for (String error : new String[] {
                "Error: Permission Denial",
                "Error: Not found; no service started.",
                "java.lang.SecurityException: not allowed",
                "Error: Background activity start denied"
        }) {
            ReceiverFake f = new ReceiverFake();
            f.first = shell(255, false, true, error);
            assertEquals(REJECTED, prepare(f));
            assertEquals(Arrays.asList("start"), f.calls);
        }
    }

    @Test public void ambiguousStartCannotBeRetried() {
        ReceiverFake f = new ReceiverFake();
        f.first = shell(-1, true, true, "Error: app is in background uid null");
        assertEquals(UNKNOWN, prepare(f));
        assertEquals(Arrays.asList("start"), f.calls);
        f = new ReceiverFake();
        f.first = shell(-1, false, false, "not started");
        assertEquals(UNAVAILABLE, prepare(f));
        assertEquals(Arrays.asList("start"), f.calls);
    }

    @Test public void failedOrAmbiguousExemptionNeverStartsServiceAgain() {
        for (ClusterAdasController.ShellResult exemption : new ClusterAdasController.ShellResult[] {
                shell(255, false, true, "Error: Permission Denial"),
                shell(-1, true, true, ""),
                shell(0, false, true, "Unknown command: tempwhitelist"),
                shell(-1, false, false, "not started")
        }) {
            ReceiverFake f = new ReceiverFake();
            f.exemption = exemption;
            assertTrue(prepare(f) != COMPLETED);
            assertEquals(Arrays.asList("start", "temporary"), f.calls);
        }
    }

    @Test public void stopBeforeExemptionCancelsTheFallback() {
        ReceiverFake f = new ReceiverFake();
        f.stopAfterStart = true;
        assertEquals(CANCELLED, prepare(f));
        assertEquals(Arrays.asList("start"), f.calls);
    }

    @Test public void stopAfterExemptionCancelsTheServiceRetry() {
        ReceiverFake f = new ReceiverFake();
        f.stopAfterExemption = true;
        assertEquals(CANCELLED, prepare(f));
        assertEquals(Arrays.asList("start", "temporary"), f.calls);
    }

    @Test public void cancelledSourceDoesNotStartAnyPreparationCommand() {
        ReceiverFake f = new ReceiverFake();
        f.live = false;
        assertEquals(CANCELLED, prepare(f));
        assertTrue(f.calls.isEmpty());
    }

    @Test public void repeatedBackgroundDenialDoesNotLoop() {
        ReceiverFake f = new ReceiverFake();
        f.success = f.first;
        assertEquals(REJECTED, prepare(f));
        assertEquals(Arrays.asList("start", "temporary", "start"), f.calls);
        assertEquals(2, f.logs.size());
    }

    private static ClusterAdasLifecycle.Result prepare(ReceiverFake f) {
        return ClusterAdasController.prepareReceiver(() -> f.live, f);
    }

    private static ClusterAdasController.ShellResult shell(
            int exitCode, boolean timeout, boolean started, String output) {
        return new ClusterAdasController.ShellResult(exitCode, timeout, started, output);
    }

    private static final class ReceiverFake implements ClusterAdasController.ReceiverCommands {
        final List<String> calls = new ArrayList<>(), logs = new ArrayList<>();
        boolean live = true, stopAfterStart, stopAfterExemption;
        int starts;
        ClusterAdasController.ShellResult first = shell(255, false, true,
                "Starting service: Intent { cmp=com.byd.clusterdebug/.ClusterDebugService }\n"
                        + "Error: app is in background uid null\n");
        ClusterAdasController.ShellResult success = shell(0, false, true,
                "Starting service: Intent { cmp=com.byd.clusterdebug/.ClusterDebugService }\n");
        ClusterAdasController.ShellResult exemption = shell(0, false, true, "");
        @Override public ClusterAdasController.ShellResult startService() {
            calls.add("start");
            if (stopAfterStart) live = false;
            return starts++ == 0 ? first : success;
        }
        @Override public ClusterAdasController.ShellResult temporaryExemption() {
            calls.add("temporary");
            if (stopAfterExemption) live = false;
            return exemption;
        }
        @Override public void log(String message) { logs.add(message); }
    }

    private static ClusterAdasLifecycle.Result classify(int exitCode, boolean timeout,
            String output, boolean di5) {
        return ClusterAdasController.classifyContainer(exitCode, timeout, true,
                output, "AutoContainer", di5);
    }
}
