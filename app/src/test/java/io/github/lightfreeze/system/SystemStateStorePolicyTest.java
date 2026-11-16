package io.github.yudigaga.lightfreeze.system;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class SystemStateStorePolicyTest {
    @Test public void observedLaunchSurvivesRestartWithoutRecreatingAFailedLaunchTimer() throws Exception {
        AutomationEngine.Session session = new AutomationEngine.Session();
        session.target = new AutomationEngine.Target("example.app", 0); session.requestId = "request";
        session.digest = "digest"; session.uid = 10001; session.original = 1; session.lease = 1;
        session.generation = 1; session.phase = AutomationEngine.Phase.ACTIVE; session.temporary = true;
        session.launchExpected = true; session.firstVisible = true; session.sourceTab = "builtin:all";
        JSONObject saved = SystemStateStore.sessionJson(session);
        assertTrue(SystemStateStore.parseSession(saved, 20000).firstVisible);
        saved.remove("firstVisible");
        assertFalse(SystemStateStore.parseSession(saved, 20000).firstVisible);
    }
    private JSONObject legacyPolicy() throws Exception {
        return new JSONObject().put("version", 1).put("mode", 2).put("lockDelayMs", 3000)
                .put("leaveDelayMs", 10000).put("protectMedia", false)
                .put("managed", new JSONArray().put("example.app")).put("appModes", new JSONObject());
    }
    @Test public void loadingAnOlderSnapshotDoesNotEnableRecentTaskProtection() throws Exception {
        AutomationEngine.Policy policy = SystemStateStore.parsePolicy(legacyPolicy());
        assertFalse(policy.protectRecents());
        assertFalse(policy.protectMedia());
        assertTrue(policy.managed().contains("example.app"));
    }
    @Test public void recentTaskProtectionCanBeEnabledIndependentlyOfMedia() throws Exception {
        AutomationEngine.Policy policy = SystemStateStore.parsePolicy(legacyPolicy().put("protectRecents", true));
        assertTrue(policy.protectRecents());
        assertFalse(policy.protectMedia());
    }
    @Test public void oldExitOnlyPolicyBecomesManualWithoutInventingTabRules() throws Exception {
        AutomationEngine.Policy policy = SystemStateStore.parsePolicy(legacyPolicy());
        assertTrue(policy.tabModes().isEmpty());
        assertEquals(0, policy.modeFor("example.app", ""));
        assertEquals(0, policy.modeFor("example.app", "builtin:all"));
    }
    @Test public void legacyTabMetadataRoundTripsButCannotOverrideTheOwnerRule() throws Exception {
        String custom = "11111111-1111-4111-8111-111111111111";
        JSONObject value = legacyPolicy().put("tabModes", new JSONObject().put("builtin:all", 0)
                .put("builtin:managed", 1).put(custom, 4))
                .put("appModes", new JSONObject().put("example.app", 1));
        AutomationEngine.Policy original = SystemStateStore.parsePolicy(value);
        assertEquals(Map.of("builtin:all", 0, "builtin:managed", 1, custom, 4), original.tabModes());
        assertEquals(original, SystemStateStore.parsePolicy(SystemStateStore.policyJson(original)));
        assertEquals(1, original.modeFor("example.app", custom));
    }
    @Test public void malformedTabIdsModesAndTypesCannotEnterPolicy() throws Exception {
        for (JSONObject invalid : new JSONObject[]{new JSONObject().put("builtin:other", 1),
                new JSONObject().put("", 1), new JSONObject().put("builtin:all", -1),
                new JSONObject().put("builtin:all", 8), new JSONObject().put("not-a-tab", 1)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> SystemStateStore.parsePolicy(legacyPolicy().put("tabModes", invalid)));
        }
        assertThrows(org.json.JSONException.class,
                () -> SystemStateStore.parsePolicy(legacyPolicy().put("tabModes", new JSONArray())));
    }
    @Test public void savedSourceTabAndLegacySessionsUseTheActualSessionCodec() throws Exception {
        AutomationEngine.Session source = new AutomationEngine.Session();
        source.target = new AutomationEngine.Target("example.app", 0); source.requestId = "request";
        source.digest = "digest"; source.uid = 10001; source.original = 1; source.lease = 3;
        source.generation = 4; source.phase = AutomationEngine.Phase.ACTIVE; source.temporary = true;
        source.launchExpected = false; source.sourceTab = "builtin:available";
        JSONObject saved = SystemStateStore.sessionJson(source);
        AutomationEngine.Session restored = SystemStateStore.parseSession(saved, 5000);
        assertEquals(source.sourceTab, restored.sourceTab); assertEquals(5000, restored.activeSince);
        assertTrue(restored.firstVisible); assertEquals(3, restored.lease);
        saved.remove("sourceTab");
        assertEquals("", SystemStateStore.parseSession(saved, 5000).sourceTab);
    }
    @Test public void pendingSessionAfterRestartKeepsItsSourceButCannotResumeAWritableLease() throws Exception {
        JSONObject saved = new JSONObject().put("packageName", "example.app").put("userId", 0)
                .put("requestId", "pending").put("digest", "digest").put("uid", 10001)
                .put("original", 1).put("lease", 4).put("generation", 5).put("phase", "ENABLING")
                .put("temporary", true).put("cancelled", false).put("sourceTab", "builtin:frozen");
        AutomationEngine.Session pending = SystemStateStore.parseSession(saved, 1000);
        assertEquals("builtin:frozen", pending.sourceTab);
        assertEquals(AutomationEngine.Phase.UNRESOLVED, pending.phase);
        assertEquals(0, SystemStateStore.parsePolicy(legacyPolicy().put("tabModes", new JSONObject()))
                .modeFor("example.app", pending.sourceTab));
        saved.put("sourceTab", "builtin:unknown");
        assertThrows(IllegalArgumentException.class, () -> SystemStateStore.parseSession(saved, 1000));
    }
    @Test public void restoredExitOnlyLaunchCannotFallThroughIntoFailedLaunchFreeze() throws Exception {
        AutomationEngine.Host host = new AutomationEngine.Host() {
            public long now() { return 30000; }
            public AutomationEngine.Observation observe(AutomationEngine.Session session) { throw new AssertionError("manual session observed"); }
            public void freeze(AutomationEngine.Session session) { throw new AssertionError("removed exit rule froze app"); }
            public void persist(AutomationEngine engine) { }
            public void schedule(AutomationEngine.Target target, long delay, Runnable task) { throw new AssertionError("obsolete timer"); }
            public void cancel(AutomationEngine.Target target) { }
        };
        AutomationEngine engine = new AutomationEngine(host);
        AutomationEngine.Session active = new AutomationEngine.Session();
        active.target = new AutomationEngine.Target("example.app", 0); active.requestId = "launch";
        active.digest = "digest"; active.uid = 10001; active.original = 1; active.lease = 1;
        active.generation = 1; active.phase = AutomationEngine.Phase.ACTIVE; active.temporary = true;
        active.launchExpected = true; active.firstVisible = false;
        JSONObject root = new JSONObject().put("schema", 1).put("revision", 1).put("config", legacyPolicy())
                .put("sessions", new JSONArray().put(SystemStateStore.sessionJson(active)));
        SystemStateStore.restore(engine, root, 0);
        assertTrue(engine.sessions.isEmpty()); engine.onEvent();
        // An unresolved backend lease still owns exclusion, but can never resume the removed rule.
        active.phase = AutomationEngine.Phase.ENABLING;
        root.put("sessions", new JSONArray().put(SystemStateStore.sessionJson(active)));
        SystemStateStore.restore(engine, root, 0);
        assertEquals(AutomationEngine.Phase.UNRESOLVED, engine.sessions.get(active.target).phase);
        assertTrue(engine.sessions.get(active.target).cancelled); engine.onEvent();
    }
    @Test public void everyLegacyExitBitIsRemovedFromGlobalPackageAndTabRules() throws Exception {
        for (int old = 0; old <= 7; old++) {
            AutomationEngine.Policy migrated = SystemStateStore.parsePolicy(legacyPolicy().put("mode", old)
                    .put("appModes", new JSONObject().put("example.app", old))
                    .put("tabModes", new JSONObject().put("builtin:all", old)));
            assertEquals(old & 5, migrated.mode());
            assertEquals(Integer.valueOf(old & 5), migrated.appModes().get("example.app"));
            assertEquals(Integer.valueOf(old & 5), migrated.tabModes().get("builtin:all"));
        }
    }
    @Test public void combinedRulesRoundTripButOldCardGesturesAndClocksDoNot() throws Exception {
        AutomationEngine.Policy policy = SystemStateStore.parsePolicy(legacyPolicy().put("mode", 7)
                .put("tabModes", new JSONObject().put("builtin:all", 7).put("builtin:available", 4)));
        assertEquals(5, policy.mode());
        assertEquals(Integer.valueOf(5), policy.tabModes().get("builtin:all"));
        assertEquals(policy, SystemStateStore.parsePolicy(SystemStateStore.policyJson(policy)));
        AutomationEngine.Session session = new AutomationEngine.Session();
        session.target = new AutomationEngine.Target("example.app", 0); session.requestId = "r";
        session.digest = "digest"; session.uid = 10001; session.original = 1; session.lease = 1;
        session.generation = 1; session.phase = AutomationEngine.Phase.ACTIVE; session.temporary = true;
        session.launchExpected = false; session.sourceTab = "builtin:all";
        session.cardPending = true; session.lockDeadline = 3000;
        JSONObject json = SystemStateStore.sessionJson(session);
        assertFalse(json.has("cardPending")); assertFalse(json.has("lockDeadline"));
        AutomationEngine.Session restored = SystemStateStore.parseSession(json, 20000);
        assertFalse(restored.cardPending); assertEquals(-1, restored.lockDeadline);
    }
    private static final class RestoreHost implements AutomationEngine.Host {
        int setting = 1, writes;
        public long now() { return 0; }
        public AutomationEngine.Observation observe(AutomationEngine.Session session) {
            return new AutomationEngine.Observation(true, setting, true, false, true, true, true, false);
        }
        public void freeze(AutomationEngine.Session session) { writes++; setting = 3; }
        public void persist(AutomationEngine engine) { }
        public void schedule(AutomationEngine.Target target, long delay, Runnable task) { }
        public void cancel(AutomationEngine.Target target) { }
    }
    private JSONObject oldExplicitSessionSnapshot(int ownerMode) throws Exception {
        String oldTag = "11111111-1111-4111-8111-111111111111";
        JSONObject config = legacyPolicy().put("mode", 4).put("lockDelayMs", 0)
                .put("appModes", new JSONObject().put("example.app", ownerMode))
                .put("tabModes", new JSONObject());
        AutomationEngine.Session old = new AutomationEngine.Session();
        old.target = new AutomationEngine.Target("example.app", 0); old.uid = 10001; old.digest = "digest";
        old.original = 1; old.requestId = "old-entry"; old.sourceTab = oldTag;
        old.lease = 3; old.generation = 3; old.phase = AutomationEngine.Phase.ACTIVE;
        old.temporary = true; old.launchExpected = false; old.firstVisible = true;
        return new JSONObject().put("schema", 1).put("revision", 3).put("config", config)
                .put("sessions", new JSONArray().put(SystemStateStore.sessionJson(old)));
    }
    @Test public void restoredManualOwnerDropsAnOldAutomaticEntryEvenIfDefaultIsAutomatic() throws Exception {
        RestoreHost host = new RestoreHost(); AutomationEngine engine = new AutomationEngine(host);
        SystemStateStore.restore(engine, oldExplicitSessionSnapshot(0), 0);
        assertTrue(engine.sessions.isEmpty()); engine.onEvent(); assertEquals(0, host.writes);
    }
    @Test public void restoredActiveOwnerUsesCurrentRuleEvenWhenItsOldEntryWasDeleted() throws Exception {
        RestoreHost host = new RestoreHost(); AutomationEngine engine = new AutomationEngine(host);
        SystemStateStore.restore(engine, oldExplicitSessionSnapshot(1), 0);
        assertEquals(1, engine.sessions.size()); engine.onEvent();
        assertEquals(1, host.writes); assertTrue(engine.sessions.isEmpty());
    }
}
