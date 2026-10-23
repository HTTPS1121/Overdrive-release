package com.overdrive.app.voice.audio;

/** Receives raw mono 16-bit little-endian PCM from the shared cabin capture thread. */
public interface PcmSink {

    /** Called on the capture thread with the capture rate's native samples; must not block. */
    void onPcm(byte[] pcm, int length, int sampleRate);
}
