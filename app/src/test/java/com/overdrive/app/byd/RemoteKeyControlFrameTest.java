package com.overdrive.app.byd;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.overdrive.app.logging.DaemonLogger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Exact pre-change packet fixtures, plus dynamic-vs-fixed command dispatch through an injected
 * fake vendor manager (reflection on a plain object — no Android {@code Context} required).
 */
public class RemoteKeyControlFrameTest {
    private static final byte[] BASE = {
            0x5E, 0x02, 0x14, 0x00, (byte) 0xFE, 0x14, 0x2E, (byte) 0x82, 0x5C, (byte) 0xF1,
            0x78, 0x50, 0x44, (byte) 0xA2, (byte) 0xB9, (byte) 0xCA, 0x15, (byte) 0xA3,
            (byte) 0xEE, (byte) 0xBC, (byte) 0xD4, 0x7B, 0x00, 0x00, 0x00, 0x00
    };

    private DaemonLogger.Config previousLogConfig;

    @Before public void muteLogger() {
        previousLogConfig = DaemonLogger.getConfig();
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false).withFileLog(false).withStdoutLog(false));
    }

    @After public void restoreLogger() {
        DaemonLogger.configure(previousLogConfig);
    }

    @Test public void everyExistingCommandRetainsTheExactVendorFrame() {
        for (int code : new int[] {1, 5, 7, 9, 10, 22}) {
            byte[] expected = BASE.clone();
            expected[22] = (byte) code;
            assertArrayEquals(expected, RemoteKeyControl.buildFrame(code));
        }
        assertEquals(1, RemoteKeyControl.CODE_POWER_ON);
        assertEquals(5, RemoteKeyControl.CODE_UNLOCK);
        assertEquals(7, RemoteKeyControl.CODE_LOCK);
        assertEquals(9, RemoteKeyControl.CODE_POWER_OFF);
        assertEquals(10, RemoteKeyControl.CODE_FIND_CAR);
        assertEquals(22, RemoteKeyControl.CODE_FLASH_LIGHTS);
    }

    @Test public void frameMutationCannotAffectAnotherCommandOrTheTemplate() {
        byte[] previous = RemoteKeyControl.buildFrame(7);
        previous[0] = 0;
        previous[22] = 0;
        byte[] current = BASE.clone();
        current[22] = 5;
        assertArrayEquals(current, RemoteKeyControl.buildFrame(5));
    }

    @Test public void vendorDeviceFeatureAndCodeOffsetAreUnchanged() throws Exception {
        assertEquals(1034, field("DEVICE_REMOTE_KEY"));
        assertEquals(0xAA000004, field("FID_REMOTE_KEY_COMMAND"));
        assertEquals(22, field("CODE_OFFSET"));
    }

    @Test public void onlyTheExactDynamicStringSelectsTheDynamicFormat() {
        assertEquals("fixed", RemoteKeyControl.normalizeFrameFormat(null));
        assertEquals("fixed", RemoteKeyControl.normalizeFrameFormat(""));
        assertEquals("fixed", RemoteKeyControl.normalizeFrameFormat("fixed"));
        assertEquals("fixed", RemoteKeyControl.normalizeFrameFormat("garbage"));
        assertEquals("fixed", RemoteKeyControl.normalizeFrameFormat("Dynamic"));
        assertEquals("fixed", RemoteKeyControl.normalizeFrameFormat("DYNAMIC"));
        assertEquals("fixed", RemoteKeyControl.normalizeFrameFormat(" dynamic "));
        assertEquals("dynamic", RemoteKeyControl.normalizeFrameFormat("dynamic"));
    }

    @Test public void dynamicFormatDispatchesTheSeventyThreeByteFrameAndAcceptsRcZero()
            throws Exception {
        // 37-byte seed and 21-byte key: each is exactly four bytes over the expected length, so
        // normalizeBuffer strips the 4-byte prefix down to 33 / 17 before the frame is built.
        FakeManager mgr = new FakeManager(sequentialBytes(37), sequentialBytes(21), 0);
        byte[] frame = RemoteKeyControl.resolveFrame(mgr, RemoteKeyControl.CODE_UNLOCK, true);
        assertNotNull(frame);
        assertEquals(73, frame.length);
        Method setBuffer = mgr.getClass().getMethod("setBuffer", int.class, int.class, byte[].class);
        assertTrue(RemoteKeyControl.writeFrameToManager(
                mgr, setBuffer, RemoteKeyControl.CODE_UNLOCK, frame));
        assertEquals(1, mgr.setBufferCalls);
        assertEquals(1034, mgr.lastDevice);
        assertEquals(0xAA000004, mgr.lastFeature);
        assertEquals(73, mgr.lastFrame.length);
    }

    @Test public void shortDynamicSeedRefusesWithoutCallingSetBufferOrFallingBackToFixed()
            throws Exception {
        // Seed is 20 bytes — neither the exact 33 nor 33+4 — so it cannot normalize: an ordinary
        // refusal (null frame), never a silent downgrade to the 26-byte fixed frame.
        FakeManager mgr = new FakeManager(sequentialBytes(20), sequentialBytes(21), 0);
        byte[] frame = RemoteKeyControl.resolveFrame(mgr, RemoteKeyControl.CODE_UNLOCK, true);
        assertNull(frame);
        assertEquals(0, mgr.setBufferCalls);
    }

    @Test public void fixedFormatDispatchesTheExactTwentySixByteFrame() throws Exception {
        FakeManager mgr = new FakeManager(sequentialBytes(37), sequentialBytes(21), 0);
        for (int code : new int[] {1, 5, 7, 9, 10, 22}) {
            byte[] expected = BASE.clone();
            expected[22] = (byte) code;
            byte[] frame = RemoteKeyControl.resolveFrame(mgr, code, false);
            assertEquals(26, frame.length);
            assertArrayEquals(expected, frame);
        }
        byte[] lock = RemoteKeyControl.resolveFrame(mgr, RemoteKeyControl.CODE_LOCK, false);
        Method setBuffer = mgr.getClass().getMethod("setBuffer", int.class, int.class, byte[].class);
        assertTrue(RemoteKeyControl.writeFrameToManager(
                mgr, setBuffer, RemoteKeyControl.CODE_LOCK, lock));
        assertEquals(1, mgr.setBufferCalls);
        assertEquals(26, mgr.lastFrame.length);
        assertEquals(1034, mgr.lastDevice);
        assertEquals(0xAA000004, mgr.lastFeature);
    }

    private static byte[] sequentialBytes(int length) {
        byte[] b = new byte[length];
        for (int i = 0; i < length; i++) {
            b[i] = (byte) (i + 1);
        }
        return b;
    }

    /** Minimal stand-in for {@code BYDAutoManager}: public so reflection can invoke its methods. */
    public static final class FakeManager {
        private final byte[] seed;
        private final byte[] key;
        private final int rc;
        int setBufferCalls;
        int lastDevice;
        int lastFeature;
        byte[] lastFrame;

        FakeManager(byte[] seed, byte[] key, int rc) {
            this.seed = seed;
            this.key = key;
            this.rc = rc;
        }

        public byte[] getBuffer(int device, int feature) {
            // Documented dynamic-frame feeds: seed on 1034/0x99000005, key on 1027/0x9900021a.
            if (device == 1034 && feature == 0x99000005) return seed == null ? null : seed.clone();
            if (device == 1027 && feature == 0x9900021a) return key == null ? null : key.clone();
            return null;
        }

        public int setBuffer(int device, int feature, byte[] frame) {
            setBufferCalls++;
            lastDevice = device;
            lastFeature = feature;
            lastFrame = frame == null ? null : frame.clone();
            return rc;
        }
    }

    private static int field(String name) throws Exception {
        Field field = RemoteKeyControl.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }
}
