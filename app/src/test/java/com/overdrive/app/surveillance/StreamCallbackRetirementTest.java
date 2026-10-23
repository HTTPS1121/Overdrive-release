package com.overdrive.app.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.media.MediaCodec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Retire/re-attach contract for /ws viewers: when the stream encoder is torn
 * down, every registered {@code StreamCallback} is told exactly once, BEFORE
 * the callback set is cleared, and one throwing sink cannot hide the notice
 * from the others. Legacy sinks that never override the hook keep working
 * (default no-op).
 */
public class StreamCallbackRetirementTest {

    private static final String RECORDER =
            "app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java";

    /** Minimal sink that counts retirement notices. */
    private static final class CountingSink implements HardwareEventRecorderGpu.StreamCallback {
        final AtomicInteger retired = new AtomicInteger();
        final boolean throwOnRetire;

        CountingSink(boolean throwOnRetire) { this.throwOnRetire = throwOnRetire; }

        @Override public void onSpsPps(ByteBuffer sps, ByteBuffer pps) {}
        @Override public void onH264Packet(ByteBuffer h264Data, MediaCodec.BufferInfo info) {}

        @Override public void onStreamRetired() {
            retired.incrementAndGet();
            if (throwOnRetire) throw new IllegalStateException("viewer gone");
        }
    }

    /** Sink in the pre-hook shape: relies on the interface default. */
    private static final class LegacySink implements HardwareEventRecorderGpu.StreamCallback {
        @Override public void onSpsPps(ByteBuffer sps, ByteBuffer pps) {}
        @Override public void onH264Packet(ByteBuffer h264Data, MediaCodec.BufferInfo info) {}
    }

    @Test
    public void everyRegisteredCallbackIsNotifiedExactlyOnce() {
        CountingSink a = new CountingSink(false);
        CountingSink b = new CountingSink(false);
        CopyOnWriteArraySet<HardwareEventRecorderGpu.StreamCallback> sinks =
                new CopyOnWriteArraySet<>(Arrays.asList(a, b, new LegacySink()));

        int notified = StreamCallbackRetirement.notifyRetired(sinks);

        assertEquals(3, notified);
        assertEquals(1, a.retired.get());
        assertEquals(1, b.retired.get());
        // The helper does not clear: the caller clears AFTER the notice so the
        // set it iterates is the full set of live viewers.
        assertEquals(3, sinks.size());
    }

    @Test
    public void aThrowingSinkDoesNotStarveTheOthers() {
        CountingSink first = new CountingSink(true);
        CountingSink second = new CountingSink(false);

        int notified = StreamCallbackRetirement.notifyRetired(Arrays.asList(first, second));

        assertEquals(2, notified);
        assertEquals(1, first.retired.get());
        assertEquals(1, second.retired.get());
    }

    @Test
    public void emptyAndNullEntriesAreTolerated() {
        assertEquals(0, StreamCallbackRetirement.notifyRetired(Collections.emptyList()));
        assertEquals(1, StreamCallbackRetirement.notifyRetired(
                Arrays.asList(null, new LegacySink())));
    }

    /**
     * Source pin on the heavy class: clearStreamCallback() fans the notice out
     * under streamCallbackLock and only then clears the set; the stream lane's
     * viewer-driven IDR requests go through the rate-limited entry point while
     * the recorder's own calls keep the unlimited one.
     */
    @Test
    public void clearStreamCallbackNotifiesBeforeClearingUnderTheLock() throws Exception {
        String source = readRepositoryFile(RECORDER);
        int method = source.indexOf("public void clearStreamCallback() {");
        assertTrue(method >= 0);
        int lock = source.indexOf("synchronized (streamCallbackLock) {", method);
        int notify = source.indexOf("StreamCallbackRetirement.notifyRetired(streamCallbacks);", method);
        int clear = source.indexOf("streamCallbacks.clear();", method);
        int end = source.indexOf("public Surface getInputSurface()", method);
        assertTrue(lock > method && notify > lock && clear > notify && clear < end);

        assertTrue(source.contains("default void onStreamRetired() {}"));
        assertTrue(source.contains("public boolean requestSyncFrameRateLimited()"));
        // The recorder's own forced IDRs (event start, rotation splice) stay unlimited.
        assertTrue(source.contains("public void requestSyncFrame() {"));
    }

    private static String readRepositoryFile(String relativePath) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
