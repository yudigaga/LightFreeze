package io.github.yudigaga.lightfreeze.system;

import io.github.yudigaga.lightfreeze.automation.FrozenUrlSnapshot;
import io.github.yudigaga.lightfreeze.automation.UrlAction;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class FrozenUrlCoordinatorTest {
    private static final String TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String URI = "lightfreeze-yudigaga://v1/freeze?tab=all&token=" + TOKEN;
    private static final String DIGEST = FrozenUrlSnapshot.actionDigest(UrlAction.parse(URI));
    private static final class Fake implements FrozenUrlCoordinator.Host {
        int setting, enables, disables, launches, barriers; boolean failSave, failEnable, failLaunch, failBarrier;
        String saved, ticket; final List<Runnable> jobs = new ArrayList<>(), timeouts = new ArrayList<>();
        public int moduleSetting(int user) { return setting; }
        public void enable(int user, int original) throws Exception { if (failEnable) throw new Exception("enable"); enables++; setting = original; }
        public void disable(int user) { disables++; setting = 3; }
        public void prepareFreeze() throws Exception { if (failBarrier) throw new Exception("lease unresolved"); barriers++; }
        public void persist(String json) throws Exception { if (failSave) throw new Exception("disk full"); saved = json; }
        public boolean post(Runnable job, long delay) { (delay == 500 ? jobs : timeouts).add(job); return true; }
        public void launch(String ticket, String uri, int user) throws Exception { if (failLaunch) throw new Exception("launch"); this.ticket = ticket; launches++; }
        public void log(String problem) { }
        void run() { List<Runnable> copy = new ArrayList<>(jobs); jobs.clear(); copy.forEach(Runnable::run); }
        void expire() { List<Runnable> copy = new ArrayList<>(timeouts); timeouts.clear(); copy.forEach(Runnable::run); }
    }
    private FrozenUrlCoordinator armed(Fake f) throws Exception {
        FrozenUrlCoordinator c = new FrozenUrlCoordinator(f); c.restore(null, "owner");
        c.sync(FrozenUrlSnapshot.create(0, true, Map.of("tab:all", TOKEN)), "owner");
        c.arm(0, c.digest(), c.epoch(), true, true); f.run();
        assertEquals(3, f.setting); return c;
    }
    @Test public void authorizedRequestWakesAndOnlyExplicitCompletionRefreezes() throws Exception {
        Fake f = new Fake(); FrozenUrlCoordinator c = armed(f);
        assertTrue(c.route(URI, 0)); assertEquals(0, f.setting); assertEquals(1, f.enables);
        assertTrue(c.begin(f.ticket, DIGEST)); assertFalse(c.begin(f.ticket, DIGEST));
        c.end(f.ticket); assertEquals(0, f.setting); f.run(); assertEquals(3, f.setting);
        assertEquals(2, f.disables); assertFalse(f.saved.contains(TOKEN)); assertFalse(f.saved.contains(URI));
    }
    @Test public void invalidTokensUsersAndDigestsDoNotExecute() throws Exception {
        Fake f = new Fake(); FrozenUrlCoordinator c = armed(f);
        assertFalse(c.route(URI.replace(TOKEN, "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"), 0));
        assertFalse(c.route(URI, 10)); assertEquals(0, f.enables);
        c.route(URI, 0); assertFalse(c.begin(f.ticket, "wrong")); assertEquals(1, c.activeCount());
    }
    @Test public void simultaneousRequestsAreCountedAndAnOldCompletionCannotCloseAnotherWindow() throws Exception {
        Fake f = new Fake(); FrozenUrlCoordinator c = armed(f);
        c.route(URI, 0); String a = f.ticket; c.begin(a, DIGEST);
        c.route(URI, 0); String b = f.ticket; c.begin(b, DIGEST);
        assertEquals(1, f.enables); assertEquals(2, c.activeCount());
        c.end(a); f.run(); assertEquals(0, f.setting);
        c.end(b); c.end(a); f.run(); assertEquals(3, f.setting);
        c.route(URI, 0); String fresh = f.ticket; c.begin(fresh, DIGEST);
        c.end(a); f.run(); assertEquals(0, f.setting); assertEquals(1, c.activeCount());
    }
    @Test public void keepInvalidatesDelayedRefreezeWithoutCancellingRunningDelivery() throws Exception {
        Fake f = new Fake(); FrozenUrlCoordinator c = armed(f);
        c.route(URI, 0); String t = f.ticket; c.begin(t, DIGEST);
        String before = c.epoch(); c.keepEnabled(); assertNotEquals(before, c.epoch());
        c.end(t); f.run(); assertEquals(0, f.setting); assertFalse(c.armed());
    }
    @Test public void staleSelfFreezeEvidenceAndPersistenceFailureFailClosed() throws Exception {
        Fake f = new Fake(); FrozenUrlCoordinator c = new FrozenUrlCoordinator(f); c.restore(null, "owner");
        c.sync(FrozenUrlSnapshot.create(0, true, Map.of("tab:all", TOKEN)), "owner");
        assertThrows(IllegalStateException.class, () -> c.arm(0, "stale", c.epoch(), true, true));
        assertThrows(SecurityException.class, () -> c.arm(0, c.digest(), c.epoch(), false, true));
        f.failSave = true; assertThrows(Exception.class, () -> c.arm(0, c.digest(), c.epoch(), true, true));
        f.run(); assertEquals(0, f.disables); assertFalse(c.armed());
    }
    @Test public void restoredAwakeCheckpointAndChangedOwnerNeverAutomaticallyFreeze() throws Exception {
        Fake f = new Fake(); FrozenUrlCoordinator c = armed(f); c.route(URI, 0);
        FrozenUrlCoordinator restored = new FrozenUrlCoordinator(f); restored.restore(f.saved, "owner");
        assertEquals("uncertain", restored.phase()); assertFalse(restored.armed()); f.run(); assertEquals(0, f.setting);
        FrozenUrlCoordinator changed = new FrozenUrlCoordinator(f); changed.restore(f.saved, "different");
        assertEquals("", changed.digest()); assertFalse(changed.armed());
    }
    @Test public void missingEndTimesOutWithoutKillingTheClientBetweenBatchItems() throws Exception {
        Fake f = new Fake(); FrozenUrlCoordinator c = armed(f);
        c.route(URI, 0); String ticket = f.ticket; c.begin(ticket, DIGEST);
        f.expire(); f.run(); assertEquals(0, f.setting); assertFalse(c.armed());
        assertEquals("uncertain", c.phase()); c.end(ticket); f.run(); assertEquals(1, f.disables);
    }
    @Test public void successfulCompletionMakesItsOldTimeoutHarmless() throws Exception {
        Fake f = new Fake(); FrozenUrlCoordinator c = armed(f);
        c.route(URI, 0); c.begin(f.ticket, DIGEST); c.end(f.ticket); f.run(); f.expire();
        assertEquals(3, f.setting); assertEquals("frozen", c.phase()); assertTrue(c.armed());
    }
    @Test public void failedUnclaimedLaunchRefreezesButUnresolvedBackendDoesNot() throws Exception {
        Fake f = new Fake(); FrozenUrlCoordinator c = armed(f); f.failLaunch = true;
        assertTrue(c.route(URI, 0)); assertEquals(0, c.activeCount()); f.run(); assertEquals(3, f.setting);
        f.failLaunch = false; c.route(URI, 0); c.begin(f.ticket, DIGEST);
        f.failBarrier = true; c.end(f.ticket); f.run();
        assertEquals(0, f.setting); assertEquals("uncertain", c.phase()); assertFalse(c.armed());
    }
    @Test public void persistenceFailureBeforeWakeCannotEnableOrLaunch() throws Exception {
        Fake f = new Fake(); FrozenUrlCoordinator c = armed(f); f.failSave = true;
        assertThrows(Exception.class, () -> c.route(URI, 0));
        assertEquals(0, f.enables); assertEquals(0, f.launches); assertFalse(c.armed());
    }
    @Test public void finalFreezeCheckpointMustPersistBeforeThePmDisable() throws Exception {
        Fake f = new Fake(); FrozenUrlCoordinator c = new FrozenUrlCoordinator(f); c.restore(null, "owner");
        c.sync(FrozenUrlSnapshot.create(0, true, Map.of("tab:all", TOKEN)), "owner");
        c.arm(0, c.digest(), c.epoch(), true, true); f.failSave = true; f.run();
        assertEquals(0, f.disables); assertEquals(0, f.setting); assertFalse(c.armed());
    }
    @Test public void keepThenNewArmWaitsForAlreadyClaimedDeliveryToEnd() throws Exception {
        Fake f = new Fake(); FrozenUrlCoordinator c = armed(f);
        c.route(URI, 0); String old = f.ticket; c.begin(old, DIGEST); c.keepEnabled();
        c.sync(FrozenUrlSnapshot.create(0, true, Map.of("tab:all", TOKEN)), "owner");
        c.arm(0, c.digest(), c.epoch(), true, true); f.run(); assertEquals(0, f.setting);
        c.end(old); f.run(); assertEquals(3, f.setting);
    }
    @Test public void selfFreezeWaitsForTrackedUrlInsteadOfRejectingItsInFlightLease() throws Exception {
        Fake f = new Fake(); FrozenUrlCoordinator c = armed(f);
        c.route(URI, 0); String old = f.ticket; c.begin(old, DIGEST); c.keepEnabled();
        c.sync(FrozenUrlSnapshot.create(0, true, Map.of("tab:all", TOKEN)), "owner");
        f.failBarrier = true;
        c.arm(0, c.digest(), c.epoch(), true, true); f.run(); assertEquals(0, f.setting);
        f.failBarrier = false; c.end(old); f.run(); assertEquals(3, f.setting);
    }
    @Test public void manuallyChangedModuleStateCancelsRefreeze() throws Exception {
        Fake f = new Fake(); FrozenUrlCoordinator c = armed(f);
        c.route(URI, 0); c.begin(f.ticket, DIGEST); c.end(f.ticket); f.setting = 1;
        f.run(); assertEquals(1, f.setting); assertFalse(c.armed());
    }
    @Test public void aNewDeliveryCancelsThePreviousDelayedRefreeze() throws Exception {
        Fake f = new Fake(); FrozenUrlCoordinator c = armed(f);
        c.route(URI, 0); c.begin(f.ticket, DIGEST); c.end(f.ticket);
        c.route(URI, 0); String latest = f.ticket; c.begin(latest, DIGEST);
        f.run(); assertEquals(0, f.setting); assertEquals(1, c.activeCount());
        c.end(latest); f.run(); assertEquals(3, f.setting);
    }
    @Test public void keepRejectsUnclaimedDeliveryAndCancelsInitialSelfFreezeJob() throws Exception {
        Fake f = new Fake(); FrozenUrlCoordinator c = armed(f);
        c.route(URI, 0); String unclaimed = f.ticket; c.keepEnabled();
        assertFalse(c.begin(unclaimed, DIGEST)); assertEquals(0, c.activeCount()); f.run(); assertEquals(0, f.setting);
        c.sync(FrozenUrlSnapshot.create(0, true, Map.of("tab:all", TOKEN)), "owner");
        c.arm(0, c.digest(), c.epoch(), true, true); c.keepEnabled(); f.run(); assertEquals(0, f.setting);
    }
}
