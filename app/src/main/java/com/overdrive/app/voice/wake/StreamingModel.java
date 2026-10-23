package com.overdrive.app.voice.wake;

import java.nio.ByteBuffer;

/** A stateful streaming classifier: int8 [1, stride, 40] features in, one uint8 probability out. */
interface StreamingModel extends AutoCloseable {

    /** Feature slices consumed per inference. */
    int stride();

    float inputScale();

    int inputZeroPoint();

    /** Runs one inference on {@code stride() * 40} int8 values at absolute positions; returns 0..255. */
    int invoke(ByteBuffer input);

    /** Drops all streaming state so the next inference starts from the model's initial state. */
    void resetState();

    @Override
    void close();
}
