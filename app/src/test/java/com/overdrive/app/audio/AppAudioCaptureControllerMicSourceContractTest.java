package com.overdrive.app.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Pins the experimental microphone input of the shared cabin capture: the config vocabulary maps
 * onto MediaRecorder.AudioSource (MIC by default), the AudioRecord opens with it, only
 * VOICE_COMMUNICATION attaches the echo canceller and noise suppressor (logged once, kept for
 * /api/voice/status, released with the record), a source change reopens a wake-only capture at
 * once but never interrupts recording or the remote listener, an input the head unit refuses
 * falls back to plain MIC for the same demands (remembered until the setting changes, reported
 * as micSourceFailed), and the overlay pushes the source before it reconciles capture demand.
 */
public class AppAudioCaptureControllerMicSourceContractTest {

    private static final String CONTROLLER =
            "src/main/java/com/overdrive/app/audio/AppAudioCaptureController.java";
    private static final String OVERLAY =
            "src/main/java/com/overdrive/app/overlay/StatusOverlayService.java";
    private static final String RUNTIME =
            "src/main/java/com/overdrive/app/voice/VoiceAssistantRuntime.java";
    private static final String DAEMON =
            "src/main/java/com/overdrive/app/server/VoiceAssistantApiHandler.java";

    // android.media.MediaRecorder.AudioSource constants (compile-time values).
    private static final int MIC = 1;
    private static final int VOICE_COMMUNICATION = 7;
    private static final int VOICE_RECOGNITION = 6;

    @Test
    public void configVocabularyMapsOntoAudioSourcesWithMicAsTheFallback() {
        assertEquals(MIC, AppAudioCaptureController.audioSourceFor("mic"));
        assertEquals(VOICE_RECOGNITION, AppAudioCaptureController.audioSourceFor("voice_recognition"));
        assertEquals(VOICE_COMMUNICATION, AppAudioCaptureController.audioSourceFor("voice_communication"));
        assertEquals(MIC, AppAudioCaptureController.audioSourceFor("camcorder"));
        assertEquals(MIC, AppAudioCaptureController.audioSourceFor(null));
        for (String name : new String[] {"mic", "voice_recognition", "voice_communication"}) {
            assertEquals(name, AppAudioCaptureController.micSourceName(
                    AppAudioCaptureController.audioSourceFor(name)));
        }
        assertEquals("mic", AppAudioCaptureController.micSourceName(5));
    }

    @Test
    public void onlyVoiceCommunicationGetsTheVoiceEffects() {
        assertTrue(AppAudioCaptureController.wantsVoiceEffects(VOICE_COMMUNICATION));
        assertFalse(AppAudioCaptureController.wantsVoiceEffects(VOICE_RECOGNITION));
        assertFalse(AppAudioCaptureController.wantsVoiceEffects(MIC));
    }

    @Test
    public void audioRecordOpensWithTheRequestedSourceAndAttachesEffectsBeforeRecording() throws Exception {
        String src = readProjectFile(CONTROLLER);
        String init = block(src, "    private void initAudioRecord() {");
        assertTrue(init.contains("audioRecord = new AudioRecord(audioSource,"));
        assertFalse(init.contains("new AudioRecord(MediaRecorder.AudioSource.MIC"));
        int attach = init.indexOf("if (wantsVoiceEffects(audioSource)) attachVoiceEffects(audioRecord.getAudioSessionId());");
        int start = init.indexOf("audioRecord.startRecording();");
        assertTrue(attach >= 0);
        assertTrue(start > attach);
        // The default stays plain MIC (CabinAudioAssetContractTest pins the literal too).
        assertTrue(src.contains("private static volatile int requestedAudioSource = MediaRecorder.AudioSource.MIC;"));
        // The status read is lock-free so the engine's audio thread never waits on a capture restart.
        String active = block(src, "    public static String activeMicSource() {");
        assertFalse(active.contains("synchronized"));
        assertTrue(active.contains("openAudioSource"));
    }

    @Test
    public void effectsAreProbedOnceLoggedKeptForStatusAndReleasedWithTheRecord() throws Exception {
        String src = readProjectFile(CONTROLLER);
        String attach = block(src, "    private void attachVoiceEffects(int sessionId) {");
        assertTrue(attach.contains("AcousticEchoCanceler.isAvailable()"));
        assertTrue(attach.contains("AcousticEchoCanceler.create(sessionId)"));
        assertTrue(attach.contains("echoCanceler.setEnabled(true)"));
        assertTrue(attach.contains("NoiseSuppressor.isAvailable()"));
        assertTrue(attach.contains("NoiseSuppressor.create(sessionId)"));
        assertTrue(attach.contains("aecAvailable = available;"));
        assertTrue(attach.contains("aecEnabled = enabled;"));
        assertTrue(attach.contains("catch (Throwable t)"));
        assertTrue(src.contains("public static Boolean echoCancelerAvailable()"));
        assertTrue(src.contains("public static Boolean echoCancelerEnabled()"));
        assertTrue(src.contains("public static String activeMicSource()"));
        // The probe describes the open capture: a capture without effects clears it, so the status
        // never pairs micSource "mic" with a stale aecEnabled from an earlier capture.
        String start = block(src, "    private boolean start() {");
        int open = start.indexOf("openAudioSource = audioSource;");
        int clear = start.indexOf("if (!wantsVoiceEffects(audioSource)) {\n"
                + "                // The probe describes the open capture");
        assertTrue(open >= 0);
        assertTrue(clear > open);
        assertTrue(start.substring(clear).contains("aecAvailable = null;\n                aecEnabled = null;"));
        String cleanup = block(src, "    private void cleanup() {");
        int effects = cleanup.indexOf("releaseVoiceEffects();");
        int release = cleanup.indexOf("audioRecord.release();");
        assertTrue(effects >= 0);
        assertTrue(release > effects);
    }

