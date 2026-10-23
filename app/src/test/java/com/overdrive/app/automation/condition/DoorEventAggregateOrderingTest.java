package com.overdrive.app.automation.condition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.automation.action.SetVariableAction;
import com.overdrive.app.automation.value.Value;
import com.overdrive.app.byd.bodywork.BodyworkConstants;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.UUID;

/**
 * Regression test for the 2026-09-23 field log: "when the DRIVER door changes, if ANY door
 * == closed → …" never fired, and only worked once the trigger was changed to "any door".
 *
 * <p>One door edge publishes two keys — the per-door key and the {@code any} aggregate.
 * {@link Automations#update} evaluates triggers against the LIVE state map the moment a key
 * is stored, so publishing the two keys back-to-back evaluated the driver-door triggers
 * while the aggregate still held its PRE-edge value: the rule was enqueued when the door
 * opened (aggregate still "closed") and cancelled when it closed (aggregate still "open").
 * {@link DoorEvent#onDoorStateChanged} now publishes both keys through
 * {@link Automations#publishTogether} (store both, then deliver), so a rule that triggers
 * on either key and conditions on the other reads the post-edge value of both.
 */
public class DoorEventAggregateOrderingTest {

    private static final int AREA_DRIVER = 1;

    /** Route DaemonLogger to stdout: android.util.Log is not mocked on the JVM. */
    @BeforeClass
    public static void muteAndroidLog() {
        com.overdrive.app.logging.DaemonLogger.Config cfg =
                new com.overdrive.app.logging.DaemonLogger.Config();
        cfg.enableConsoleLog = false;
        cfg.enableFileLog = false;
        cfg.enableStdoutLog = true;
        com.overdrive.app.logging.DaemonLogger.configure(cfg);
    }

    /** Unique per-run so state left in the static maps by other tests can't collide. */
    private final String variableName =
            "doorProbe_" + UUID.randomUUID().toString().substring(0, 8);
    private String automationId;

    @After
    public void cleanup() {
        if (automationId != null) {
            Automations.deleteAutomation(automationId);
        }
        // Leave the driver door CLOSED for whichever test shares this JVM next (a failed
        // assertion above could otherwise strand it open and skew another class's "any"
        // aggregate). The rule is gone, so this is a silent store under the editor-seed gate.
        Automations.markEditorSeedActive();
        DoorEvent.onDoorStateChanged(AREA_DRIVER, BodyworkConstants.STATE_CLOSED);
    }

    @Test
    public void driverDoorTriggerSeesPostEdgeAnyDoorAggregate() throws Exception {
        // DoorEvent's per-area tracking map is static and other tests leave openings OPEN
        // (the hood in AutomationSignalContractTest). Close every opening first so the
        // "any" aggregate in this test is decided only by the driver door. The editor-seed
        // window lets these samples through DoorEvent's reference gate before a rule exists.
        Automations.markEditorSeedActive();
        for (int area = 1; area <= 7; area++) {
            DoorEvent.onDoorStateChanged(area, BodyworkConstants.STATE_CLOSED);
        }
        assertEquals("closed", rawState(DoorEvent.DOOR_DRIVER));
        assertEquals("closed", rawState(DoorEvent.DOOR_ANY));

        automationId = UUID.randomUUID().toString();
        // "When the driver door changes → if any door == closed → set <probe> = fired".
        // The exact rule shape from the field log (its action was a light flash).
        JSONObject json = new JSONObject()
                .put("triggers", new JSONArray()
                        .put(new JSONObject()
                                .put("type", DoorEvent.DOOR_TYPE)
                                .put("variables", new JSONObject()
                                        .put("area", "driver"))))
                .put("conditions", new JSONArray()
                        .put(new JSONObject()
                                .put("type", DoorEvent.DOOR_TYPE)
                                .put("variables", new JSONObject()
                                        .put("area", "any"))
                                .put("comparator", "eq")
                                .put("value", "closed")))
                .put("delay", 0)
                .put("actions", new JSONArray()
                        .put(new JSONObject()
                                .put("type", "setVariable")
                                .put("variables", new JSONObject()
                                        .put("name", variableName)
                                        .put("value", "fired"))))
                .put("name", "driver door → any closed probe")
                .put("disabled", false);
        assertTrue("test automation must parse and register",
                Automations.updateAutomation(automationId, json));
        assertTrue("door keys must be referenced once the rule is enabled",
                DoorEvent.shouldPoll());

        // Registration alone must not run the rule.
        Thread.sleep(1500);
        assertFalse("registering the rule must not fire it",
                "fired".equals(probeVariableText()));

        // Driver door OPENS. The aggregate becomes "open" in the same edge, so the condition
        // "any door == closed" is FALSE and the rule must stay silent. Before the fix the
        // driver-door trigger was evaluated against the stale "closed" aggregate and was
        // ENQUEUED here ("Adding automation to queue" on door-open in the field log); the
        // queue's fire-time re-check then saw the true aggregate and dropped it silently.
        DoorEvent.onDoorStateChanged(AREA_DRIVER, BodyworkConstants.STATE_OPEN);
        assertEquals("open", rawState(DoorEvent.DOOR_DRIVER));
        assertEquals("open", rawState(DoorEvent.DOOR_ANY));
        Thread.sleep(1500);
        assertFalse("driver door OPEN must not satisfy 'any door == closed'",
                "fired".equals(probeVariableText()));

        // Driver door CLOSES — now every opening is closed, so the rule must fire. Before the
        // fix the trigger saw the stale "open" aggregate and the pending item was cancelled.
        DoorEvent.onDoorStateChanged(AREA_DRIVER, BodyworkConstants.STATE_CLOSED);
        assertEquals("closed", rawState(DoorEvent.DOOR_DRIVER));
        assertEquals("closed", rawState(DoorEvent.DOOR_ANY));
        assertEquals("driver door CLOSE with every opening closed must fire the rule",
                "fired", awaitProbeVariable("fired", 5000));
    }

