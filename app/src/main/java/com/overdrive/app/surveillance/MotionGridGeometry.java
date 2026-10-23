package com.overdrive.app.surveillance;

import java.util.Arrays;

/**
 * Pixel geometry and persisted-mask compatibility for the per-camera motion grid.
 * The last row is 16 px high; the other seven rows retain their original 32 px cells.
 */
public final class MotionGridGeometry {
    public static final int NATIVE_ABI_VERSION = 2;
    public static final int QUADRANT_WIDTH = 320;
    public static final int QUADRANT_HEIGHT = 240;
    public static final int BLOCK_SIZE = 32;
    public static final int GRID_COLS = QUADRANT_WIDTH / BLOCK_SIZE;
    public static final int GRID_ROWS = (QUADRANT_HEIGHT + BLOCK_SIZE - 1) / BLOCK_SIZE;
    public static final int TOTAL_BLOCKS = GRID_COLS * GRID_ROWS;

    // Policy calibration stays on its original basis. Adding coverage must not
    // change the flash/mass decisions or distance tiers for the original cells.
    public static final int POLICY_ROWS = QUADRANT_HEIGHT / BLOCK_SIZE;
    public static final int POLICY_BLOCKS = GRID_COLS * POLICY_ROWS;
    public static final int LEGACY_COVERED_HEIGHT = POLICY_ROWS * BLOCK_SIZE;

    private MotionGridGeometry() {}

    public static int rowTop(int row) {
        if (row < 0 || row >= GRID_ROWS) throw new IllegalArgumentException("Invalid motion row");
        return row * BLOCK_SIZE;
    }

    public static int rowBottom(int row) {
        return Math.min(rowTop(row) + BLOCK_SIZE, QUADRANT_HEIGHT);
    }

    public static float rowCenterPixels(int row) {
        return (rowTop(row) + rowBottom(row)) * 0.5f;
    }

    /** Coordinates consumed by the existing {@code (centroid + .5) * 32} crop mapping. */
    public static float rowCenterBlocks(int row) {
        return rowCenterPixels(row) / BLOCK_SIZE - 0.5f;
    }

    public static int policyRow(int row) {
        return row < 0 ? row : Math.min(row, POLICY_ROWS - 1);
    }

    /**
     * Untagged saved polygons retain their original cell decisions. Newly
     * edited polygons use physical pixel centers, including the clipped row.
     */
    public static byte[] polygonMask(float[][] polygon, boolean legacyCenters) {
        if (polygon == null || polygon.length < 3) {
            throw new IllegalArgumentException("Invalid motion ROI polygon");
        }
        for (float[] point : polygon) {
            if (point == null || point.length < 2
                    || !Float.isFinite(point[0]) || !Float.isFinite(point[1])) {
                throw new IllegalArgumentException("Invalid motion ROI polygon vertex");
            }
        }
        byte[] mask = new byte[legacyCenters ? POLICY_BLOCKS : TOTAL_BLOCKS];
        int rows = legacyCenters ? POLICY_ROWS : GRID_ROWS;
        for (int row = 0; row < rows; row++) {
            float cy = legacyCenters ? (row + 0.5f) / POLICY_ROWS
                    : rowCenterPixels(row) / QUADRANT_HEIGHT;
            for (int col = 0; col < GRID_COLS; col++) {
                float cx = (col + 0.5f) / GRID_COLS;
                boolean inside = false;
                for (int i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
                    float xi = polygon[i][0], yi = polygon[i][1];
                    float xj = polygon[j][0], yj = polygon[j][1];
                    if (((yi > cy) != (yj > cy))
                            && (cx < (xj - xi) * (cy - yi) / (yj - yi) + xi)) {
                        inside = !inside;
                    }
                }
                mask[row * GRID_COLS + col] = (byte) (inside ? 1 : 0);
            }
        }
        return migrateRoiMask(mask);
    }

    /**
     * Accepts current 80-cell masks and legacy 70-cell masks. The original cells
     * are unchanged; each new bottom cell inherits the same column's last old
     * row, so deliberate exclusions extend to the newly measurable strip.
     * Returns null for malformed lengths rather than guessing a new ROI.
     */
    public static byte[] migrateRoiMask(byte[] mask) {
        if (mask == null || (mask.length != TOTAL_BLOCKS && mask.length != POLICY_BLOCKS)) {
            return null;
        }
        byte[] migrated = Arrays.copyOf(mask, TOTAL_BLOCKS);
        if (mask.length == POLICY_BLOCKS) {
            System.arraycopy(mask, (POLICY_ROWS - 1) * GRID_COLS,
                    migrated, POLICY_BLOCKS, GRID_COLS);
        }
        for (int i = 0; i < migrated.length; i++) {
            migrated[i] = (byte) (migrated[i] != 0 ? 1 : 0);
        }
        return migrated;
    }

    /** Adapts the persisted grid to a validated legacy or current native layout. */
    public static byte[] nativeRoiMask(byte[] mask, int nativeBlockCount) {
        if (mask == null) return null;
        if (nativeBlockCount != POLICY_BLOCKS && nativeBlockCount != TOTAL_BLOCKS) {
            throw new IllegalArgumentException("Unsupported native motion grid");
        }
        byte[] migrated = migrateRoiMask(mask);
        if (migrated == null) throw new IllegalArgumentException("Invalid motion ROI mask");
        return Arrays.copyOf(migrated, nativeBlockCount);
    }
}