    @Test
    public void sourceChangeReopensOnlyAWakeOnlyCaptureAndDefersOtherwise() throws Exception {
        String src = readProjectFile(CONTROLLER);
        String set = block(src, "    public static boolean setCaptureSource(String micSource) {");
        assertTrue(set.contains("synchronized (OWNER_LOCK)"));
        assertTrue(set.contains("if (source == requestedAudioSource) return true;"));
        assertTrue(set.contains("return reconcileOwnerLocked();"));
        String reconcile = block(src, "    private static boolean reconcileOwnerLocked() {");
        // Staleness is judged against the effective source (see below), never the raw request.
        assertTrue(reconcile.contains("boolean sourceStale = current.audioSource != source;"));
        assertFalse(reconcile.contains("current.audioSource != requestedAudioSource"));
        assertTrue(reconcile.contains("if (!sourceStale || needsIngest) {"));
        assertTrue(reconcile.contains("deferred until the recording/listener capture reopens"));
        assertTrue(reconcile.contains("current.loggedDeferredSource = true;"));
        // The replacement is built with the effective source: the requested one, or plain MIC
        // once the head unit refused it (so a capture that already fell back is kept, not stale).
        assertTrue(reconcile.contains("int source = effectiveAudioSource();"));
        int stop = reconcile.indexOf("stopOwnerLocked();");
        assertTrue(stop >= 0);
        assertTrue(reconcile.substring(stop).contains(
                "newCaptureLocked(needsIngest, desiredCaptureRate, source, ownerSink);"));
        assertFalse(reconcile.substring(stop).contains("requestedAudioSource);"));
    }

    @Test
    public void refusedInputFallsBackToMicForTheSameDemandsAndIsRememberedUntilTheSettingChanges()
            throws Exception {
        // Decision rules (pure): the requested input is used until exactly that input was refused;
        // a different request is always tried; only a failing AudioRecord on a non-MIC source
        // counts as "refused" (never a socket/encoder failure, a back-off skip, or MIC itself).
        assertEquals(VOICE_COMMUNICATION, AppAudioCaptureController.effectiveAudioSource(VOICE_COMMUNICATION, -1));
        assertEquals(MIC, AppAudioCaptureController.effectiveAudioSource(VOICE_COMMUNICATION, VOICE_COMMUNICATION));
        assertEquals(VOICE_RECOGNITION, AppAudioCaptureController.effectiveAudioSource(VOICE_RECOGNITION, VOICE_COMMUNICATION));
        assertEquals(MIC, AppAudioCaptureController.effectiveAudioSource(MIC, -1));
        assertEquals(MIC, AppAudioCaptureController.effectiveAudioSource(MIC, MIC));
        assertTrue(AppAudioCaptureController.sourceRefused(VOICE_COMMUNICATION, true));
        assertTrue(AppAudioCaptureController.sourceRefused(VOICE_RECOGNITION, true));
        assertFalse(AppAudioCaptureController.sourceRefused(VOICE_COMMUNICATION, false));
        assertFalse(AppAudioCaptureController.sourceRefused(MIC, true));

        String src = readProjectFile(CONTROLLER);
        assertTrue(src.contains("private static volatile int failedAudioSource = -1;"));
        // The owner: a refused input (start() failed at the AudioRecord step, or the capture died
        // before its first chunk) opens plain MIC for the same demands at once, past the back-off
        // the refused input armed; MIC coming up is what marks the input failed (logged once).
        // MIC failing too means the microphone itself is unavailable: today's back-off, no blame.
        String reconcile = block(src, "    private static boolean reconcileOwnerLocked() {");
        assertTrue(reconcile.contains(
                "&& current.audioSource == source && sourceRefused(source, current.recordFailed);"));
        assertTrue(reconcile.contains("if (!sourceRefused(source, replacement.recordFailed)) return false;"));
        int reset = reconcile.indexOf("lastFailureNanos = 0L;");
        int fallback = reconcile.indexOf(
                "newCaptureLocked(needsIngest, desiredCaptureRate, MediaRecorder.AudioSource.MIC, ownerSink);");
        int fallbackStart = reconcile.indexOf("if (!fallback.start()) return false;");
        int remember = reconcile.indexOf("failedAudioSource = source;");
        int log = reconcile.indexOf("refused by the head unit");
        int own = reconcile.indexOf("owner = fallback;");
        assertTrue(reset >= 0);
        assertTrue(fallback > reset);
        assertTrue(fallbackStart > fallback);
        assertTrue(remember > fallbackStart);
        assertTrue(log > remember);
        assertTrue(own > log);
        // Only the AudioRecord step of start() and a read failure before the first chunk count.
        String start = block(src, "    private boolean start() {");
        assertTrue(start.contains("recordFailed = \"audio-record\".equals(currentStep);"));
        String loop = block(src, "    private void captureLoop() {");
        assertEquals(2, count(loop, "noteReadFailure();"));
        assertTrue(loop.indexOf("deliveredPcm = true;") > loop.lastIndexOf("noteReadFailure();"));
        String note = block(src, "    private void noteReadFailure() {");
        assertTrue(note.contains("if (!deliveredPcm && running.get()) recordFailed = true;"));
        assertTrue(note.indexOf("running.set(false);") > note.indexOf("recordFailed = true;"));
        // A new request gets a fresh try, even of an input refused earlier.
        String set = block(src, "    public static boolean setCaptureSource(String micSource) {");
        assertTrue(set.indexOf("failedAudioSource = -1;") > set.indexOf("requestedAudioSource = source;"));
        assertTrue(set.indexOf("failedAudioSource = -1;") < set.indexOf("return reconcileOwnerLocked();"));
    }

