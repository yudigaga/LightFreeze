package io.github.yudigaga.lightfreeze.system;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class AutomationEngineTest {
    private static final AutomationEngine.Target TARGET = new AutomationEngine.Target("example.app", 0);
    private static final class Fake implements AutomationEngine.Host {
        long now; int writes, saves; boolean visible, locked, known = true, protectedNow, recent;
        int setting = 3; boolean failStore; Runnable timer, onWriteBarrier; long due;
        public long now() { return now; }
        public AutomationEngine.Observation observe(AutomationEngine.Session s) {
            return new AutomationEngine.Observation(true, setting, known, visible, known, locked, known, protectedNow);
        }
        public void freeze(AutomationEngine.Session s) { writes++; setting = 3; }
        public boolean hasRecentTask(AutomationEngine.Session s) { return recent; }
        public void persist(AutomationEngine e) throws Exception {
            if (failStore) throw new Exception("disk full");
            saves++;
            if (onWriteBarrier != null && e.sessions.values().stream().anyMatch(s -> s.phase == AutomationEngine.Phase.UNRESOLVED)) {
                Runnable r = onWriteBarrier; onWriteBarrier = null; r.run();
            }
        }
        public void schedule(AutomationEngine.Target t, long delay, Runnable r) { timer = r; due = now + delay; }
        public void cancel(AutomationEngine.Target t) { timer = null; }
        void tick(long time) { now = time; if (timer != null && due <= now) { Runnable r = timer; timer = null; r.run(); } }
    }
    private AutomationEngine active(Fake f, int mode) throws Exception {
        AutomationEngine e = new AutomationEngine(f);
        e.setPolicy(new AutomationEngine.Policy(mode, 3000, 10000, true, Set.of("example.app"), Map.of()));
        long lease = e.acquire(TARGET, "r1", 10001, "digest", 0, true);
        f.setting = 0;
        e.finish(TARGET, "r1", lease, true, false, 0);
        return e;
    }
    @Test public void changingTagRuleRearmsUsingTheNewDelay() throws Exception {
        Fake f = new Fake(); AutomationEngine e = active(f, 1);
        f.visible = true; e.onEvent(); f.visible = false; f.locked = true; e.onEvent();
        f.now = 1000;
        e.setPolicy(new AutomationEngine.Policy(1, 10000, 10000, true, Set.of("example.app"), Map.of()));
        f.tick(3000); assertEquals(0, f.writes);
        f.tick(11000); assertEquals(1, f.writes);
    }
    @Test public void identicalConfigSyncDoesNotExtendAnExistingDelay() throws Exception {
        Fake f = new Fake(); AutomationEngine e = active(f, 1);
        f.visible = true; e.onEvent(); f.visible = false; f.locked = true; e.onEvent(); f.now = 2000;
        e.setPolicy(new AutomationEngine.Policy(1, 3000, 10000, true, Set.of("example.app"), Map.of()));
        f.tick(3000); assertEquals(1, f.writes);
    }
    @Test public void identicalConfigDoesNotWriteAnotherSnapshotOrAdvanceRevision() throws Exception {
        Fake f = new Fake(); AutomationEngine e = new AutomationEngine(f);
        e.setPolicy(new AutomationEngine.Policy(4, 3000, 10000, true, Set.of("example.app"), Map.of()));
        int saves = f.saves; long revision = e.revision;
        e.setPolicy(new AutomationEngine.Policy(4, 3000, 10000, true, Set.of("example.app"), Map.of()));
        assertEquals(saves, f.saves); assertEquals(revision, e.revision);
    }
    @Test public void unchangedCancelledConfigStillRejectsUntilLeaseTerminates() throws Exception {
        Fake f = new Fake(); AutomationEngine e = new AutomationEngine(f);
        e.setPolicy(new AutomationEngine.Policy(4, 3000, 10000, true, Set.of("example.app"), Map.of()));
        e.acquire(TARGET, "pending", 10001, "digest", 0, true);
        AutomationEngine.Policy cancelled = new AutomationEngine.Policy(0, 3000, 10000, true, Set.of(), Map.of());
        assertThrows(IllegalStateException.class, () -> e.setPolicy(cancelled));
        int saves = f.saves; long revision = e.revision;
        assertThrows(IllegalStateException.class, () -> e.setPolicy(cancelled));
        assertEquals(saves, f.saves); assertEquals(revision, e.revision);
    }
    @Test public void graceDoesNotFreezeBeforeFirstWindowCanAppear() throws Exception {
        Fake f = new Fake(); AutomationEngine e = active(f, 4);
        f.tick(14999); assertEquals(0, f.writes);
        f.visible = true; e.onEvent(); f.tick(15000); assertEquals(0, f.writes);
    }
    @Test public void failedLaunchRestoresOnlyConfirmedTemporaryEnable() throws Exception {
        Fake f = new Fake(); active(f, 4); f.tick(15000); assertEquals(1, f.writes);
    }
    @Test public void visibleSplitScreenOrPipDefersCardFreezeUntilItsWindowDisappears() throws Exception {
        Fake f = new Fake(); AutomationEngine e = active(f, 4);
        f.visible = true; e.onEvent(); e.onTaskRemoved(TARGET);
        f.tick(20000); assertEquals(0, f.writes); assertNull(f.timer);
        f.visible = false; e.onEvent(); assertEquals(1, f.writes);
    }
    @Test public void duplicateEventsDoNotExtendOneShotDelay() throws Exception {
        Fake f = new Fake(); AutomationEngine e = active(f, 1);
        f.visible = true; e.onEvent(); f.visible = false; f.locked = true; e.onEvent();
        f.now = 2000; e.onEvent(); f.tick(3000); assertEquals(1, f.writes);
    }
    @Test public void screenOffWithoutKeyguardDoesNotFreezeLockMode() throws Exception {
        Fake f = new Fake(); AutomationEngine e = active(f, 1);
        f.visible = true; e.onEvent(); f.visible = false; e.onEvent();
        f.tick(3000); assertEquals(0, f.writes);
        f.locked = true; e.onEvent(); f.tick(6000); assertEquals(1, f.writes);
    }
    @Test public void unknownBackendRetainsExclusiveLease() throws Exception {
        Fake f = new Fake(); AutomationEngine e = active(f, 4);
        long lease = e.acquire(TARGET, "r2", 10001, "digest", 0, false);
        e.finish(TARGET, "r2", lease, false, true, 0); f.tick(999999);
        assertEquals(0, f.writes);
        try { e.acquire(TARGET, "r3", 10001, "digest", 0, false); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("lease")); }
    }
    @Test public void staleTimerCannotFreezeAfterManualLease() throws Exception {
        Fake f = new Fake(); AutomationEngine e = active(f, 1);
        f.visible = true; e.onEvent(); f.visible = false; f.locked = true; e.onEvent(); Runnable stale = f.timer;
        e.acquire(TARGET, "manual", 10001, "digest", 0, false); f.now = 3000; stale.run();
        assertEquals(0, f.writes);
    }
    @Test public void persistenceFailureNeverGrantsLease() throws Exception {
        Fake f = new Fake(); AutomationEngine e = new AutomationEngine(f); f.failStore = true;
        try { e.acquire(TARGET, "r", 10001, "digest", 0, false); fail(); }
        catch (Exception expected) { assertEquals("disk full", expected.getMessage()); }
        assertFalse(e.healthy); assertEquals(0, f.writes);
    }
    @Test public void uncertainVisibilityAndMediaProtectionSkipWrites() throws Exception {
        Fake f = new Fake(); AutomationEngine e = active(f, 1);
        f.visible = true; e.onEvent(); f.visible = false; f.locked = true; f.known = false; e.onEvent(); f.tick(20000);
        assertEquals(0, f.writes);
        f.known = true; f.protectedNow = true; e.onEvent(); f.tick(30000); assertEquals(0, f.writes);
        f.protectedNow = false; e.onEvent(); f.tick(32999); assertEquals(0, f.writes);
        f.tick(33000); assertEquals(1, f.writes);
    }
    @Test public void cancellationDuringEnablingCannotResurrectSession() throws Exception {
        Fake f = new Fake(); AutomationEngine e = new AutomationEngine(f);
        e.setPolicy(new AutomationEngine.Policy(4, 0, 0, false, Set.of("example.app"), Map.of()));
        long lease = e.acquire(TARGET, "r", 10001, "digest", 0, true);
        try { e.setPolicy(new AutomationEngine.Policy(0, 0, 0, false, Set.of(), Map.of())); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("cancellation awaits")); }
        f.setting = 0; e.finish(TARGET, "r", lease, true, false, 0); e.onEvent(); f.tick(20000);
        assertEquals(0, f.writes); assertTrue(e.sessions.isEmpty());
    }
    @Test public void selfFreezeBarrierPreservesEveryTargetSession() throws Exception {
        Fake f = new Fake(); AutomationEngine e = active(f, 4);
        e.prepareSelfFreeze(); assertEquals(1, e.sessions.size());
        assertEquals(AutomationEngine.Phase.ACTIVE, e.sessions.get(TARGET).phase);
    }
    @Test public void finishReceiptCanBeRetriedAfterManualCompletion() throws Exception {
        Fake f = new Fake(); AutomationEngine e = new AutomationEngine(f);
        long lease = e.acquire(TARGET, "manual", 10001, "digest", 0, false);
        e.finish(TARGET, "manual", lease, true, false, 3);
        e.finish(TARGET, "manual", lease, true, false, 3);
        assertTrue(e.sessions.isEmpty());
    }
    @Test public void externalEnabledSettingChangeCancelsAutomaticOwnership() throws Exception {
        Fake f = new Fake(); AutomationEngine e = active(f, 1);
        f.visible = true; e.onEvent(); f.visible = false; f.locked = true; e.onEvent();
        f.setting = 1; f.tick(3000); assertEquals(0, f.writes); assertTrue(e.sessions.isEmpty());
    }
    @Test public void selfFreezeIsBlockedDuringUnknownBackendOperation() throws Exception {
        Fake f = new Fake(); AutomationEngine e = active(f, 4);
        e.acquire(TARGET, "manual", 10001, "digest", 0, false);
        try { e.prepareSelfFreeze(); fail(); } catch (IllegalStateException expected) { }
        assertEquals(1, e.sessions.size()); assertEquals(0, f.writes);
    }
    @Test public void reopeningWhileSnapshotIsWrittenPreventsFreeze() throws Exception {
        Fake f = new Fake(); AutomationEngine e = active(f, 4);
        f.visible = true; e.onEvent(); f.visible = false; e.onEvent();
        f.onWriteBarrier = () -> f.visible = true;
        e.onTaskRemoved(TARGET); assertEquals(0, f.writes);
        assertEquals(AutomationEngine.Phase.ACTIVE, e.sessions.get(TARGET).phase);
    }
    @Test public void unlockingWhileSnapshotIsWrittenPreventsFreeze() throws Exception {
        Fake f = new Fake(); AutomationEngine e = active(f, 1);
        f.visible = true; e.onEvent(); f.locked = true; e.onEvent();
        f.onWriteBarrier = () -> f.locked = false;
        f.tick(3000); assertEquals(0, f.writes);
        assertEquals(AutomationEngine.Phase.ACTIVE, e.sessions.get(TARGET).phase);
    }
    @Test public void tagUnlockWithoutLaunchWaitsForActualKeyguard() throws Exception {
        Fake f = new Fake(); AutomationEngine e = new AutomationEngine(f);
        e.setPolicy(new AutomationEngine.Policy(1, 3000, 10000, true, Set.of("example.app"), Map.of()));
        long lease = e.acquire(TARGET, "tag", 10001, "digest", 0, true, false);
        f.setting = 0; e.finish(TARGET, "tag", lease, true, false, 0);
        f.tick(15000); assertEquals(0, f.writes);
        f.locked = true; e.onEvent(); f.tick(18000); assertEquals(1, f.writes);
    }
    @Test public void cardOnlyTagUnlockWaitsForAnActualCardRemoval() throws Exception {
        Fake f = new Fake(); AutomationEngine e = new AutomationEngine(f);
        e.setPolicy(new AutomationEngine.Policy(4, 3000, 10000, true, Set.of("example.app"), Map.of()));
        long lease = e.acquire(TARGET, "tag", 10001, "digest", 0, true, false);
        f.setting = 0; e.finish(TARGET, "tag", lease, true, false, 0);
        f.tick(20000); assertEquals(0, f.writes); assertNull(f.timer);
        e.onTaskRemoved(TARGET); assertEquals(1, f.writes);
    }
    @Test public void recentTaskProtectionBlocksEvenWhenMediaProtectionIsDisabled() throws Exception {
        Fake f = new Fake(); AutomationEngine e = active(f, 1);
        f.visible = true; e.onEvent(); f.visible = false; f.locked = true;
        e.setPolicy(new AutomationEngine.Policy(1, 3000, 10000, false, true, Set.of("example.app"), Map.of()));
        // The host combines enabled protections; this represents a matching recent task.
        f.protectedNow = true; e.onEvent(); f.tick(30000);
        assertEquals(0, f.writes); assertNull(f.timer);
        assertEquals(AutomationEngine.Phase.ACTIVE, e.sessions.get(TARGET).phase);
    }
    @Test public void removingRecentTaskStartsLockDelayOnlyWhileKeyguardIsLocked() throws Exception {
        Fake f = new Fake(); AutomationEngine e = active(f, 1);
        f.visible = true; e.onEvent(); f.visible = false; f.locked = true;
        e.setPolicy(new AutomationEngine.Policy(1, 3000, 10000, false, true, Set.of("example.app"), Map.of()));
        f.protectedNow = true; e.onEvent(); f.tick(30000);
        assertEquals(0, f.writes);
        f.protectedNow = false; e.onEvent();
        f.tick(32999); assertEquals(0, f.writes);
        f.tick(33000); assertEquals(1, f.writes);
    }
    @Test public void recentTaskAppearingAtSnapshotBarrierPreventsPackageManagerWrite() throws Exception {
        Fake f = new Fake(); AutomationEngine e = active(f, 1);
        f.visible = true; e.onEvent(); f.visible = false; f.locked = true;
        e.setPolicy(new AutomationEngine.Policy(1, 3000, 10000, false, true, Set.of("example.app"), Map.of()));
        f.onWriteBarrier = () -> f.protectedNow = true;
        f.tick(3000); assertEquals(0, f.writes);
        assertEquals(AutomationEngine.Phase.ACTIVE, e.sessions.get(TARGET).phase);
    }
    @Test public void legacyPolicyKeepsRecentTaskProtectionOptIn() {
        assertFalse(new AutomationEngine.Policy(4, 3000, 10000, true, Set.of("example.app"), Map.of()).protectRecents());
    }
    @Test public void freezeCheckRequiresTheSamePendingManualLeaseAndInstallation() throws Exception {
        Fake f = new Fake(); AutomationEngine e = new AutomationEngine(f);
        long lease = e.acquire(TARGET, "manual", 10001, "digest", 0, false);
        e.checkFreezeLease(TARGET, "manual", lease, 10001, "digest");
        try { e.checkFreezeLease(TARGET, "other", lease, 10001, "digest"); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("lease")); }
        try { e.checkFreezeLease(TARGET, "manual", lease, 10002, "digest"); fail(); }
        catch (SecurityException expected) { }
        try { e.checkFreezeLease(TARGET, "manual", lease, 10001, "reinstalled"); fail(); }
        catch (SecurityException expected) { }
        e.finish(TARGET, "manual", lease, false, true, 3);
        try { e.checkFreezeLease(TARGET, "manual", lease, 10001, "digest"); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("lease")); }
        assertEquals(AutomationEngine.Phase.UNRESOLVED, e.sessions.get(TARGET).phase);
    }
    @Test public void freezeCheckCannotBorrowAnAutomaticSessionLease() throws Exception {
        Fake f = new Fake(); AutomationEngine e = active(f, 4);
        AutomationEngine.Session s = e.sessions.get(TARGET);
        try { e.checkFreezeLease(TARGET, "r1", s.lease, 10001, "digest"); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("lease")); }
        assertEquals(AutomationEngine.Phase.ACTIVE, s.phase);
    }
    @Test public void windowSeenWhileProtectedDoesNotBecomeFailedLaunchAfterRecentRemoval() throws Exception {
        Fake f = new Fake(); f.protectedNow = true; AutomationEngine e = active(f, 1);
        e.setPolicy(new AutomationEngine.Policy(1, 3000, 10000, false, true, Set.of("example.app"), Map.of()));
        f.visible = true; e.onEvent();
        f.tick(20000); f.visible = false; f.protectedNow = false; e.onEvent();
        assertEquals(0, f.writes); assertNull(f.timer);
        f.locked = true; e.onEvent(); f.tick(23000); assertEquals(1, f.writes);
    }
    @Test public void windowSeenWhileProtectedDoesNotTurnCardOnlyIntoFailedLaunchRollback() throws Exception {
        Fake f = new Fake(); f.protectedNow = true; AutomationEngine e = active(f, 4);
        e.setPolicy(new AutomationEngine.Policy(4, 3000, 10000, false, true, Set.of("example.app"), Map.of()));
        f.visible = true; e.onEvent();
        f.tick(20000); f.visible = false; f.protectedNow = false; e.onEvent();
        assertEquals(0, f.writes); assertNull(f.timer);
        f.tick(30000); assertEquals(0, f.writes);
        e.onTaskRemoved(TARGET); assertEquals(1, f.writes);
    }
    @Test public void windowSeenDuringMediaPlaybackStillWaitsForActualKeyguard() throws Exception {
        Fake f = new Fake(); f.protectedNow = true; AutomationEngine e = active(f, 1);
        f.visible = true; e.onEvent();
        f.tick(20000); f.visible = false; f.protectedNow = false; e.onEvent();
        assertEquals(0, f.writes); assertNull(f.timer);
        f.locked = true; e.onEvent(); f.tick(23000); assertEquals(1, f.writes);
    }
}
