package io.github.yudigaga.lightfreeze.system;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class FollowStoreTest {
    private static final AutomationEngine.Target APP = new AutomationEngine.Target("example.app", 0);
    private static final class Host implements AutomationEngine.Host {
        int state; boolean unknown;
        public long now() { return 0; }
        public AutomationEngine.Observation observe(AutomationEngine.Session s) {
            return new AutomationEngine.Observation(true, state, true, false, true, false, true, false);
        }
        public void freeze(AutomationEngine.Session s) throws Exception { state = 3; if (unknown) throw new Exception("unknown"); }
        public void persist(AutomationEngine e) { }
        public void schedule(AutomationEngine.Target t, long d, Runnable r) { throw new AssertionError("followed card app got launch timer"); }
        public void cancel(AutomationEngine.Target t) { }
    }
    private static AutomationEngine engine(Host h) throws Exception {
        AutomationEngine e = new AutomationEngine(h);
        e.setPolicy(new AutomationEngine.Policy(4, 3000, 10000, false, Set.of(APP.packageName()), Map.of()));
        return e;
    }
    @Test public void completedOwnershipSurvivesRestartAndRemovingTagForLaterRestore() throws Exception {
        Host h = new Host(); AutomationEngine e = engine(h); e.follow(APP, 10001, "digest", 0); e.onTaskRemoved(APP);
        e.setPolicy(new AutomationEngine.Policy(0, 3000, 10000, false, Set.of(), Map.of()));
        AutomationEngine restored = new AutomationEngine(h);
        SystemStateStore.restore(restored, SystemStateStore.snapshot(e), 30000);
        assertTrue(restored.sessions.isEmpty());
        assertEquals(Integer.valueOf(0), restored.ownedBaseline(APP, 10001, "digest"));
    }
    @Test public void pendingOwnershipIsRetainedButCannotClaimConfirmedRestoreAfterRestart() throws Exception {
        Host h = new Host(); h.unknown = true; AutomationEngine e = engine(h);
        e.follow(APP, 10001, "digest", 0); e.onTaskRemoved(APP);
        AutomationEngine restored = new AutomationEngine(h);
        SystemStateStore.restore(restored, SystemStateStore.snapshot(e), 30000);
        assertFalse(restored.ownedFreezes.get(APP).confirmed());
        assertNull(restored.ownedBaseline(APP, 10001, "digest"));
        assertEquals(AutomationEngine.Phase.UNRESOLVED, restored.sessions.get(APP).phase);
        assertFalse(restored.follow(APP, 10001, "digest", 0));
        assertThrows(IllegalStateException.class, () -> restored.acquire(APP, "restore", 10001, "digest", 0, false));
    }
    @Test public void restoredFollowedSessionCannotBecomeANewUnobservedLaunchTimeout() throws Exception {
        Host h = new Host(); AutomationEngine e = engine(h); e.follow(APP, 10001, "digest", 0);
        AutomationEngine restored = new AutomationEngine(h);
        SystemStateStore.restore(restored, SystemStateStore.snapshot(e), 30000);
        assertTrue(restored.sessions.get(APP).followed);
        assertTrue(restored.sessions.get(APP).firstVisible);
        restored.onEvent(); assertEquals(0, h.state);
        // The optional marker remains compatible with old session snapshots.
        JSONObject old = SystemStateStore.snapshot(e);
        old.getJSONArray("sessions").getJSONObject(0).remove("followed");
        old.remove("ownedFreezes");
        restored = new AutomationEngine(h); SystemStateStore.restore(restored, old, 30000);
        assertFalse(restored.sessions.get(APP).followed); restored.onEvent();
    }
    @Test public void malformedOrDuplicateRecoveryRecordsAreRejected() throws Exception {
        Host h = new Host(); AutomationEngine e = engine(h); e.follow(APP, 10001, "digest", 0); e.onTaskRemoved(APP);
        JSONObject root = SystemStateStore.snapshot(e);
        JSONObject record = root.getJSONArray("ownedFreezes").getJSONObject(0);
        root.put("ownedFreezes", new JSONArray().put(record).put(record));
        assertThrows(IOException.class, () -> SystemStateStore.restore(new AutomationEngine(h), root, 0));
        root.put("ownedFreezes", new JSONArray().put(new JSONObject(record.toString()).put("original", 3)));
        assertThrows(IOException.class, () -> SystemStateStore.restore(new AutomationEngine(h), root, 0));
        root.put("ownedFreezes", new JSONArray().put(new JSONObject(record.toString()).put("uid", 110001)));
        assertThrows(IOException.class, () -> SystemStateStore.restore(new AutomationEngine(h), root, 0));
        root.put("ownedFreezes", new JSONArray().put(new JSONObject(record.toString()).put("original", 0.5)));
        assertThrows(IOException.class, () -> SystemStateStore.restore(new AutomationEngine(h), root, 0));
        root.put("ownedFreezes", new JSONObject());
        assertThrows(IOException.class, () -> SystemStateStore.restore(new AutomationEngine(h), root, 0));
    }
}
