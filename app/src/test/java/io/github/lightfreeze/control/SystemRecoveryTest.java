package io.github.yudigaga.lightfreeze.control;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SystemRecoveryTest {
    private static JSONObject item(int original) throws Exception {
        return new JSONObject().put("packageName", "example.app").put("userId", 0).put("uid", 10001)
                .put("signature", "live-signature").put("installedAt", 123L).put("originalSetting", original);
    }
    private static RecoveryStore.Record verified(JSONObject item) throws Exception {
        return SystemRecovery.verified(item, 10001, "live-signature", 123L, 3);
    }
    @Test public void systemConfirmedFreezePreservesDefaultBaselineWithoutPendingFlags() throws Exception {
        RecoveryStore.Record record = verified(item(0));
        assertEquals("example.app", record.packageName);
        assertEquals(0, record.originalSetting);
        assertTrue(record.hasRestoreBaseline());
        assertFalse(record.pending);
        assertFalse(record.temporary);
        assertFalse(record.directEnablePending);
    }
    @Test public void explicitEnabledBaselineAlsoRemainsEnabled() throws Exception {
        assertEquals(1, verified(item(1)).originalSetting);
    }
    @Test public void coreUidRecoveryKeepsItsDefaultBaselineWithoutNeedingFreezePermission() throws Exception {
        JSONObject receipt = item(0).put("packageName", "com.android.thememanager").put("uid", 1000);
        RecoveryStore.Record restored = SystemRecovery.verified(receipt, 1000, "live-signature", 123L, 3);
        assertEquals(0, restored.originalSetting);
        assertTrue(restored.hasRestoreBaseline());
        assertFalse(restored.pending);
    }
    @Test public void externalDisabledSettingsCannotBeClaimedAsOriginalBaselines() throws Exception {
        for (int original : new int[]{-1, 2, 3, 4, 5}) {
            JSONObject item = item(original);
            assertThrows(IllegalArgumentException.class, () -> verified(item));
        }
    }
    @Test public void liveStateMustStillBeTheSystemOwnedDisabledUserState() throws Exception {
        for (int state : new int[]{-1, 0, 1, 2, 4}) {
            assertThrows(IllegalArgumentException.class,
                    () -> SystemRecovery.verified(item(0), 10001, "live-signature", 123L, state));
        }
    }
    @Test public void uidAndUserMustBothMatchTheLiveInstallation() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> SystemRecovery.verified(item(0), 10002, "live-signature", 123L, 3));
        JSONObject otherUser = item(0).put("userId", 10);
        assertThrows(IllegalArgumentException.class, () -> verified(otherUser));
        JSONObject uidForOtherUser = item(0).put("uid", 1010001);
        assertThrows(IllegalArgumentException.class,
                () -> SystemRecovery.verified(uidForOtherUser, 1010001, "live-signature", 123L, 3));
    }
    @Test public void reinstallationAndCertificateChangesAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> SystemRecovery.verified(item(0), 10001, "replacement-signature", 123L, 3));
        assertThrows(IllegalArgumentException.class,
                () -> SystemRecovery.verified(item(0), 10001, "live-signature", 124L, 3));
        assertThrows(IllegalArgumentException.class,
                () -> SystemRecovery.verified(item(0), 10001, null, 123L, 3));
    }
    @Test public void malformedPackageAndEmptySignatureAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> verified(item(0).put("packageName", "bad;package")));
        assertThrows(IllegalArgumentException.class,
                () -> SystemRecovery.verified(item(0).put("signature", ""), 10001, "", 123L, 3));
    }
    @Test public void numericOverflowOrFractionalFieldsCannotMasqueradeAsMatchingIdentity() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> verified(item(0).put("uid", 4294977297L)));
        assertThrows(IllegalArgumentException.class, () -> verified(item(0).put("uid", 10001.9)));
        assertThrows(IllegalArgumentException.class, () -> verified(item(0).put("installedAt", 123.5)));
    }
}
