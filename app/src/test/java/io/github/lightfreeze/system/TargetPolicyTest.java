package io.github.yudigaga.lightfreeze.system;

import org.junit.Test;
import static org.junit.Assert.*;

public class TargetPolicyTest {
    @Test public void ordinaryAppUidIsAllowedIncludingNoncriticalPreinstalledApps() {
        TargetPolicy.check(0, 0, 10001, false, false);
        TargetPolicy.check(10, 10, 1010001, false, false);
    }
    @Test public void criticalPackagesAndCoreUidsCannotBeFrozenButCanBeManuallyEnabled() {
        assertThrows(SecurityException.class, () -> TargetPolicy.check(0, 0, 10001, true, false));
        assertThrows(SecurityException.class, () -> TargetPolicy.check(0, 0, 1000, false, false));
        TargetPolicy.check(0, 0, 10001, true, true);
        TargetPolicy.check(0, 0, 1000, false, true);
    }
    @Test public void manualEnableCannotBypassUserAndUidChecks() {
        assertThrows(SecurityException.class, () -> TargetPolicy.check(10, 0, 1010001, false, true));
        assertThrows(SecurityException.class, () -> TargetPolicy.check(0, 0, 1010001, false, true));
    }
    @Test public void exactHighRiskGrantCanAllowThemeSharedSystemUid() {
        TargetPolicy.checkFreeze("com.android.thememanager", 0, 0, 1000, false, true);
        assertThrows(SecurityException.class, () -> TargetPolicy.checkFreeze(
                "com.android.thememanager", 0, 0, 1000, false, false));
        // Another package with the same UID receives no implicit grant.
        assertThrows(SecurityException.class, () -> TargetPolicy.checkFreeze(
                "com.example.sibling", 0, 0, 1000, false, false));
    }
    @Test public void highRiskGrantCanAllowFixedOrCurrentRoleProtection() {
        TargetPolicy.checkFreeze("com.android.systemui", 0, 0, 10001, true, true);
        assertThrows(SecurityException.class, () -> TargetPolicy.checkFreeze(
                "com.android.systemui", 0, 0, 10001, true, false));
    }
    @Test public void highRiskGrantNeverAllowsForeignUserOrWrongUid() {
        assertThrows(SecurityException.class, () -> TargetPolicy.checkFreeze(
                "com.android.thememanager", 10, 0, 1001000, true, true));
        assertThrows(SecurityException.class, () -> TargetPolicy.checkFreeze(
                "com.android.thememanager", 0, 0, 1001000, true, true));
    }
    @Test public void genericFreezeCannotDisableModuleBodyEvenWithAStaleGrant() {
        assertThrows(SecurityException.class, () -> TargetPolicy.checkFreeze(
                "io.github.yudigaga.lightfreeze", 0, 0, 10001, false, true));
    }
    @Test public void revokingFreezeGrantStillPermitsManualRestoreOfCoreUid() {
        assertThrows(SecurityException.class, () -> TargetPolicy.checkFreeze(
                "com.android.thememanager", 0, 0, 1000, false, false));
        TargetPolicy.check(0, 0, 1000, true, true);
    }
    @Test public void temporaryThawAcceptsAllThreePmDisabledStates() {
        for (int state : new int[]{2, 3, 4}) assertTrue(TargetPolicy.disabled(state));
        for (int state : new int[]{-1, 0, 1, 5}) assertFalse(TargetPolicy.disabled(state));
    }
    @Test public void enabledApplicationsCanBeginTabSessionsOnlyWithTheirConfirmedBaseline() {
        TargetPolicy.checkTemporaryState(0, 0, true);
        TargetPolicy.checkTemporaryState(1, 1, true);
        assertThrows(IllegalStateException.class, () -> TargetPolicy.checkTemporaryState(0, 1, true));
        assertThrows(IllegalStateException.class, () -> TargetPolicy.checkTemporaryState(1, 0, true));
    }
    @Test public void defaultOrExplicitEnabledSettingsDoNotOverrideActualManifestRestrictions() {
        assertThrows(IllegalStateException.class, () -> TargetPolicy.checkTemporaryState(0, 0, false));
        assertThrows(IllegalStateException.class, () -> TargetPolicy.checkTemporaryState(1, 1, false));
        assertThrows(IllegalStateException.class, () -> TargetPolicy.checkTemporaryState(5, 1, true));
    }
    @Test public void disabledApplicationsKeepDirectThawSupportWithAnEnabledRestoreBaseline() {
        for (int state : new int[]{2, 3, 4}) {
            TargetPolicy.checkTemporaryState(state, 0, false);
            TargetPolicy.checkTemporaryState(state, 1, false);
            assertThrows(IllegalArgumentException.class, () -> TargetPolicy.checkTemporaryState(state, 3, false));
        }
    }
}
