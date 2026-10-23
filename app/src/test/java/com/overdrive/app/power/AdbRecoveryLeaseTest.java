package com.overdrive.app.power;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AdbRecoveryLeaseTest {

    private static final String BOOT = "boot-abc-123";

    @Test
    public void formatParseRoundTripForBothOwners() {
        for (AdbRecoveryLease.Owner owner : AdbRecoveryLease.Owner.values()) {
            String raw = AdbRecoveryLease.format(owner, BOOT, 10_000L);
            AdbRecoveryLease.Parsed parsed = AdbRecoveryLease.parse(raw, 10_000L, BOOT);
            assertEquals(AdbRecoveryLease.State.FRESH, parsed.state);
            assertEquals(owner, parsed.owner);
            assertEquals(0L, parsed.ageMs);
            assertEquals(10_000L, parsed.stampMs);
            assertTrue(parsed.isFresh(owner));
        }
    }

    @Test
    public void freshAppLeaseIsNotFreshForDaemon() {
        String raw = AdbRecoveryLease.format(AdbRecoveryLease.Owner.APP, BOOT, 1_000L);
        AdbRecoveryLease.Parsed parsed = AdbRecoveryLease.parse(raw, 1_000L, BOOT);
        assertTrue(parsed.isFresh(AdbRecoveryLease.Owner.APP));
        assertFalse(parsed.isFresh(AdbRecoveryLease.Owner.DAEMON));
    }

    @Test
    public void nullEmptyAndLiteralNullAreAbsent() {
        assertEquals(AdbRecoveryLease.State.ABSENT,
                AdbRecoveryLease.parse(null, 0L, BOOT).state);
        assertEquals(AdbRecoveryLease.State.ABSENT,
                AdbRecoveryLease.parse("", 0L, BOOT).state);
        assertEquals(AdbRecoveryLease.State.ABSENT,
                AdbRecoveryLease.parse("   ", 0L, BOOT).state);
        assertEquals(AdbRecoveryLease.State.ABSENT,
                AdbRecoveryLease.parse("null", 0L, BOOT).state);
    }

    @Test
    public void malformedShapesAreRejected() {
        assertEquals(AdbRecoveryLease.State.MALFORMED,
                AdbRecoveryLease.parse("app:boot", 0L, BOOT).state);        // 2 fields
        assertEquals(AdbRecoveryLease.State.MALFORMED,
                AdbRecoveryLease.parse("app:boot:1:2", 0L, BOOT).state);    // 4 fields
        assertEquals(AdbRecoveryLease.State.MALFORMED,
                AdbRecoveryLease.parse("robot:boot:1", 0L, BOOT).state);    // unknown owner
        assertEquals(AdbRecoveryLease.State.MALFORMED,
                AdbRecoveryLease.parse("app:boot:xyz", 0L, BOOT).state);    // non-numeric stamp
    }

    @Test
    public void ageBoundaryIsInclusiveAtTtl() {
        String raw = AdbRecoveryLease.format(AdbRecoveryLease.Owner.APP, BOOT, 0L);
        assertEquals(AdbRecoveryLease.State.FRESH,
                AdbRecoveryLease.parse(raw, AdbRecoveryLease.TTL_MS, BOOT).state);
        assertEquals(AdbRecoveryLease.State.EXPIRED,
                AdbRecoveryLease.parse(raw, AdbRecoveryLease.TTL_MS + 1L, BOOT).state);
    }

    @Test
    public void negativeAgeIsExpired() {
        String raw = AdbRecoveryLease.format(AdbRecoveryLease.Owner.APP, BOOT, 5_000L);
        assertEquals(AdbRecoveryLease.State.EXPIRED,
                AdbRecoveryLease.parse(raw, 4_000L, BOOT).state);
    }

    @Test
    public void differentBootIdIsExpired() {
        String raw = AdbRecoveryLease.format(AdbRecoveryLease.Owner.APP, BOOT, 1_000L);
        assertEquals(AdbRecoveryLease.State.EXPIRED,
                AdbRecoveryLease.parse(raw, 1_000L, "other-boot").state);
    }

    @Test
    public void sanitizeBootIdTrimsAndStrips() {
        assertEquals("abc-123", AdbRecoveryLease.sanitizeBootId("abc-123\n"));
        assertEquals("abc123def", AdbRecoveryLease.sanitizeBootId("abc:123/def"));
        assertEquals("", AdbRecoveryLease.sanitizeBootId(null));
    }
}
