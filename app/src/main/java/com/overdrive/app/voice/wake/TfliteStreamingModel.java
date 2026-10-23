package com.overdrive.app.voice.wake;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.Map;

/** TensorFlow Lite backed {@link StreamingModel}; state lives in resource variables. */
final class TfliteStreamingModel implements StreamingModel {

    private final ByteBuffer modelBytes;
    private final int stride;
    private final float inputScale;
    private final int inputZeroPoint;
    private final ByteBuffer output = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder());
    private final Object[] inputs = new Object[1];
    private final Map<Integer, Object> outputs = Collections.<Integer, Object>singletonMap(0, output);
    private Interpreter interpreter;

    private TfliteStreamingModel(ByteBuffer modelBytes, Interpreter interpreter) throws IOException {
        this.modelBytes = modelBytes;
        this.interpreter = interpreter;
        if (interpreter.getInputTensorCount() != 1 || interpreter.getOutputTensorCount() != 1) {
            throw new IOException("Wake model must have one input and one output");
        }
        Tensor in = interpreter.getInputTensor(0);
        int[] shape = in.shape();
        if (shape.length != 3 || shape[0] != 1 || shape[1] < 1
                || shape[2] != MicroFrontend.NUM_CHANNELS || in.dataType() != DataType.INT8) {
            throw new IOException("Wake model input must be int8 [1, stride, 40]");
        }
        Tensor out = interpreter.getOutputTensor(0);
        int[] outShape = out.shape();
        if (outShape.length != 2 || outShape[0] != 1 || outShape[1] != 1
                || out.dataType() != DataType.UINT8) {
            throw new IOException("Wake model output must be uint8 [1, 1]");
        }
        stride = shape[1];
        inputScale = in.quantizationParams().getScale();
        inputZeroPoint = in.quantizationParams().getZeroPoint();
        if (!(inputScale > 0f) || MicroFrontend.quantizeDivisor(inputScale) < 1) {
            throw new IOException("Wake model input scale " + inputScale);
        }
    }

    /**
     * Opens the model and proves one inference runs, then starts from fresh state.
     * {@code modelBytes} must be a direct buffer in native order and stay untouched afterwards.
     */
    static TfliteStreamingModel open(ByteBuffer modelBytes) throws IOException {
        Interpreter first;
        try {
            first = create(modelBytes);
        } catch (RuntimeException e) {
            throw new IOException("Wake model rejected: " + e.getMessage(), e);
        }
        TfliteStreamingModel model = null;
        try {
            model = new TfliteStreamingModel(modelBytes, first);
            ByteBuffer probe = ByteBuffer.allocateDirect(model.stride * MicroFrontend.NUM_CHANNELS)
                    .order(ByteOrder.nativeOrder());
            for (int i = 0; i < probe.capacity(); i++) probe.put(i, (byte) model.inputZeroPoint);
            model.invoke(probe);
            model.resetState();
            return model;
        } catch (IOException e) {
            first.close();
            throw e;
        } catch (RuntimeException e) {
            if (model != null) model.close(); else first.close();
            throw new IOException("Wake model failed to run: " + e.getMessage(), e);
        }
    }

    /** One thread, no delegates: the graph is tiny and its resource-variable ops stay on the CPU kernels. */
    private static Interpreter create(ByteBuffer modelBytes) {
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(1);
        options.setUseXNNPACK(false);
        options.setUseNNAPI(false);
        Interpreter interpreter = new Interpreter(modelBytes, options);
        try {
            interpreter.allocateTensors();
        } catch (RuntimeException e) {
            interpreter.close();
            throw e;
        }
        return interpreter;
    }

    @Override
    public int stride() {
        return stride;
    }

    @Override
    public float inputScale() {
        return inputScale;
    }

    @Override
    public int inputZeroPoint() {
        return inputZeroPoint;
    }

    @Override
    public int invoke(ByteBuffer input) {
        inputs[0] = input;
        output.clear();
        interpreter.runForMultipleInputsOutputs(inputs, outputs);
        return output.get(0) & 0xFF;
    }

    /** Resource variables survive resetVariableTensors(), so a fresh interpreter is the only clean state. */
    @Override
    public void resetState() {
        Interpreter fresh = create(modelBytes);
        Interpreter old = interpreter;
        interpreter = fresh;
        if (old != null) old.close();
    }

    @Override
    public void close() {
        Interpreter old = interpreter;
        interpreter = null;
        if (old != null) old.close();
    }
}
