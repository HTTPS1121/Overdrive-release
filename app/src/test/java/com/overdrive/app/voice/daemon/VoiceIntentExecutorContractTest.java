package com.overdrive.app.voice.daemon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Pins the executor's answer to a seat the car cannot control: a plain refusal with a message that
 * names the seats it can, never a guess at the driver's or passenger's seat; and the ambient
 * main-switch failure that names its cause (unsupported on this car vs a refused write).
 */
public class VoiceIntentExecutorContractTest {

    private static final String EXECUTOR =
            "src/main/java/com/overdrive/app/voice/daemon/VoiceIntentExecutor.java";

    @Test
    public void unsupportedSeatIsRefusedWithAnExplicitMessage() throws Exception {
        String body = block(readProjectFile(EXECUTOR),
                "    private static void seat(Plan plan, JSONObject slots, boolean ventilation) {");
        assertTrue(body.contains("UNSUPPORTED_SEATS.contains(seat)"));
        assertTrue(body.contains("plan.code = REFUSED;"));
        assertTrue(body.contains(
                "\"Only the driver and passenger seats can be controlled by voice.\""));
        // A bad level is still "unsupported", not a refusal.
        assertTrue(body.contains("unsupported(plan, \"Seat levels are off, low and high"));
    }

    @Test
    public void unsupportedSeatsCoverEverythingTheMatcherCanReport() throws Exception {
        String src = readProjectFile(EXECUTOR);
        int at = src.indexOf("UNSUPPORTED_SEATS = new HashSet<>(Arrays.asList(");
        assertTrue(at >= 0);
        String set = src.substring(at, src.indexOf("));", at));
        for (String seat : new String[] {"rear", "back", "left", "right", "all"}) {
            assertTrue(seat, set.contains("\"" + seat + "\""));
        }
    }

    @Test
    public void ambientFailureSaysUnsupportedOnlyWhenTheCarReportsNoAmbientState() {
        // Readback UNAVAILABLE on every source: the car does not report ambient lights at all.
        assertEquals("unsupported", VoiceIntentExecutor.ambientFailureCode(Boolean.FALSE));
        assertEquals("This car does not report ambient lights, so they cannot be switched by voice.",
                VoiceIntentExecutor.ambientFailureMessage(Boolean.FALSE));
        // The car reports a state but refused the write: a write error, named as such.
        assertEquals("failed", VoiceIntentExecutor.ambientFailureCode(Boolean.TRUE));
        assertEquals("The car refused the ambient light switch on every path.",
                VoiceIntentExecutor.ambientFailureMessage(Boolean.TRUE));
        // The readback itself threw: never upgrade to unsupported on a guess.
        assertEquals("failed", VoiceIntentExecutor.ambientFailureCode(null));
        assertEquals("The car refused the ambient light switch on every path.",
                VoiceIntentExecutor.ambientFailureMessage(null));
    }

    @Test
    public void ambientPowerRefinesOnlyARouterFailure() throws Exception {
        String body = block(readProjectFile(EXECUTOR),
                "    private static void ambientPower(Plan plan, JSONObject slots) {");
        int toggle = body.indexOf("toggle(plan, slots, \"on\", on -> new VehicleCommandRouter.AmbientPowerCommand(on, \"both\")");
        int guard = body.indexOf("if (!FAILED.equals(plan.code)) return;");
        int readback = body.indexOf("ambientStateReported()");
        assertTrue(toggle >= 0);
        assertTrue(guard > toggle);
        assertTrue(readback > guard);
        // The write is never skipped on the readback alone: a trim may accept the write yet report nothing.
        assertTrue(body.indexOf("toggle(") < body.indexOf("ambientStateReported"));
    }

    @Test
    public void outcomeLogNamesTheReasonWhenACommandDidNotComplete() throws Exception {
        assertEquals("done", VoiceIntentExecutor.outcomeLog(
                new JSONObject().put("code", "done").put("message", "Ambient lights off.")));
        assertEquals("failed: The car refused the ambient light switch on every path.",
                VoiceIntentExecutor.outcomeLog(new JSONObject().put("code", "failed")
                        .put("message", "The car refused the ambient light switch on every path.")));
        assertEquals("unsupported: Say on or off.", VoiceIntentExecutor.outcomeLog(
                new JSONObject().put("code", "unsupported").put("message", " Say on or off. ")));
        assertEquals("failed", VoiceIntentExecutor.outcomeLog(new JSONObject().put("code", "failed")));
        assertEquals("failed", VoiceIntentExecutor.outcomeLog(null));
        assertEquals("refused: line one line two", VoiceIntentExecutor.outcomeLog(
                new JSONObject().put("code", "refused").put("message", "line one\nline two")));
    }

    @Test
    public void seatIntentsStayTiered() throws Exception {
        String body = block(readProjectFile(EXECUTOR), "    static int tierOf(String intent) {");
        assertTrue(body.contains("\"seat.heat\""));
        assertTrue(body.contains("\"seat.vent\""));
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
