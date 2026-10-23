package com.overdrive.app.audio;

import android.util.Log;

/**
 * JNI front-end (libvoice_frontend, SpeexDSP): the car's microphones averaged, the speaker echo
 * cancelled against the reference channels, low rumble high-passed and noise suppressed, one 10 ms
 * frame at a time. Loaded only when the car-microphone input is selected.
 */
final class VoiceFrontend implements AutoCloseable {

    private static final String TAG = "VoiceFrontend";
    /** Echo tail the canceller models: 128 ms at 16 kHz covers the cabin's speaker-to-mic path. */
    static final int FILTER_SAMPLES = 2048;
    static final int SUPPRESS_DB = -25;
    static final int HIGH_PASS_HZ = 200;

    private static volatile Boolean loaded;

    private final int frame;
    private final int channels;
    private long handle;

    private VoiceFrontend(long handle, int frame, int channels) {
        this.handle = handle;
        this.frame = frame;
        this.channels = channels;
    }

    static boolean isAvailable() {
        Boolean known = loaded;
        if (known != null) return known;
        synchronized (VoiceFrontend.class) {
            if (loaded == null) {
                boolean ok;
                try {
                    System.loadLibrary("voice_frontend");
                    ok = true;
                } catch (Throwable t) {
                    Log.w(TAG, "Voice front-end library unavailable: " + t.getMessage());
                    ok = false;
                }
                loaded = ok;
            }
            return loaded;
        }
    }

    /** {@code mics} microphone channels followed by {@code refs} speaker-reference channels; null when it cannot start. */
    static VoiceFrontend create(int rate, int frame, int mics, int refs) {
        if (!isAvailable()) return null;
        long h = nativeCreate(rate, frame, FILTER_SAMPLES, mics, refs, SUPPRESS_DB, HIGH_PASS_HZ);
        return h == 0 ? null : new VoiceFrontend(h, frame, mics + refs);
    }

    /** One interleaved frame ({@code frame * channels} samples) in, {@code frame} mono samples out. */
    boolean process(short[] interleaved, short[] mono) {
        long h = handle;
        return h != 0 && interleaved.length >= frame * channels && mono.length >= frame
                && nativeProcess(h, interleaved, channels, mono);
    }

    @Override
    public void close() {
        long h = handle;
        handle = 0;
        if (h != 0) nativeDestroy(h);
    }

    private static native long nativeCreate(int rate, int frame, int filterLen, int mics, int refs,
                                            int suppressDb, int hpfHz);

    private static native boolean nativeProcess(long handle, short[] in, int channels, short[] out);

    private static native void nativeDestroy(long handle);
}
