package com.overdrive.app.surveillance;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class ClusterAdasPropertySamplerTest {
    private static ClusterAdasLifecycle.Snapshot active(long generation) {
        return new ClusterAdasLifecycle.Snapshot(
                new ClusterAdasLifecycle.Source(generation, true, true),
                new ClusterAdasLifecycle.Source(0, false, false));
    }
    private static ClusterAdasLifecycle.Snapshot idle(long generation) {
        return new ClusterAdasLifecycle.Snapshot(
                new ClusterAdasLifecycle.Source(generation, false, false),
                new ClusterAdasLifecycle.Source(0, false, false));
    }

    private static final class Fake implements ClusterAdasPropertySampler.Backend {
        volatile ClusterAdasLifecycle.Snapshot session = active(1);
        volatile boolean allowed = true;
        volatile long now;
        Integer power = 3, platform = 102;
        boolean failPower, failPlatform;
        Runnable duringPower, duringPlatform;
        int powerReads, platformReads;

        @Override public ClusterAdasLifecycle.Snapshot snapshot() { return session; }
        @Override public boolean canRead() { return allowed; }
        @Override public long nowMs() { return now; }
        @Override public Integer readPower() {
            powerReads++;
            if (duringPower != null) duringPower.run();
            if (failPower) throw new IllegalStateException("power getter");
            return power;
        }
        @Override public Integer readPlatform() {
            platformReads++;
            if (duringPlatform != null) duringPlatform.run();
            if (failPlatform) throw new IllegalStateException("platform getter");
            return platform;
        }
        @Override public void log(String message) { }
    }

    @Test public void platformSamplingHasItsOwnThreeSecondCadence() {
        Fake f = new Fake();
        ClusterAdasPropertySampler s = new ClusterAdasPropertySampler(f);
        s.tick();
        f.now = 1000; s.tick();
        f.now = 2000; s.tick();
        assertEquals(3, f.powerReads);
        assertEquals(1, f.platformReads);
        f.now = 3000; s.tick();
        assertEquals(4, f.powerReads);
        assertEquals(2, f.platformReads);
        assertTrue(s.state(f.session, f.now).maintain());
    }

    @Test public void unknownAndOutOfRangeValuesNeverBecomeReady() {
        for (Integer power : new Integer[] {null, -999, -1, 4, 315621418}) {
            Fake f = new Fake(); f.power = power;
            ClusterAdasPropertySampler s = new ClusterAdasPropertySampler(f);
            s.tick();
            assertFalse(s.state(f.session, f.now).ready());
            assertFalse(s.state(f.session, f.now).maintain());
        }
        for (Integer platform : new Integer[] {null, -999, -1, 256, 1087373328}) {
            Fake f = new Fake(); f.platform = platform;
            ClusterAdasPropertySampler s = new ClusterAdasPropertySampler(f);
            s.tick();
            assertTrue(s.state(f.session, f.now).ready());
            assertFalse(s.state(f.session, f.now).maintain());
        }
    }

    @Test public void invalidReadsReplaceTheCurrentCacheRatherThanKeepingLastGoodValues() {
        Fake f = new Fake();
        ClusterAdasPropertySampler s = new ClusterAdasPropertySampler(f);
        s.tick();
        assertTrue(s.state(f.session, f.now).maintain());
        f.now = 1000; f.power = null; s.tick();
        assertFalse(s.state(f.session, f.now).ready());
        f.now = 3000; f.power = 3; f.platform = null; s.tick();
        assertTrue(s.state(f.session, f.now).ready());
        assertFalse(s.state(f.session, f.now).maintain());
    }

    @Test public void failedGettersInvalidateOnlyTheirOwnProperty() {
        Fake f = new Fake();
        ClusterAdasPropertySampler s = new ClusterAdasPropertySampler(f);
        s.tick();
        f.now = 1000; f.failPower = true; s.tick();
        assertFalse(s.state(f.session, f.now).ready());
        assertEquals(102, s.state(f.session, f.now).platform);
        f.now = 3000; f.failPower = false; f.failPlatform = true; s.tick();
        assertTrue(s.state(f.session, f.now).ready());
        assertEquals(-1, s.state(f.session, f.now).platform);
    }

    @Test public void cachedValuesExpireIfThePropertyWorkerStopsReturning() {
        Fake f = new Fake();
        ClusterAdasPropertySampler s = new ClusterAdasPropertySampler(f);
        s.tick();
        assertTrue(s.state(f.session, 3000).ready());
        assertFalse(s.state(f.session, 3001).ready());
        assertEquals(102, s.state(f.session, 7000).platform);
        assertEquals(-1, s.state(f.session, 7001).platform);
        assertFalse(s.state(f.session, -1).ready());
    }

    @Test public void otherSessionsCannotConsumeThePreviousSessionsCache() {
        Fake f = new Fake();
        ClusterAdasPropertySampler s = new ClusterAdasPropertySampler(f);
        s.tick();
        f.session = active(2);
        assertFalse(s.state(f.session, f.now).ready());
        s.tick();
        assertTrue(s.state(f.session, f.now).maintain());
        assertEquals(2, f.platformReads); // A new session gets its own platform sample.
        f.session = idle(3);
        assertFalse(s.state(f.session, f.now).ready());
    }

    @Test public void sourceChangeOrPowerOffDuringTheGetterDiscardsTheSample() {
        for (boolean powerOff : new boolean[] {false, true}) {
            Fake f = new Fake();
            f.duringPower = () -> {
                if (powerOff) f.allowed = false;
                else f.session = active(2);
            };
            ClusterAdasPropertySampler s = new ClusterAdasPropertySampler(f);
            s.tick();
            assertFalse(s.state(f.session, f.now).ready());
            assertEquals(0, f.platformReads);
        }
    }

    @Test public void inactiveOrUnpoweredSourcesDoNotPerformNativeReads() {
        Fake f = new Fake(); f.allowed = false;
        ClusterAdasPropertySampler s = new ClusterAdasPropertySampler(f);
        s.tick();
        f.allowed = true; f.session = idle(1); s.tick();
        assertEquals(0, f.powerReads);
        assertEquals(0, f.platformReads);
    }

    @Test public void powerLossIsPublishedEvenWhileThePlatformGetterIsStalled() throws Exception {
        for (Integer lost : new Integer[] {0, null}) {
            Fake f = new Fake();
            ClusterAdasPropertySampler s = new ClusterAdasPropertySampler(f);
            s.tick();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            f.now = 3000; f.power = lost;
            f.duringPlatform = () -> {
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            };
            Thread reader = new Thread(s::tick);
            reader.setDaemon(true);
            reader.start();
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertFalse("A completed loss cannot be masked by a stalled platform read",
                        s.state(f.session, 4000).ready());
                assertFalse(s.state(f.session, 4000).maintain());
            } finally {
                release.countDown();
                reader.join(2000);
            }
            assertFalse(reader.isAlive());
        }
    }
}
