package io.github.yudigaga.lightfreeze.system;

import io.github.yudigaga.lightfreeze.core.RuleSnapshot;
import io.github.yudigaga.lightfreeze.tags.TagRules;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class HighRiskModelTest {
    private static final String PKG = "com.android.thememanager";
    private static final AutomationEngine.Target TARGET = new AutomationEngine.Target(PKG, 0);
    private static final class Host implements AutomationEngine.Host {
        long time; int writes, setting;
        public long now() { return time; }
        public AutomationEngine.Observation observe(AutomationEngine.Session session) {
            return new AutomationEngine.Observation(true, setting, true, false, true, true, true, false);
        }
        public void freeze(AutomationEngine.Session session) { writes++; setting = 3; }
        public void persist(AutomationEngine engine) { }
        public void schedule(AutomationEngine.Target target, long delay, Runnable task) { }
        public void cancel(AutomationEngine.Target target) { }
    }
    private static AutomationEngine.Policy policy(Set<String> allowed) {
        return new AutomationEngine.Policy(1, 3000, 10000, false, false, Set.of(PKG), Map.of(), Map.of(), allowed);
    }
    @Test public void selectedPackageSurvivesAppSnapshotAndSystemPersistence() throws Exception {
        String snapshot = RuleSnapshot.encodeTabs(3000, 10000, false, false, Set.of(PKG), List.of(),
                TagRules.pageModes(1, List.of(), Map.of()), Set.of(PKG));
        AutomationEngine.Policy decoded = SystemStateStore.parsePolicy(new JSONObject(snapshot));
        assertEquals(Set.of(PKG), decoded.highRiskAllowed());
        assertEquals(decoded, SystemStateStore.parsePolicy(SystemStateStore.policyJson(decoded)));
    }
    @Test public void legacyPolicyStartsWithNoRiskyAllowance() throws Exception {
        JSONObject old = SystemStateStore.policyJson(policy(Set.of())); old.remove("highRiskAllowed");
        assertTrue(SystemStateStore.parsePolicy(old).highRiskAllowed().isEmpty());
    }
    @Test public void malformedPermissionDataCannotEnterSystemPolicy() throws Exception {
        JSONObject base = SystemStateStore.policyJson(policy(Set.of()));
        for (Object invalid : new Object[]{new JSONObject(), "com.android.thememanager",
                new JSONArray().put(1), new JSONArray().put(PKG).put(PKG),
                new JSONArray().put("io.github.yudigaga.lightfreeze"), new JSONArray().put("android")}) {
            base.put("highRiskAllowed", invalid);
            assertThrows(Exception.class, () -> SystemStateStore.parsePolicy(base));
        }
    }
    @Test public void coreUidCanOnlyBeFollowedWithExactPackageAllowance() throws Exception {
        Host host = new Host(); AutomationEngine engine = new AutomationEngine(host);
        engine.setPolicy(policy(Set.of()));
        assertThrows(IllegalArgumentException.class, () -> engine.follow(TARGET, 1000, "identity", 0));
        engine.setPolicy(policy(Set.of(PKG)));
        assertTrue(engine.follow(TARGET, 1000, "identity", 0));
        assertTrue(engine.sessions.get(TARGET).followed);
    }
    @Test public void allowingOnePackageDoesNotPermitAnotherPackageWithTheSameCoreUid() throws Exception {
        Host host = new Host(); AutomationEngine engine = new AutomationEngine(host);
        String other = "com.android.systemui";
        engine.setPolicy(new AutomationEngine.Policy(1, 3000, 10000, false, false,
                Set.of(PKG, other), Map.of(), Map.of(), Set.of(PKG)));
        assertTrue(engine.follow(TARGET, 1000, "theme", 0));
        assertThrows(IllegalArgumentException.class,
                () -> engine.follow(new AutomationEngine.Target(other, 0), 1000, "systemui", 0));
    }
    @Test public void revokingAllowanceCancelsActiveClockAndDoesNotFreeze() throws Exception {
        Host host = new Host(); AutomationEngine engine = new AutomationEngine(host);
        engine.setPolicy(policy(Set.of(PKG))); engine.follow(TARGET, 1000, "identity", 0); engine.onEvent();
        assertTrue(engine.sessions.get(TARGET).lockDeadline >= 0);
        engine.setPolicy(policy(Set.of()));
        assertFalse(engine.sessions.containsKey(TARGET));
        host.time = 5000; engine.onEvent(); assertEquals(0, host.writes);
    }
    @Test public void revokingAllowanceRetainsPendingLeaseUntilConfirmedCompletion() throws Exception {
        Host host = new Host(); AutomationEngine engine = new AutomationEngine(host);
        engine.setPolicy(policy(Set.of(PKG)));
        long lease = engine.acquire(TARGET, "pending", 1000, "identity", 0, true, false);
        assertThrows(IllegalStateException.class, () -> engine.setPolicy(policy(Set.of())));
        assertEquals(AutomationEngine.Phase.ENABLING, engine.sessions.get(TARGET).phase);
        assertTrue(engine.sessions.get(TARGET).cancelled);
        engine.finish(TARGET, "pending", lease, true, false, 0);
        assertFalse(engine.sessions.containsKey(TARGET)); assertEquals(0, host.writes);
        assertTrue(engine.receipts.containsKey(TARGET));
    }
    @Test public void permissionChangeInvalidatesAnExistingConfigurationToken() throws Exception {
        AutomationEngine engine = new AutomationEngine(new Host()); PolicyAuthority authority = new PolicyAuthority(engine);
        String before = authority.sync(policy(Set.of()));
        String after = authority.sync(policy(Set.of(PKG)));
        assertFalse(authority.matches(before)); assertTrue(authority.matches(after));
    }
    @Test public void coreRestoreOwnershipSurvivesRevocationAndReboot() throws Exception {
        Host host = new Host(); AutomationEngine engine = new AutomationEngine(host);
        engine.setPolicy(policy(Set.of(PKG))); engine.follow(TARGET, 1000, "identity", 0);
        engine.onEvent(); host.time = 5000; engine.onEvent();
        assertEquals(1, host.writes); assertEquals(Integer.valueOf(0), engine.ownedBaseline(TARGET, 1000, "identity"));
        engine.setPolicy(policy(Set.of()));
        AutomationEngine restored = new AutomationEngine(host);
        SystemStateStore.restore(restored, SystemStateStore.snapshot(engine), 0);
        assertEquals(Integer.valueOf(0), restored.ownedBaseline(TARGET, 1000, "identity"));
    }
    @Test public void coreFollowedSessionRestoresOnlyWhileItsPermissionExists() throws Exception {
        Host host = new Host(); AutomationEngine engine = new AutomationEngine(host);
        engine.setPolicy(policy(Set.of(PKG))); engine.follow(TARGET, 1000, "identity", 0);
        JSONObject saved = SystemStateStore.snapshot(engine);
        AutomationEngine allowed = new AutomationEngine(host); SystemStateStore.restore(allowed, saved, 1000);
        assertTrue(allowed.sessions.containsKey(TARGET));
        saved.getJSONObject("config").put("highRiskAllowed", new JSONArray());
        AutomationEngine revoked = new AutomationEngine(host); SystemStateStore.restore(revoked, saved, 1000);
        assertFalse(revoked.sessions.containsKey(TARGET));
    }
    @Test public void revokedCorePendingLeaseRestoresCancelledAndCanStillComplete() throws Exception {
        Host host = new Host(); AutomationEngine engine = new AutomationEngine(host);
        engine.setPolicy(policy(Set.of(PKG)));
        long lease = engine.acquire(TARGET, "pending", 1000, "identity", 0, true, false);
        assertThrows(IllegalStateException.class, () -> engine.setPolicy(policy(Set.of())));
        AutomationEngine restored = new AutomationEngine(host);
        SystemStateStore.restore(restored, SystemStateStore.snapshot(engine), 1000);
        assertEquals(AutomationEngine.Phase.UNRESOLVED, restored.sessions.get(TARGET).phase);
        assertTrue(restored.sessions.get(TARGET).cancelled);
        restored.finish(TARGET, "pending", lease, true, false, 0);
        assertFalse(restored.sessions.containsKey(TARGET)); assertEquals(0, host.writes);
    }
}
