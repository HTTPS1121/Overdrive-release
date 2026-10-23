package com.overdrive.app.voice.genai;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import com.overdrive.app.voice.audio.Resampler;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Streams reply PCM16 mono to one AudioTrack per reply burst on the configured channel, holds
 * transient may-duck focus until the burst drains, and counts played frames per audio item.
 */
final class ReplyPlayer {

    interface Callback {
        /** Called with the player lock held, in transition order; must only hand off. */
        void onSpeakingChanged(boolean speaking);

        /** Called on the player thread, without locks, when no AudioTrack can be opened. */
        void onPlaybackFailed();
    }

    private static final String TAG = "VoiceReply";
    private static final int BUFFER_MS = 160;
    private static final int WRITE_MS = 40;
    private static final long TAIL_MS = 250;
    private static final long STALL_MS = 600;
    private static final int MAX_QUEUED_MS = 60_000;
    private static final int MAX_ZERO_WRITES = 25;
    private static final int MAX_SPANS = 8;
    private static final int STREAM_VOICE_OEM = 16;

    private final Object lock = new Object();
    private final ArrayDeque<Chunk> queue = new ArrayDeque<>();
    private final Map<String, Span> spans = new LinkedHashMap<String, Span>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Span> eldest) {
            return size() > MAX_SPANS;
        }
    };
    private final AudioManager audioManager;
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> { };
    private final String channel;
    private final int rate;
    private final Callback callback;
    private final long maxQueuedSamples;

    private volatile boolean active;
    private Thread thread;
    private boolean closed;
    private int generation;
    private long queuedSamples;

    // Assigned only by the player thread; flush()/close() touch it under the lock.
    private AudioTrack track;
    private long framesWritten;
    private boolean detachRequested;
    private long drainedAt;
    private long lastHead = -1;
    private long lastHeadAt;
    private boolean kicked;
    private boolean focusHeld;

    ReplyPlayer(Context context, String channel, int sampleRate, Callback callback) {
        this.audioManager = context == null
                ? null : (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.channel = channel;
        this.rate = sampleRate > 0 ? sampleRate : 24_000;
        this.callback = callback;
        this.maxQueuedSamples = (long) this.rate * MAX_QUEUED_MS / 1000;
    }

    /** True from the first queued reply chunk until the burst drains (plus tail) or is flushed. */
    boolean isActive() {
        return active;
    }

    /** Queues little-endian PCM16 for {@code itemKey}; never blocks on audio I/O. */
    void enqueue(String itemKey, byte[] data) {
        int samples = data == null ? 0 : data.length / 2;
        if (samples == 0) return;
        short[] pcm = new short[samples];
        Resampler.bytesToShorts(data, samples * 2, pcm);
        synchronized (lock) {
            if (closed || queuedSamples + samples > maxQueuedSamples) return;
            queue.addLast(new Chunk(itemKey == null ? "" : itemKey, pcm));
            queuedSamples += samples;
            if (!active) {
                active = true;
                callback.onSpeakingChanged(true);
            }
            if (thread == null) {
                thread = new Thread(this::run, "VoiceReplyPlayer");
                thread.setDaemon(true);
                thread.start();
            }
            lock.notifyAll();
        }
    }

    /** Stops audio now, drops everything queued, and returns ms of {@code itemKey} heard. */
    int flush(String itemKey) {
        synchronized (lock) {
            long played = stopTrackLocked(itemKey);
            generation++;
            queue.clear();
            queuedSamples = 0;
            if (active) {
                active = false;
                callback.onSpeakingChanged(false);
            }
            lock.notifyAll();
            return (int) (played * 1000L / rate);
        }
    }

    /** Idempotent; the player thread releases the track and focus asynchronously. */
    void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            stopTrackLocked(null);
            generation++;
            queue.clear();
            queuedSamples = 0;
            if (active) {
                active = false;
                callback.onSpeakingChanged(false);
            }
            lock.notifyAll();
        }
    }

    private long stopTrackLocked(String itemKey) {
        AudioTrack t = track;
        if (t == null || detachRequested) return playedFramesLocked(itemKey, -1);
        try {
            t.pause();
        } catch (Throwable ignored) {
        }
        long head = headOf(t);
        long played = playedFramesLocked(itemKey, head);
        try {
            t.flush();
        } catch (Throwable ignored) {
        }
        settleSpansLocked(head);
        detachRequested = true;
        return played;
    }

    private void run() {
        boolean crashed = false;
        try {
            loop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            Log.w(TAG, "Reply player stopped: " + e.getMessage());
            crashed = true;
        } finally {
            AudioTrack left;
            synchronized (lock) {
                closed = true;
                if (track != null && !detachRequested) {
                    try {
                        track.pause();
                        track.flush();
                    } catch (Throwable ignored) {
                    }
                }
                left = detachLocked();
                queue.clear();
                queuedSamples = 0;
                if (active) {
                    active = false;
                    callback.onSpeakingChanged(false);
                }
            }
            if (left != null) releaseTrack(left);
            abandonFocus();
        }
        if (crashed) callback.onPlaybackFailed();
    }

    private void loop() throws InterruptedException {
        while (true) {
            Chunk chunk = null;
            AudioTrack detached = null;
            boolean exit = false;
            int gen;
            synchronized (lock) {
                while (true) {
                    if (closed) {
                        exit = true;
                        detached = detachLocked();
                        break;
                    }
                    if (detachRequested) {
                        detached = detachLocked();
                        break;
                    }
                    if (!queue.isEmpty()) {
                        chunk = queue.pollFirst();
                        queuedSamples -= chunk.pcm.length;
                        break;
                    }
                    if (track == null) {
                        lock.wait();
                        continue;
                    }
                    long waitMs = drainWaitLocked();
                    if (waitMs > 0) {
                        lock.wait(waitMs);
                        continue;
                    }
                    detached = detachLocked();
                    if (active) {
                        active = false;
                        callback.onSpeakingChanged(false);
                    }
                    break;
                }
                gen = generation;
            }
            if (detached != null) releaseTrack(detached);
            if (exit) return;
            if (chunk != null) play(chunk, gen);
        }
    }

    private void play(Chunk chunk, int gen) {
        AudioTrack t = track;
        if (t == null) {
            long[] primed = new long[1];
            AudioTrack opened = open(primed);
            if (opened == null) {
                synchronized (lock) {
                    if (gen == generation) {
                        queue.clear();
                        queuedSamples = 0;
                        if (active) {
                            active = false;
                            callback.onSpeakingChanged(false);
                        }
                    }
                }
                callback.onPlaybackFailed();
                return;
            }
            boolean stale;
            synchronized (lock) {
                stale = closed || gen != generation;
                if (!stale) {
                    track = opened;
                    framesWritten = primed[0];
                    drainedAt = 0;
                    lastHead = -1;
                    kicked = false;
                }
            }
            if (stale) {
                releaseTrack(opened);
                return;
            }
            t = opened;
        }

        short[] pcm = chunk.pcm;
        int step = Math.max(1, rate * WRITE_MS / 1000);
        int offset = 0;
        int zeroWrites = 0;
        while (offset < pcm.length) {
            int n = Math.min(step, pcm.length - offset);
            Span span;
            synchronized (lock) {
                if (closed || gen != generation || detachRequested) return;
                span = spanLocked(chunk.key);
                if (span != null) {
                    if (span.startFrame < 0) span.startFrame = framesWritten;
                    span.frames += n;
                }
                framesWritten += n;
                drainedAt = 0;
            }
            int written;
            try {
                written = t.write(pcm, offset, n, AudioTrack.WRITE_BLOCKING);
            } catch (Throwable e) {
                written = AudioTrack.ERROR;
            }
            synchronized (lock) {
                if (gen == generation && !detachRequested) {
                    int unwritten = n - Math.max(0, written);
                    if (unwritten > 0) {
                        framesWritten -= unwritten;
                        if (span != null) span.frames -= unwritten;
                    }
                    if (written < 0) {
                        // Dead track: count what it accepted as heard so truncation stays in range.
                        settleSpansLocked(-1);
                        detachRequested = true;
                        lock.notifyAll();
                    }
                }
            }
            if (written < 0) {
                Log.w(TAG, "Reply write failed: " + written);
                return;
            }
            if (written == 0) {
                if (++zeroWrites > MAX_ZERO_WRITES) {
                    Log.w(TAG, "Reply track stopped accepting audio");
                    synchronized (lock) {
                        if (gen == generation && !detachRequested) {
                            settleSpansLocked(-1);
                            detachRequested = true;
                        }
                    }
                    return;
                }
                synchronized (lock) {
                    if (closed || gen != generation || detachRequested) return;
                    try {
                        lock.wait(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                continue;
            }
            zeroWrites = 0;
            offset += written;
        }
    }

    /** Returns ms to keep waiting, or 0 once drained and the echo tail has elapsed. */
    private long drainWaitLocked() {
        long now = SystemClock.elapsedRealtime();
        long head = headOf(track);
        if (head != lastHead) {
            lastHead = head;
            lastHeadAt = now;
        }
        long pending = framesWritten - head;
        if (pending > 0) {
            if (now - lastHeadAt < STALL_MS) {
                return Math.max(10, Math.min(100, pending * 1000L / rate));
            }
            if (!kicked) {
                // A stream that never reached its start threshold plays out once topped up.
                kicked = true;
                lastHeadAt = now;
                kickLocked();
                return 50;
            }
        }
        if (drainedAt == 0) drainedAt = now;
        long left = TAIL_MS - (now - drainedAt);
        return left > 0 ? left : 0;
    }

    private void kickLocked() {
        AudioTrack t = track;
        if (t == null) return;
        try {
            int frames = Math.max(1, t.getBufferSizeInFrames());
            int w = t.write(new short[frames], 0, frames, AudioTrack.WRITE_NON_BLOCKING);
            if (w > 0) framesWritten += w;
        } catch (Throwable ignored) {
        }
    }

    private AudioTrack detachLocked() {
        AudioTrack t = track;
        if (t != null && !detachRequested) settleSpansLocked(-1);
        track = null;
        detachRequested = false;
        framesWritten = 0;
        drainedAt = 0;
        lastHead = -1;
        kicked = false;
        return t;
    }

    private Span spanLocked(String key) {
        if (key.isEmpty()) return null;
        Span span = spans.get(key);
        if (span == null) {
            span = new Span();
            spans.put(key, span);
        }
        return span;
    }

    private long playedFramesLocked(String key, long head) {
        Span span = key == null || key.isEmpty() ? null : spans.get(key);
        if (span == null) return 0;
        long played = span.baseFrames;
        if (head >= 0 && span.startFrame >= 0) {
            played += Math.max(0, Math.min(span.frames, head - span.startFrame));
        }
        return played;
    }

    /** Folds frames on the current track into each item's base; head < 0 means fully played. */
    private void settleSpansLocked(long head) {
        for (Span span : spans.values()) {
            if (span.startFrame >= 0) {
                span.baseFrames += head < 0
                        ? span.frames
                        : Math.max(0, Math.min(span.frames, head - span.startFrame));
            }
            span.startFrame = -1;
            span.frames = 0;
        }
    }

    private AudioTrack open(long[] primedOut) {
        int stream = streamFor(channel);
        int minBytes = AudioTrack.getMinBufferSize(
                rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bytes = Math.max(minBytes, rate * 2 * BUFFER_MS / 1000);
        bytes += bytes & 1;
        AudioTrack t = null;
        if (stream != AudioManager.STREAM_MUSIC) t = directStreamTrack(stream, bytes);
        if (t == null) t = attributedTrack(stream, bytes);
        if (t == null && stream != AudioManager.STREAM_MUSIC) {
            stream = AudioManager.STREAM_MUSIC;
            t = attributedTrack(stream, bytes);
        }
        if (t == null) {
            Log.w(TAG, "No reply AudioTrack for channel " + channel);
            return null;
        }
        try {
            int frames = t.getBufferSizeInFrames();
            if (frames <= 0) frames = bytes / 2;
            int prime = frames;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                int want = Math.max(1, rate * BUFFER_MS / 1000);
                if (want < frames) {
                    prime = Math.max(1, Math.min(frames, t.setStartThresholdInFrames(want)));
                }
            }
            // Silence at the start meets the start threshold and covers route warm-up.
            int written = t.write(new short[prime], 0, prime, AudioTrack.WRITE_NON_BLOCKING);
            primedOut[0] = Math.max(0, written);
            requestFocus(stream);
            t.play();
            return t;
        } catch (Throwable e) {
            Log.w(TAG, "Reply AudioTrack start failed: " + e.getMessage());
            releaseTrack(t);
            return null;
        }
    }

    /** OEM-extended streams take the direct stream-type path, like the automation player. */
    @SuppressWarnings("deprecation")
    private AudioTrack directStreamTrack(int stream, int bytes) {
        try {
            AudioTrack t = new AudioTrack(stream, rate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bytes, AudioTrack.MODE_STREAM);
            if (t.getState() == AudioTrack.STATE_INITIALIZED) return t;
            t.release();
        } catch (Throwable e) {
            Log.w(TAG, "Stream " + stream + " track unavailable: " + e.getMessage());
        }
        return null;
    }

    private AudioTrack attributedTrack(int stream, int bytes) {
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setLegacyStreamType(stream)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build();
            AudioTrack t = new AudioTrack(attributes, format, bytes,
                    AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (t.getState() == AudioTrack.STATE_INITIALIZED) return t;
            t.release();
        } catch (Throwable e) {
            Log.w(TAG, "Attributed track for stream " + stream + " unavailable: "
                    + e.getMessage());
        }
        return null;
    }

    private void releaseTrack(AudioTrack t) {
        try {
            t.stop();
        } catch (Throwable ignored) {
        }
        try {
            t.release();
        } catch (Throwable ignored) {
        }
        abandonFocus();
    }

    @SuppressWarnings("deprecation")
    private void requestFocus(int stream) {
        if (audioManager == null || focusHeld) return;
        try {
            // Legacy stream-typed request keeps focus on the same stream the track plays on.
            audioManager.requestAudioFocus(focusListener, stream,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            focusHeld = true;
        } catch (Throwable e) {
            Log.w(TAG, "Reply focus request failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private void abandonFocus() {
        if (!focusHeld || audioManager == null) return;
        focusHeld = false;
        try {
            audioManager.abandonAudioFocus(focusListener);
        } catch (Throwable ignored) {
        }
    }

    private static long headOf(AudioTrack t) {
        if (t == null) return 0;
        try {
            return t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        } catch (Throwable e) {
            return 0;
        }
    }

    static int streamFor(String channel) {
        String c = channel == null ? "" : channel.trim().toLowerCase(Locale.ROOT);
        if ("navigation".equals(c)) return resolveStream("STREAM_NAVI", 14);
        if ("voice".equals(c)) return STREAM_VOICE_OEM;
        return AudioManager.STREAM_MUSIC;
    }

    private static int resolveStream(String field, int fallback) {
        try {
            return AudioManager.class.getField(field).getInt(null);
        } catch (Throwable e) {
            return fallback;
        }
    }

    private static final class Chunk {
        final String key;
        final short[] pcm;

        Chunk(String key, short[] pcm) {
            this.key = key;
            this.pcm = pcm;
        }
    }

    private static final class Span {
        long baseFrames;
        long startFrame = -1;
        long frames;
    }
}
