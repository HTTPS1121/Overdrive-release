package com.overdrive.app.voice.daemon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.automation.AutomationAction;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The hands-free allowlist walker must fail closed on anything it cannot vouch for. */
public class VoiceExecutionPolicyTest {

    private static final VoiceExecutionPolicy.GroupResolver NO_GROUPS = id -> List.of();

    @Test
    public void allowlistedOnlyAutomationPasses() {
        VoiceExecutionPolicy.Verdict v = VoiceExecutionPolicy.check(Arrays.asList(
                action("setAc", "on", "true"),
                action("setAcTemp", "temp", "22"),
                action("seat", "seat", "driver"),
                action("showToast", "text", "hi"),
                action("pause", "seconds", "2"),
                action("setVariable", "name", "x")), NO_GROUPS);
        assertTrue(v.allowed);
        assertNull(v.offendingType);
    }

    @Test
    public void emptyAndNullListsPass() {
        assertTrue(VoiceExecutionPolicy.check(List.of(), NO_GROUPS).allowed);
        assertTrue(VoiceExecutionPolicy.check(null, NO_GROUPS).allowed);
    }

    @Test
    public void topLevelShellIsRefused() {
        VoiceExecutionPolicy.Verdict v = VoiceExecutionPolicy.check(Arrays.asList(
                action("setAc", "on", "true"),
                action("shell", "command", "reboot")), NO_GROUPS);
        assertFalse(v.allowed);
        assertEquals("shell", v.offendingType);
        assertEquals("That automation includes an action that can't be run by voice (shell).",
                v.message);
    }

    @Test
    public void forbiddenInsideIfBodyIsRefused() {
        AutomationAction cond = new AutomationAction("if", new HashMap<>(),
                Arrays.asList(action("setAc", "on", "true"), action("unlock")),
                null);
        VoiceExecutionPolicy.Verdict v = VoiceExecutionPolicy.check(List.of(cond), NO_GROUPS);
        assertFalse(v.allowed);
        assertEquals("unlock", v.offendingType);
    }

    @Test
    public void elseBranchIsWalkedToo() {
        AutomationAction cond = new AutomationAction("if", new HashMap<>(),
                List.of(action("setAc", "on", "true")),
                List.of(action("iviReboot")));
        VoiceExecutionPolicy.Verdict v = VoiceExecutionPolicy.check(List.of(cond), NO_GROUPS);
        assertFalse(v.allowed);
        assertEquals("iviReboot", v.offendingType);
    }

    @Test
    public void loopBodyIsWalked() {
        AutomationAction loop = new AutomationAction("loop", new HashMap<>(),
                List.of(action("flash")), null);
        assertEquals("flash", VoiceExecutionPolicy.check(List.of(loop), NO_GROUPS).offendingType);
        AutomationAction clean = new AutomationAction("loop", new HashMap<>(),
                List.of(action("speak", "text", "hello")), null);
        assertTrue(VoiceExecutionPolicy.check(List.of(clean), NO_GROUPS).allowed);
    }

    @Test
    public void forbiddenInsideActionGroupIsRefused() {
        Map<String, List<AutomationAction>> groups = new HashMap<>();
        groups.put("g1", List.of(action("setAc", "on", "true"), action("lock")));
        VoiceExecutionPolicy.Verdict v = VoiceExecutionPolicy.check(
                List.of(group("g1")), groups::get);
        assertFalse(v.allowed);
        assertEquals("lock", v.offendingType);
    }

    @Test
    public void nestedCleanGroupsPass() {
        Map<String, List<AutomationAction>> groups = new HashMap<>();
        groups.put("a", List.of(action("setAc", "on", "true"), group("b")));
        groups.put("b", List.of(action("rearDefrost", "on", "true")));
        assertTrue(VoiceExecutionPolicy.check(List.of(group("a")), groups::get).allowed);
    }

    @Test
    public void sameGroupTwiceInSequenceIsNotACycle() {
        Map<String, List<AutomationAction>> groups = new HashMap<>();
        groups.put("a", List.of(action("speak", "text", "x")));
        assertTrue(VoiceExecutionPolicy.check(
                List.of(group("a"), group("a")), groups::get).allowed);
    }

    @Test
    public void groupCycleIsRefused() {
        Map<String, List<AutomationAction>> groups = new HashMap<>();
        groups.put("a", List.of(group("b")));
        groups.put("b", List.of(group("a")));
        VoiceExecutionPolicy.Verdict v = VoiceExecutionPolicy.check(
                List.of(group("a")), groups::get);
        assertFalse(v.allowed);
        assertEquals("cycle", v.offendingType);
    }