    @Test
    public void anyDoorTriggerSeesPostEdgeDriverDoorKey() throws Exception {
        // The mirror rule shape: "when ANY door changes → if the DRIVER door == open → …".
        // A plain reorder of the two publishes (aggregate first) would have broken THIS one
        // the same way the original order broke the rule above.
        Automations.markEditorSeedActive();
        for (int area = 1; area <= 7; area++) {
            DoorEvent.onDoorStateChanged(area, BodyworkConstants.STATE_CLOSED);
        }

        automationId = UUID.randomUUID().toString();
        JSONObject json = new JSONObject()
                .put("triggers", new JSONArray()
                        .put(new JSONObject()
                                .put("type", DoorEvent.DOOR_TYPE)
                                .put("variables", new JSONObject()
                                        .put("area", "any"))))
                .put("conditions", new JSONArray()
                        .put(new JSONObject()
                                .put("type", DoorEvent.DOOR_TYPE)
                                .put("variables", new JSONObject()
                                        .put("area", "driver"))
                                .put("comparator", "eq")
                                .put("value", "open")))
                .put("delay", 0)
                .put("actions", new JSONArray()
                        .put(new JSONObject()
                                .put("type", "setVariable")
                                .put("variables", new JSONObject()
                                        .put("name", variableName)
                                        .put("value", "fired"))))
                .put("name", "any door → driver open probe")
                .put("disabled", false);
        assertTrue("test automation must parse and register",
                Automations.updateAutomation(automationId, json));
        Thread.sleep(1500);
        assertFalse("registering the rule must not fire it",
                "fired".equals(probeVariableText()));

        // Driver door OPENS: the aggregate transitions closed→open (the trigger) and the
        // driver key already reads "open" (the condition) — must fire.
        DoorEvent.onDoorStateChanged(AREA_DRIVER, BodyworkConstants.STATE_OPEN);
        assertEquals("any door OPEN with the driver door open must fire the rule",
                "fired", awaitProbeVariable("fired", 5000));

        // Driver door CLOSES: aggregate open→closed (trigger), driver key "closed" → silent.
        Automations.update(SetVariableAction.variableEvent(variableName), "reset");
        DoorEvent.onDoorStateChanged(AREA_DRIVER, BodyworkConstants.STATE_CLOSED);
        Thread.sleep(1500);
        assertFalse("driver door CLOSE must not satisfy 'driver door == open'",
                "fired".equals(probeVariableText()));
    }

    private static String rawState(EventData event) {
        Value value = Automations.getStateValue(event);
        return value == null ? null : value.toString();
    }

    private String probeVariableText() {
        Value v = Automations.getStateValue(SetVariableAction.variableEvent(variableName));
        return v == null ? null : v.toString();
    }

    private String awaitProbeVariable(String expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String v = probeVariableText();
            if (expected.equals(v)) return v;
            Thread.sleep(50);
        }
        return probeVariableText();
    }
}
