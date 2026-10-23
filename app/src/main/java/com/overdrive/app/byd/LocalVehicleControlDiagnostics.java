package com.overdrive.app.byd;

import android.content.Context;
import android.os.Build;

import com.overdrive.app.camera.dilink5.DiLink5Platform;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Read-only API metadata. Never invokes a setter or exports VINs, credentials, or key buffers. */
final class LocalVehicleControlDiagnostics {
    private LocalVehicleControlDiagnostics() {}

    static JSONObject read(Context context, Object bodywork, Object doorlock, Object ota,
            Object engine, Object power, int powerLevel, int lockState) {
        JSONObject result = new JSONObject();
        try {
            result.put("sdk", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE)
                    .put("headUnit", Build.MODEL).put("mode", DiLink5Platform.currentActiveMode())
                    .put("powerLevel", powerLevel).put("doorLockState", lockState);
            Object manager = context == null ? null : context.getSystemService("auto");
            result.put("autoManager", describe(manager)).put("bodywork", describe(bodywork))
                    .put("doorlock", describe(doorlock)).put("ota", describe(ota))
                    .put("engine", describe(engine)).put("power", describe(power));
            if (bodywork != null) {
                try {
                    Method getter = bodywork.getClass().getMethod("getAutoModelName");
                    if (getter.getReturnType() == String.class)
                        result.put("vehicleModel", getter.invoke(bodywork));
                } catch (ReflectiveOperationException unavailable) {
                    // Method metadata still identifies the SDK; model names are optional.
                }
            }
        } catch (Exception | LinkageError unavailable) {
            try { result.put("diagnosticError", unavailable.getClass().getSimpleName()); }
            catch (Exception ignored) {}
        }
        return result;
    }

    static JSONObject describe(Object device) throws Exception {
        JSONObject result = new JSONObject().put("available", device != null);
        if (device == null) return result;
        Class<?> type = device.getClass();
        result.put("class", type.getName());
        List<String> methods = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (!relevant(method.getName())) continue;
            StringBuilder signature = new StringBuilder(method.getReturnType().getTypeName())
                    .append(' ').append(method.getName()).append('(');
            Class<?>[] arguments = method.getParameterTypes();
            for (int i = 0; i < arguments.length; i++) {
                if (i > 0) signature.append(',');
                signature.append(arguments[i].getTypeName());
            }
            methods.add(signature.append(')').toString());
        }
        Collections.sort(methods);
        result.put("methods", new JSONArray(methods));
        JSONObject constants = new JSONObject();
        for (Field field : type.getFields()) {
            String name = field.getName();
            if (field.getType() == int.class && Modifier.isStatic(field.getModifiers())
                    && (name.startsWith("DOOR_LOCK_") || name.startsWith("POWER_")
                        || name.startsWith("START_") || name.startsWith("REMOTE_KEY_"))) {
                try { constants.put(name, field.getInt(null)); }
                catch (ReflectiveOperationException inaccessible) {}
            }
        }
        return result.put("constants", constants);
    }

    private static boolean relevant(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return name.equals("setBuffer") || name.equals("getBuffer")
                || lower.contains("power") || lower.contains("ignition")
                || lower.contains("doorlock") || lower.contains("doorslock")
                || lower.contains("remotekey") || lower.contains("remotecontrol")
                || lower.contains("startstate") || lower.contains("startengine")
                || lower.contains("engineon") || lower.contains("enginestart")
                || lower.contains("accstate") || lower.contains("autostart");
    }
}