    @Test
    public void groupChainBeyondDepthCapIsRefused() {
        Map<String, List<AutomationAction>> groups = new HashMap<>();
        int chain = VoiceExecutionPolicy.MAX_DEPTH + 1;
        for (int i = 0; i < chain; i++) {
            List<AutomationAction> body = new ArrayList<>();
            body.add(i + 1 < chain ? group("g" + (i + 1)) : action("speak", "text", "deep"));
            groups.put("g" + i, body);
        }
        VoiceExecutionPolicy.Verdict v = VoiceExecutionPolicy.check(
                List.of(group("g0")), groups::get);
        assertFalse(v.allowed);
        assertEquals("depth", v.offendingType);
    }

    @Test
    public void unknownTypeIsRefused() {
        VoiceExecutionPolicy.Verdict v = VoiceExecutionPolicy.check(
                List.of(action("frobnicate")), NO_GROUPS);
        assertFalse(v.allowed);
        assertEquals("frobnicate", v.offendingType);
        assertEquals("null", VoiceExecutionPolicy.check(
                List.of(new AutomationAction(null, new HashMap<>())), NO_GROUPS).offendingType);
        List<AutomationAction> withNull = new ArrayList<>();
        withNull.add(null);
        assertEquals("null", VoiceExecutionPolicy.check(withNull, NO_GROUPS).offendingType);
    }

    @Test
    public void groupWithoutIdOrUnresolvableGroup() {
        assertEquals("actionGroup", VoiceExecutionPolicy.check(
                List.of(new AutomationAction("actionGroup", new HashMap<>())),
                NO_GROUPS).offendingType);
        // An unknown group resolves to an empty body, which has nothing to refuse.
        assertTrue(VoiceExecutionPolicy.check(List.of(group("missing")), NO_GROUPS).allowed);
        assertTrue(VoiceExecutionPolicy.check(List.of(group("missing")), id -> null).allowed);
    }

    @Test
    public void windowsAllOnlyAllowedForClose() {
        assertTrue(VoiceExecutionPolicy.check(
                List.of(action("windowsAll", "command", "2")), NO_GROUPS).allowed);
        VoiceExecutionPolicy.Verdict open = VoiceExecutionPolicy.check(
                List.of(action("windowsAll", "command", "1")), NO_GROUPS);
        assertFalse(open.allowed);
        assertEquals("windowsAll", open.offendingType);
        assertFalse(VoiceExecutionPolicy.check(
                List.of(action("windowsAll")), NO_GROUPS).allowed);
    }

    @Test
    public void appMediaAndLightActionsAreAllowed() {
        // "OverDrive, run <automation>" may open apps (incl. split screen), drive the media
        // session, play library audio and toggle DRL / HUD / screen power: none of these
        // unlock, move or power the vehicle.
        VoiceExecutionPolicy.Verdict v = VoiceExecutionPolicy.check(Arrays.asList(
                action("openApp", "package", "com.spotify.music"),
                action("openAppSplit", "package", "com.google.android.apps.maps"),
                action("openAppsSplit", "primary", "com.spotify.music"),
                action("mediaControl", "key", "play"),
                action("mediaVolume", "level", "12"),
                action("channelVolume", "channel", "media"),
                action("playAudio", "name", "chime"),
                action("stopAudio"),
                action("drl", "payload", "off"),
                action("hudPower", "value", "1"),
                action("screenPower", "value", "0")), NO_GROUPS);
        assertTrue(v.allowed);
        assertNull(v.offendingType);
    }

    @Test
    public void headlightsVideoAndRadioStayRefused() {
        for (String key : new String[] {"headlightLevel", "playVideo", "radio"}) {
            VoiceExecutionPolicy.Verdict v = VoiceExecutionPolicy.check(
                    List.of(action(key)), NO_GROUPS);
            assertFalse(key, v.allowed);
            assertEquals(key, v.offendingType);
        }
    }

    @Test
    public void everyDeniedVehicleKeyIsRefused() {
        for (String key : new String[] {"unlock", "lock", "flash", "findCar", "powerOn",
                "powerOff", "tailgate", "windows", "windowsPreset", "rainCloseWindows",
                "child_lock", "esp_control", "mirror_fold", "drive_mode", "mqttPublish",
                "automationControl", "radio", "safeKeep", "uiNav", "screenshot",
                "surveillance", "recording", "iviReboot", "api"}) {
            VoiceExecutionPolicy.Verdict v = VoiceExecutionPolicy.check(
                    List.of(action(key)), NO_GROUPS);
            assertFalse(key, v.allowed);
            assertEquals(key, v.offendingType);
        }
    }

    private static AutomationAction action(String type) {
        return new AutomationAction(type, new HashMap<>());
    }

    private static AutomationAction action(String type, String key, String value) {
        Map<String, Object> vars = new HashMap<>();
        vars.put(key, value);
        return new AutomationAction(type, vars);
    }

    private static AutomationAction group(String id) {
        return action("actionGroup", "groupId", id);
    }
}
