package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.type.EnumType;
import com.overdrive.app.automation.type.IntType;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.safekeep.SafeKeep;
import com.overdrive.app.server.Messages;

import java.util.Arrays;
import java.util.List;

/**
 * SafeKeep control from automations and key mapping. In-process and detached: it only signals
 * the SafeKeep planner and returns, so a slow network never blocks the automation queue.
 */
public class SafeKeepAction extends BaseAction {
    private static final String TYPE = "safeKeep";

    private final Label label;
    private final String description;
    private final List<Type> variables = Arrays.asList(
            new EnumType(new Label("command", "automation.safekeep_command"),
                    new Label("syncNow", "automation.safekeep_sync_now"),
                    new Label("pause", "automation.safekeep_pause"),
                    new Label("resume", "automation.safekeep_resume"),
                    new Label("uploadLatestEvent", "automation.safekeep_upload_latest")),
            new IntType(new Label("minutes", "automation.safekeep_minutes"), 0, 1440));

    public SafeKeepAction(Label label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getType() {
        return TYPE;
    }

    public Label getLabel() {
        return label;
    }

    public String getDescription() {
        return Messages.get(description);
    }

    public List<Type> getVariables() {
        return variables;
    }

    public void trigger(AutomationAction automationAction) {
        Object cmd = automationAction.getVariables().get("command");
        Object minutes = automationAction.getVariables().get("minutes");
        int m = minutes instanceof Number ? ((Number) minutes).intValue() : 60;
        String c = cmd == null ? "syncNow" : cmd.toString();
        if (!SafeKeep.command(c, m)) {
            logger.warn("Automation safeKeep " + c + " ignored: SafeKeep is off");
        }
    }
}
