package com.overdrive.app.genai;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The search_web function for realtime sessions. The daemon runs each call
 * ({@link GenAiRuntime#webSearch}) and hands back a short spoken answer. OpenAI sessions always
 * get it (the Realtime API accepts only function and MCP tools); Gemini sessions get it whenever
 * Google's built-in search tool is not offered, as for a key without Search quota on the Live model.
 */
public final class GenAiWebSearch {

    public static final String TOOL_NAME = "search_web";
    private static final int MAX_QUERY_CHARS = 300;
    private static final String DESCRIPTION =
            "Search the web for anything current or not reliably known: "
                    + "weather, traffic, news, sports, prices, opening "
                    + "hours, events, places. Returns a short answer and "
                    + "the names of the sites it came from.";
    private static final String QUERY_GUIDE =
            "A short search query; include the place when it matters.";

    private GenAiWebSearch() {
    }

    public static JSONObject openAiTool() {
        try {
            return new JSONObject()
                    .put("type", "function")
                    .put("name", TOOL_NAME)
                    .put("description", DESCRIPTION)
                    .put("parameters", new JSONObject()
                            .put("type", "object")
                            .put("additionalProperties", false)
                            .put("required", new JSONArray().put("query"))
                            .put("properties", new JSONObject()
                                    .put("query", new JSONObject()
                                            .put("type", "string")
                                            .put("maxLength", MAX_QUERY_CHARS)
                                            .put("description", QUERY_GUIDE))));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    /** Gemini Live declaration: like the other Gemini tools, the field guidance is in the description. */
    public static JSONObject geminiTool() {
        try {
            return new JSONObject()
                    .put("name", TOOL_NAME)
                    .put("description", DESCRIPTION + " query: " + QUERY_GUIDE)
                    .put("parameters", new JSONObject()
                            .put("type", "object")
                            .put("required", new JSONArray().put("query"))
                            .put("properties", new JSONObject()
                                    .put("query", new JSONObject()
                                            .put("type", "string")
                                            .put("maxLength", MAX_QUERY_CHARS))));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    /** Appended to the session instructions wherever this tool is offered. */
    public static String realtimeGuidance() {
        return "For anything current or that you are not sure of, on any subject, call "
                + TOOL_NAME + " with a short query and answer from its result in a "
                + "sentence or two. Say a brief \"let me check\" before calling it. Never "
                + "read out web addresses. If it fails, say you could not check right now.";
    }

    /** The model's query, trimmed and capped; empty when missing. */
    public static String query(JSONObject arguments) {
        String query = arguments == null ? "" : arguments.optString("query", "").trim();
        return query.length() <= MAX_QUERY_CHARS ? query : query.substring(0, MAX_QUERY_CHARS);
    }
}
