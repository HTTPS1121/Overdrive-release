package com.overdrive.app.safekeep.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

/** Reads ONE SoC thermal zone, chosen once, instead of scanning every zone on each sample. */
public final class Thermal {

    private static volatile String zoneTempPath;
    private static volatile boolean scanned;

    private Thermal() {}

    /** Degrees Celsius, or NaN when no usable zone exists. */
    public static float readCelsius() {
        if (!scanned) scan();
        String p = zoneTempPath;
        return p == null ? Float.NaN : readTemp(p);
    }

    private static synchronized void scan() {
        if (scanned) return;
        String best = null;
        float bestTemp = -1f;
        String anyBest = null;
        float anyTemp = -1f;
        File[] zones = new File("/sys/class/thermal").listFiles();
        if (zones != null) {
            for (File z : zones) {
                if (!z.getName().startsWith("thermal_zone")) continue;
                String tempPath = new File(z, "temp").getAbsolutePath();
                float t = readTemp(tempPath);
                if (Float.isNaN(t)) continue;
                String type = readLine(new File(z, "type").getAbsolutePath());
                String ty = type == null ? "" : type.toLowerCase(Locale.ROOT);
                boolean socLike = ty.contains("cpu") || ty.contains("soc") || ty.contains("apc")
                        || ty.contains("cluster") || ty.contains("tsens") || ty.contains("cpuss");
                if (socLike && t > bestTemp) {
                    bestTemp = t;
                    best = tempPath;
                }
                boolean excluded = ty.contains("battery") || ty.contains("pa_") || ty.contains("xo");
                if (!excluded && t > anyTemp) {
                    anyTemp = t;
                    anyBest = tempPath;
                }
            }
        }
        zoneTempPath = best != null ? best : anyBest;
        scanned = true;
    }

    private static float readTemp(String path) {
        String s = readLine(path);
        if (s == null) return Float.NaN;
        try {
            float v = Float.parseFloat(s.trim());
            if (v > 1000f) v = v / 1000f;
            return (v > 0f && v < 150f) ? v : Float.NaN;
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    private static String readLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return r.readLine();
        } catch (Throwable t) {
            return null;
        }
    }
}
