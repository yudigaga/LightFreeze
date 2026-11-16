package io.github.yudigaga.lightfreeze.system;

import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class MultiTriggerTest {
    private static final AutomationEngine.Target TARGET = new AutomationEngine.Target("example.app", 0);
    private static final class Host implements AutomationEngine.Host {
        long now, due; int writes; boolean visible, locked, protectedNow, recent, queryFails, protectionKnown = true;
        Runnable timer, barrier;
        public long now() { return now; }
        public AutomationEngine.Observation observe(AutomationEngine.Session s) {
            return new AutomationEngine.Observation(true, writes == 0 ? 1 : 3, true, visible, true, locked, protectionKnown, protectedNow);
        }
        public boolean hasRecentTask(AutomationEngine.Session s) throws Exception {
            if (queryFails) throw new Exception("recent query unavailable");
            return recent;
        }
        public void freeze(AutomationEngine.Session s) { writes++; }
        public void persist(AutomationEngine e) {
            if (barrier != null && e.sessions.values().stream().anyMatch(s -> s.phase == AutomationEngine.Phase.UNRESOLVED)) {
                Runnable r = barrier; barrier = null; r.run();
            }
        }
        public void schedule(AutomationEngine.Target t, long delay, Runnable task) { timer = task; due = now + delay; }
        public void cancel(AutomationEngine.Target t) { timer = null; }
        void tick(long time) { now = time; if (timer != null && due <= time) { Runnable r = timer; timer = null; r.run(); } }
    }
    private static AutomationEngine active(Host host, int mode) throws Exception {
        AutomationEngine engine = new AutomationEngine(host);
        engine.setPolicy(new AutomationEngine.Policy(mode, 3000, 10000, true, false,
                Set.of(TARGET.packageName()), Map.of(), Map.of("builtin:all", mode)));
        long lease = engine.acquire(TARGET, "thaw", 10001, "digest", 1, true, false, "builtin:all");
        engine.finish(TARGET, "thaw", lease, true, false, 1);
        return engine;
    }
    @Test public void cardRemovalWinsWhileLockDelayIsStillPending() throws Exception {
        Host host = new Host(); host.locked = true; AutomationEngine engine = active(host, 5);
        assertEquals(3000, host.due);
        host.now = 1000; engine.onTaskRemoved(TARGET);
        assertEquals(1, host.writes); assertNull(host.timer);
    }
    @Test public void unlockingCancelsTheLockDeadlineButKeepsTheIndependentCardRule() throws Exception {
        Host host = new Host(); host.locked = true; AutomationEngine engine = active(host, 5);
        host.now = 1000; host.locked = false; engine.onEvent();
        assertNull(host.timer); host.tick(20000); assertEquals(0, host.writes);
        engine.onTaskRemoved(TARGET); assertEquals(1, host.writes);
    }
    @Test public void availableCardOnlySessionWithoutLaunchOrRemovalDoesNotFreeze() throws Exception {
        Host host = new Host(); AutomationEngine engine = active(host, 4);
        assertFalse(engine.sessions.get(TARGET).launchExpected);
        assertTrue(engine.sessions.get(TARGET).firstVisible);
        host.tick(15000); engine.onEvent(); assertEquals(0, host.writes); assertNull(host.timer);
        host.tick(60000); engine.onEvent(); assertEquals(0, host.writes); assertNull(host.timer);
        engine.onTaskRemoved(TARGET); assertEquals(1, host.writes);
    }
    @Test public void cardQueryFailureDoesNotCancelAnIndependentLockDeadline() throws Exception {
        Host host = new Host(); host.locked = true; AutomationEngine engine = active(host, 5);
        assertEquals(3000, host.due);
        host.queryFails = true;
        engine.onTaskRemoved(TARGET);
        host.tick(3000); assertEquals(1, host.writes);
    }
    @Test public void globalRecentProtectionUnknownStillBlocksAllSelectedTriggers() throws Exception {
        Host host = new Host(); host.locked = true; AutomationEngine engine = active(host, 5);
        host.queryFails = true; host.protectionKnown = false;
        engine.setPolicy(new AutomationEngine.Policy(5, 3000, 10000, true, true,
                Set.of(TARGET.packageName()), Map.of(), Map.of("builtin:all", 5)));
        engine.onTaskRemoved(TARGET);
        host.tick(3000); host.tick(20000); assertEquals(0, host.writes);
        assertEquals(AutomationEngine.Phase.ACTIVE, engine.sessions.get(TARGET).phase);
    }
    @Test public void anotherCardSuppressesTheEventUntilTheLastCardIsRemoved() throws Exception {
        Host host = new Host(); host.recent = true; AutomationEngine engine = active(host, 4);
        engine.onTaskRemoved(TARGET); assertEquals(0, host.writes);
        host.recent = false; engine.onEvent(); assertEquals(0, host.writes);
        engine.onTaskRemoved(TARGET); assertEquals(1, host.writes);
    }
    @Test public void cardEventWaitsForMediaAndVisibleWindowProtectionToEnd() throws Exception {
        Host host = new Host(); AutomationEngine engine = active(host, 4);
        host.protectedNow = true; engine.onTaskRemoved(TARGET); assertEquals(0, host.writes);
        host.protectedNow = false; host.visible = true; engine.onEvent(); assertEquals(0, host.writes);
        host.visible = false; engine.onEvent(); assertEquals(1, host.writes);
    }
    @Test public void reappearingCardAtWriteBarrierPreventsTheWriteAndConsumesOldEvent() throws Exception {
        Host host = new Host(); AutomationEngine engine = active(host, 4);
        host.barrier = () -> host.recent = true;
        engine.onTaskRemoved(TARGET); assertEquals(0, host.writes);
        host.recent = false; engine.onEvent(); assertEquals(0, host.writes);
    }
    @Test public void cardEventCannotOverrideOwnerRuleOrAnUnresolvedLease() throws Exception {
        Host host = new Host(); AutomationEngine engine = active(host, 1);
        engine.onTaskRemoved(TARGET); assertEquals(0, host.writes);
        engine.setPolicy(new AutomationEngine.Policy(4, 3000, 10000, true, false,
                Set.of(TARGET.packageName()), Map.of(), Map.of("builtin:all", 4)));
        engine.acquire(TARGET, "manual", 10001, "digest", 1, false, false, "builtin:all");
        engine.onTaskRemoved(TARGET); assertEquals(0, host.writes);
        assertEquals(AutomationEngine.Phase.ENABLING, engine.sessions.get(TARGET).phase);
    }
    @Test public void cardQueryFailureNeverWritesAndCanRecoverOnAnEvent() throws Exception {
        Host host = new Host(); AutomationEngine engine = active(host, 4);
        host.queryFails = true; engine.onTaskRemoved(TARGET); assertEquals(0, host.writes);
        host.queryFails = false; engine.onEvent(); assertEquals(1, host.writes);
    }
    @Test public void unlockingAtWriteBarrierCancelsTheTimerAndAllowsALaterCardGesture() throws Exception {
        Host host = new Host(); host.locked = true; AutomationEngine engine = active(host, 5);
        host.barrier = () -> host.locked = false;
        host.tick(3000); assertEquals(0, host.writes); assertNull(host.timer);
        engine.onTaskRemoved(TARGET); assertEquals(1, host.writes);
    }
    @Test public void failedCardQueryAtWriteBarrierDoesNotInventAnUnknownPmMutation() throws Exception {
        Host host = new Host(); AutomationEngine engine = active(host, 4);
        host.barrier = () -> host.queryFails = true;
        engine.onTaskRemoved(TARGET); assertEquals(0, host.writes);
        assertEquals(AutomationEngine.Phase.ACTIVE, engine.sessions.get(TARGET).phase);
        host.queryFails = false; engine.onEvent(); assertEquals(1, host.writes);
    }
}
