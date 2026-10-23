package com.overdrive.app.byd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarSvcTelemetryDumpFreshnessTest {
    private val offDump = """
        Power Mute State :
          previous : 2=PowerMode StartUp
          current : 4=PowerMode Standby
    """.trimIndent()

    @Test
    fun suspendedHeadUnitCannotReuseOffDespiteYoungNanoCache() {
        val cachedAtNanos = 1_000_000_000L
        assertFalse(
            CarSvcTelemetry.canReuseDump(
                cachedAtNanos + 100_000_000L, cachedAtNanos,
                3_610_000L, 10_000L
            )
        )
        assertNull(CarSvcTelemetry.freshPowerModeLine(offDump, 10_000L) { 3_610_000L })
    }

    @Test
    fun sourceAgeStartsAtDumpStartEvenWhenCacheWasJustWritten() {
        assertFalse(
            CarSvcTelemetry.canReuseDump(
                8_000_000_000L, 8_000_000_000L,
                14_000L, 10_000L
            )
        )
        assertNull(CarSvcTelemetry.freshPowerModeLine(offDump, 10_000L) { 14_000L })
    }

    @Test
    fun elapsedSourceBoundaryIsAcceptedAndNextMillisecondIsUnknown() {
        assertTrue(CarSvcTelemetry.dumpSourceFresh(12_000L, 10_000L))
        assertEquals(
            "current : 4=PowerMode Standby",
            CarSvcTelemetry.freshPowerModeLine(offDump, 10_000L) { 12_000L }
        )
        assertFalse(CarSvcTelemetry.dumpSourceFresh(12_001L, 10_000L))
        assertNull(CarSvcTelemetry.freshPowerModeLine(offDump, 10_000L) { 12_001L })
    }

    @Test
    fun cacheKeepsOriginalNanoTtlWithoutExtendingSourceTtl() {
        val cachedAtNanos = 1_000_000_000L
        assertTrue(
            CarSvcTelemetry.canReuseDump(
                cachedAtNanos + 1_999_000_000L, cachedAtNanos,
                11_999L, 10_000L
            )
        )
        assertFalse(
            CarSvcTelemetry.canReuseDump(
                cachedAtNanos + 2_000_000_000L, cachedAtNanos,
                12_000L, 10_000L
            )
        )
        // Completion was only a millisecond ago, but the source is already too old.
        assertFalse(
            CarSvcTelemetry.canReuseDump(
                cachedAtNanos + 1_000_000L, cachedAtNanos,
                12_001L, 10_000L
            )
        )
    }

    @Test
    fun regressedElapsedClockAndFutureSourcesNeverProveOff() {
        assertFalse(CarSvcTelemetry.dumpSourceFresh(9_999L, 10_000L))
        assertNull(CarSvcTelemetry.freshPowerModeLine(offDump, 10_000L) { 9_999L })
        assertFalse(
            CarSvcTelemetry.canReuseDump(
                1_100_000_000L, 1_000_000_000L, 9_999L, 10_000L
            )
        )
    }

    @Test
    fun regressedNanoClockCannotReuseEvenFreshElapsedSource() {
        assertFalse(
            CarSvcTelemetry.canReuseDump(
                999_999_999L, 1_000_000_000L, 10_100L, 10_000L
            )
        )
    }

    @Test
    fun invalidAndExtremeElapsedTimesCannotOverflowIntoFreshOff() {
        for ((observed, now) in listOf(
            -1L to 0L,
            Long.MIN_VALUE to Long.MAX_VALUE,
            0L to -1L,
            0L to Long.MAX_VALUE,
            Long.MAX_VALUE to 0L
        )) {
            assertFalse(CarSvcTelemetry.dumpSourceFresh(now, observed))
            assertNull(CarSvcTelemetry.freshPowerModeLine(offDump, observed) { now })
        }
    }

    @Test
    fun bootZeroAndFreshOnRemainValidWithoutInventingMissingPowerMode() {
        assertTrue(CarSvcTelemetry.dumpSourceFresh(0L, 0L))
        assertEquals(
            "Power Mute State : 2=PowerMode StartUp",
            CarSvcTelemetry.freshPowerModeLine(
                "Power Mute State : 2=PowerMode StartUp", 10_000L
            ) { 10_050L }
        )
        assertNull(CarSvcTelemetry.freshPowerModeLine(null, 10_000L) { 10_050L })
        assertNull(CarSvcTelemetry.freshPowerModeLine("no power row", 10_000L) { 10_050L })
    }
}
