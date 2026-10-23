package com.overdrive.app.automation.type;

import com.overdrive.app.automation.AutomationCondition;
import com.overdrive.app.automation.value.Label;

import org.json.JSONObject;

/**
 * A {@link ColourType} whose value may also be a {@code ${var:NAME}} / {@code ${signal:TYPE}}
 * reference, resolved when the action fires (see {@code ApiAction}). A plain palette index
 * validates exactly as in the base type.
 */
public class DynamicColourType extends ColourType {

    public DynamicColourType(Label label, String... colourCodes) {
        super(label, colourCodes);
    }

    @Override
    public boolean isValid(Object value) {
        if (AutomationCondition.isDynamicRef(value)) {
            return ((String) value).length() <= 128;
        }
        return super.isValid(value);
    }

    @Override
    public JSONObject toJson() {
        JSONObject json = super.toJson();
        try { json.put("dynamic", true); } catch (Exception ignored) { }
        return json;
    }
}
