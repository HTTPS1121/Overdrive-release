package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Every provider tool call leaves one field-log line with the tool, its enum argument, latency
 * and result size, never argument text or a result; hands-free sessions start with the live
 * snapshot and offer the location context mode, the Assistant page does neither.
 */
public class GenAiVoiceWebSocketToolCallLogTest {

    private static final String SOCKET =
            "src/main/java/com/overdrive/app/server/GenAiVoiceWebSocket.java";

    @Test
    public void theLineCarriesOnlyTheToolTheEnumTheLatencyAndTheSize() {
        assertEquals("Realtime tool call: name=get_overdrive_context mode=current_vehicle ms=42 resultBytes=1834 status=approved",
                GenAiVoiceWebSocket.toolCallLine("get_overdrive_context", "mode", "current_vehicle", 42, 1834, "approved"));
        assertEquals("Realtime tool call: name=run_overdrive_command intent=climate.set_temp ms=310 resultBytes=48 status=completed",
                GenAiVoiceWebSocket.toolCallLine("run_overdrive_command", "intent", "climate.set_temp", 310, 48, "completed"));
        assertEquals("Realtime tool call: name=propose_overdrive_action action=sunshade ms=0 resultBytes=90 status=confirmation_required",
                GenAiVoiceWebSocket.toolCallLine("propose_overdrive_action", "action", "sunshade", -5, 90, "confirmation_required"));
    }

    @Test
    public void freeTextAndUnknownNamesNeverReachTheLog() {
        String line = GenAiVoiceWebSocket.toolCallLine("tell me the user's secret plan", "mode",
                "what is my address at 12 Main St", 7, 10, "a status with spaces");
        assertEquals("Realtime tool call: name=unknown mode=invalid ms=7 resultBytes=10", line);
        assertFalse(line.contains("secret"));
        assertFalse(line.contains("Main"));
        assertEquals("Realtime tool call: name=run_overdrive_command intent=invalid ms=1 resultBytes=0",
                GenAiVoiceWebSocket.toolCallLine("run_overdrive_command", "intent", null, 1, -3, null));
        assertEquals("Realtime tool call: name=unknown ms=0 resultBytes=5 status=rejected",
                GenAiVoiceWebSocket.toolCallLine(null, null, null, 0, 5, "rejected"));
        assertEquals(6, GenAiVoiceWebSocket.utf8Bytes("°C ok"));
        assertEquals(0, GenAiVoiceWebSocket.utf8Bytes(null));
    }

    @Test
    public void theContextModeIsTakenFromTheValidatedRequestOnly() throws Exception {
        assertEquals("latest_trip", GenAiVoiceWebSocket.contextMode(
                new JSONObject().put("mode", "latest_trip").put("query", "free text"), false));
        assertNull(GenAiVoiceWebSocket.contextMode(new JSONObject().put("mode", "everything"), true));
        assertNull(GenAiVoiceWebSocket.contextMode(new JSONObject().put("mode", "location"), false));
        assertEquals("location", GenAiVoiceWebSocket.contextMode(new JSONObject().put("mode", "location"), true));
        assertNull(GenAiVoiceWebSocket.contextMode(null, true));
    }

    @Test
    public void everyToolResponsePathLogsTheCall() throws Exception {
        String src = readProjectFile(SOCKET);
        String openAi = between(src, "            private boolean handleOpenAiToolCalls(JSONObject event)",
                "            private void sendOpenAiFunctionOutput(");
        // Multi-call rejection, context, proposal, invalid proposal, unknown tool.
        assertEquals(5, count(openAi, "logToolCall("));
        String gemini = between(src, "            private boolean handleGeminiToolCalls(JSONObject event)",
                "    private static String providerUrl(");
        assertEquals(1, count(gemini, "logToolCall(name, argKey, argValue, calledAt, response.toString(),"));
        // Deferred results: the typed command, the approved/declined context, the hands-free action.
        assertTrue(between(src, "        private void replyToVoiceCommand(", "        private void sendProviderActionResult(")
                .contains("logToolCall(GenAiVoiceCommand.TOOL_NAME, \"intent\", intent, calledAt,"));
        assertTrue(between(src, "        private void respondToContextApproval(", "        private String sendProviderContextResult(")
                .contains("logToolCall(GenAiContext.REALTIME_TOOL_NAME, \"mode\","));
        assertTrue(between(src, "        private boolean sendProviderFunctionResult(", "        private void sendFunctionOutput(")
                .contains("logToolCall(GenAiAction.TOOL_NAME, \"action\", actionType,"));
        // The log helper is the only writer of the line and prints the formatter's output only.
        assertEquals(1, count(src, "logger.info(toolCallLine("));
    }

    @Test
    public void handsFreeSessionsStartWithTheSnapshotAndOfferTheLocationMode() throws Exception {
        String src = readProjectFile(SOCKET);
        assertTrue(src.contains("+ (farField ? \"\\n\\n\" + liveSnapshot() : \"\"),"));
        assertTrue(src.contains("GenAiLiveSnapshot.forSession(shareVehicleState, shareLocation)"));
        assertTrue(src.contains("shareLocation = voice.shareLocationWithAi;"));
        assertTrue(src.contains("GenAiContext.realtimeToolResult(pending.arguments, farField, shareLocation)"));
        assertTrue(src.contains("GenAiContext.realtimeToolRequest(arguments, farField);"));
        for (boolean far : new boolean[] {false, true}) {
            JSONObject openAi = GenAiVoiceWebSocket.openAiSetup("gpt-realtime-2.1", "grounded", far);
            String context = openAi.getJSONObject("session").getJSONArray("tools").getJSONObject(1).toString();
            assertEquals(far, context.contains("\"location\""));
            JSONArray gemini = GenAiVoiceWebSocket.geminiSetup("gemini-3.1-flash-live-preview", "grounded", far)
                    .getJSONObject("setup").getJSONArray("tools").getJSONObject(0)
                    .getJSONArray("functionDeclarations");
            assertEquals(far, gemini.getJSONObject(1).toString().contains("\"location\""));
        }
        // The two-argument page setups are unchanged.
        assertFalse(GenAiVoiceWebSocket.openAiSetup("gpt-realtime-2.1", "grounded").toString().contains("\"location\""));
        assertFalse(GenAiVoiceWebSocket.geminiSetup("gemini-3.1-flash-live-preview", "grounded").toString().contains("\"location\""));
    }

    private static String between(String src, String start, String end) {
        int from = src.indexOf(start);
        assertTrue("missing " + start.trim(), from >= 0);
        int to = src.indexOf(end, from + start.length());
        assertTrue("missing " + end.trim(), to > from);
        return src.substring(from, to);
    }

    private static int count(String text, String needle) {
        int n = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            n++;
            at = text.indexOf(needle, at + needle.length());
        }
        return n;
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