    @Test
    public void statusReportsWhatOpenedAndFlagsARefusedInput() throws Exception {
        String src = readProjectFile(CONTROLLER);
        String active = block(src, "    public static String activeMicSource() {");
        assertTrue(active.contains("open >= 0 ? open : effectiveAudioSource()"));
        String failed = block(src, "    public static boolean micSourceFailed() {");
        assertFalse(failed.contains("synchronized"));
        assertTrue(failed.contains("failed != -1 && failed == requestedAudioSource"));
        // No capture can open in this JVM, so the report follows the request; a new request also
        // clears the refusal flag.
        try {
            assertTrue(AppAudioCaptureController.setCaptureSource("voice_recognition"));
            assertEquals("voice_recognition", AppAudioCaptureController.activeMicSource());
            assertFalse(AppAudioCaptureController.micSourceFailed());
        } finally {
            assertTrue(AppAudioCaptureController.setCaptureSource("mic"));
        }
        assertEquals("mic", AppAudioCaptureController.activeMicSource());
        assertFalse(AppAudioCaptureController.micSourceFailed());
        // The runtime report carries the flag only when set, and the daemon copies it as a flag.
        String runtime = readProjectFile(RUNTIME);
        assertTrue(runtime.contains(
                "if (AppAudioCaptureController.micSourceFailed()) body.put(\"micSourceFailed\", true);"));
        String daemon = readProjectFile(DAEMON);
        assertTrue(daemon.contains("copyBoolean(input, clean, \"micSourceFailed\");"));
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    @Test
    public void overlayPushesTheSourceBeforeReconcilingDemandAndRuntimeReportsIt() throws Exception {
        String overlay = readProjectFile(OVERLAY);
        String refresh = block(overlay, "    private void refreshAudioConfig() {");
        assertTrue(refresh.contains("VoiceAssistantConfig.isEnabled(root)"));
        assertTrue(refresh.contains("VoiceAssistantConfig.fromRoot(root).micSource"));
        assertTrue(refresh.contains("VoiceAssistantConfig.MIC_SOURCE_MIC"));
        assertTrue(refresh.contains("AppAudioCaptureController.setCaptureSource(micSource);"));
        int refreshCall = overlay.indexOf("refreshAudioConfig();\n                reconcileAudioCapture();");
        assertTrue(refreshCall >= 0);
        String runtime = readProjectFile(RUNTIME);
        assertTrue(runtime.contains("body.put(\"micSource\", AppAudioCaptureController.activeMicSource());"));
        assertTrue(runtime.contains("body.put(\"aecAvailable\", aec);"));
        assertTrue(runtime.contains("body.put(\"aecEnabled\", aecOn);"));
    }

    /** Returns the text between a method's opening line and its closing brace at the same indent. */
    private static String block(String src, String openingLine) {
        int start = src.indexOf(openingLine);
        assertTrue("missing " + openingLine.trim(), start >= 0);
        int bodyStart = start + openingLine.length();
        int end = src.indexOf("\n    }\n", bodyStart);
        assertTrue(end > bodyStart);
        return src.substring(bodyStart, end);
    }

    private static String readProjectFile(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        Path direct = current.resolve(relativePath);
        Path file = Files.exists(direct)
                ? direct : current.resolve("app").resolve(relativePath);
        if (!Files.exists(file)) {
            throw new AssertionError("Could not locate project file: " + relativePath);
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
