package com.overdrive.app.camera.dilink5;

/**
 * Tile corners of the native DiLink 5 2x2 mosaic in sampling UV. Its texture keeps the
 * image bottom at row 0, so front/right sit at v 0.5–1; top-origin corners pick the
 * opposite row (front↔rear, right↔left).
 */
public final class DiLink5MosaicLayout {
    private DiLink5MosaicLayout() {}

    public static final float[] CORNER_FRONT = { 0.0f, 0.5f };
    public static final float[] CORNER_RIGHT = { 0.5f, 0.5f };
    public static final float[] CORNER_REAR  = { 0.0f, 0.0f };
    public static final float[] CORNER_LEFT  = { 0.5f, 0.0f };
    public static final float[] NO_FLIP = { 0.0f, 0.0f };

    /** Bottom-origin V of the 0.5-tall tile whose top-origin corner Y is {@code topOriginY}. */
    public static float tileV(float topOriginY) {
        return 0.5f - topOriginY;
    }
}
