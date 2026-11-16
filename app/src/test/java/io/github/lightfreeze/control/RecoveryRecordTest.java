package io.github.yudigaga.lightfreeze.control;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecoveryRecordTest {
    @Test public void pendingExternalEnableIsAnIntentAndNotARestoreBaseline() throws Exception {
        RecoveryStore.Record pending = RecoveryStore.Record.directEnable("com.example.app", 0, 10001, "digest", 10, true);
        assertTrue(pending.pending);
        assertTrue(pending.directEnablePending);
        assertFalse(pending.hasRestoreBaseline());
        RecoveryStore.Record read = RecoveryStore.Record.parse(pending.json().toString());
        assertFalse(read.hasRestoreBaseline());
        assertTrue(read.state(true, false).directEnablePending);
    }
    @Test public void confirmedTemporaryEnableCreatesOnlyTheNewEnabledBaseline() throws Exception {
        RecoveryStore.Record confirmed = RecoveryStore.Record.directEnable("com.example.app", 0, 10001, "digest", 10, true)
                .confirmed(true);
        assertTrue(confirmed.hasRestoreBaseline());
        assertFalse(confirmed.pending);
        assertTrue(confirmed.temporary);
        assertEquals(1, confirmed.originalSetting);
        assertTrue(RecoveryStore.Record.parse(confirmed.json().toString()).hasRestoreBaseline());
    }
    @Test public void legacyOwnedRecordsKeepTheirOriginalDefaultState() throws Exception {
        RecoveryStore.Record old = new RecoveryStore.Record("com.example.app", 0, 10001, "digest", 10, 0, false, false);
        JSONObject json = old.json(); json.remove("directEnablePending");
        RecoveryStore.Record read = RecoveryStore.Record.parse(json.toString());
        assertTrue(read.hasRestoreBaseline());
        assertEquals(0, read.originalSetting);
    }
    @Test public void malformedPendingIntentCannotPretendToBeAConfirmedBaseline() throws Exception {
        JSONObject json = new RecoveryStore.Record("com.example.app", 0, 10001, "digest", 10, 0, false, false).json();
        json.put("directEnablePending", true);
        assertThrows(org.json.JSONException.class, () -> RecoveryStore.Record.parse(json.toString()));
    }
}
