package com.overdrive.app.genai;

import com.overdrive.app.byd.BydDataCollector;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Typed-intent realtime tool for hands-free GenAI sessions. The model fills the same
 * intent + slots the local command catalog produces, the daemon runs it in-process through
 * VoiceIntentExecutor, and the reply carries only a truthful status. Pure Java (org.json
 * only; the BydDataCollector bound is a compile-time constant) so it is JVM-testable.
 */
public final class GenAiVoiceCommand {

    public static final String TOOL_NAME = "run_overdrive_command";

    /** Result statuses the model is told about; nothing else is ever reported. */
    public static final String STATUS_ACCEPTED = "accepted";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_DUPLICATE = "duplicate";
    public static final String STATUS_BLOCKED = "blocked";
    public static final String STATUS_REFUSED = "refused";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_UNKNOWN = "unknown";
    /** A risky command: ask the driver, then call again with the token after a clear yes. */
    public static final String STATUS_NEEDS_CONFIRMATION = "needs_confirmation";
    /** The call left out a field the command needs (the reply names it): call again with it filled. */
    public static final String STATUS_NEEDS_DETAIL = "needs_detail";

    /** Executor pseudo-code for a result that did not arrive in time. */
    public static final String TIMEOUT = "timeout";

    static final String NONE = "none";

    /** The 18 local catalog intents in catalog order, then the hands-free-only ones (I21). */
    static final List<String> INTENTS = Collections.unmodifiableList(Arrays.asList(
            "climate.power", "climate.set_temp", "climate.step_temp", "climate.fan",
            "climate.recirculation", "climate.max_cool", "climate.auto",
            "climate.defrost_front", "climate.defrost_rear", "climate.goal",
            "seat.heat", "seat.vent", "steering.heat", "sunshade",
            "windows.close_all", "windows.vent_all", "ambient.power", "recording.save_clip",
            "media.pause", "media.play", "media.next", "media.previous", "media.search", "volume.step", "volume.set",
            "nav.go", "phone.call", "vehicle.lock", "vehicle.unlock", "vehicle.power_off",
            "trunk.open", "trunk.close", "windows.open_all", "window.set", "windows.rain_close",
            "sunroof", "child_lock", "mirrors.fold", "lights.headlights", "lights.drl",
            "lights.hazard", "lights.reading", "ambient.colour", "ambient.brightness",
            "climate.purify", "climate.fan_only", "climate.sync", "battery.heat", "display.hud",
            "display.hud_brightness", "display.cluster_brightness", "charging.start",
            "charging.limit", "charging.limit_enable", "charging.current", "charging.smart",
            "battery.hold", "battery.hold_target", "drive.mode", "drive.power", "drive.regen",
            "drive.steering", "drive.brake", "adas.on", "adas.off"));

    private static final List<String> STATUSES = Collections.unmodifiableList(Arrays.asList(
            STATUS_ACCEPTED, STATUS_COMPLETED, STATUS_DUPLICATE, STATUS_BLOCKED,
            STATUS_REFUSED, STATUS_FAILED, STATUS_UNKNOWN, STATUS_NEEDS_CONFIRMATION, STATUS_NEEDS_DETAIL));

    private static final int LEVEL_MAX = 40;
    private static final int DELTA_MAX = 10;
    private static final int QUERY_MAX = 200;
    private static final int ARTIST_MAX = 100;
    private static final int NUMBER_MAX = 24;
    private static final int TOKEN_MAX = 12;

    private GenAiVoiceCommand() {
    }

