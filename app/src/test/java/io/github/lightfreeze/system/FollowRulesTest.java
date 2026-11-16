package io.github.yudigaga.lightfreeze.system;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class FollowRulesTest {
    private static final AutomationEngine.Target APP = new AutomationEngine.Target("example.app", 0);
    private static final class Host implements AutomationEngine.Host {
        long now; int state = 0, writes, saves; boolean locked, visible, failSave, unknownWrite;
        boolean sawOwnedBeforeWrite; Runnable timer, barrier;
        AutomationEngine engine;
        public long now() { return now; }
        public AutomationEngine.Observation observe(AutomationEngine.Session s) {
            return new AutomationEngine.Observation(true, state, true, visible, true, locked, true, false);
        }
        public void freeze(AutomationEngine.Session s) throws Exception {
            sawOwnedBeforeWrite = engine.ownedFreezes.containsKey(s.target); writes++; state = 3;
            if (unknownWrite) throw new Exception("write result unavailable");
        }
        public void persist(AutomationEngine e) throws Exception {
            if (failSave) throw new Exception("disk full");
            saves++;
            if (barrier != null && e.sessions.values().stream().anyMatch(s -> s.phase == AutomationEngine.Phase.UNRESOLVED)) {
                Runnable r = barrier; barrier = null; r.run();
            }
        }
        public void schedule(AutomationEngine.Target t, long delay, Runnable r) { timer = r; }
        public void cancel(AutomationEngine.Target t) { timer = null; }
    }
    private static AutomationEngine engine(Host host, int mode) throws Exception {
        AutomationEngine e = new AutomationEngine(host); host.engine = e;
        e.setPolicy(new AutomationEngine.Policy(mode, 3000, 10000, true, Set.of(APP.packageName()), Map.of()));
        return e;
    }
    @Test public void externalEnableFollowsRuleWithoutWaitingForAnotherAppOwnedThaw() throws Exception {
        Host h = new Host(); AutomationEngine e = engine(h, 4);
        assertTrue(e.follow(APP, 10001, "digest", 0));
        AutomationEngine.Session session = e.sessions.get(APP);
        assertTrue(session.followed); assertTrue(session.firstVisible); assertFalse(session.launchExpected);
        assertEquals(0, h.writes); assertNull(h.timer);
        e.onTaskRemoved(APP);
        assertEquals(1, h.writes); assertTrue(h.sawOwnedBeforeWrite);
        assertEquals(Integer.valueOf(0), e.ownedBaseline(APP, 10001, "digest"));
    }
    @Test public void lockAppliesToAnExternallyEnabledTagMemberAndHonorsDelay() throws Exception {
        Host h = new Host(); h.state = 1; AutomationEngine e = engine(h, 1);
        assertTrue(e.follow(APP, 10001, "digest", 1));
        h.locked = true; e.onEvent(); assertNotNull(h.timer); assertEquals(0, h.writes);
        h.now = 3000; h.timer.run(); assertEquals(1, h.writes);
        assertEquals(Integer.valueOf(1), e.ownedBaseline(APP, 10001, "digest"));
    }
    @Test public void followNeverReplacesAnExplicitSourceOrUnresolvedLease() throws Exception {
        Host h = new Host(); AutomationEngine e = engine(h, 4);
        e.setPolicy(new AutomationEngine.Policy(4, 3000, 10000, true, false,
                Set.of(APP.packageName()), Map.of(), Map.of("builtin:available", 1)));
        long lease = e.acquire(APP, "explicit", 10001, "digest", 0, true, false, "builtin:available");
        assertFalse(e.follow(APP, 10001, "digest", 0));
        e.finish(APP, "explicit", lease, true, false, 0);
        AutomationEngine.Session old = e.sessions.get(APP);
        assertFalse(e.follow(APP, 10001, "digest", 0));
        assertSame(old, e.sessions.get(APP)); assertEquals("builtin:available", old.sourceTab);
    }
    @Test public void manualUnmanagedOrDisabledTargetsAreNotAdopted() throws Exception {
        Host h = new Host(); AutomationEngine e = engine(h, 0);
        assertFalse(e.follow(APP, 10001, "digest", 0));
        e.setPolicy(new AutomationEngine.Policy(4, 3000, 10000, true, Set.of(), Map.of()));
        assertFalse(e.follow(APP, 10001, "digest", 0));
        e = engine(h, 4); h.state = 3;
        assertFalse(e.follow(APP, 10001, "digest", 3));
        assertFalse(e.follow(APP, 10001, "digest", 0));
    }
    @Test public void changingDefaultTagRuleUpdatesFollowedSessionWithoutLaunchRollback() throws Exception {
        Host h = new Host(); AutomationEngine e = engine(h, 4);
        e.follow(APP, 10001, "digest", 0); h.now = 20000; e.onEvent();
        assertEquals(0, h.writes); assertNull(h.timer);
        e.setPolicy(new AutomationEngine.Policy(4, 3000, 10000, true,
                Set.of(APP.packageName()), Map.of(APP.packageName(), 0)));
        assertFalse(e.sessions.containsKey(APP));
    }
    @Test public void persistenceFailurePreventsAutomaticWriteAndKeepsRecoverySafe() throws Exception {
        Host h = new Host(); AutomationEngine e = engine(h, 4);
        e.follow(APP, 10001, "digest", 0); h.failSave = true; e.onTaskRemoved(APP);
        assertEquals(0, h.writes); assertFalse(e.healthy);
    }
    @Test public void protectionChangeAtPersistBarrierCancelsWriteAndOwnershipCandidate() throws Exception {
        Host h = new Host(); AutomationEngine e = engine(h, 1);
        e.follow(APP, 10001, "digest", 0); h.locked = true; e.onEvent();
        h.barrier = () -> h.locked = false; h.now = 3000; h.timer.run();
        assertEquals(0, h.writes); assertFalse(e.ownedFreezes.containsKey(APP));
        assertEquals(AutomationEngine.Phase.ACTIVE, e.sessions.get(APP).phase);
    }
    @Test public void uncertainAutomaticWriteRetainsOriginalUntilConfirmedRestore() throws Exception {
        Host h = new Host(); AutomationEngine e = engine(h, 4); e.follow(APP, 10001, "digest", 0);
        h.unknownWrite = true; e.onTaskRemoved(APP);
        assertEquals(3, h.state); assertEquals(AutomationEngine.Phase.UNRESOLVED, e.sessions.get(APP).phase);
        assertNull(e.ownedBaseline(APP, 10001, "digest"));
        assertFalse(e.ownedFreezes.get(APP).confirmed());
        assertFalse(e.follow(APP, 10001, "digest", 0));
    }
    @Test public void confirmedThawClearsOwnedBaselineAndIdentityMismatchCannotClaimIt() throws Exception {
        Host h = new Host(); AutomationEngine e = engine(h, 4); e.follow(APP, 10001, "digest", 0); e.onTaskRemoved(APP);
        long lease = e.acquire(APP, "restore", 10001, "digest", 0, false);
        h.state = 0; e.finish(APP, "restore", lease, true, false, 0);
        assertNull(e.ownedBaseline(APP, 10001, "digest"));
        e.follow(APP, 10001, "digest", 0); e.onTaskRemoved(APP);
        assertNull(e.ownedBaseline(APP, 10002, "replacement"));
        assertFalse(e.ownedFreezes.containsKey(APP));
    }
    @Test public void recoveryCapacityFailsClosedBeforeAnotherAutomaticWrite() throws Exception {
        Host h = new Host(); AutomationEngine e = engine(h, 4); e.follow(APP, 10001, "digest", 0);
        for (int i = 0; i < 500; i++) e.ownedFreezes.put(new AutomationEngine.Target("example.app" + i, 0),
                new AutomationEngine.OwnedFreeze(10001, "digest", 0, true));
        e.onTaskRemoved(APP); assertEquals(0, h.writes); assertEquals(500, e.ownedFreezes.size());
    }
    @Test public void bulkFollowingPersistsOnceBeforeAnyAutomaticWrite() throws Exception {
        Host h = new Host(); AutomationEngine e = engine(h, 4);
        AutomationEngine.Target other = new AutomationEngine.Target("example.other", 0);
        e.setPolicy(new AutomationEngine.Policy(4, 3000, 10000, true,
                Set.of(APP.packageName(), other.packageName()), Map.of()));
        int saves = h.saves;
        assertEquals(2, e.followAll(List.of(new AutomationEngine.FollowCandidate(APP, 10001, "digest", 0),
                new AutomationEngine.FollowCandidate(other, 10002, "second", 0))));
        assertEquals(saves + 1, h.saves); assertEquals(0, h.writes);
        assertEquals(2, e.sessions.size());
    }
    @Test public void bulkFollowPersistenceFailureStopsAllSubsequentWrites() throws Exception {
        Host h = new Host(); AutomationEngine e = engine(h, 4); h.failSave = true;
        assertThrows(Exception.class, () -> e.followAll(List.of(new AutomationEngine.FollowCandidate(APP, 10001, "digest", 0))));
        e.onTaskRemoved(APP); assertEquals(0, h.writes); assertFalse(e.healthy);
    }
}
