package io.github.yudigaga.lightfreeze.system;

import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class PolicyAuthorityTest {
    private static final class Host implements AutomationEngine.Host {
        int saves; boolean failSave;
        public long now() { return 0; }
        public AutomationEngine.Observation observe(AutomationEngine.Session s) { throw new AssertionError(); }
        public void freeze(AutomationEngine.Session s) { throw new AssertionError(); }
        public void persist(AutomationEngine e) throws Exception {
            if (failSave) throw new java.io.IOException("snapshot failed");
            saves++;
        }
        public void schedule(AutomationEngine.Target t, long delay, Runnable r) { }
        public void cancel(AutomationEngine.Target t) { }
    }
    private static AutomationEngine.Policy policy(int mode) {
        return new AutomationEngine.Policy(mode, 3000, 10000, true, true, Set.of("example.app"), Map.of());
    }
    @Test public void equivalentConfigurationKeepsItsAcknowledgementWithoutAnotherSave() throws Exception {
        Host host = new Host(); PolicyAuthority authority = new PolicyAuthority(new AutomationEngine(host));
        assertFalse(authority.matches(null));
        String token = authority.sync(policy(1));
        assertTrue(authority.matches(token));
        int saves = host.saves;
        assertEquals(token, authority.sync(policy(1)));
        assertEquals(saves, host.saves);
    }
    @Test public void changedConfigurationAndNewControllerInvalidateOldAcknowledgements() throws Exception {
        Host host = new Host(); AutomationEngine engine = new AutomationEngine(host);
        PolicyAuthority authority = new PolicyAuthority(engine);
        String old = authority.sync(policy(1));
        String fresh = authority.sync(policy(4));
        assertFalse(authority.matches(old)); assertTrue(authority.matches(fresh));
        assertFalse(new PolicyAuthority(engine).matches(fresh));
    }
    @Test public void persistedCancellationThatRejectsSyncStillInvalidatesOldAcknowledgement() throws Exception {
        Host host = new Host(); AutomationEngine engine = new AutomationEngine(host);
        PolicyAuthority authority = new PolicyAuthority(engine);
        String old = authority.sync(policy(1));
        engine.acquire(new AutomationEngine.Target("example.app", 0), "request", 10001, "digest", 0, true);
        assertThrows(IllegalStateException.class, () -> authority.sync(policy(0)));
        assertFalse(authority.matches(old));
        assertThrows(IllegalStateException.class, () -> authority.sync(policy(0)));
    }
    @Test public void persistedRemovalCanBeAcknowledgedWhileItsLeaseStillNeedsFinish() throws Exception {
        Host host = new Host(); AutomationEngine engine = new AutomationEngine(host);
        PolicyAuthority authority = new PolicyAuthority(engine);
        authority.sync(policy(1));
        AutomationEngine.Target target = new AutomationEngine.Target("example.app", 0);
        engine.acquire(target, "remove", 10001, "digest", 0, false);
        AutomationEngine.Policy removed = new AutomationEngine.Policy(0, 3000, 10000, true, true, Set.of(), Map.of());
        assertThrows(IllegalStateException.class, () -> authority.sync(removed));
        String token = authority.persistedToken(removed);
        assertNotNull(token); assertTrue(authority.matches(token));
        assertTrue(engine.sessions.get(target).cancelled);
        assertEquals(AutomationEngine.Phase.ENABLING, engine.sessions.get(target).phase);
        assertNull(authority.persistedToken(policy(1)));
        assertFalse(engine.receipts.containsKey(target));
    }
    @Test public void failedSnapshotCannotProduceAPersistedAcknowledgement() throws Exception {
        Host host = new Host(); AutomationEngine engine = new AutomationEngine(host);
        PolicyAuthority authority = new PolicyAuthority(engine);
        authority.sync(policy(1)); host.failSave = true;
        assertThrows(Exception.class, () -> authority.sync(policy(4)));
        assertNull(authority.persistedToken(policy(4)));
    }
}