    public static JSONObject openAiTool() {
        try {
            return new JSONObject()
                    .put("type", "function")
                    .put("name", TOOL_NAME)
                    .put("description", toolDescription())
                    .put("parameters", toolParameters());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    /**
     * Gemini Live declaration. Its parameter schema uses only the keywords the two tools that
     * reached {@code setupComplete} on the car already use (type, additionalProperties,
     * required, properties, enum, minimum, maximum); the per-field guidance the OpenAI strict
     * schema carries as property descriptions lives in the tool description instead.
     */
    public static JSONObject geminiTool() {
        try {
            return new JSONObject()
                    .put("name", TOOL_NAME)
                    .put("description", toolDescription() + " " + fieldGuide())
                    .put("parameters", toolParameters(false));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    /**
     * Turns tool arguments into a VoiceIntentExecutor request, dropping the sentinels the
     * strict schema forces the model to send. Seat intents default to the driver like the
     * local catalog does. An intent outside the enum is rejected (the bridge reports refused).
     */
    public static JSONObject toIntentRequest(JSONObject args, String utteranceId, String locale) {
        String intent = args == null ? "" : args.optString("intent", "").trim();
        if (!INTENTS.contains(intent)) {
            throw new IllegalArgumentException("That command is not available by voice.");
        }
        JSONObject slots = new JSONObject();
        try {
            String on = enumValue(args, "on");
            if (!on.isEmpty()) slots.put("on", on);
            String open = enumValue(args, "open");
            if (!open.isEmpty()) slots.put("open", open);
            String seat = enumValue(args, "seat");
            if (seat.isEmpty() && intent.startsWith("seat.")) seat = "driver";
            if (!seat.isEmpty()) slots.put("seat", seat);
            int level = args.optInt("level", -1);
            if (level >= 0) slots.put("level", level);
            double celsius = args.optDouble("celsius", -1);
            if (!Double.isNaN(celsius) && celsius >= 0) slots.put("celsius", celsius);
            int delta = args.optInt("delta", 0);
            if (delta != 0) slots.put("delta", delta);
            String goal = enumValue(args, "goal");
            if (!goal.isEmpty()) slots.put("goal", goal);
            for (String key : new String[] {"channel", "window", "mode", "feature", "colour"}) {
                String value = enumValue(args, key);
                if (!value.isEmpty()) slots.put(key, value);
            }
            int percent = args.optInt("percent", -1);
            if (percent >= 0) slots.put("percent", percent);
            String query = text(args, "query", QUERY_MAX);
            if (!query.isEmpty()) slots.put("query", query);
            String kind = enumValue(args, "media_kind");
            if (!kind.isEmpty()) slots.put("kind", kind);
            String artist = text(args, "artist", ARTIST_MAX);
            if (!artist.isEmpty()) slots.put("artist", artist);
            String number = text(args, "number", NUMBER_MAX);
            if (!number.isEmpty()) slots.put("number", number);
            JSONObject request = new JSONObject()
                    .put("intent", intent)
                    .put("slots", slots)
                    .put("utteranceId", utteranceId == null ? "" : utteranceId)
                    .put("source", "genai")
                    .put("locale", locale == null ? "" : locale);
            String token = text(args, "confirm_token", TOKEN_MAX);
            if (!token.isEmpty()) request.put("confirmToken", token);
            return request;
        } catch (org.json.JSONException error) {
            throw new IllegalArgumentException("The command arguments were not understood.");
        }
    }

    /** The call's intent when it is one of the catalog intents, else null (for the tool-call log). */
    public static String knownIntent(JSONObject args) {
        String intent = args == null ? "" : args.optString("intent", "").trim();
        return INTENTS.contains(intent) ? intent : null;
    }

    /** Maps a VoiceIntentExecutor (or execute-route) code onto the status enum. */
    public static String statusFor(String executorCode, boolean fireAndForget) {
        if (fireAndForget) return STATUS_ACCEPTED;
        String code = executorCode == null ? "" : executorCode.trim().toLowerCase(Locale.ROOT);
        switch (code) {
            case "done": return STATUS_COMPLETED;
            case "duplicate": return STATUS_DUPLICATE;
            case "blocked_moving": return STATUS_BLOCKED;
            case "unsupported":
            case "refused":
            case "voice_policy": return STATUS_REFUSED;
            case "failed": return STATUS_FAILED;
            case "needs_confirmation": return STATUS_NEEDS_CONFIRMATION;
            default: return STATUS_UNKNOWN;
        }
    }

    /** A status reported by the hands-free client, or the success fallback when it is not one. */
    public static String resultStatus(String reported, boolean success) {
        String status = reported == null ? "" : reported.trim().toLowerCase(Locale.ROOT);
        if (STATUSES.contains(status)) return status;
        return success ? STATUS_COMPLETED : STATUS_FAILED;
    }

    private static String enumValue(JSONObject args, String key) {
        String value = args.optString(key, NONE).trim().toLowerCase(Locale.ROOT);
        return NONE.equals(value) ? "" : value;
    }

    /** Free text; empty or "none" means unused. */
    private static String text(JSONObject args, String key, int max) {
        String value = args.optString(key, "").trim();
        if (NONE.equalsIgnoreCase(value)) return "";
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static final String[][] FIELD_GUIDE = {
            {"intent", "The cabin command to run."},
            {"on", "On or off for power-style commands; none when unused."},
            {"open", "Open or close for the sunshade; none when unused."},
            {"seat", "Seat for seat.heat and seat.vent; none means the driver."},
            {"level", "Seat level 0 (off), 1 (low) or 2 (high), fan speed 1 to 7, volume.set level 0 to 40, or charging.current amps 6, 8, 10 or 16; -1 when unused."},
            {"celsius", "Target temperature in Celsius for climate.set_temp; -1 when unused."},
            {"delta", "Steps for climate.step_temp, climate.fan or volume.step, negative for cooler, slower or quieter; 0 when unused."},
            {"goal", "Comfort goal for climate.goal; none when unused."},
            {"channel", "Audio channel for volume.step and volume.set; none means media."},
            {"percent", "Percent for window.set, ambient.brightness, display brightness, charging.limit (50 to 100), battery.hold_target (15 to 70) or volume.set; -1 when unused."},
            {"window", "Window for window.set; none when unused."},
            {"mode", "Mode for sunroof, lights.headlights, drive.mode, drive.power, drive.regen, drive.steering, drive.brake, or max for charging.current; none when unused."},
            {"feature", "Driver assistance feature for adas.on; none when unused."},
            {"colour", "Ambient light colour for ambient.colour; none when unused."},
            {"query", "Destination for nav.go (a place, an address, or a saved place such as Home), the contact name for phone.call, or for media.search only the name of what to play, as the driver said it, without words such as play, songs, music or on Spotify; empty when unused."},
            {"media_kind", "What media.search plays: song (query is its title), artist, album, playlist (also a genre, mood or chart, such as lofi, Punjabi or Top 50 India), or liked (the driver's liked songs, query empty); none when unused."},
            {"artist", "For media.search, the artist when the driver names one with a song or album (Tum Hi Ho by Arijit Singh: query Tum Hi Ho, artist Arijit Singh); empty when unused."},
            {"number", "Phone number for phone.call when the driver says digits; empty when unused."},
            {"confirm_token", "Only when repeating a call after needs_confirmation and a clear yes: the token it returned; empty otherwise."},
    };

    /** The OpenAI strict schema: every property described in place. */
    private static JSONObject toolParameters() throws Exception {
        return toolParameters(true);
    }

    /**
     * {@code describeProperties} false yields the Gemini variant: same properties, required list,
     * enums and bounds, but no property-level {@code description} (see {@link #geminiTool()}).
     */
    private static JSONObject toolParameters(boolean describeProperties) throws Exception {
        JSONObject properties = new JSONObject()
                .put("intent", new JSONObject()
                        .put("type", "string")
                        .put("enum", new JSONArray(INTENTS)))
                .put("on", new JSONObject()
                        .put("type", "string")
                        .put("enum", enumOf("on", "off")))
                .put("open", new JSONObject()
                        .put("type", "string")
                        .put("enum", enumOf("open", "close")))
                .put("seat", new JSONObject()
                        .put("type", "string")
                        .put("enum", enumOf("driver", "passenger")))
                .put("level", new JSONObject()
                        .put("type", "integer")
                        .put("minimum", -1)
                        .put("maximum", LEVEL_MAX))
                .put("celsius", new JSONObject()
                        .put("type", "number")
                        .put("minimum", -1)
                        .put("maximum", BydDataCollector.AC_SETPOINT_MAX_C))
                .put("delta", new JSONObject()
                        .put("type", "integer")
                        .put("minimum", -DELTA_MAX)
                        .put("maximum", DELTA_MAX))
                .put("goal", new JSONObject()
                        .put("type", "string")
                        .put("enum", enumOf("cool", "warm", "defog", "fresh")))
                .put("channel", new JSONObject()
                        .put("type", "string")
                        .put("enum", enumOf("media", "navigation", "voice", "phone")))
                .put("percent", new JSONObject()
                        .put("type", "integer")
                        .put("minimum", -1)
                        .put("maximum", 100))
                .put("window", new JSONObject()
                        .put("type", "string")
                        .put("enum", enumOf("all", "driver", "passenger", "rear_left", "rear_right")))
                .put("mode", new JSONObject()
                        .put("type", "string")
                        .put("enum", enumOf("open", "close", "stop", "half", "tilt", "off", "auto",
                                "parking", "low", "normal", "eco", "sport", "snow", "ev", "hev",
                                "standard", "high", "max", "comfort")))
                .put("feature", new JSONObject()
                        .put("type", "string")
                        .put("enum", enumOf("esp", "itac", "lane_assist", "blind_spot", "traffic_sign",
                                "rear_cross_traffic", "front_cross_traffic", "traffic_light",
                                "open_door_warning", "rear_collision_warning", "speed_limit_control",
                                "speed_limit_warning", "emergency_lane_keep", "rear_cross_brake",
                                "front_cross_brake", "forward_collision_warning", "emergency_braking")))
                .put("colour", new JSONObject()
                        .put("type", "string")
                        .put("enum", enumOf("red", "orange", "amber", "yellow", "green", "cyan", "blue",
                                "purple", "pink", "white")))
                .put("query", new JSONObject()
                        .put("type", "string")
                        .put("maxLength", QUERY_MAX))
                .put("media_kind", new JSONObject()
                        .put("type", "string")
                        .put("enum", enumOf("song", "artist", "album", "playlist", "liked")))
                .put("artist", new JSONObject()
                        .put("type", "string")
                        .put("maxLength", ARTIST_MAX))
                .put("number", new JSONObject()
                        .put("type", "string")
                        .put("maxLength", NUMBER_MAX))
                .put("confirm_token", new JSONObject()
                        .put("type", "string")
                        .put("maxLength", TOKEN_MAX));
        if (describeProperties) {
            for (String[] field : FIELD_GUIDE) {
                properties.getJSONObject(field[0]).put("description", field[1]);
            }
        }
        return new JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put("required", new JSONArray()
                        .put("intent")
                        .put("on")
                        .put("open")
                        .put("seat")
                        .put("level")
                        .put("celsius")
                        .put("delta")
                        .put("goal")
                        .put("channel")
                        .put("percent")
                        .put("window")
                        .put("mode")
                        .put("feature")
                        .put("colour")
                        .put("query")
                        .put("media_kind")
                        .put("artist")
                        .put("number")
                        .put("confirm_token"))
                .put("properties", properties);
    }

    /** The property descriptions as one sentence per field, for a schema that carries none. */
    static String fieldGuide() {
        StringBuilder out = new StringBuilder("Fields:");
        for (int i = 0; i < FIELD_GUIDE.length; i++) {
            out.append(i == 0 ? " " : " | ").append(FIELD_GUIDE[i][0]).append(": ")
                    .append(FIELD_GUIDE[i][1]);
        }
        return out.toString();
    }

    private static JSONArray enumOf(String... values) {
        JSONArray out = new JSONArray().put(NONE);
        for (String value : values) out.put(value);
        return out;
    }

    private static String toolDescription() {
        return "Run one OverDrive command right away: climate power, temperature, "
                + "fan, recirculation, max cooling, auto mode, defrost, a comfort goal, seat "
                + "heating or ventilation, steering wheel heat, sunshade, windows, ambient "
                + "lights, or saving a dashcam clip; also music (pause, play, next, previous, "
                + "volume, or media.search to play a named song, artist, album, playlist or mood, "
                + "or the driver's liked songs, in the car's music app), navigation to a place, "
                + "calling a contact or number, locking or "
                + "unlocking, the trunk, sunroof, child lock, mirrors, lights (lights.drl is the "
                + "daytime running lights, said \"DRL\" or \"DRLs\", and turning them off is allowed; "
                + "a lights request is never music), "
                + "displays, charging "
                + "and battery hold, drive settings, and turning driver assistance on (never off). "
                + "Fill every field; send none, -1, 0 or an empty string for fields the command "
                + "does not use. Call it only for an explicit request with every detail known, "
                + "never for questions. media.play resumes whatever the car was playing: call it "
                + "at once for play, resume, continue or start the music, without asking what to "
                + "play. media.search plays the closest match at once: never ask which version, "
                + "artist, album or language, and never answer a music request from your own "
                + "playback, which cannot reach the car's speakers. "
                + "A needs_confirmation status means: ask the driver its "
                + "detail as a yes or no question, and only after a clear yes call it again with "
                + "the same fields and its confirm_token.";
    }
}
