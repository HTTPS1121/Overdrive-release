package com.overdrive.app.byd;

import static org.junit.Assert.*;

import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import org.junit.Test;

/**
 * Vectors are produced by an INDEPENDENT oracle that shares no code with the builder:
 * {@code .agents/tasks/remote-key-mcu-gate-dynamic-frame-20261019/oracle/dynamic_frame_oracle.py}
 * (Python, hand-written CRC16, DES via the system openssl/LibreSSL). The expected ciphertexts
 * below are its output; the structural checks additionally DECRYPT the built frame and verify the
 * documented layout, so a shared mistake in the Java builder cannot pass unnoticed.
 */
public class RemoteKeyDynamicFrameTest {

    private static final byte[] SEED33 =
            hex("000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20");
    private static final byte[] KEY17 = hex("808182838485868788898A8B8C8D8E8F90");
    private static final byte[] UUID16 = hex("A0A1A2A3A4A5A6A7A8A9AAABACADAEAF");
    private static final long EPOCH = 1700000000L;

    // Oracle ciphertexts (uuid present), keyed by command code.
    private static final String C1  = "5E1112131415161718191A1B1C1D1E1F20EE20799EEDA99C148E05899617871A9"
            + "0DF7B65E2E889D9109A48F19248010C8D4520F6F77ED2D29837FE2937C9BFA1898B658AA534BBDB45";
    private static final String C5  = "5E1112131415161718191A1B1C1D1E1F20EE20799EEDA99C148E05899617871A9"
            + "0DF7B65E2E889D9109A48F19248010C8D4520F6F77ED2D29837FE2937C9BFA189FEBA62C42289F973";
    private static final String C7  = "5E1112131415161718191A1B1C1D1E1F20EE20799EEDA99C148E05899617871A9"
            + "0DF7B65E2E889D9109A48F19248010C8D4520F6F77ED2D29837FE2937C9BFA189C6A357EC7CAC928F";
    private static final String C9  = "5E1112131415161718191A1B1C1D1E1F20EE20799EEDA99C148E05899617871A9"
            + "0DF7B65E2E889D9109A48F19248010C8D4520F6F77ED2D29837FE2937C9BFA1896CFE384E13E552B1";
    private static final String C10 = "5E1112131415161718191A1B1C1D1E1F20EE20799EEDA99C148E05899617871A9"
            + "0DF7B65E2E889D9109A48F19248010C8D4520F6F77ED2D29837FE2937C9BFA18940A62C031606714E";
    private static final String C22 = "5E1112131415161718191A1B1C1D1E1F20EE20799EEDA99C148E05899617871A9"
            + "0DF7B65E2E889D9109A48F19248010C8D4520F6F77ED2D29837FE2937C9BFA189583D32661AECF6CE";
    private static final String C5_NULL_UUID = "5E1112131415161718191A1B1C1D1E1F20EE20799EEDA99C148E0589961"
            + "7871A90DF7B65E2E889D9109A48F19248010C8DE90F35044A9D54A7EA2487AEC5896AD9B4787308C94646DA";

    @Test public void buildMatchesTheOracleForEveryGenericCodeWithUuid() {
        assertArrayEquals(hex(C1), RemoteKeyDynamicFrame.build(SEED33, KEY17, EPOCH, UUID16, 1));
        assertArrayEquals(hex(C5), RemoteKeyDynamicFrame.build(SEED33, KEY17, EPOCH, UUID16, 5));
        assertArrayEquals(hex(C7), RemoteKeyDynamicFrame.build(SEED33, KEY17, EPOCH, UUID16, 7));
        assertArrayEquals(hex(C9), RemoteKeyDynamicFrame.build(SEED33, KEY17, EPOCH, UUID16, 9));
        assertArrayEquals(hex(C10), RemoteKeyDynamicFrame.build(SEED33, KEY17, EPOCH, UUID16, 10));
        assertArrayEquals(hex(C22), RemoteKeyDynamicFrame.build(SEED33, KEY17, EPOCH, UUID16, 22));
    }

    @Test public void buildMatchesTheOracleWithNoUuid() {
        byte[] frame = RemoteKeyDynamicFrame.build(SEED33, KEY17, EPOCH, null, 5);
        assertArrayEquals(hex(C5_NULL_UUID), frame);
        assertEquals(73, frame.length);
    }

    @Test public void everyFrameIs73BytesAndStartsWith0x5E() {
        for (int code : new int[] {1, 5, 7, 9, 10, 22}) {
            byte[] frame = RemoteKeyDynamicFrame.build(SEED33, KEY17, EPOCH, UUID16, code);
            assertEquals(73, frame.length);
            assertEquals((byte) 0x5E, frame[0]);
        }
    }

