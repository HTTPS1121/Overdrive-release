package com.overdrive.app.surveillance;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.Test;

public class MotionResultLayoutTest {
    private static int[] currentGeometry() {
        return new int[] {2, 320, 240, 32, 10, 8, 7, 52};
    }

    private static ByteBuffer packet(int cells, int prefix, boolean shadow) {
        int bytes = prefix + cells * 4;
        ByteBuffer buffer = ByteBuffer.allocate(bytes * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int q = 0; q < 4; q++) {
            int start = buffer.position();
            buffer.put((byte) (q % 2));
            buffer.put(new byte[] {11, 12, 13});
            buffer.putInt(q + 1);
            buffer.putInt(q + 2);
            buffer.putInt(q + 3);
            buffer.putInt(q + 4);
            buffer.putFloat(q + .25f);
            buffer.putFloat(q + .75f);
            buffer.putFloat(q + 30f);
            buffer.put((byte) 1);
            buffer.put((byte) (shadow ? 1 : 99));
            buffer.put(new byte[] {14, 15});
            if (prefix >= 44) {
                buffer.putFloat(q + .5f);
                buffer.putFloat(q + 2.5f);
            }
            if (prefix == 52) {
                buffer.putInt(q + 7);
                buffer.putInt(q + 8);
            }
            assertEquals(prefix, buffer.position() - start);
            for (int i = 0; i < cells; i++) buffer.putFloat(q * 1000f + i + .125f);
            assertEquals(bytes, buffer.position() - start);
        }
        return (ByteBuffer) buffer.flip();
    }

    private static void assertPackets(MotionResultLayout layout, int prefix, boolean shadow) {
        ByteBuffer buffer = packet(layout.blockCount, prefix, shadow);
        MotionResultLayout.Result result = new MotionResultLayout.Result();
        for (int q = 0; q < 4; q++) {
            Arrays.fill(result.blockConfidence, -99f);
            layout.readQuadrant(buffer, result);
            assertEquals(q % 2 != 0, result.motionDetected);
            assertEquals(q + 1, result.threatLevel);
            assertEquals(q + 2, result.activeBlocks);
            assertEquals(q + 3, result.confirmedBlocks);
            assertEquals(q + 4, result.componentSize);
            assertEquals(q + .25f, result.centroidX, 0f);
            assertEquals(q + .75f, result.centroidY, 0f);
            assertEquals(q + 30f, result.meanLuma, 0f);
            assertTrue(result.brightnessSuppressed);
            assertEquals(shadow, result.shadowFiltered);
            assertEquals(prefix >= 44 ? q + .5f : -1f, result.flowCoherence, 0f);
            assertEquals(prefix >= 44 ? q + 2.5f : -1f, result.netDriftBlocks, 0f);
            assertEquals(prefix == 52 ? q + 7 : 0, result.componentCount);
            assertEquals(prefix == 52 ? q + 8 : 0, result.salienceState);
            assertEquals(layout.blockCount, result.nativeBlockCount);
            assertEquals(layout.coveredHeight, result.coveredHeight);
            for (int i = 0; i < layout.blockCount; i++) {
                assertEquals(q * 1000f + i + .125f, result.blockConfidence[i], 0f);
            }
            for (int i = layout.blockCount; i < 80; i++) {
                assertEquals(0f, result.blockConfidence[i], 0f);
            }
            assertEquals((q + 1) * layout.resultSize, buffer.position());
        }
        assertFalse(buffer.hasRemaining());
    }

    @Test
    public void currentPacketsDecodeAllFourQuadrantsAtExactBoundaries() {
        MotionResultLayout layout = MotionResultLayout.resolve(100, 372, currentGeometry());
        assertEquals(80, layout.blockCount);
        assertEquals(240, layout.coveredHeight);
        assertPackets(layout, 52, true);
    }

    @Test
    public void legacySaliencePacketsDoNotConsumeTheNextQuadrantsConfidence() {
        MotionResultLayout layout = MotionResultLayout.resolve(100, 332, null);
        assertEquals(70, layout.blockCount);
        assertEquals(224, layout.coveredHeight);
        assertPackets(layout, 52, true);
    }

    @Test
    public void knownLegacyCapabilityTiersDecodeWithUnavailableSentinels() {
        assertPackets(MotionResultLayout.resolve(60, 316, null), 36, false);
        assertPackets(MotionResultLayout.resolve(76, 316, null), 36, true);
        assertPackets(MotionResultLayout.resolve(88, 324, null), 44, true);
    }

    @Test
    public void descriptorAndPayloadMustAgreeBeforeAnyDecode() {
        rejected(100, 372, null);
        rejected(100, 332, currentGeometry());
        rejected(100, 368, currentGeometry());
        rejected(100, 376, currentGeometry());
        rejected(76, 372, currentGeometry());
        rejected(76, 332, null);
        rejected(99, 332, null);
        rejected(100, 336, null);
    }

    @Test
    public void malformedDescriptorsAreNotGuessedFromBufferSize() {
        rejected(100, 372, new int[0]);
        for (int index : new int[] {0, 1, 2, 3, 4, 5, 6, 7}) {
            int[] geometry = currentGeometry();
            geometry[index]++;
            rejected(100, 372, geometry);
        }
    }

    @Test
    public void truncatedPayloadIsRejectedWithoutPublishingPartialData() {
        MotionResultLayout layout = MotionResultLayout.resolve(100, 372, currentGeometry());
        ByteBuffer buffer = ByteBuffer.allocate(371).order(ByteOrder.LITTLE_ENDIAN);
        MotionResultLayout.Result result = new MotionResultLayout.Result();
        result.activeBlocks = 42;
        try {
            layout.readQuadrant(buffer, result);
            fail("Truncated result must be rejected");
        } catch (IllegalArgumentException expected) {}
        assertEquals(0, buffer.position());
        assertEquals(42, result.activeBlocks);
    }

    @Test
    public void shortDestinationMaskCannotOverrunTheResultArray() {
        MotionResultLayout layout = MotionResultLayout.resolve(100, 372, currentGeometry());
        MotionResultLayout.Result result = new MotionResultLayout.Result();
        result.blockConfidence = new float[70];
        ByteBuffer buffer = packet(80, 52, true);
        try {
            layout.readQuadrant(buffer, result);
            fail("Short destination must be rejected");
        } catch (IllegalArgumentException expected) {}
        assertEquals(0, buffer.position());
    }

    private static void rejected(int config, int bytes, int[] descriptor) {
        try {
            MotionResultLayout.resolve(config, bytes, descriptor);
            fail("Unsupported native layout must be rejected");
        } catch (IllegalArgumentException expected) {}
    }
}
