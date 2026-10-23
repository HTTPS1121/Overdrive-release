package com.overdrive.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.launcher.DaemonLauncher;
import com.overdrive.app.launcher.TailscaleLauncher;
import com.overdrive.app.launcher.ZrokLauncher;
import com.overdrive.app.logging.DaemonLogPaths;
import com.overdrive.app.surveillance.ScreenDeterrentAsset;
import com.overdrive.app.ui.model.DaemonType;
import com.overdrive.app.ui.model.ParkedShutdown;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * THE regression gate for every vehicle that is not opted in.
 *
 * <p>With no {@code OVERDRIVE_SCRATCH} in the environment, no marker and no
 * stored preference, {@link ScratchRoot} must be transparent: every accessor
 * hands back the literal the callers used to hardcode, and the shell
 * generators that were converted to it must emit byte-identical text. The
 * hashes below were captured from the tree BEFORE the conversion; a changed
 * hash means the legacy output moved and the change is a regression until
 * proven otherwise.
 */
public class ScratchRootLegacyIdentityTest {

    private static final String LEGACY = "/data/local/tmp";

    @Before
    public void requireLegacyEnvironment() {
        Assume.assumeTrue("OVERDRIVE_SCRATCH must be unset for this test",
                System.getenv(ScratchRoot.ENV_SCRATCH) == null);
        ScratchRoot.setResolvedDirForTest(null);
    }

    @After
    public void reset() {
        ScratchRoot.setResolvedDirForTest(null);
    }

    @Test
    public void resolvesToLegacyRoot() {
        assertEquals(LEGACY, ScratchRoot.dir());
        assertTrue(ScratchRoot.usesLegacyDir());
        assertEquals(LEGACY, ScratchRoot.LEGACY_DIR);
    }

    @Test
    public void pathReturnsTheHardcodedLiterals() {
        assertEquals(LEGACY + "/overdrive_config.json",
                ScratchRoot.path("overdrive_config.json"));
        assertEquals(LEGACY + "/.overdrive/audio",
                ScratchRoot.path(".overdrive/audio"));
        assertEquals(LEGACY + "/x", ScratchRoot.path(LEGACY + "/x"));
        assertEquals(LEGACY, ScratchRoot.path(LEGACY));
        assertEquals(LEGACY, ScratchRoot.path(""));
        assertEquals(LEGACY, ScratchRoot.path(null));
        assertEquals("/sdcard/DCIM/BYDCam", ScratchRoot.path("/sdcard/DCIM/BYDCam"));
        assertEquals(LEGACY + "/x", ScratchRoot.file("x").getPath());
    }

    @Test
    public void shellPathwaysAreIdentity() {
        String command = "rm -f /data/local/tmp/camera_daemon.disabled 2>/dev/null; "
                + "nohup sh /data/local/tmp/start_cam_daemon.sh > /dev/null 2>&1 &";
        assertEquals("", ScratchRoot.shellPrefix());
        assertSame(command, ScratchRoot.remap(command));
        assertSame(command, ScratchRoot.prepare(command));
        assertEquals("", ScratchRoot.prepare(null));

        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", "/system/bin");
        ScratchRoot.applyEnv(env);
        assertEquals(1, env.size());
        assertEquals("/system/bin", env.get("PATH"));
    }

    // ==================== GOLDEN SNAPSHOT ====================
    //
    // Captured from the pre-conversion tree with the fixed arguments below.
    // Regenerate ONLY when a deliberate change to legacy shell text is made,
    // and say so in the commit.

