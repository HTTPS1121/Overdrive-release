package com.overdrive.app.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class DiLink5AdbRecoveryDiagnosticsTest {

    @Test
    public void capacityBoundKeepsMostRecent() {
        DiLink5AdbRecoveryDiagnostics ring = new DiLink5AdbRecoveryDiagnostics();
        for (int i = 1; i <= 100; i++) {
            ring.record(i, i * 10L, "stage" + i, "result" + i, "verdict");
        }
        assertEquals(64, ring.size());
        List<DiLink5AdbRecoveryDiagnostics.Event> all = ring.snapshot();
        assertEquals(64, all.size());
        assertEquals("stage37", all.get(0).stage);   // 100 - 64 + 1 = 37
        assertEquals("stage100", all.get(63).stage);
    }

    @Test
    public void snapshotPreservesOldestToNewestOrder() {
        DiLink5AdbRecoveryDiagnostics ring = new DiLink5AdbRecoveryDiagnostics();
        ring.record(1L, 1L, "a", "r1", "v");
        ring.record(2L, 2L, "b", "r2", "v");
        ring.record(3L, 3L, "c", "r3", "v");
        List<DiLink5AdbRecoveryDiagnostics.Event> all = ring.snapshot();
        assertEquals("a", all.get(0).stage);
        assertEquals("b", all.get(1).stage);
        assertEquals("c", all.get(2).stage);
    }

    @Test
    public void longFieldsAreTruncated() {
        DiLink5AdbRecoveryDiagnostics ring = new DiLink5AdbRecoveryDiagnostics();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            big.append('x');
        }
        ring.record(1L, 1L, big.toString(), big.toString(), big.toString());
        DiLink5AdbRecoveryDiagnostics.Event event = ring.snapshot().get(0);
        assertEquals(DiLink5AdbRecoveryDiagnostics.MAX_FIELD_CHARS, event.stage.length());
        assertEquals(DiLink5AdbRecoveryDiagnostics.MAX_FIELD_CHARS, event.result.length());
        assertEquals(DiLink5AdbRecoveryDiagnostics.MAX_FIELD_CHARS, event.gateVerdict.length());
    }

    @Test
    public void tailReturnsAllWhenFewerThanRequested() {
        DiLink5AdbRecoveryDiagnostics ring = new DiLink5AdbRecoveryDiagnostics();
        for (int i = 0; i < 5; i++) {
            ring.record(i, i, "s" + i, "r", "v");
        }
        assertEquals(5, ring.tail(16).size());
    }

    @Test
    public void tailReturnsMostRecentSuffix() {
        DiLink5AdbRecoveryDiagnostics ring = new DiLink5AdbRecoveryDiagnostics();
        for (int i = 0; i < 10; i++) {
            ring.record(i, i, "s" + i, "r", "v");
        }
        List<DiLink5AdbRecoveryDiagnostics.Event> tail = ring.tail(3);
        assertEquals(3, tail.size());
        assertEquals("s7", tail.get(0).stage);
        assertEquals("s9", tail.get(2).stage);
    }

    @Test
    public void nullFieldsAreTolerated() {
        DiLink5AdbRecoveryDiagnostics ring = new DiLink5AdbRecoveryDiagnostics();
        ring.record(1L, 1L, null, null, null);
        DiLink5AdbRecoveryDiagnostics.Event event = ring.snapshot().get(0);
        assertEquals("", event.stage);
        assertTrue(event.format().length() > 0);
    }
}
