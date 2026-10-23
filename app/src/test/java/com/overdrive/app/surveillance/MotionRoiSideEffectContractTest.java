package com.overdrive.app.surveillance;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** Pins the boundary between compatible configuration reads and explicit user saves. */
public class MotionRoiSideEffectContractTest {
    @Test
    public void compatibleConfigReadsAndEffectiveRoiDoNotWriteSettings() throws Exception {
        String api = read("app/src/main/java/com/overdrive/app/server/SurveillanceApiHandler.java");
        String engine = read("app/src/main/java/com/overdrive/app/surveillance/SurveillanceEngineGpu.java");
        String get = method(api, "private static void sendConfig(");
        String effective = method(engine, "private void applyEffectiveRoi(");
        assertTrue(get.contains("MotionPipelineV2.roiMaskFromJson(blocks)"));
        assertTrue(get.contains("config.put(\"roiBlocks_\" + qKeys[q], effectiveBlocks)"));
        assertFalse("GET must not create a newer mutation stamp", get.contains("setSurveillance("));
        assertTrue(effective.contains("MotionPipelineV2.roiMaskFromJson(blockArr)"));
        assertTrue(effective.contains("if (roiEnabled && blockMask != null)"));
        assertFalse("Applying saved ROI must not overwrite concurrent settings",
                effective.contains("setSurveillance("));
    }

    @Test
    public void explicitBlockSavePersistsTheNormalizedMask() throws Exception {
        String api = read("app/src/main/java/com/overdrive/app/server/SurveillanceApiHandler.java");
        int start = api.indexOf("// Direct block mask per quadrant");
        int end = api.indexOf("// Surveillance schedule", start);
        assertTrue(start >= 0 && end > start);
        String save = api.substring(start, end);
        assertTrue(save.contains("MotionPipelineV2.roiMaskFromJson(arr)"));
        assertTrue(save.contains("survCfg.put(blocksKey, MotionPipelineV2.roiMaskToJson(blockMask))"));
        assertTrue(save.contains("setSurveillance(survCfg)"));
    }

    @Test
    public void polygonRoundTripsPreserveEachQuadrantsGeometryVersion() throws Exception {
        String api = read("app/src/main/java/com/overdrive/app/server/SurveillanceApiHandler.java");
        String get = method(api, "private static void sendConfig(");
        assertTrue(get.contains("config.put(\"roiGridVersion_Q\" + q, 1)"));
        assertTrue(get.contains("String gridVersionKey = \"roiGridVersion_\" + qKeys[q]"));
        assertTrue(get.contains("config.put(gridVersionKey, survCfg.optInt(gridVersionKey, 1))"));
        int start = api.indexOf("// Per-quadrant ROI polygons");
        int end = api.indexOf("// Per-quadrant ROI enabled/disabled toggle", start);
        assertTrue(start >= 0 && end > start);
        String save = api.substring(start, end).replaceAll("\\s+", " ");
        assertTrue(save.contains("String gridVersionKey = \"roiGridVersion_\" + quadrantKeys[q]"));
        assertTrue("Saved per-quadrant intent must take precedence over a global geometry version",
                save.contains("survCfg.put(gridVersionKey, configJson.optInt(gridVersionKey, "
                        + "configJson.optInt(\"roiGridVersion\", 1)))"));
        assertTrue(save.indexOf("survCfg.put(gridVersionKey,")
                < save.indexOf("sentry.applyQuadrantRoi(q, polygon)"));
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue("Expected integration method", start >= 0);
        Matcher next = Pattern.compile(
                "(?m)^    (?:public|private|protected)\\s+(?:static\\s+)?[^\\n]+\\(")
                .matcher(source);
        next.region(start + signature.length(), source.length());
        return source.substring(start, next.find() ? next.start() : source.length());
    }

    private static String read(String relative) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Repository source not found: " + relative);
    }
}