    private static final Map<String, String> EXPECTED = new LinkedHashMap<>();
    static {
        EXPECTED.put("camWatchdog", "aa6e14b622eefd6e5ede0a0e141fd8c31ba964c47e90319140d40c203740bea6");
        EXPECTED.put("accSentryWatchdog", "e5d6b4e9cab75cb74feabcfd6b8283502a6b325de7e3fdf4369059c6355173f0");
        EXPECTED.put("telegramWatchdog", "44a915e39d11f2a8863621b3b44c5b9f5f6d2691971716ff199567be2cb585ab");
        EXPECTED.put("watchdogWriteCommand", "b99b6b33413237a014c8eb1e73deeeef5a1625195b917c9dafe52761e6f1a24b");
        EXPECTED.put("psAwkKillLine", "5f953da755240daf8e738ed7fc72e01aa33cc3fa8d9f098efa8c2e6375ca26a8");
        EXPECTED.put("logRotateGuard", "4eb7ceaafdbf0bd5fd39d689e11790acac20f79ed51643290ab44d22f22a3e54");
        EXPECTED.put("logRotateCoprocess", "b53c9080b0596a164cc4a5d0c26d1a44f5dd8c643d0b71f4e0f1e35395a885e0");
        EXPECTED.put("zrokWatchdogReserved", "c7af6f9636be9356916dde7854152970540cbb0c9124d4d59872ae52ba5a6af4");
        EXPECTED.put("zrokWatchdogPublicProxy", "bcfede4a7bad88d8733dfec14a420e936d012a61f5ced4a2742b4fd880a3f524");
        EXPECTED.put("tailscaleDashboardGuard", "5043677ad005dc56cf2fd97ee3c7757862ebcf2278d9595528a160a92c0eab2e");
        EXPECTED.put("daemonLogPaths", "6222041aa8ccc8be671dc924f3c0dbdd653c3d17362eaa16e3a9e21e9a58cfaf");
        EXPECTED.put("daemonTypeSentinels", "d3b0849a7696784977157c406611a001634640d9c68da6d0a7bd4aba7f59762b");
        EXPECTED.put("parkedShutdownMarkers", "657c80160981608739ab9015ec5cf4664fe35080a30e6bac79ae28ef59bfee90");
        EXPECTED.put("screenDeterrentDirectory", "50493292b2a1d4dcc47df8b5db47fa919d03c70eeb0aa7330b7d678f7c7ffa1a");
    }

    static Map<String, String> snapshot() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("camWatchdog", sha256(join(DaemonLauncher.Companion
                .buildCamDaemonWatchdogScript(
                        "/data/app/base.apk",
                        "/data/app/lib",
                        "/sdcard/Android/data/com.overdrive.app/files",
                        ""))));
        actual.put("accSentryWatchdog", sha256(join(DaemonLauncher.Companion
                .buildAccSentryWatchdogScript("/data/app/base.apk", ""))));
        actual.put("telegramWatchdog", sha256(join(DaemonLauncher.Companion
                .buildTelegramWatchdogScript("/data/app/base.apk", ""))));
        actual.put("watchdogWriteCommand", sha256(DaemonLauncher.Companion
                .buildWatchdogScriptWriteCommand(
                        "/data/local/tmp/start_cam_daemon.sh",
                        Arrays.asList("#!/system/bin/sh", "echo 'quoted' \"x\" $VAR"))));
        actual.put("psAwkKillLine", sha256(
                DaemonLauncher.Companion.psAwkKillLine("cam_daemon")));
        actual.put("logRotateGuard", sha256(join(
                DaemonLauncher.Companion.logRotateGuardLines())));
        actual.put("logRotateCoprocess", sha256(join(
                DaemonLauncher.Companion.logRotateCoprocessLines(
                        "DAEMON_PID", "ROTATE_PID"))));
        actual.put("zrokWatchdogReserved", sha256(join(ZrokLauncher.Companion
                .buildZrokWatchdogScriptStatic(true, "token-123", false))));
        actual.put("zrokWatchdogPublicProxy", sha256(join(ZrokLauncher.Companion
                .buildZrokWatchdogScriptStatic(false, "token-123", true))));
        actual.put("tailscaleDashboardGuard", sha256(join(
                TailscaleLauncher.buildDashboardServeGuardScript())));

        StringBuilder logPaths = new StringBuilder();
        for (String key : DaemonLogPaths.keys()) {
            logPaths.append(key).append('=')
                    .append(DaemonLogPaths.pathFor(key)).append('\n');
        }
        actual.put("daemonLogPaths", sha256(logPaths.toString()));

        StringBuilder sentinels = new StringBuilder();
        for (DaemonType type : DaemonType.values()) {
            sentinels.append(type.name()).append('=')
                    .append(type.getSentinelPath()).append('\n');
        }
        actual.put("daemonTypeSentinels", sha256(sentinels.toString()));
        actual.put("parkedShutdownMarkers", sha256(
                ParkedShutdown.MARKER_PATH + "\n" + ParkedShutdown.ENDED_PATH));
        actual.put("screenDeterrentDirectory", sha256(ScreenDeterrentAsset.DIRECTORY));
        return actual;
    }

    @Test
    public void legacyShellTextIsByteIdentical() {
        Map<String, String> actual = snapshot();
        StringBuilder report = new StringBuilder("\nActual snapshot:\n");
        for (Map.Entry<String, String> e : actual.entrySet()) {
            report.append("        EXPECTED.put(\"").append(e.getKey())
                    .append("\", \"").append(e.getValue()).append("\");\n");
        }
        for (Map.Entry<String, String> e : EXPECTED.entrySet()) {
            assertEquals("legacy text changed for " + e.getKey() + report,
                    e.getValue(), actual.get(e.getKey()));
        }
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
