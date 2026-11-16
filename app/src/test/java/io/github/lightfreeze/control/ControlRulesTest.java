package io.github.yudigaga.lightfreeze.control;

import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;

public class ControlRulesTest {
    @Test public void combinedAndCardTriggersRequireEverySelectedCapability() {
        ControlRules.requireTriggerSupport(1, true, false, false, false);
        ControlRules.requireTriggerSupport(5, true, false, true, true);
        ControlRules.requireTriggerSupport(4, false, false, true, true);
        assertThrows(IllegalStateException.class, () -> ControlRules.requireTriggerSupport(5, true, true, true, false));
        assertThrows(IllegalStateException.class, () -> ControlRules.requireTriggerSupport(5, true, true, false, true));
        assertThrows(IllegalStateException.class, () -> ControlRules.requireTriggerSupport(5, false, true, true, true));
        assertThrows(IllegalArgumentException.class, () -> ControlRules.requireTriggerSupport(2, false, true, false, false));
    }
    @Test public void legacyTabPausePreservesAnAvailableRecentsGuard() throws Exception {
        String input = new JSONObject().put("mode", 2).put("appModes", new JSONObject().put("com.example", 1))
                .put("tabModes", new JSONObject().put("builtin:all", 2).put("builtin:managed", 0)
                        .put("11111111-1111-4111-8111-111111111111", 1)).put("protectRecents", true)
                .put("protectMedia", true).toString();
        JSONObject paused = new JSONObject(ControlRules.pausedConfig(input, false));
        assertEquals(0, paused.getInt("mode"));
        assertEquals(0, paused.getJSONObject("appModes").length());
        JSONObject pages = paused.getJSONObject("tabModes");
        assertEquals(3, pages.length());
        assertEquals(0, pages.getInt("builtin:all"));
        assertEquals(0, pages.getInt("builtin:managed"));
        assertEquals(0, pages.getInt("11111111-1111-4111-8111-111111111111"));
        assertFalse(pages.has("missing"));
        assertTrue(paused.getBoolean("protectRecents")); assertTrue(paused.getBoolean("protectMedia"));
        assertFalse(new JSONObject(ControlRules.pausedConfig(input, true)).getBoolean("protectRecents"));
        assertEquals(3, new JSONObject(ControlRules.pausedConfig(input, true)).getJSONObject("tabModes").length());
    }
    @Test public void sourcedAutomaticThawNeverFallsBackToLegacyRules() {
        ControlRules.requireTabSessionSupport(false, "builtin:all", false);
        ControlRules.requireTabSessionSupport(true, "builtin:all", true);
        assertThrows(IllegalStateException.class,
                () -> ControlRules.requireTabSessionSupport(true, "builtin:all", false));
        assertThrows(IllegalStateException.class,
                () -> ControlRules.requireTabSessionSupport(true, "", true));
    }
    @Test public void freezeSkipsOnlyTheLiveDisabledStateAndNeverAPendingResult() {
        AppEntry staleFrozen = new AppEntry("com.example.app", "Example", 0, 10001, 3, false, true);
        assertTrue(staleFrozen.frozen);
        assertFalse(ControlRules.freezeAlreadySatisfied(0, false));
        for (int live : new int[]{2, 3, 4}) assertTrue(ControlRules.freezeAlreadySatisfied(live, false));
        for (int live : new int[]{-1, 0, 1, 2, 3, 4})
            assertThrows(IllegalStateException.class, () -> ControlRules.freezeAlreadySatisfied(live, true));
    }
    @Test public void directThawHandlesAllPmDisabledStatesWithoutClaimingAnExternalBaseline() {
        assertEquals(1, ControlRules.thawSetting(2, null));
        assertEquals(1, ControlRules.thawSetting(3, null));
        assertEquals(1, ControlRules.thawSetting(4, null));
        assertEquals(0, ControlRules.thawSetting(3, 0));
        assertEquals(1, ControlRules.thawSetting(3, 1));
        assertEquals(1, ControlRules.thawSetting(2, 0));
        assertEquals(1, ControlRules.thawSetting(4, 0));
        assertEquals(0, ControlRules.thawSetting(0, null));
        assertEquals(1, ControlRules.thawSetting(1, 0));
        assertThrows(IllegalArgumentException.class, () -> ControlRules.thawSetting(-1, null));
    }

    @Test public void frozenCatalogIncludesDisableDisableUserAndDisableUntilUsed() {
        for (int setting : new int[]{2, 3, 4}) assertTrue(AppEntry.isDisabledSetting(setting));
        for (int setting : new int[]{-1, 0, 1, 5}) assertFalse(AppEntry.isDisabledSetting(setting));
    }
    @Test public void commandRejectsShellAndWrongUser() {
        for (String name : new String[]{"com.example;id", "com.example\nother", "com.example x", "../x", "com.example'"}) {
            try { ControlRules.command(name, 0, 3); fail("accepted unsafe package " + name); }
            catch (IllegalArgumentException expected) { }
        }
        try { ControlRules.command("com.example", -1, 3); fail("accepted negative user"); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void commandsPreserveDefaultAndEnabled() {
        assertEquals("cmd package default-state --user 10 com.example", ControlRules.command("com.example", 10, 0));
        assertEquals("cmd package enable --user 10 com.example", ControlRules.command("com.example", 10, 1));
        assertEquals("cmd package disable-user --user 10 com.example", ControlRules.command("com.example", 10, 3));
    }
    @Test public void originalSettingOnlyCapturesUsableStates() {
        assertTrue(ControlRules.canTakeOwnership(0));
        assertTrue(ControlRules.canTakeOwnership(1));
        assertFalse(ControlRules.canTakeOwnership(2));
        assertFalse(ControlRules.canTakeOwnership(3));
        assertFalse(ControlRules.canTakeOwnership(4));
    }
    @Test public void exitCodeAloneNeverMeansConfirmedSuccess() {
        assertFalse(ControlRules.confirmed(0, 1, 3));
        assertFalse(ControlRules.confirmed(-1, 3, 3));
        assertTrue(ControlRules.confirmed(0, 3, 3));
    }
    @Test public void thawRequiresActualAvailabilityEvenWhenDefaultStateMatches() {
        assertFalse(ControlRules.confirmedThaw(0, 0, false, false));
        assertFalse(ControlRules.confirmedThaw(1, 1, true, true));
        assertFalse(ControlRules.confirmedThaw(3, 1, true, false));
        assertTrue(ControlRules.confirmedThaw(0, 0, true, false));
        assertTrue(ControlRules.confirmedThaw(1, 1, true, false));
    }
    @Test public void reinstallOrAnotherUserCannotInheritOwnership() {
        assertTrue(ControlRules.sameIdentity(10, 1012345, "aa", 10, 1012345, "aa"));
        assertFalse(ControlRules.sameIdentity(10, 1012345, "aa", 0, 12345, "aa"));
        assertFalse(ControlRules.sameIdentity(10, 1012345, "aa", 10, 1012346, "aa"));
        assertFalse(ControlRules.sameIdentity(10, 1012345, "aa", 10, 1012345, "bb"));
        assertFalse(ControlRules.sameIdentity(10, 1012345, "", 10, 1012345, ""));
    }
}
