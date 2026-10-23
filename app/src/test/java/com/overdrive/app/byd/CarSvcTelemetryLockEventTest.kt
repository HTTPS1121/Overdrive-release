package com.overdrive.app.byd

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Stage 0 diagnostics seam of [CarSvcTelemetry]: lock-event parser, cached dump, dump stats. */
class CarSvcTelemetryLockEventTest {
    private val gunLine =
        "lastEvent:Property:0x21403407,status: 2,timestamp:74475491501900,zone:0x0," +
            "floatValues: [],int32Values: [0],int64Values: [],bytes: [],string:"

    private fun lockLine(propId: Int, value: Int, status: Int?, ts: String?): String {
        val sb = StringBuilder("lastEvent:Property:0x").append(propId.toString(16)).append(',')
        if (status != null) sb.append("status: ").append(status).append(',')
        if (ts != null) sb.append("timestamp:").append(ts).append(',')
        sb.append("zone:0x0,floatValues: [],int32Values: [").append(value)
            .append("],int64Values: [],bytes: [],string:")
        return sb.toString()
    }

    @Before
    fun reset() = CarSvcTelemetry.resetDumpStatsForTest()

    @After
    fun cleanup() = CarSvcTelemetry.resetDumpStatsForTest()

    @Test
    fun absentKeyIsNull() {
        val text = lockLine(CarSvcTelemetry.RF_LOCK_PROP_ID, 2, 0, "100")
        assertNull(CarSvcTelemetry.parseLockEvent(text, CarSvcTelemetry.LF_LOCK_PROP_ID))
        assertNull(CarSvcTelemetry.parseLockEvent(null, CarSvcTelemetry.LF_LOCK_PROP_ID))
    }

    @Test
    fun statusIsReportedVerbatimOrNullWhenMissing() {
        val lf = CarSvcTelemetry.LF_LOCK_PROP_ID
        assertEquals(0, CarSvcTelemetry.parseLockEvent(lockLine(lf, 2, 0, "5"), lf)!!.status)
        assertEquals(2, CarSvcTelemetry.parseLockEvent(lockLine(lf, 2, 2, "5"), lf)!!.status)
        assertNull(CarSvcTelemetry.parseLockEvent(lockLine(lf, 2, null, "5"), lf)!!.status)
    }

    @Test
    fun greatestTimestampWinsAcrossSeveralRows() {
        val lf = CarSvcTelemetry.LF_LOCK_PROP_ID
        val text = listOf(
            lockLine(lf, 1, 0, "300"),
            lockLine(lf, 2, 0, "900"),
            lockLine(lf, 1, 0, "600")
        ).joinToString("\n")
        val event = CarSvcTelemetry.parseLockEvent(text, lf)!!
        assertEquals(2, event.value)
        assertEquals(900L, event.timestampNs)
        assertEquals(3, event.lines)
    }

    @Test
    fun fieldLineL5846ParsesExactly() {
        val event = CarSvcTelemetry.parseLockEvent(gunLine, 0x21403407)!!
        assertEquals(0, event.value)
        assertEquals(2, event.status)
        assertEquals(74475491501900L, event.timestampNs)
        assertEquals(1, event.lines)
    }

    @Test
    fun garbageOrOverlongTimestampIsNull() {
        val lf = CarSvcTelemetry.LF_LOCK_PROP_ID
        assertNull(CarSvcTelemetry.parseLockEvent(lockLine(lf, 2, 0, "abc"), lf)!!.timestampNs)
        assertNull(
            CarSvcTelemetry.parseLockEvent(
                lockLine(lf, 2, 0, "12345678901234567890"), lf
            )!!.timestampNs
        )
        assertEquals(
            1234567890123456789L,
            CarSvcTelemetry.parseTimestamp("x timestamp:1234567890123456789,zone")
        )
        assertNull(CarSvcTelemetry.parseTimestamp("no marker here"))
        assertNull(CarSvcTelemetry.parseTimestamp("timestamp:,zone"))
    }

    @Test
    fun describeLockPropertiesFormatsEveryDoorAndAbsent() {
        val text = listOf(
            lockLine(CarSvcTelemetry.LF_LOCK_PROP_ID, 2, 0, "5000000000"),
            lockLine(CarSvcTelemetry.RR_LOCK_PROP_ID, 1, null, "1000000000")
        ).joinToString("\n")
        val described = CarSvcTelemetry.describeLockProperties(text, 8_000_000_000L)
        assertEquals(
            "lf={v=2,status=0,ts=5000000000,ageS=3,lines=1} rf=absent lr=absent" +
                " rr={v=1,status=null,ts=1000000000,ageS=7,lines=1}",
            described
        )
        assertEquals(
            "lf=absent rf=absent lr=absent rr=absent",
            CarSvcTelemetry.describeLockProperties("", 0L)
        )
    }

    @Test
    fun cachedDumpFromHonoursSourceTtlAndExposesDuration() {
        val snapshot = CarSvcTelemetry.DumpSnapshot(
            "text", 1_000_000_000L, 1_350_000_000L, 10_000L
        )
        assertNull(CarSvcTelemetry.cachedDumpFrom(null, 10_000L))
        assertNull(CarSvcTelemetry.cachedDumpFrom(snapshot, 12_001L))
        val fresh = CarSvcTelemetry.cachedDumpFrom(snapshot, 12_000L)
        assertNotNull(fresh)
        assertEquals("text", fresh!!.text)
        assertEquals(10_000L, fresh.observedAtElapsedMs)
        assertEquals(350L, fresh.durationMs)
    }

