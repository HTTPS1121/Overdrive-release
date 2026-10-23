package com.overdrive.app.surveillance;

import static org.junit.Assert.*;

import java.util.Arrays;
import org.junit.Test;

public class MotionGridGeometryTest {
    @Test
    public void partialRowHasPhysicalBoundsAndCompatibleCentroidCoordinates() {
        assertEquals(80, MotionGridGeometry.TOTAL_BLOCKS);
        assertEquals(70, MotionGridGeometry.POLICY_BLOCKS);
        assertEquals(7, MotionGridGeometry.POLICY_ROWS);
        for (int row = 0; row < 7; row++) {
            assertEquals(row * 32, MotionGridGeometry.rowTop(row));
            assertEquals((row + 1) * 32, MotionGridGeometry.rowBottom(row));
            assertEquals(row, MotionGridGeometry.rowCenterBlocks(row), 0f);
            assertEquals(row, MotionGridGeometry.policyRow(row));
        }
        assertEquals(224, MotionGridGeometry.rowTop(7));
        assertEquals(240, MotionGridGeometry.rowBottom(7));
        assertEquals(232f, MotionGridGeometry.rowCenterPixels(7), 0f);
        assertEquals(6.75f, MotionGridGeometry.rowCenterBlocks(7), 0f);
        assertEquals(6, MotionGridGeometry.policyRow(7));
        assertEquals(-1, MotionGridGeometry.policyRow(-1));
    }

    @Test
    public void legacyMasksKeepEveryOldCellAndInheritLastRowExclusions() {
        for (int q = 0; q < 4; q++) {
            byte[] legacy = new byte[70];
            for (int i = 0; i < legacy.length; i++) legacy[i] = (byte) ((i + q) % 3 == 0 ? 0 : 1);
            byte[] before = legacy.clone();
            byte[] current = MotionGridGeometry.migrateRoiMask(legacy);
            assertArrayEquals(before, legacy);
            assertArrayEquals(before, Arrays.copyOf(current, 70));
            for (int col = 0; col < 10; col++) assertEquals(legacy[60 + col], current[70 + col]);
            current[0] ^= 1;
            assertArrayEquals(before, legacy);
        }
    }

    @Test
    public void emptyAndFullLegacySelectionsKeepTheirIntent() {
        byte[] empty = new byte[70];
        assertArrayEquals(new byte[80], MotionGridGeometry.migrateRoiMask(empty));
        byte[] full = new byte[70];
        Arrays.fill(full, (byte) 1);
        byte[] expected = new byte[80];
        Arrays.fill(expected, (byte) 1);
        assertArrayEquals(expected, MotionGridGeometry.migrateRoiMask(full));
    }

    @Test
    public void currentPartialRowEditsAreNotOverwrittenByMigration() {
        byte[] current = new byte[80];
        Arrays.fill(current, 0, 70, (byte) 1);
        byte[] migrated = MotionGridGeometry.migrateRoiMask(current);
        assertArrayEquals(current, migrated);
        assertNotSame(current, migrated);
    }

    @Test
    public void malformedLengthsAreRejectedWithoutReadingPastTheMask() {
        assertNull(MotionGridGeometry.migrateRoiMask(null));
        for (int length : new int[] {0, 1, 69, 71, 79, 81, 800}) {
            assertNull(MotionGridGeometry.migrateRoiMask(new byte[length]));
        }
        try {
            MotionGridGeometry.nativeRoiMask(new byte[79], 80);
            fail("Malformed mask must not be adapted");
        } catch (IllegalArgumentException expected) {}
        try {
            MotionGridGeometry.nativeRoiMask(new byte[80], 79);
            fail("Unknown native geometry must not be guessed");
        } catch (IllegalArgumentException expected) {}
        assertNull(MotionGridGeometry.nativeRoiMask(null, 70));
    }

    @Test
    public void oldAndNewNativeMasksShareTheSameOriginalCells() {
        byte[] legacy = new byte[70];
        for (int i = 0; i < 70; i++) legacy[i] = (byte) (i % 2);
        byte[] current = MotionGridGeometry.migrateRoiMask(legacy);
        assertArrayEquals(legacy, MotionGridGeometry.nativeRoiMask(current, 70));
        assertArrayEquals(current, MotionGridGeometry.nativeRoiMask(legacy, 80));
        assertArrayEquals(current, MotionGridGeometry.nativeRoiMask(current, 80));
    }

    @Test
    public void newPolygonsUseThePartialRowsActualPixelCenter() {
        float[][] bottom = {{0f, 224f / 240f}, {1f, 224f / 240f}, {1f, 1f}, {0f, 1f}};
        byte[] physical = MotionGridGeometry.polygonMask(bottom, false);
        assertArrayEquals(new byte[70], Arrays.copyOf(physical, 70));
        for (int col = 0; col < 10; col++) assertEquals(1, physical[70 + col]);
        // The old normalized row-6 center was above this polygon. A saved
        // polygon must keep those exclusions and extend them into the new row.
        assertArrayEquals(new byte[80], MotionGridGeometry.polygonMask(bottom, true));
    }

    @Test
    public void untaggedPolygonRetainsOriginalCoveredCells() {
        float[][] legacyBottom = {{0f, .9f}, {1f, .9f}, {1f, 1f}, {0f, 1f}};
        byte[] legacy = MotionGridGeometry.polygonMask(legacyBottom, true);
        for (int i = 0; i < 60; i++) assertEquals(0, legacy[i]);
        for (int i = 60; i < 80; i++) assertEquals(1, legacy[i]);
        byte[] physical = MotionGridGeometry.polygonMask(legacyBottom, false);
        for (int i = 0; i < 70; i++) assertEquals(0, physical[i]);
        for (int i = 70; i < 80; i++) assertEquals(1, physical[i]);
    }
}
