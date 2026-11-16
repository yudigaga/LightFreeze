package io.github.yudigaga.lightfreeze.control;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemovalCommitTest {
    private static final class Steps implements RemovalCommit.Steps {
        final List<String> order = new ArrayList<>(); boolean acknowledged = true, saveFails;
        public void removeLocal() throws Exception {
            order.add("local"); if (saveFails) throw new Exception("disk full");
        }
        public boolean syncRemoved() { order.add("sync"); return acknowledged; }
    }
    @Test public void failedThawPreservesConfigurationAndNeverFinishesRemoval() {
        Steps steps = new Steps();
        assertThrows(Exception.class, () -> RemovalCommit.afterConfirmedThaw(false, true, true, steps));
        assertTrue(steps.order.isEmpty());
    }
    @Test public void oldModuleCannotRemoveConfigurationWhileItCanStillReadoptTheApp() {
        Steps steps = new Steps();
        assertThrows(Exception.class, () -> RemovalCommit.afterConfirmedThaw(true, true, false, steps));
        assertTrue(steps.order.isEmpty());
    }
    @Test public void acknowledgedDetachCompletesOnlyAfterLocalAndSystemAgree() throws Exception {
        Steps steps = new Steps(); RemovalCommit.afterConfirmedThaw(true, true, true, steps);
        steps.order.add("finish");
        assertEquals(List.of("local", "sync", "finish"), steps.order);
    }
    @Test public void missingSystemAckCannotReleaseTheLeaseAsSuccessful() {
        Steps steps = new Steps(); steps.acknowledged = false;
        assertThrows(Exception.class, () -> {
            RemovalCommit.afterConfirmedThaw(true, true, true, steps);
            steps.order.add("finish");
        });
        assertEquals(List.of("local", "sync"), steps.order);
    }
    @Test public void failedLocalWriteCannotSendAFalseSystemDetach() {
        Steps steps = new Steps(); steps.saveFails = true;
        assertThrows(Exception.class, () -> RemovalCommit.afterConfirmedThaw(true, true, true, steps));
        assertEquals(List.of("local"), steps.order);
    }
    @Test public void standaloneManualThawNeedsNoSystemAck() throws Exception {
        Steps steps = new Steps(); RemovalCommit.afterConfirmedThaw(true, false, false, steps);
        assertEquals(List.of("local"), steps.order);
    }
}
