package com.overdrive.app.overlay;

import android.app.AppOpsManager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OverlayPermissionDecisionTest {

    @Test
    public void explicitAppOpsDenialOverridesFrameworkFalsePositive() {
        assertFalse(OverlayPermissionDecision.resolve(Boolean.FALSE, true));
    }

    @Test
    public void explicitAppOpsGrantOverridesFrameworkFalseNegative() {
        assertTrue(OverlayPermissionDecision.resolve(Boolean.TRUE, false));
    }

    @Test
    public void frameworkIsOnlyFallbackWhenAppOpsIsUnavailable() {
        assertTrue(OverlayPermissionDecision.resolve(null, true));
        assertFalse(OverlayPermissionDecision.resolve(null, false));
    }

    /**
     * MODE_DEFAULT is "never toggled", not "denied": the platform resolves it from the static
     * grant, which is exactly what the framework check reads — so it must defer on EVERY
     * platform. Treating it as a denial on non-DiLink5 cars refused overlay windows the OS
     * would have accepted and silenced every Show-toast automation on pm-grant installs
     * (2026-09-23 field log).
     */
    @Test
    public void untoggledDefaultModeDefersToFrameworkOnEveryPlatform() {
        assertNull(OverlayPermissionChecker.appOpsDecision(
                AppOpsManager.MODE_DEFAULT, false, 25));
        assertNull(OverlayPermissionChecker.appOpsDecision(
                AppOpsManager.MODE_DEFAULT, true, 25));
        // …and the framework then decides on its own.
        assertTrue(OverlayPermissionDecision.resolve(
                OverlayPermissionChecker.appOpsDecision(AppOpsManager.MODE_DEFAULT, false, 25),
                true));
        assertFalse(OverlayPermissionDecision.resolve(
                OverlayPermissionChecker.appOpsDecision(AppOpsManager.MODE_DEFAULT, false, 25),
                false));
    }

    @Test
    public void ambiguousAppOpsModesDeferOnlyOnDiLink5() {
        assertNull(OverlayPermissionChecker.appOpsDecision(
                AppOpsManager.MODE_DEFAULT, true, 25));
        assertNull(OverlayPermissionChecker.appOpsDecision(
                AppOpsManager.MODE_FOREGROUND, true, 25));
        assertTrue(OverlayPermissionChecker.appOpsDecision(
                AppOpsManager.MODE_FOREGROUND, false, 25));
        assertTrue(OverlayPermissionChecker.appOpsDecision(
                AppOpsManager.MODE_ALLOWED, false, 25));
        assertFalse(OverlayPermissionChecker.appOpsDecision(
                AppOpsManager.MODE_IGNORED, true, 25));
        assertFalse(OverlayPermissionChecker.appOpsDecision(
                AppOpsManager.MODE_IGNORED, true, 26));
        assertFalse(OverlayPermissionChecker.appOpsDecision(
                AppOpsManager.MODE_IGNORED, false, 25));
        assertFalse(OverlayPermissionChecker.appOpsDecision(
                AppOpsManager.MODE_ERRORED, false, 25));
    }
}