    @Test
    fun parserHasNoLastKnownFallback() {
        val lf = CarSvcTelemetry.LF_LOCK_PROP_ID
        val withLf = lockLine(lf, 2, 0, "5")
        assertNotNull(CarSvcTelemetry.parseLockEvent(withLf, lf))
        val withoutLf = lockLine(CarSvcTelemetry.RF_LOCK_PROP_ID, 2, 0, "5")
        assertNull(CarSvcTelemetry.parseLockEvent(withoutLf, lf))
    }

    @Test
    fun dumpStatsArithmeticAndCounters() {
        assertEquals(
            "n=0 p50=0 p95=0 max=0 slowDiscarded=0 timeout=0 failed=0",
            CarSvcTelemetry.dumpStats()
        )
        for (ms in listOf(100L, 200L, 300L, 400L)) {
            CarSvcTelemetry.recordDumpOutcome(ms, CarSvcTelemetry.DumpOutcome.OK)
        }
        CarSvcTelemetry.recordDumpOutcome(2_500L, CarSvcTelemetry.DumpOutcome.SLOW_DISCARDED)
        CarSvcTelemetry.recordDumpOutcome(4_000L, CarSvcTelemetry.DumpOutcome.TIMEOUT)
        CarSvcTelemetry.recordDumpOutcome(0L, CarSvcTelemetry.DumpOutcome.FAILED)
        CarSvcTelemetry.recordDumpOutcome(7L, CarSvcTelemetry.DumpOutcome.FAILED)
        // Durations {100,200,300,400,2500}: nearest-rank p50 = 3rd = 300, p95 = 5th = 2500.
        assertEquals(
            "n=8 p50=300 p95=2500 max=2500 slowDiscarded=1 timeout=1 failed=2",
            CarSvcTelemetry.dumpStats()
        )
    }

    @Test
    fun ringIsBoundedAtSixtyFourAndEvictsTheOldest() {
        CarSvcTelemetry.recordDumpOutcome(9_999L, CarSvcTelemetry.DumpOutcome.TIMEOUT)
        for (i in 1..63) {
            CarSvcTelemetry.recordDumpOutcome(i.toLong(), CarSvcTelemetry.DumpOutcome.OK)
        }
        assertTrue(CarSvcTelemetry.dumpStats().startsWith("n=64 "))
        assertTrue(CarSvcTelemetry.dumpStats().contains("timeout=1"))
        // 65th record evicts the oldest slot (the TIMEOUT).
        CarSvcTelemetry.recordDumpOutcome(64L, CarSvcTelemetry.DumpOutcome.OK)
        val stats = CarSvcTelemetry.dumpStats()
        assertTrue(stats, stats.startsWith("n=64 "))
        assertTrue(stats, stats.contains("timeout=0"))
        assertTrue(stats, stats.contains("max=64"))
    }

    @Test
    fun cachedDumpNeverSpawnsAndEveryOutcomeRecordIsGuarded() {
        val source = readRepositoryFile("app/src/main/java/com/overdrive/app/byd/CarSvcTelemetry.kt")
        val start = source.indexOf("fun cachedDump(): CachedDump? =")
        assertTrue(start >= 0)
        val end = source.indexOf("internal fun cachedDumpFrom(", start)
        assertTrue(end > start)
        val body = source.substring(start, end)
        assertFalse(body.contains("dumpSnapshot("))
        assertTrue(body.contains("cachedDumpFrom(cachedDump, android.os.SystemClock.elapsedRealtime())"))

        // Every recordDumpOutcome call inside dumpSnapshot() sits in its own try/catch(Throwable).
        val dumpStart = source.indexOf("private fun dumpSnapshot(): DumpSnapshot? {")
        val dumpEnd = source.indexOf("private fun elapsedMs(", dumpStart)
        val dump = source.substring(dumpStart, dumpEnd)
        var at = dump.indexOf("recordDumpOutcome(")
        var calls = 0
        while (at >= 0) {
            calls++
            val before = dump.lastIndexOf("try {", at)
            val after = dump.indexOf("catch (_: Throwable)", at)
            assertTrue("recordDumpOutcome call #$calls not guarded", before >= 0 && after > at)
            at = dump.indexOf("recordDumpOutcome(", at + 1)
        }
        assertEquals(4, calls)
        assertTrue(dump.contains("DumpOutcome.TIMEOUT"))
        assertTrue(dump.contains("DumpOutcome.SLOW_DISCARDED"))
        assertTrue(dump.contains("DumpOutcome.OK"))
        assertTrue(dump.indexOf("DumpOutcome.FAILED") > dump.indexOf("} finally {"))
    }

    private fun readRepositoryFile(relative: String): String {
        var file = File(System.getProperty("user.dir"), relative)
        if (!file.isFile) file = File(File(System.getProperty("user.dir")).parentFile, relative)
        return file.readText()
    }
}
