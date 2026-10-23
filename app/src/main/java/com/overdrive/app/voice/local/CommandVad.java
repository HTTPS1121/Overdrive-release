package com.overdrive.app.voice.local;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * On-device voice activity detection (Silero VAD, MIT) for the command window only: it decides
 * when the driver starts and stops talking after the wake word, where an RMS threshold mistakes
 * steady cabin noise for speech. The wake path never feeds it.
 */
public final class CommandVad implements AutoCloseable {
    private static final String TAG = "CommandVad";
    static final String ASSET = "voice/vad/silero_vad.onnx";
    private static final String ASSET_DIR = "voice/vad";
    private static final String ASSET_NAME = "silero_vad.onnx";
    private static final int SAMPLE_RATE = 16_000;
    // The model scores fixed 512-sample (32 ms) windows; partial chunks wait for the next one.
    private static final int WINDOW = 512;
    private static final float THRESHOLD = 0.5f;
    private static final float MIN_SILENCE_S = 0.10f;
    private static final float MIN_SPEECH_S = 0.10f;
    // Matches VoiceEngine's MAX_UTTERANCE_MS so a segment never outlives the window it is in.
    private static final float MAX_SPEECH_S = 8.0f;
    private final Vad vad;
    private final float[] window = new float[WINDOW];
    private int pending;
    private boolean closed;

    private CommandVad(Vad vad) {
        this.vad = vad;
    }

    /** The model asset is present. Loads nothing. */
    public static boolean isAvailable(Context ctx) {
        if (ctx == null) return false;
        try {
            String[] listed = ctx.getAssets().list(ASSET_DIR);
            return listed != null && Arrays.asList(listed).contains(ASSET_NAME);
        } catch (Throwable t) {
            return false;
        }
    }

    public static CommandVad load(Context ctx) throws IOException {
        AssetManager assets = ctx.getAssets();
        // The native loader exits the process on a missing asset, so prove it opens first.
        try (InputStream ignored = assets.open(ASSET)) {
            // present
        }
        try {
            SileroVadModelConfig silero = new SileroVadModelConfig();
            silero.setModel(ASSET);
            silero.setThreshold(THRESHOLD);
            silero.setMinSilenceDuration(MIN_SILENCE_S);
            silero.setMinSpeechDuration(MIN_SPEECH_S);
            silero.setWindowSize(WINDOW);
            silero.setMaxSpeechDuration(MAX_SPEECH_S);
            VadModelConfig config = new VadModelConfig();
            config.setSileroVadModelConfig(silero);
            config.setSampleRate(SAMPLE_RATE);
            config.setNumThreads(1);
            config.setProvider("cpu");
            return new CommandVad(new Vad(assets, config));
        } catch (Throwable t) {
            throw new IOException("Voice activity detector failed to initialise: " + t, t);
        }
    }

    /**
     * Feeds 16 kHz mono PCM and reports whether the detector currently hears speech. Samples that
     * do not fill a whole window are carried into the next call.
     */
    public synchronized boolean speech(short[] pcm, int off, int n) {
        if (closed || pcm == null || n <= 0 || off < 0 || off > pcm.length - n) return false;
        try {
            for (int i = 0; i < n; i++) {
                window[pending++] = pcm[off + i] / 32768f;
                if (pending == WINDOW) {
                    vad.acceptWaveform(window);
                    pending = 0;
                }
            }
            // Only the live decision is used; finished segments would otherwise pile up for the
            // whole cloud turn.
            while (!vad.empty()) vad.pop();
            return vad.isSpeechDetected();
        } catch (Throwable t) {
            Log.w(TAG, "VAD failed: " + t.getMessage());
            return false;
        }
    }

    /** Forgets the previous turn: its trailing speech must not leak into the next window. */
    public synchronized void reset() {
        if (closed) return;
        pending = 0;
        try {
            vad.reset();
        } catch (Throwable t) {
            Log.w(TAG, "VAD reset failed: " + t.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            vad.release();
        } catch (Throwable t) {
            Log.w(TAG, "VAD release failed: " + t.getMessage());
        }
    }
}
