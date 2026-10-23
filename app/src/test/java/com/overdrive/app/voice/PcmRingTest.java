package com.overdrive.app.voice;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * available() is what the post-wake listen budget is anchored on: it must agree exactly with what
 * read() will hand out, including the cap after the producer lapped the consumer.
 */
public class PcmRingTest {

    @Test
    public void availableIsWrittenMinusRead() {
        PcmRing ring = new PcmRing(16000);
        ring.write(new short[3000], 0, 3000);
        ring.read(new short[1024], 0, 1024);
        assertEquals(1976, ring.available());
    }

    @Test
    public void availableIsZeroAfterSkipToLatest() {
        PcmRing ring = new PcmRing(16000);
        ring.write(new short[3000], 0, 3000);
        ring.skipToLatest();
        assertEquals(0, ring.available());
    }

    @Test
    public void availableIsBoundedByCapacityAfterALap() {
        PcmRing ring = new PcmRing(1024);
        ring.write(new short[5000], 0, 5000);
        assertEquals(1024, ring.available());
        assertEquals(1024, ring.read(new short[5000], 0, 5000));
    }

    @Test
    public void availableAgreesWithRead() {
        PcmRing ring = new PcmRing(4096);
        ring.write(new short[3000], 0, 3000);
        ring.read(new short[500], 0, 500);
        ring.write(new short[2000], 0, 2000);
        int expected = ring.available();
        int total = 0;
        short[] dst = new short[700];
        int n;
        while ((n = ring.read(dst, 0, dst.length)) > 0) total += n;
        assertEquals(expected, total);
        assertEquals(0, ring.available());
    }
}