    /** Decrypt the seven DES blocks and verify the documented plaintext layout and command byte. */
    @Test public void decryptingRecoversTheDocumentedLayoutAndCommand() throws Exception {
        for (int code : new int[] {1, 5, 7, 9, 10, 22}) {
            byte[] plain = decryptLayout(
                    RemoteKeyDynamicFrame.build(SEED33, KEY17, EPOCH, UUID16, code));

            assertEquals("command at byte 66", (byte) code, plain[66]);
            // Fixed template bytes survive into the plaintext.
            assertArrayEquals(new byte[] {0x27, 0x08, 0x02, 0x14, (byte) 0xFE},
                    Arrays.copyOfRange(plain, 17, 22));
            assertArrayEquals(new byte[] {0x01, 0x03}, Arrays.copyOfRange(plain, 40, 42));
            assertEquals((byte) 0x01, plain[72]);
            // Live fields.
            assertArrayEquals(Arrays.copyOfRange(SEED33, 17, 33), Arrays.copyOfRange(plain, 1, 17));
            assertArrayEquals(KEY17, Arrays.copyOfRange(plain, 23, 40));
            assertArrayEquals(new byte[] {0x65, 0x53, (byte) 0xF1, 0x00},
                    Arrays.copyOfRange(plain, 46, 50));
            assertArrayEquals(UUID16, Arrays.copyOfRange(plain, 50, 66));
            // CRC16 over 17..69 of the recovered plaintext, big-endian at 70..71.
            int crc = RemoteKeyDynamicFrame.crc16(plain, 17, 69);
            assertEquals((byte) (crc >> 8), plain[70]);
            assertEquals((byte) crc, plain[71]);
        }
    }

    @Test public void nullUuidLeavesTheTemplateUuidRegionInThePlaintext() throws Exception {
        byte[] plain = decryptLayout(RemoteKeyDynamicFrame.build(SEED33, KEY17, EPOCH, null, 5));
        // Template bytes 50..65, not the UUID.
        assertArrayEquals(
                hex("6F52DE4AEF284BC3B30A481613EDD682"), Arrays.copyOfRange(plain, 50, 66));
    }

    @Test public void bytesBeforeTheFirstDesBlockAreLeftInClear() {
        byte[] frame = RemoteKeyDynamicFrame.build(SEED33, KEY17, EPOCH, UUID16, 7);
        assertEquals((byte) 0x5E, frame[0]);
        // Bytes 1..16 are seed[17..32] in the clear (the first DES block starts at offset 17).
        assertArrayEquals(Arrays.copyOfRange(SEED33, 17, 33), Arrays.copyOfRange(frame, 1, 17));
    }

    /** Only the final DES block (offset 65, covering the command + CRC) differs between codes. */
    @Test public void onlyTheFinalBlockChangesWithTheCommand() {
        byte[] lock = RemoteKeyDynamicFrame.build(SEED33, KEY17, EPOCH, UUID16, 7);
        byte[] unlock = RemoteKeyDynamicFrame.build(SEED33, KEY17, EPOCH, UUID16, 5);
        assertArrayEquals(Arrays.copyOfRange(lock, 0, 65), Arrays.copyOfRange(unlock, 0, 65));
        assertFalse(Arrays.equals(Arrays.copyOfRange(lock, 65, 73),
                Arrays.copyOfRange(unlock, 65, 73)));
    }

    @Test public void crc16MatchesTheIndependentlyComputedVector() {
        byte[] sample = hex("27080214FE000102030405060708090A0B0C0D0E0F10111213141516171819"
                + "1A1B1C1D1E1F202122232425262728292A2B2C2D2E2F");
        assertEquals(0x359E, RemoteKeyDynamicFrame.crc16(sample, 0, sample.length - 1));
    }

    @Test public void normalizerAcceptsExactLengthAndStripsFourLeadingBytes() {
        byte[] exact = new byte[33];
        for (int i = 0; i < 33; i++) exact[i] = (byte) i;
        assertSame(exact, RemoteKeyDynamicFrame.normalizeBuffer(exact, 33));

        byte[] padded = new byte[37];
        for (int i = 0; i < 37; i++) padded[i] = (byte) i;
        byte[] stripped = RemoteKeyDynamicFrame.normalizeBuffer(padded, 33);
        assertEquals(33, stripped.length);
        assertEquals((byte) 4, stripped[0]);
        assertEquals((byte) 36, stripped[32]);
    }

    @Test public void normalizerRejectsShortNullAndOtherLengths() {
        assertNull(RemoteKeyDynamicFrame.normalizeBuffer(null, 33));
        assertNull(RemoteKeyDynamicFrame.normalizeBuffer(new byte[32], 33));
        assertNull(RemoteKeyDynamicFrame.normalizeBuffer(new byte[34], 33));
        assertNull(RemoteKeyDynamicFrame.normalizeBuffer(new byte[38], 33));
        assertNull(RemoteKeyDynamicFrame.normalizeBuffer(new byte[0], 33));
    }

    @Test public void buildRejectsWrongLengthSeedOrKey() {
        try {
            RemoteKeyDynamicFrame.build(new byte[32], KEY17, EPOCH, UUID16, 5);
            fail("expected IllegalArgumentException for short seed");
        } catch (IllegalArgumentException expected) { /* ok */ }
        try {
            RemoteKeyDynamicFrame.build(SEED33, new byte[16], EPOCH, UUID16, 5);
            fail("expected IllegalArgumentException for short key");
        } catch (IllegalArgumentException expected) { /* ok */ }
    }

    /** Decrypt the seven 8-byte blocks in place with DES key seed[1..8]; bytes 0..16 pass through. */
    private static byte[] decryptLayout(byte[] frame) throws Exception {
        Cipher cipher = Cipher.getInstance("DES/ECB/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(Arrays.copyOfRange(SEED33, 1, 9), "DES"));
        byte[] out = frame.clone();
        for (int offset : new int[] {17, 25, 33, 41, 49, 57, 65}) {
            byte[] block = cipher.doFinal(frame, offset, 8);
            System.arraycopy(block, 0, out, offset, 8);
        }
        return out;
    }

    private static byte[] hex(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
