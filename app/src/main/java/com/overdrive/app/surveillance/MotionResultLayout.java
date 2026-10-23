package com.overdrive.app.surveillance;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Validated native result layout, including the confidence count for each quadrant. */
public final class MotionResultLayout {
    public final int resultSize;
    public final int blockCount;
    public final int coveredHeight;
    public final boolean hasShadow;
    public final boolean hasCoherence;
    public final boolean hasSalience;

    private MotionResultLayout(int configSize, int resultSize, int blockCount, int prefix) {
        this.resultSize = resultSize;
        this.blockCount = blockCount;
        this.coveredHeight = blockCount == MotionGridGeometry.POLICY_BLOCKS
                ? MotionGridGeometry.LEGACY_COVERED_HEIGHT : MotionGridGeometry.QUADRANT_HEIGHT;
        this.hasShadow = configSize >= 76;
        this.hasCoherence = prefix >= 44;
        this.hasSalience = prefix == 52;
    }

    /**
     * Native descriptor: ABI version, width, height, block size, columns, rows,
     * policy rows, result-prefix bytes. A missing descriptor means a legacy
     * seven-row library, never an inferred eight-row layout.
     */
    public static MotionResultLayout resolve(int configSize, int resultSize, int[] descriptor) {
        if (configSize != 60 && configSize != 76 && configSize != 88 && configSize != 100) {
            throw new IllegalArgumentException("Unsupported native motion config size");
        }
        int blocks = MotionGridGeometry.POLICY_BLOCKS;
        int prefix = resultSize - blocks * Float.BYTES;
        if (descriptor != null) {
            if (descriptor.length != 8
                    || descriptor[1] != MotionGridGeometry.QUADRANT_WIDTH
                    || descriptor[2] != MotionGridGeometry.QUADRANT_HEIGHT
                    || descriptor[3] != MotionGridGeometry.BLOCK_SIZE
                    || descriptor[4] != MotionGridGeometry.GRID_COLS
                    || descriptor[6] != MotionGridGeometry.POLICY_ROWS
                    || !((descriptor[0] == 1 && descriptor[5] == MotionGridGeometry.POLICY_ROWS)
                        || (descriptor[0] == MotionGridGeometry.NATIVE_ABI_VERSION
                            && descriptor[5] == MotionGridGeometry.GRID_ROWS))) {
                throw new IllegalArgumentException("Unsupported native motion geometry");
            }
            blocks = descriptor[4] * descriptor[5];
            prefix = descriptor[7];
            if (descriptor[0] == MotionGridGeometry.NATIVE_ABI_VERSION
                    && (configSize != 100 || prefix != 52)) {
                throw new IllegalArgumentException("Invalid native motion ABI version");
            }
        }
        if ((prefix != 36 && prefix != 44 && prefix != 52)
                || resultSize != prefix + blocks * Float.BYTES
                || (prefix >= 44 && configSize < 88)
                || (prefix == 52 && configSize < 100)) {
            throw new IllegalArgumentException("Native motion result size does not match its layout");
        }
        return new MotionResultLayout(configSize, resultSize, blocks, prefix);
    }

    /** Reused per-quadrant result, with unused legacy confidence cells cleared on every decode. */
    public static class Result {
        public boolean motionDetected;
        public int threatLevel;
        public int activeBlocks;
        public int confirmedBlocks;
        public int componentSize;
        public float centroidX;
        // Pixel-center convention: (centroidY + .5) * 32. A bottom-only
        // component is 6.75, mapping to the partial row's actual center at 232 px.
        public float centroidY;
        public float meanLuma;
        public boolean brightnessSuppressed;
        public boolean shadowFiltered;
        // -1 remains unavailable, not evidence of incoherent motion.
        public float flowCoherence = -1f;
        public float netDriftBlocks = -1f;
        // Absent salience data does not authorize the salience trigger channel.
        public int componentCount;
        public int salienceState;
        public int nativeBlockCount = MotionGridGeometry.TOTAL_BLOCKS;
        public int coveredHeight = MotionGridGeometry.QUADRANT_HEIGHT;
        public float[] blockConfidence = new float[MotionGridGeometry.TOTAL_BLOCKS];
    }

    public void readQuadrant(ByteBuffer buffer, Result result) {
        if (buffer.remaining() < resultSize || result.blockConfidence == null
                || result.blockConfidence.length != MotionGridGeometry.TOTAL_BLOCKS) {
            throw new IllegalArgumentException("Incomplete native motion result");
        }
        int start = buffer.position();
        result.motionDetected = buffer.get() != 0;
        buffer.get(); buffer.get(); buffer.get();
        result.threatLevel = buffer.getInt();
        result.activeBlocks = buffer.getInt();
        result.confirmedBlocks = buffer.getInt();
        result.componentSize = buffer.getInt();
        result.centroidX = buffer.getFloat();
        result.centroidY = buffer.getFloat();
        result.meanLuma = buffer.getFloat();
        result.brightnessSuppressed = buffer.get() != 0;
        byte shadow = buffer.get();
        result.shadowFiltered = hasShadow && shadow != 0;
        buffer.get(); buffer.get();
        result.flowCoherence = hasCoherence ? buffer.getFloat() : -1f;
        result.netDriftBlocks = hasCoherence ? buffer.getFloat() : -1f;
        result.componentCount = hasSalience ? buffer.getInt() : 0;
        result.salienceState = hasSalience ? buffer.getInt() : 0;
        Arrays.fill(result.blockConfidence, 0f);
        for (int i = 0; i < blockCount; i++) {
            result.blockConfidence[i] = buffer.getFloat();
        }
        result.nativeBlockCount = blockCount;
        result.coveredHeight = coveredHeight;
        if (buffer.position() != start + resultSize) {
            throw new IllegalStateException("Native motion result boundary mismatch");
        }
    }
}
