package io.github.yudigaga.lightfreeze.system;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class TabSessionTest {
    private static final String ALL = "builtin:all", FROZEN = "builtin:frozen";
    private static final String OLD_TAG = "123e4567-e89b-12d3-a456-426614174000";
    private static final AutomationEngine.Target A = new AutomationEngine.Target("example.first", 0);
    private static final AutomationEngine.Target B = new AutomationEngine.Target("example.second", 0);
    private static final class Host implements AutomationEngine.Host {
        long now; int writes;
        final Map<AutomationEngine.Target,Integer> settings = new HashMap<>();
        final Map<AutomationEngine.Target,Boolean> locks = new HashMap<>();
        final Map<AutomationEngine.Target,Runnable> timers = new HashMap<>();
        final Map<AutomationEngine.Target,Long> due = new HashMap<>();
        public long now() { return now; }
        public AutomationEngine.Observation observe(AutomationEngine.Session s) {
            return new AutomationEngine.Observation(true, settings.getOrDefault(s.target, 3), true, false,
                    true, locks.getOrDefault(s.target, false), true, false);
        }
        public void freeze(AutomationEngine.Session s) { writes++; settings.put(s.target, 3); }
        public void persist(AutomationEngine engine) { }
        public void schedule(AutomationEngine.Target target, long delay, Runnable task) {
            timers.put(target, task); due.put(target, now + delay);
        }
        public void cancel(AutomationEngine.Target target) { timers.remove(target); due.remove(target); }
    }
    private static AutomationEngine.Policy policy(int defaultMode, Map<String,Integer> ownerModes,
                                                  Map<String,Integer> entryModes) {
        return new AutomationEngine.Policy(defaultMode, 3000, 10000, false, false,
                Set.of(A.packageName(), B.packageName()), ownerModes, entryModes);
    }
    private static long begin(AutomationEngine engine, AutomationEngine.Target target, String id, String entry) throws Exception {
        return engine.acquire(target, id, 10001, "digest", 1, true, false, entry);
    }
    private static void active(AutomationEngine engine, Host host, AutomationEngine.Target target, String id, String entry) throws Exception {
        long lease = begin(engine, target, id, entry);
        host.settings.put(target, 1); engine.finish(target, id, lease, true, false, 1);
    }

    @Test public void manualOwnerCannotBeOverriddenByAnAllOrFrozenView() throws Exception {
        Host host = new Host(); AutomationEngine engine = new AutomationEngine(host);
        engine.setPolicy(policy(1, Map.of(A.packageName(), 0), Map.of(ALL, 1, FROZEN, 4)));
        assertEquals(0, engine.policy.modeFor(A.packageName(), ALL));
        assertEquals(0, engine.policy.modeFor(A.packageName(), FROZEN));
        assertThrows(IllegalStateException.class, () -> begin(engine, A, "all", ALL));
        assertThrows(IllegalStateException.class, () -> begin(engine, A, "frozen", FROZEN));
        assertTrue(engine.sessions.isEmpty());
    }
    @Test public void twoPackagesUseTheirOwnRulesRegardlessOfEntryView() throws Exception {
        Host host = new Host(); AutomationEngine engine = new AutomationEngine(host);
        engine.setPolicy(policy(0, Map.of(A.packageName(), 4, B.packageName(), 1), Map.of(ALL, 1, FROZEN, 4)));
        active(engine, host, A, "all", ALL); active(engine, host, B, "frozen", FROZEN);
        assertEquals(4, engine.policy.modeFor(A.packageName(), ALL));
        assertEquals(1, engine.policy.modeFor(B.packageName(), FROZEN));
        host.locks.put(B, true); engine.onEvent();
        assertFalse(host.timers.containsKey(A)); assertEquals(Long.valueOf(3000), host.due.get(B));
        engine.onTaskRemoved(A); assertEquals(1, host.writes);
        assertFalse(engine.sessions.containsKey(A)); assertTrue(engine.sessions.containsKey(B));
    }
    @Test public void movingOneOwnerRuleDoesNotResetAnotherPackagesCountdown() throws Exception {
        Host host = new Host(); AutomationEngine engine = new AutomationEngine(host);
        engine.setPolicy(policy(0, Map.of(A.packageName(), 1, B.packageName(), 1), Map.of(OLD_TAG, 1)));
        host.locks.put(A, true); host.locks.put(B, true);
        active(engine, host, A, "a", OLD_TAG); active(engine, host, B, "b", OLD_TAG);
        Runnable originalB = host.timers.get(B); long generationB = engine.sessions.get(B).generation;
        host.now = 1000;
        engine.setPolicy(policy(0, Map.of(A.packageName(), 4, B.packageName(), 1), Map.of(OLD_TAG, 1)));
        assertFalse(host.timers.containsKey(A)); assertSame(originalB, host.timers.get(B));
        assertEquals(Long.valueOf(3000), host.due.get(B)); assertEquals(generationB, engine.sessions.get(B).generation);
        engine.onTaskRemoved(A); assertEquals(1, host.writes);
    }
    @Test public void movingToAManualOwnerCancelsOldEntryClockImmediately() throws Exception {
        Host host = new Host(); AutomationEngine engine = new AutomationEngine(host);
        engine.setPolicy(policy(0, Map.of(A.packageName(), 1), Map.of(OLD_TAG, 1)));
        host.locks.put(A, true); active(engine, host, A, "a", OLD_TAG);
        Runnable stale = host.timers.get(A);
        engine.setPolicy(policy(0, Map.of(A.packageName(), 0), Map.of(OLD_TAG, 1)));
        assertFalse(engine.sessions.containsKey(A)); assertFalse(host.timers.containsKey(A));
        host.now = 50000; stale.run(); engine.onEvent(); assertEquals(0, host.writes);
    }
    @Test public void deletingOnlyTheOldEntryDoesNotCancelAnOwnersActiveRule() throws Exception {
        Host host = new Host(); AutomationEngine engine = new AutomationEngine(host);
        engine.setPolicy(policy(0, Map.of(A.packageName(), 1), Map.of(OLD_TAG, 4)));
        host.locks.put(A, true); active(engine, host, A, "a", OLD_TAG);
        Runnable original = host.timers.get(A);
        engine.setPolicy(policy(0, Map.of(A.packageName(), 1), Map.of()));
        assertTrue(engine.sessions.containsKey(A)); assertSame(original, host.timers.get(A));
        host.now = 3000; original.run(); assertEquals(1, host.writes);
    }
    @Test public void ownerBecomingManualKeepsThePendingLeaseUntilItsReceipt() throws Exception {
        Host host = new Host(); AutomationEngine engine = new AutomationEngine(host);
        engine.setPolicy(policy(4, Map.of(), Map.of(ALL, 4)));
        active(engine, host, A, "active", ALL); long pending = begin(engine, B, "pending", ALL);
        assertThrows(IllegalStateException.class, () -> engine.setPolicy(policy(0, Map.of(), Map.of(ALL, 4))));
        assertFalse(engine.sessions.containsKey(A)); assertTrue(engine.sessions.get(B).cancelled);
        assertEquals(AutomationEngine.Phase.ENABLING, engine.sessions.get(B).phase);
        host.settings.put(B, 1); engine.finish(B, "pending", pending, true, false, 1);
        assertFalse(engine.sessions.containsKey(B)); assertEquals(0, host.writes);
    }
    @Test public void aValidDeletedEntryIsOnlyDiagnosticButMalformedEntryIsRejected() throws Exception {
        Host host = new Host(); AutomationEngine engine = new AutomationEngine(host);
        engine.setPolicy(policy(4, Map.of(), Map.of()));
        active(engine, host, A, "deleted-entry", OLD_TAG);
        assertEquals(OLD_TAG, engine.sessions.get(A).sourceTab);
        assertThrows(IllegalArgumentException.class, () -> begin(engine, B, "bad", "builtin:unknown"));
        assertThrows(IllegalArgumentException.class, () -> begin(engine, B, "null", null));
    }
    @Test public void idempotentRequestCannotRewriteItsEntryMetadata() throws Exception {
        Host host = new Host(); AutomationEngine engine = new AutomationEngine(host);
        engine.setPolicy(policy(4, Map.of(), Map.of(ALL, 1, FROZEN, 0)));
        long lease = begin(engine, A, "same", ALL);
        assertEquals(lease, begin(engine, A, "same", ALL));
        assertThrows(IllegalStateException.class, () -> begin(engine, A, "same", FROZEN));
        assertEquals(ALL, engine.sessions.get(A).sourceTab);
    }
    @Test public void anotherEntryStillUsesTheOwnerAndOnlyReplacesThatPackagesSession() throws Exception {
        Host host = new Host(); AutomationEngine engine = new AutomationEngine(host);
        engine.setPolicy(policy(1, Map.of(), Map.of(ALL, 4, FROZEN, 0)));
        host.locks.put(A, true); host.locks.put(B, true);
        active(engine, host, A, "old", ALL); active(engine, host, B, "other", ALL);
        AutomationEngine.Session other = engine.sessions.get(B); Runnable stale = host.timers.get(A);
        host.now = 2000;
        long next = begin(engine, A, "new", FROZEN); engine.finish(A, "new", next, true, false, 1);
        assertSame(other, engine.sessions.get(B)); assertEquals(2, engine.sessions.size());
        assertEquals(Long.valueOf(5000), host.due.get(A));
        host.now = 3000; stale.run(); assertEquals(0, host.writes);
        host.now = 5000; host.timers.get(A).run(); assertEquals(1, host.writes);
    }
    @Test public void unassignedPackagesKeepTheirDefaultRuleInsteadOfAViewsMode() throws Exception {
        Host host = new Host(); AutomationEngine engine = new AutomationEngine(host);
        engine.setPolicy(policy(1, Map.of(), Map.of(ALL, 1, FROZEN, 4)));
        host.locks.put(A, true); active(engine, host, A, "default", FROZEN);
        assertEquals(Long.valueOf(3000), host.due.get(A));
        assertEquals(1, engine.policy.modeFor(A.packageName(), ""));
    }
}
