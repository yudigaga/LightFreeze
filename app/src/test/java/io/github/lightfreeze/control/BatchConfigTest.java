package io.github.yudigaga.lightfreeze.control;

import org.junit.Test;
import static org.junit.Assert.*;

public class BatchConfigTest {
    @Test public void unchangedAcknowledgedConfigCanSkipOnlyWithinSameRequest() {
        FreezerController.Batch first = new FreezerController.Batch();
        assertTrue(first.needsSync("one", true));
        first.confirmSync("one", true, "server-one");
        assertFalse(first.needsSync("one", true));
        assertTrue(first.needsSync("changed", true));
        assertTrue(new FreezerController.Batch().needsSync("one", true));
    }
    @Test public void failedOrChangedConfigCannotReusePreviousAcknowledgement() {
        FreezerController.Batch scope = new FreezerController.Batch();
        scope.confirmSync("one", false, null);
        assertTrue(scope.needsSync("one", true));
        scope.confirmSync("one", true, "server-one");
        scope.confirmSync("two", false, "server-two");
        assertTrue(scope.needsSync("two", true));
        assertTrue(scope.needsSync("one", true));
    }
    @Test public void legacyOrTokenlessAcknowledgementNeverSkipsSynchronization() {
        FreezerController.Batch scope = new FreezerController.Batch();
        scope.confirmSync("one", true, "server-one");
        assertTrue(scope.needsSync("one", false));
        scope.confirmSync("one", true, null);
        assertTrue(scope.needsSync("one", true));
        scope.confirmSync("one", true, "");
        assertTrue(scope.needsSync("one", true));
    }
    @Test public void serverMismatchClearsBothCapabilitiesAndPolicyAcknowledgement() {
        FreezerController.Batch scope = new FreezerController.Batch();
        scope.confirmSync("one", true, "server-one");
        scope.invalidate();
        assertTrue(scope.needsSync("one", true));
        assertNull(scope.status);
        assertNull(scope.syncedReply);
    }
}
